# Cloudflare TURN activation — 2026-10-12

Decision: **production TURN activated and generated WebRTC audio verified**. Stage 3 overall stays in progress for physical Android and background incoming-call acceptance. No APK or main merge.

## Implementation and deployment

Code commit `23c11f5cd48c23128fa291716ec26675d2cbeec7` on `stage-4-messaging-numbers`; Cloudflare provider implementation at `4d5985ee5dc145cdf46b19b36dfc9e01f3d8487d`. Credentials are generated server-side over fixed HTTPS, expire after one hour, are independently cached per authenticated device, and exclude the long-lived API token. API errors are bounded/safe 503 responses. Existing coturn HMAC compatibility is preserved. No Android dependency or telecom provider was added.

The user created the Cloudflare TURN app. Its long-lived token is stored only in the existing Railway API variables; no value, temporary credential or account identifier is recorded in this report. An exact-token check confirmed it is absent from tracked source. Existing bootstrap variables and owner/device records were preserved.

The Railway free-plan resource limit prevented a separate validation service; no new service was created. The existing API used a temporary Playwright image and pre-deploy command for a read-only relay test, with no owner/bootstrap/database operation. The repository's fixed Dockerfile setting initially overrode service selection; removing that override enabled the temporary image. The normal root Dockerfile and empty pre-deploy commands were restored after the test.

Final deployment: `2cf1167b-1dca-43e5-acc0-65d2a26daf54`, **SUCCESS**, pinned to the tested code commit. Project, API, PostgreSQL and production environment are unchanged. HTTPS `/health` returned HTTP 200, database connected, bootstrap checksum verified, `turnConfigured=true`, `pushConfigured=false`. No test browser remains in the normal production image.

## Evidence

| Check | Result |
|---|---|
| Production Cloudflare credential issuance | HTTP 201 independently observed; pre-deploy provider issued one-hour credentials |
| Cloudflare UDP relay | Bidirectional generated audio, measured inbound RTP and non-zero received audio spectrum; forced relay; passed |
| Cloudflare TCP relay | Same media acceptance, TCP-only TURN URLs; passed |
| Cloudflare TLS relay | Same media acceptance, TLS-only TURN URLs; passed |
| Media cleanup | Tracks stopped, peers closed, audio contexts and browsers closed after each transport |
| Backend + PostgreSQL CI | https://github.com/Mtzallqmy/Nawslk/actions/runs/38299870157 — success; 28 tests, zero failures/skips |
| Android compile/JVM/Robolectric CI | https://github.com/Mtzallqmy/Nawslk/actions/runs/38299870172 — success; no APK task |
| Calling regressions | Authorized Chromium audio, mute/unmute, hangup/cleanup, real UDP loss/recovery and disposable coturn forced relay passed |
| Provider failures | Invalid configuration, failed/upstream malformed responses, bounded response size, retry cleanup, device-isolated cache and no secret in client responses tested |

Live media evidence is in validation deployment `9e8c3676-208a-4acc-90de-a5a93c578173` logs: credential event followed by `turn_media_validation` with `passed=true` separately for udp, tcp and tls (19:48:59–19:49:01 UTC). These tests used Cloudflare's public relay from Railway, not the localhost coturn fixture. They used two PeerConnections and a generated tone, not two physical Android microphones or the live owner account. Authentication/signaling were independently exercised in the PostgreSQL CI suite.

## Remaining acceptance

- Physical Android-to-Android microphone, reply/reject/end/mute, wired/Bluetooth routing, phone interruption, network handover, process death and history checks remain. Browser/Robolectric evidence does not replace hardware checks.
- Firebase settings and actual background delivery remain absent. Foreground polling works only while the app is open and authenticated. A personal foreground-only scope may defer push, but does not satisfy the original background incoming-call requirement. No unconditional force-stop wake promise.
- Stage 4 demo/offline conversations and numbers foundation is already deployed on this branch; see STAGE_4_ACCEPTANCE.md. Real SMS, inbound delivery and number assignment require a licensed provider account and vendor-specific integration. Those adapters remain disabled.

Source and evidence are saved in GitHub. Stages 3/4 are not declared fully complete, and no Stage 5/6 release is produced.
