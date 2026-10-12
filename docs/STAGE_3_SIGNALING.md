# Stage 3 signaling contract

The account owner, trusted device, and peer are distinct IDs. Legacy `owner_device.id` is the root owner/device identity; `trusted_devices.id` is a delegated participant referencing that owner. Email never authorizes access. No open account signup was added.

## Enrollment

| Method | Path | Authorization / behavior |
|---|---|---|
| POST | /v1/bootstrap | Out-of-band bootstrap secret, email metadata; singleton owner, including concurrent requests |
| GET | /v1/me | Active owner/device bearer; returns deviceId, ownerId, email, isOwner |
| POST | /v1/devices/invites | Owner only; 256-bit one-use token, ten-minute TTL; token hash stored |
| POST | /v1/devices/redeem | Invite and label; atomically consumes invite and issues a distinct device token |
| GET | /v1/devices | Active devices in this owner's account, never their tokens |
| DELETE | /v1/devices/:id | Owner or the delegated device itself; revokes token and ends its sessions |

Root owner recovery/rotation remains out of scope: a lost root token does not authorize taking over by email or re-bootstrap. Delegated bearer tokens persist until revocation, are hashed in PostgreSQL, and encrypted with Android Keystore on each device. Invitations are sensitive; share them out of band and never commit them.

## Calls (all routes authenticated)

| Method | Path | Contract |
|---|---|---|
| POST | /v1/calls/sessions | `{peerId}`; same-owner authorized devices; participant locks prevent concurrent/cross calls |
| GET | /v1/calls/incoming | Ringing, unexpired sessions addressed to this device |
| GET | /v1/calls/sessions/:id | Participant scoped; terminal state or `expired` flag is observable |
| POST | /v1/calls/sessions/:id/accept | Callee only, unexpired ringing -> accepted |
| POST | /v1/calls/sessions/:id/reject | Callee only, unexpired ringing -> ended/rejected |
| DELETE | /v1/calls/sessions/:id | Either participant -> ended |
| POST | /v1/calls/sessions/:id/heartbeat | Accepted and unexpired only; renews two-minute lease |
| POST | /v1/calls/sessions/:id/signals | `{kind,payload,messageId:UUID}`; accepted session required |
| GET | /v1/calls/sessions/:id/signals?after=0 | Ordered remote messages, 50 per page, participant authorization in SQL |
| GET | /v1/calls/ice-config | Short-lived authenticated TURN credentials; 503 when unconfigured |

Only the caller may offer; only the callee may answer, after the offer. One offer and answer per session; ICE can arrive before remote SDP and is buffered by Android. Retrying the same message ID/payload is idempotent; changing its payload or submitting another SDP is rejected. Reconnect after a terminal ICE failure requires a new call, not replaying negotiation. ICE continual gathering supports recoverable network changes, but external network recovery is not verified.

Ringing expires after two minutes; active sessions require heartbeat every 20 seconds. Terminal/expired sessions cannot append signals. A daily retention window is enforced by minute-based cleanup (expired rows removed after one day); no SDP/ICE is logged. The HTTP body limit is 24 KiB, signaling payload limit 16 KiB measured in UTF-8, and 512 messages per session. Limiters allow 10 public auth requests/minute per socket identity, and 240 authenticated requests/minute/device. This single-replica limiter must be replaced by shared state before scaling; behind a reverse proxy the public auth socket bucket is intentionally shared rather than trusting spoofable client headers.

## ICE infrastructure

Configure `TURN_URLS` as comma-separated `turn:` / `turns:` URLs and `TURN_SHARED_SECRET` as the coturn-compatible REST credential secret. Credentials use HMAC-SHA1 with a one-hour timestamp and authenticated device ID; the permanent secret stays on the backend. No TURN service, secret, or telecom account was fabricated. The current deployment lacks these settings and returns 503. Optional STUN discovery is not a substitute for TURN; the Android engine accepts ICE server lists, while this endpoint currently supplies the configured TURN list.

HTTP polling remains the SDP/ICE signaling transport. Optional FCM high-priority data delivery now announces a ringing call to Android without background microphone startup. `/v1/push/config` returns authenticated public Firebase client config; PUT/DELETE `/v1/devices/push-token` registers/unregisters the current device. Call creation and push-outbox insertion are atomic. Push sender configuration is absent in production, so foreground polling remains the live fallback. See STAGE_3_PRODUCTION_SETUP.md. An active call continues in its microphone foreground service; FCM does not bypass Android permission, forced-stop or battery restrictions.
