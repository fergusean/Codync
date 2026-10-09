use super::*;

#[tokio::test]
async fn generated_files_are_selectable_cards_without_duplicate_fallback_text() {
    use crate::tui::app::Msg;
    use serde_json::json;
    let (tx, _rx) = tokio::sync::mpsc::unbounded_channel();
    let mut app = App::new(crate::tui::net::Client::new("http://127.0.0.1:1", Some("fixture")), tx, String::new());
    app.on_msg(Msg::Event(json!({"type":"bot", "bot":{"id":"bot", "name":"Files", "cwd":"/workspace", "backend":"custom", "status":"idle"}})));
    app.on_msg(Msg::Event(json!({"type":"entry", "entry":{"id":"file-entry", "botId":"bot", "seq":1, "kind":"agent", "turn":1, "data":{"author":"bot", "text":"fallback must stay hidden", "final":true, "files":[{"id":"file", "name":".empty", "size":0, "sha256":"hash"}]}}})));
    let bot = app.bots.get("bot").unwrap();
    let chat = build_chat(&app, bot, 80);
    let text = chat.lines.iter().map(ToString::to_string).collect::<Vec<_>>().join("\n");
    assert!(text.contains(".empty"));
    assert!(text.contains("f to download"));
    assert!(!text.contains("fallback must stay hidden"));
    assert!(chat.messages.iter().any(|(id, _, _)| id == "file-entry"));
}

fn app_with_bots() -> App {
    let (tx, _) = tokio::sync::mpsc::unbounded_channel();
    let mut app = App::new(super::super::super::net::Client::new("http://127.0.0.1:1", None), tx, String::new());
    for (id, name) in [("a", "Egan"), ("b", "Owen")] {
        app.on_msg(super::super::super::app::Msg::Event(serde_json::json!({"type":"bot","bot":{"id":id,"name":name}})));
    }
    app
}

fn exchange_entry(status: &str) -> Entry {
    Entry {
        id: "n".into(),
        seq: 1,
        turn: 1,
        kind: Kind::Notice,
        created_at: 0,
        thread_id: None,
        data: serde_json::json!({
            "text": "Messaged b: Please review the secret plan\nCompleted.", "style": "info", "status": status,
            "heading": "Messaged b: Please review the secret plan",
            "botMessage": {"sourceBotId": "a", "targetBotId": "b", "text": "Please review the secret plan"},
            "sourceBotId": "a", "targetBotId": "b", "delegationId": "r",
        }),
    }
}

#[test]
fn bot_message_is_one_line_with_a_click_and_marks_only_failure() {
    let app = app_with_bots();
    let bot = &app.bots["a"];
    let render = |e: &Entry| {
        let mut out = Built { lines: vec![], buttons: vec![], messages: vec![] };
        entry_lines(&mut out, &app, bot, e, &HashMap::new(), true, 80);
        out
    };
    let ok = render(&exchange_entry("completed"));
    let text = ok.lines.iter().map(ToString::to_string).collect::<Vec<_>>().join("\n");
    assert!(text.contains("Messaged") && text.contains("Owen"));
    assert!(!text.contains("secret plan") && !text.contains("failed"));
    assert!(matches!(ok.buttons.as_slice(), [(_, 3, _, Click::BotChat(id))] if id == "n"));
    assert_eq!(ok.messages.len(), 1);
    let mut failed = exchange_entry("failed");
    failed.data["text"] = "Messaged b: Please review the secret plan\nRecipient stopped.".into();
    let bad = render(&failed);
    let line = bad.lines.last().unwrap();
    assert!(line.to_string().ends_with("Owen · failed"));
    assert_eq!(line.spans[0].style, theme().red);
}

#[test]
fn a_run_of_exchanges_is_one_counted_line() {
    let app = app_with_bots();
    let bot = &app.bots["a"];
    let mut entries = vec![];
    for (i, status) in ["completed", "failed", "completed"].into_iter().enumerate() {
        let mut e = exchange_entry(status);
        e.id = format!("n{i}");
        e.seq = i64::try_from(i).unwrap_or(0) + 1;
        entries.push(e);
    }
    let refs: Vec<&Entry> = entries.iter().collect();
    let build = |refs: &[&Entry]| {
        let mut out = Built { lines: vec![], buttons: vec![], messages: vec![] };
        for item in group(&bot.id, refs, true) {
            match item {
                Item::Entry(e) => entry_lines(&mut out, &app, bot, e, &HashMap::new(), true, 80),
                Item::Group(g) => {
                    let lead = format!("{} messages with ", g.count);
                    bot_message_line(&mut out, &app, g.first, &g.peer, &lead, g.failed, 0);
                }
            }
        }
        out
    };
    let out = build(&refs);
    assert_eq!(out.lines.len(), 1);
    let line = &out.lines[0];
    assert!(line.to_string().contains("3 messages with") && line.to_string().ends_with("Owen · failed"), "{line}");
    assert_eq!(line.spans[0].style, theme().red);
    assert!(matches!(out.buttons.as_slice(), [(_, 3, _, Click::BotChat(id))] if id == "n0"));
    assert_eq!(out.messages.len(), 1);
    assert!(build(&refs[..1]).lines[0].to_string().contains("Messaged"));
}

#[test]
fn thread_summary_counts_replies() {
    assert_eq!(thread_summary(&serde_json::json!({"count": 0})), None);
    assert_eq!(thread_summary(&serde_json::Value::Null), None);
    assert_eq!(thread_summary(&serde_json::json!({"count": 1})).as_deref(), Some("↳ 1 reply"));
    assert_eq!(thread_summary(&serde_json::json!({"count": 3, "lastAt": 0})).as_deref(), Some("↳ 3 replies"));
    assert_eq!(
        thread_summary(&serde_json::json!({"count": 3, "lastAt": 0, "unread": 1})).as_deref(),
        Some("↳ 3 replies · 1 new")
    );
}
