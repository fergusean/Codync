//! Private encrypted channel subscriptions.

use super::*;

impl Channel {
    pub(super) async fn subscribe(&self, id: u64, kind: &str, b: &Value) {
        if self.count(true) >= MAX_SUBS {
            self.send(err(id, 429, "too many subscriptions")).await;
            return;
        }
        let stream = match kind {
            "events" => match api::events_stream(
                &self.hub,
                b["since"].as_i64().unwrap_or(0),
                b["client"].as_str(),
                &self.caller,
            ) {
                Ok(s) => s.boxed(),
                Err(e) => {
                    self.send(err(id, 500, &format!("{e:#}"))).await;
                    return;
                }
            },
            "screenCandidates" => {
                if let Err(error) = devices::permit(&self.caller, "screenCandidates") {
                    self.send(err(id, 403, &error.to_string())).await;
                    return;
                }
                match self.hub.screen.candidates(b["session"].as_str().unwrap_or_default(), self.caller.device_key()) {
                    Ok(stream) => stream.boxed(),
                    Err(error) => {
                        self.send(err(id, 400, &error.to_string())).await;
                        return;
                    }
                }
            }
            "term" => {
                let Some(s) = api::term_events(&self.hub, b["term"].as_str().unwrap_or_default()) else {
                    self.send(err(id, 404, "that terminal is gone")).await;
                    return;
                };
                s.boxed()
            }
            _ => {
                self.send(err(id, 400, "unknown subscription")).await;
                return;
            }
        };
        let screen_subscription = kind == "screenCandidates";
        let hub = self.hub.clone();
        let owner = self.caller.device_key().to_owned();
        let mut auth = hub.auth.subscribe();
        let (jobs, tx) = (self.jobs.clone(), self.tx.clone());
        // Held while spawning so the task can't finish (and remove itself) before it's listed.
        let mut list = self.jobs.locked();
        let task = tokio::spawn(async move {
            let mut stream = stream;
            loop {
                let ev = tokio::select! {
                    event = stream.next() => { let Some(event) = event else { break }; event }
                    changed = auth.changed(), if screen_subscription => {
                        let authorized = devices::authorize(&hub, &owner).is_ok_and(|device| device.scopes.contains(&Scope::Screen));
                        if changed.is_err() || !authorized { break; }
                        continue;
                    }
                };
                let sent = tx.send(ToDevice::Inner(json!({"id": id, "ev": ev}))).await;
                if sent.is_err() {
                    return;
                }
            }
            if jobs.locked().remove(&id).is_some() {
                let _ = tx.send(ToDevice::Inner(json!({"id": id, "end": true}))).await;
            }
        });
        list.insert(id, Job::Sub(task.abort_handle()));
    }
}
