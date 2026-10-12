# Stage 3 acceptance — 2026-10-12

Decision: **IN PROGRESS**. All implementation changes are on `stage-3-calling-webrtc`, without main merge or APK packaging.

## Confirmed baseline and changes

Preserved Stage 1 local owner/PIN/DataStore and Stage 2 owner bootstrap/PostgreSQL/Keystore. Fixed README-only Railway source deployment and domain port mismatch. Added device invitations/revocation and account-scoped calls; enforced transactional concurrency, SDP roles/order, replay guards, expiry and bounded requests. Added native WebRTC audio, foreground service, microphone permission, mute/speaker/headset controls, Arabic real-call UI and separate existing demo, and bounded local history.

## Evidence

- Android code plus existing unit tests passed at commits `3733c2e` and `0900d06` using `:app:compileDebugKotlin :app:testDebugUnitTest`; no APK task.
- Backend run https://github.com/Mtzallqmy/Nawslk/actions/runs/38189328701 passed 13 Node tests, including the live PostgreSQL API suite, with zero skips. The initial separate media test exposed absent Chromium RTP playout; attaching a playback element fixed the harness. Backend run https://github.com/Mtzallqmy/Nawslk/actions/runs/38189673170 at `2d16a1c` passed the complete suite, including bidirectional DTLS-SRTP tone audio, RTP reception, mute/unmute, hangup and media-track cleanup.
- Production HTTPS `/health` verified as HTTP 200 with `database: connected` and `bootstrapConfigVerified: true`. Production startup requires and compares both bootstrap settings; no secrets were exposed. Railway OAuth only supplies names, so raw value/reference inspection is unavailable.
- Existing Railway project `789d86be-99df-48ec-9c5c-8c9749cc9f10`, production `ee24148c-bf68-45ab-bf20-ea44c37b33f6`, API `7b5dc694-8a65-4583-a16c-bd5ded336f4f`, PostgreSQL `b1973c05-4cba-4cfa-bb0e-4876b1f214a3` reused.

## Acceptance matrix

| Gate | Status / scope |
|---|---|
| Call state transitions / local concurrency | Existing Android unit tests plus PostgreSQL participant-lock test |
| Auth, bootstrap singleton, trusted-device permissions | Live API/PostgreSQL integration passed |
| Expiry, offer/answer, replay and unauthorized calls | Live API/PostgreSQL integration passed |
| Real browser WebRTC audio | Passed Chromium CI tone test (two isolated contexts); not a physical Android microphone call |
| Android microphone / mute / cleanup | Implemented; physical runtime test pending |
| Active foreground service / interruption / Bluetooth | Implemented; Android 8–15+ device matrix pending |
| Network loss / recovery | Bounded retries and timeout implemented; external network handover/ICE restart not verified |
| Incoming call UI / notification | Foreground polling implemented; killed/idle app push delivery pending |
| Local call history | Metadata save/load implemented; device validation pending |
| TURN across independent networks | Unconfigured; endpoint returns 503 rather than claiming STUN guarantees |
| PSTN | Separate unconfigured provider interface; no ordinary-number calling claim |
| APK / Stage 4 / main merge | None |

## Required next inputs and tests

An approved TURN deployment/provider with `TURN_URLS` and coturn-compatible `TURN_SHARED_SECRET` is needed. No TURN host or account was invented. A Firebase/FCM project or another approved push integration is needed to implement wake-up delivery with Android restrictions. Do not paste long-term secrets into source or reports.

Once configured, authorize two devices via a one-use invite; test accept/reject/hangup/mute on independent networks, verify no microphone use after cancellation (including hangup during accept), check live history, wired/Bluetooth routing, phone interruption, app background, notification denial and process death on Android API 26 and 35+. These tests cannot be replaced by successful compilation or Chromium tone transport.
