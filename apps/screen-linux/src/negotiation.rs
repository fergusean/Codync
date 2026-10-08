//! WebRTC SDP and ICE negotiation.

use crate::stream::Session;
use anyhow::{Context, Result, anyhow, bail};
use gst::prelude::*;
use std::time::Duration;
use tokio::sync::oneshot;

impl Session {
    /// Answers once a relay route is available or gathering has completed.
    pub async fn answer(&self, offer: &str) -> Result<String> {
        let sdp = gst_sdp::SDPMessage::parse_buffer(offer.as_bytes())
            .map_err(|_| anyhow!("bad offer"))?;
        let offer =
            gst_webrtc::WebRTCSessionDescription::new(gst_webrtc::WebRTCSDPType::Offer, sdp);
        let (p, done) = promise();
        self.webrtc
            .emit_by_name::<()>("set-remote-description", &[&offer, &p]);
        done.await?;
        let (p, done) = promise();
        self.webrtc
            .emit_by_name::<()>("create-answer", &[&None::<gst::Structure>, &p]);
        let reply = done.await?.ok_or_else(|| anyhow!("no answer"))?;
        let answer = reply
            .get::<gst_webrtc::WebRTCSessionDescription>("answer")
            .map_err(|_| anyhow!("no answer"))?;
        let (p, done) = promise();
        self.webrtc
            .emit_by_name::<()>("set-local-description", &[&answer, &p]);
        done.await?;
        // Negotiate before the encoder chooses fixed caps. Starting first can
        // produce High-profile caps that reject a phone's Baseline offer.
        self.pipeline
            .set_state(gst::State::Playing)
            .context("starting the video pipeline")?;
        for _ in 0..200 {
            let local = self
                .webrtc
                .property::<Option<gst_webrtc::WebRTCSessionDescription>>("local-description");
            if local.as_ref().is_some_and(|s| has_relay_candidate(s.sdp()))
                || self
                    .webrtc
                    .property::<gst_webrtc::WebRTCICEGatheringState>("ice-gathering-state")
                    == gst_webrtc::WebRTCICEGatheringState::Complete
            {
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        let local = self
            .webrtc
            .property::<Option<gst_webrtc::WebRTCSessionDescription>>("local-description");
        let local = local.unwrap_or(answer);
        local
            .sdp()
            .as_text()
            .map_err(|_| anyhow!("couldn't write the answer"))
    }
}

/// One relay candidate is enough for a non-trickle answer; later duplicate
/// TURN transports must not delay a route that can already carry the session.
fn has_relay_candidate(sdp: &gst_sdp::SDPMessageRef) -> bool {
    sdp.medias().any(|media| {
        media.attributes().any(|attribute| {
            attribute.key() == "candidate"
                && attribute.value().is_some_and(|candidate| {
                    candidate
                        .split_ascii_whitespace()
                        .collect::<Vec<_>>()
                        .windows(2)
                        .any(|pair| pair == ["typ", "relay"])
                })
        })
    })
}

/// The answer must use the phone's payload number, which is not necessarily 96.
pub(super) fn h264_payload(offer: &str) -> Result<i32> {
    let sdp =
        gst_sdp::SDPMessage::parse_buffer(offer.as_bytes()).map_err(|_| anyhow!("bad offer"))?;
    for media in sdp.medias().filter(|m| m.media() == Some("video")) {
        for format in media.formats() {
            let Ok(payload @ 0..=127) = format.parse::<i32>() else {
                continue;
            };
            let Some(caps) = media.caps_from_media(payload) else {
                continue;
            };
            let Some(codec) = caps.structure(0) else {
                continue;
            };
            if codec.get::<&str>("encoding-name").ok() == Some("H264")
                && codec.get::<i32>("clock-rate").ok() == Some(90_000)
                && codec.get::<&str>("packetization-mode").ok() == Some("1")
            {
                return Ok(payload);
            }
        }
    }
    bail!("the phone did not offer packetized H.264 video")
}

/// Convert WebRTC's ICE URL syntax to GStreamer's URI syntax without exposing credentials.
pub(super) fn ice_uri(
    url: &str,
    username: Option<&str>,
    credential: Option<&str>,
) -> Result<String> {
    let (scheme, address) = url.split_once(':').context("invalid ICE URL")?;
    if scheme == "stun" {
        return Ok(format!("stun://{address}"));
    }
    if !matches!(scheme, "turn" | "turns") {
        bail!("unsupported ICE scheme");
    }
    let username = username.context("missing TURN username")?;
    let credential = credential.context("missing TURN credential")?;
    let escape = |s: &str| -> String {
        use std::fmt::Write as _;
        s.bytes().fold(String::new(), |mut out, byte| {
            if byte.is_ascii_alphanumeric() || b"-._~".contains(&byte) {
                out.push(char::from(byte));
            } else {
                let _ = write!(out, "%{byte:02X}");
            }
            out
        })
    };
    Ok(format!(
        "{scheme}://{}:{}@{address}",
        escape(username),
        escape(credential)
    ))
}

fn promise() -> (gst::Promise, oneshot::Receiver<Option<gst::Structure>>) {
    let (tx, rx) = oneshot::channel();
    let p = gst::Promise::with_change_func(move |reply| {
        let _ = tx.send(reply.ok().flatten().map(ToOwned::to_owned));
    });
    (p, rx)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    #[test]
    fn relay_answer_does_not_wait_for_duplicate_turn_transports() {
        gst::init().unwrap();
        let offer = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n\
            m=video 9 UDP/TLS/RTP/SAVPF 102\r\n\
            a=candidate:1 1 UDP 2130706431 192.0.2.1 5000 typ host\r\n";
        let host_only = gst_sdp::SDPMessage::parse_buffer(offer.as_bytes()).unwrap();
        assert!(!has_relay_candidate(&host_only));
        let relay = format!(
            "{offer}a=candidate:2 1 UDP 16777215 198.51.100.1 6000 typ relay raddr 192.0.2.1 rport 5000\r\n"
        );
        let relay = gst_sdp::SDPMessage::parse_buffer(relay.as_bytes()).unwrap();
        assert!(has_relay_candidate(&relay));
    }

    #[test]
    fn video_uses_the_offered_h264_payload_instead_of_a_fixed_number() {
        gst::init().unwrap();
        let offer = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n\
            m=audio 9 UDP/TLS/RTP/SAVPF 99\r\na=rtpmap:99 H264/90000\r\n\
            a=fmtp:99 packetization-mode=1\r\n\
            m=video 9 UDP/TLS/RTP/SAVPF 96 100 102\r\n\
            a=rtpmap:96 VP8/90000\r\na=rtpmap:100 H264/90000\r\n\
            a=fmtp:100 packetization-mode=0\r\na=rtpmap:102 H264/90000\r\n\
            a=fmtp:102 packetization-mode=1;profile-level-id=42e01f\r\n";
        assert_eq!(h264_payload(offer).unwrap(), 102);
        assert!(
            h264_payload(&offer.replace("packetization-mode=1", "packetization-mode=0")).is_err()
        );
    }

    #[test]
    fn turn_uri_preserves_transport_and_escapes_credentials() {
        assert_eq!(
            ice_uri(
                "turns:turn.cloudflare.com:443?transport=tcp",
                Some("a:b"),
                Some("c+/@")
            )
            .unwrap(),
            "turns://a%3Ab:c%2B%2F%40@turn.cloudflare.com:443?transport=tcp"
        );
        assert_eq!(
            ice_uri("stun:stun.cloudflare.com:3478", None, None).unwrap(),
            "stun://stun.cloudflare.com:3478"
        );
        assert!(ice_uri("turn:turn.cloudflare.com:3478", None, None).is_err());
    }
}
