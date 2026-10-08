use super::*;

#[tokio::test]
async fn screen_candidate_rpc_and_private_subscription_require_screen_scope() {
    let hub = temp_hub();
    let key = paired_phone(&hub).await;
    let dk = crypto::b64(key.verifying_key().as_bytes());
    let mut device = hub.store.device(&dk).unwrap();
    device.scopes = vec![Scope::Control];
    hub.store.put_device(&device).unwrap();
    let mut phone = Phone::direct(&hub, key.clone());
    let welcome = phone.hello(&hub, false).await;
    assert!(matches!(welcome, Out::Msg(v) if v["t"] == "welcome"));
    let reply = phone
        .call(1, "screenCandidate", json!({"session": "someone-elses-session", "candidate": {"type": "complete"}}))
        .await;
    assert_eq!(reply["err"]["status"], 403);
    phone.send(json!({"id": 2, "sub": "screenCandidates", "b": {"session": "someone-elses-session"}})).await;
    let reply = phone.recv().await;
    assert_eq!(reply.unwrap()["err"]["status"], 403);
    let reply = phone.call(3, "hello", json!({})).await;
    assert!(reply["ok"].is_object(), "denied screen access doesn't end authorized chat access");
}
