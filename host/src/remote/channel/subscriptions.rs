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
        let (jobs, tx) = (self.jobs.clone(), self.tx.clone());
        // Held while spawning so the task can't finish (and remove itself) before it's listed.
        let mut list = self.jobs.locked();
        let task = tokio::spawn(async move {
            let mut stream = stream;
            while let Some(ev) = stream.next().await {
                if tx.send(ToDevice::Inner(json!({"id": id, "ev": ev}))).await.is_err() {
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
