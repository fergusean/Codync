//! Real host actors and persistent ACP fixtures; TCP EOF proves tool-tree exit.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

mod common;

use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};

struct Host {
    child: Child,
    home: PathBuf,
    base: String,
    token: String,
    witness_port: u16,
    live: Arc<AtomicUsize>,
    witness_task: tokio::task::JoinHandle<()>,
}

impl Drop for Host {
    fn drop(&mut self) {
        common::stop(&mut self.child);
        self.witness_task.abort();
        let _ = std::fs::remove_dir_all(&self.home);
    }
}

impl Host {
    async fn start() -> Self {
        let home = std::env::temp_dir().join(format!("codync-lifecycle-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&home).unwrap();
        common::seed_memory_runtime(&home);
        let witness = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let witness_port = witness.local_addr().unwrap().port();
        let live = Arc::new(AtomicUsize::new(0));
        let observed = live.clone();
        let witness_task = tokio::spawn(async move {
            while let Ok((stream, _)) = witness.accept().await {
                let observed = observed.clone();
                tokio::spawn(async move {
                    let mut reader = BufReader::new(stream);
                    let mut greeting = String::new();
                    if reader.read_line(&mut greeting).await.unwrap() == 0 {
                        return;
                    }
                    observed.fetch_add(1, Ordering::SeqCst);
                    let mut remaining = Vec::new();
                    reader.read_to_end(&mut remaining).await.unwrap();
                    observed.fetch_sub(1, Ordering::SeqCst);
                });
            }
        });
        let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        let child = Command::new(env!("CARGO_BIN_EXE_codync-host"))
            .args(["serve", "--bind", "127.0.0.1", "--port", &port.to_string()])
            .env("CODYNC_CLOUD", "off")
            .env("CODYNC_HOME", &home)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        let mut host = Self {
            child,
            home,
            base: format!("http://127.0.0.1:{port}"),
            token: String::new(),
            witness_port,
            live,
            witness_task,
        };
        tokio::time::timeout(Duration::from_secs(20), async {
            while reqwest::get(format!("{}/health", host.base)).await.is_err() {
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .unwrap();
        std::fs::read_to_string(host.home.join("token")).unwrap().trim().clone_into(&mut host.token);
        host
    }

    async fn call(&self, method: &str, body: Value) -> Value {
        let res = reqwest::Client::new()
            .post(format!("{}/api/{method}", self.base))
            .bearer_auth(&self.token)
            .json(&body)
            .send()
            .await
            .unwrap();
        let status = res.status();
        let value: Value = res.json().await.unwrap();
        assert!(status.is_success(), "{method}: {value}");
        value
    }

    async fn bot(&self, name: &str, mut config: Value) -> (String, PathBuf) {
        let cwd = self.home.join(name);
        std::fs::create_dir_all(&cwd).unwrap();
        config["witnessPort"] = json!(self.witness_port);
        std::fs::write(cwd.join("fixture.json"), config.to_string()).unwrap();
        let script = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/lifecycle_agent.py");
        let response = self
            .call(
                "createBot",
                json!({"name":name,"backend":"custom","cwd":cwd,
            "command":common::python_agent(&script),"permission":"ask","notify":false}),
            )
            .await;
        (response["bot"]["id"].as_str().unwrap().to_owned(), cwd)
    }

    async fn send_entry(&self, bot: &str, text: &str, thread: Option<&str>) -> Value {
        let response = self
            .call(
                "send",
                json!({"botId":bot,"text":text,"threadId":thread,
            "clientNonce":uuid::Uuid::new_v4().to_string()}),
            )
            .await;
        response["entry"].clone()
    }

    async fn entries(&self, bot: &str, thread: Option<&str>) -> Vec<Value> {
        let response = if let Some(root) = thread {
            self.call("thread", json!({"botId":bot,"rootId":root})).await
        } else {
            self.call("history", json!({"botId":bot,"limit":500})).await
        };
        response["entries"].as_array().unwrap().clone()
    }

    async fn wait_entry(&self, bot: &str, thread: Option<&str>, pred: impl Fn(&Value) -> bool) -> Value {
        let deadline = Instant::now() + Duration::from_secs(30);
        loop {
            let entries = self.entries(bot, thread).await;
            if let Some(entry) = entries.iter().rev().find(|e| pred(e)) {
                return entry.clone();
            }
            assert!(Instant::now() < deadline, "missing expected entry: {entries:?}");
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }

    async fn send(&self, bot: &str, text: &str, thread: Option<&str>) -> Value {
        let entry = self.send_entry(bot, text, thread).await;
        self.wait_entry(bot, thread, |e| {
            e["turn"] == entry["turn"] && e["kind"] == "agent" && e["data"]["final"] == true
        })
        .await
    }

    async fn wait_live(&self, expected: usize, budget: Duration) {
        let deadline = Instant::now() + budget;
        while self.live.load(Ordering::SeqCst) != expected {
            assert!(
                Instant::now() < deadline,
                "expected {expected} tool descendants, saw {}",
                self.live.load(Ordering::SeqCst)
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }
}

fn sid(reply: &Value) -> &str {
    reply["data"]["text"].as_str().unwrap().split_whitespace().next().unwrap().strip_prefix("SID=").unwrap()
}

fn events(cwd: &Path) -> Vec<Value> {
    std::fs::read_to_string(cwd.join("events.jsonl"))
        .unwrap()
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
}

fn assert_bounded(cwd: &Path) {
    let events = events(cwd);
    assert!(!events.iter().any(|e| e["event"] == "duplicate_allocation"), "{events:?}");
    assert!(events.iter().all(|e| e["live"].as_u64().unwrap() <= 2), "{events:?}");
}

#[tokio::test]
async fn a_hundred_threads_release_tools_and_keep_main_and_thread_identities() {
    let host = Host::start().await;
    let (bot, cwd) = host.bot("Churn", json!({})).await;
    let mut main = host.send(&bot, "ok", None).await;
    let original = sid(&main).to_owned();
    let mut first_thread = None;
    for _ in 0..100 {
        let root = main["id"].as_str().unwrap().to_owned();
        let thread = host.send(&bot, "ok", Some(&root)).await;
        first_thread.get_or_insert((root, sid(&thread).to_owned()));
        main = host.send(&bot, "ok", None).await;
        assert_eq!(sid(&main), original);
        host.wait_live(4, Duration::from_secs(5)).await;
    }
    let (root, original_thread) = first_thread.unwrap();
    let reloaded = host.send(&bot, "ok", Some(&root)).await;
    assert_eq!(sid(&reloaded), original_thread);
    assert!(reloaded["data"]["text"].as_str().unwrap().contains("COUNT=2"));
    assert_bounded(&cwd);
    assert!(events(&cwd).iter().filter(|e| e["event"] == "started").count() > 40);
    assert!(!host.entries(&bot, None).await.iter().any(|e| e["data"]["text"] == "REPLAY"));
}

#[tokio::test]
async fn close_and_prompt_refresh_release_superseded_tools_without_losing_threads() {
    let host = Host::start().await;
    let (bot, cwd) = host.bot("Close", json!({"close":true,"claude":true})).await;
    let main = host.send(&bot, "ok", None).await;
    let root = main["id"].as_str().unwrap();
    let thread = host.send(&bot, "ok", Some(root)).await;
    host.call("updateBot", json!({"id":bot,"description":"Changed instructions"})).await;
    let refreshed = host.send(&bot, "COMPACT", None).await;
    assert_eq!(sid(&refreshed), sid(&main));
    let restored = host.send(&bot, "ok", None).await;
    assert_eq!(sid(&restored), sid(&main));
    for _ in 0..20 {
        host.call("newSession", json!({"botId":bot})).await;
        host.send(&bot, "ok", None).await;
        host.wait_live(4, Duration::from_secs(5)).await;
    }
    let restored_thread = host.send(&bot, "ok", Some(root)).await;
    assert_eq!(sid(&restored_thread), sid(&thread));
    assert_bounded(&cwd);
    assert_eq!(events(&cwd).iter().filter(|e| e["event"] == "started").count(), 1);
    assert!(events(&cwd).iter().any(|e| e["event"] == "closed"));
}

#[tokio::test]
async fn fork_parent_and_failed_close_stay_within_the_live_session_budget() {
    let host = Host::start().await;
    let (bot, cwd) = host.bot("Fork", json!({"fork":true,"close":true,"closeFails":true})).await;
    let mut main = host.send(&bot, "ok", None).await;
    for _ in 0..5 {
        let thread = host.send(&bot, "ok", main["id"].as_str()).await;
        assert_ne!(sid(&thread), sid(&main));
        let returned = host.send(&bot, "ok", None).await;
        assert_eq!(sid(&returned), sid(&main));
        main = returned;
        host.wait_live(4, Duration::from_secs(5)).await;
    }
    assert_bounded(&cwd);
}

#[tokio::test]
async fn failed_reload_keeps_saved_identity_and_retry_does_not_create_a_new_session() {
    let host = Host::start().await;
    let (bot, cwd) = host.bot("Retry", json!({})).await;
    let main = host.send(&bot, "ok", None).await;
    let root = main["id"].as_str().unwrap();
    host.send(&bot, "ok", Some(root)).await;
    let second_root = host.send_entry(&bot, "ok", None).await;
    let second_main =
        host.wait_entry(&bot, None, |e| e["turn"] == second_root["turn"] && e["data"]["final"] == true).await;
    host.send(&bot, "ok", second_main["id"].as_str()).await;
    std::fs::write(cwd.join("fail-load"), "").unwrap();
    let before = events(&cwd).iter().filter(|e| e["event"] == "allocated").count();
    let failed = host.send_entry(&bot, "ok", None).await;
    host.wait_entry(&bot, None, |e| {
        e["turn"] == failed["turn"] && e["kind"] == "notice" && e["data"]["style"] == "error"
    })
    .await;
    let store =
        rusqlite::Connection::open_with_flags(host.home.join("codync.db"), rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)
            .unwrap();
    let saved: String = store.query_row("SELECT session_id FROM bots WHERE id=?1", [&bot], |r| r.get(0)).unwrap();
    assert_eq!(saved, sid(&main));
    assert_eq!(events(&cwd).iter().filter(|e| e["event"] == "allocated").count(), before);
    std::fs::remove_file(cwd.join("fail-load")).unwrap();
    let retry = host.send(&bot, "ok", None).await;
    assert_eq!(sid(&retry), sid(&main));
    assert_bounded(&cwd);
}

#[tokio::test]
async fn nonresumable_agents_preserve_existing_context_at_the_allocation_limit() {
    let host = Host::start().await;
    let (bot, cwd) = host.bot("NoResume", json!({"load":false})).await;
    let main = host.send(&bot, "ok", None).await;
    host.send(&bot, "ok", main["id"].as_str()).await;
    let returned = host.send(&bot, "ok", None).await;
    let failed = host.send_entry(&bot, "ok", returned["id"].as_str()).await;
    host.wait_entry(&bot, returned["id"].as_str(), |e| {
        e["turn"] == failed["turn"] && e["kind"] == "notice" && e["data"]["style"] == "error"
    })
    .await;
    let intact = host.send(&bot, "ok", None).await;
    assert_eq!(sid(&intact), sid(&main));
    assert_eq!(events(&cwd).iter().filter(|e| e["event"] == "started").count(), 1);
    assert_bounded(&cwd);
}

#[tokio::test]
async fn reset_recycles_agents_without_close_and_keeps_saved_thread_context() {
    let host = Host::start().await;
    let (bot, cwd) = host.bot("Reset", json!({})).await;
    let main = host.send(&bot, "ok", None).await;
    let root = main["id"].as_str().unwrap();
    let thread = host.send(&bot, "ok", Some(root)).await;
    for _ in 0..10 {
        host.call("newSession", json!({"botId":bot})).await;
        let replacement = host.send(&bot, "ok", None).await;
        assert_ne!(sid(&replacement), sid(&main));
        host.wait_live(2, Duration::from_secs(5)).await;
    }
    let returned = host.send(&bot, "ok", Some(root)).await;
    assert_eq!(sid(&returned), sid(&thread));
    assert_bounded(&cwd);
}

#[tokio::test]
async fn timed_out_close_recycles_resources_and_preserves_saved_conversations() {
    let host = Host::start().await;
    let (bot, cwd) = host.bot("CloseTimeout", json!({"close":true,"closeHangs":true})).await;
    let main = host.send(&bot, "ok", None).await;
    host.send(&bot, "ok", main["id"].as_str()).await;
    let next = host.send(&bot, "ok", None).await;
    host.send(&bot, "ok", next["id"].as_str()).await;
    let returned = host.send(&bot, "ok", None).await;
    assert_eq!(sid(&returned), sid(&main));
    host.wait_live(4, Duration::from_secs(5)).await;
    assert_bounded(&cwd);
}

#[tokio::test]
async fn failed_allocation_keeps_nonresumable_context_until_explicit_reset() {
    let host = Host::start().await;
    let (bot, cwd) = host.bot("AllocationFailure", json!({"load":false})).await;
    let main = host.send(&bot, "ok", None).await;
    std::fs::write(cwd.join("fail-new"), "").unwrap();
    let failed = host.send_entry(&bot, "ok", main["id"].as_str()).await;
    host.wait_entry(&bot, main["id"].as_str(), |e| {
        e["turn"] == failed["turn"] && e["kind"] == "notice" && e["data"]["style"] == "error"
    })
    .await;
    let preserved = host.send(&bot, "ok", None).await;
    assert_eq!(sid(&preserved), sid(&main));
    std::fs::remove_file(cwd.join("fail-new")).unwrap();
    host.call("newSession", json!({"botId":bot})).await;
    let replacement = host.send(&bot, "ok", None).await;
    assert_ne!(sid(&replacement), sid(&main));
    host.wait_live(2, Duration::from_secs(5)).await;
    assert_bounded(&cwd);
}

#[tokio::test]
async fn failed_initial_nonresumable_allocation_can_be_released_by_explicit_reset() {
    let host = Host::start().await;
    let (bot, cwd) = host.bot("InitialAllocationFailure", json!({"load":false})).await;
    std::fs::write(cwd.join("fail-new"), "").unwrap();
    let failed = host.send_entry(&bot, "ok", None).await;
    host.wait_entry(&bot, None, |e| {
        e["turn"] == failed["turn"] && e["kind"] == "notice" && e["data"]["style"] == "error"
    })
    .await;
    host.wait_live(2, Duration::from_secs(5)).await;
    std::fs::remove_file(cwd.join("fail-new")).unwrap();
    host.call("newSession", json!({"botId":bot})).await;
    host.wait_live(0, Duration::from_secs(5)).await;
    host.send(&bot, "ok", None).await;
    host.wait_live(2, Duration::from_secs(5)).await;
    assert_bounded(&cwd);
}

#[tokio::test]
async fn routine_deadline_during_allocation_releases_uncertain_tools_before_retry() {
    let host = Host::start().await;
    let (bot, cwd) = host.bot("AllocationDeadline", json!({})).await;
    std::fs::write(cwd.join("hang-new"), "").unwrap();
    let saved = host
        .call(
            "saveRoutine",
            json!({"botId":bot,"name":"Setup timeout",
        "instruction":"ok","triggers":[{"type":"webhook"}],"timeoutSeconds":2}),
        )
        .await;
    let routine = saved["routine"]["id"].as_str().unwrap();
    host.call("runRoutine", json!({"botId":bot,"id":routine})).await;
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            let listing = host.call("routines", json!({"botId":bot})).await;
            if listing["runs"].as_array().unwrap().iter().any(|r| r["routineId"] == routine && r["status"] == "failed")
            {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    host.wait_live(2, Duration::from_secs(5)).await;
    std::fs::remove_file(cwd.join("hang-new")).unwrap();
    host.send(&bot, "ok", None).await;
    host.wait_live(2, Duration::from_secs(5)).await;
    assert!(events(&cwd).iter().any(|e| e["generation"] != events(&cwd)[0]["generation"]));
    assert_bounded(&cwd);
}

#[tokio::test]
async fn idle_retirement_preserves_active_tools_permissions_queued_work_and_nonresumable_bots() {
    let host = Host::start().await;
    let (idle, _) = host.bot("Idle", json!({})).await;
    let (keep, _) = host.bot("Keep", json!({"load":false})).await;
    let (active, active_cwd) = host.bot("Active", json!({})).await;
    let (approval, _) = host.bot("Approval", json!({})).await;
    let saved = host.send(&idle, "ok", None).await;
    let kept = host.send(&keep, "ok", None).await;
    let blocked = host.send_entry(&active, "BLOCK", None).await;
    host.send_entry(&approval, "PERMISSION", None).await;
    let card =
        host.wait_entry(&approval, None, |e| e["kind"] == "permission" && e["data"]["status"] == "pending").await;
    let queued = host.send_entry(&active, "ok", None).await;
    host.wait_live(8, Duration::from_secs(5)).await;
    host.wait_live(6, Duration::from_secs(135)).await;
    let resumed = host.send(&idle, "ok", None).await;
    assert_eq!(sid(&resumed), sid(&saved));
    let still_kept = host.send(&keep, "ok", None).await;
    assert_eq!(sid(&still_kept), sid(&kept));
    std::fs::write(active_cwd.join("release"), "").unwrap();
    host.wait_entry(&active, None, |e| e["turn"] == blocked["turn"] && e["data"]["final"] == true).await;
    host.wait_entry(&active, None, |e| e["turn"] == queued["turn"] && e["data"]["final"] == true).await;
    host.call("respondPermission", json!({"entryId":card["id"],"optionId":"allow"})).await;
    host.wait_entry(&approval, None, |e| e["data"]["final"] == true).await;
}
