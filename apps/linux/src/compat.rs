//! Phone/host compatibility (docs/reference/compatibility.md), mirroring `host/src/compat.rs`:
//! the host sends the oldest app it serves as `minApp` in `hello`; this app keeps its own floor.

use serde_json::Value;

/// The oldest host this app works with. Raise it only when the app starts depending on
/// something older hosts don't have.
pub const MIN_HOST: &str = "2.3.0";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Mismatch {
    /// This app is older than the host's `minApp`.
    UpdateApp { minimum: String },
    /// The host is older than `MIN_HOST`.
    UpdateHost { version: String, minimum: String },
}

/// `major.minor.patch`, ignoring a leading `v` and any pre-release or build suffix; missing
/// parts count as 0. `None` for anything else, which never blocks.
fn parse(version: &str) -> Option<(u64, u64, u64)> {
    let core = version.trim().trim_start_matches('v');
    let core = core.split(['-', '+']).next()?;
    let mut parts = core.split('.');
    let mut next = |required: bool| match parts.next() {
        Some(p) => p.parse::<u64>().ok(),
        None if required => None,
        None => Some(0),
    };
    let v = (next(true)?, next(false)?, next(false)?);
    parts.next().is_none().then_some(v)
}

fn below(version: &str, minimum: &str) -> bool {
    matches!((parse(version), parse(minimum)), (Some(v), Some(m)) if v < m)
}

/// This app (`app`) against a host's `hello`; hosts that predate `minApp` only get the floor check.
pub fn check(app: &str, hello: &Value) -> Option<Mismatch> {
    if let Some(minimum) = hello["minApp"].as_str()
        && below(app, minimum)
    {
        return Some(Mismatch::UpdateApp {
            minimum: minimum.to_owned(),
        });
    }
    let version = hello["version"].as_str().unwrap_or_default();
    below(version, MIN_HOST).then(|| Mismatch::UpdateHost {
        version: version.to_owned(),
        minimum: MIN_HOST.to_owned(),
    })
}

/// What to update, in the words every client uses.
pub fn text(m: &Mismatch, host: &str) -> String {
    match m {
        Mismatch::UpdateApp { minimum } => {
            format!("Update this app: {host} needs Codync {minimum} or newer here.")
        }
        Mismatch::UpdateHost { version, minimum } => format!(
            "Update Codync on {host}: it runs {version}, this app needs {minimum} or newer."
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn floor_never_exceeds_this_release() {
        assert!(!below(env!("CARGO_PKG_VERSION"), MIN_HOST));
        assert!(parse(MIN_HOST).is_some());
    }

    #[test]
    fn decides_which_side_updates() {
        assert_eq!(
            parse("2.10.0").cmp(&parse("2.9.9")),
            std::cmp::Ordering::Greater
        );
        assert_eq!(parse("v2.4"), Some((2, 4, 0)));
        assert_eq!(parse("2.4.0-dev"), Some((2, 4, 0)));
        assert_eq!(parse("2.4.0.1"), None);
        assert_eq!(
            check("2.4.0", &json!({"version": "2.4.0", "minApp": "2.3.0"})),
            None
        );
        assert_eq!(
            check("2.4.0", &json!({"version": "2.6.0", "minApp": "2.5.0"})),
            Some(Mismatch::UpdateApp {
                minimum: "2.5.0".into()
            })
        );
        assert_eq!(
            check("2.4.0", &json!({"version": "2.2.3"})),
            Some(Mismatch::UpdateHost {
                version: "2.2.3".into(),
                minimum: MIN_HOST.into()
            })
        );
        // Unknown versions never lock anyone out.
        assert_eq!(check("2.4.0", &json!({})), None);
        assert_eq!(
            check("dev", &json!({"version": "2.6.0", "minApp": "2.5.0"})),
            None
        );
    }
}
