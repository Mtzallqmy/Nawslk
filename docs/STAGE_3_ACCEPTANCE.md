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
| Incoming call UI / notification | FCM sender/receiver, registration and outbox implemented; live configuration/delivery verification pending |
| Local call history | Metadata save/load implemented; device validation pending |
| TURN across independent networks | Unconfigured; endpoint returns 503 rather than claiming STUN guarantees |
| PSTN | Separate unconfigured provider interface; no ordinary-number calling claim |
| APK / Stage 4 / main merge | None |

## Required next inputs and tests

An approved TURN deployment/provider with `TURN_URLS` and coturn-compatible `TURN_SHARED_SECRET` is needed. No TURN host or account was invented. A Firebase/FCM project configuration and scoped service-account credentials are needed to activate and verify the implemented push path. Do not paste long-term secrets into source or reports.

Once configured, authorize two devices via a one-use invite; test accept/reject/hangup/mute on independent networks, verify no microphone use after cancellation (including hangup during accept), check live history, wired/Bluetooth routing, phone interruption, app background, notification denial and process death on Android API 26 and 35+. These tests cannot be replaced by successful compilation or Chromium tone transport.

## Follow-up evidence — Stage 3 completion work

Code commit `c1affb1b6a6e0fe024b99a6d87b7a5a8f2f2b86f`:

- Android run https://github.com/Mtzallqmy/Nawslk/actions/runs/38191524614 succeeded. Includes Robolectric API 26 and 35 tests: private incoming notifications, stale/invalid call rejection, no microphone service started by a notification, persistent real-call history, session-scoped foreground readiness and audio-mode release. This is framework-shadow coverage, not hardware capture or real FCM delivery.
- Backend run https://github.com/Mtzallqmy/Nawslk/actions/runs/38191524543 succeeded: 17 tests, zero skips, PostgreSQL integration, encrypted push registration, duplicate-token ownership rejection and transactional push outbox delivery.
- Direct and forced TURN Chromium audio tests passed. Relay credentials were issued by the existing authenticated ICE endpoint using HMAC-SHA1 and accepted by a disposable coturn server. A temporary CI-only UDP drop stopped received RTP; removing it restored audio. Mute/unmute, signaling hangup and media-track cleanup also passed.
- FCM HTTP v1 OAuth sender, bounded retries, expiry, encrypted tokens, Android process-restored configuration and FirebaseMessagingService are implemented. Provider calls are tested with mock HTTP responses; live Firebase credentials remain absent.
- Railway deployment `114429a0-e9a4-4a92-9b9d-141e7d62d2d2` succeeded, pinned to the tested code commit. HTTPS health is 200, database connected, bootstrap configuration verified, and calling settings explicitly report `turnConfigured=false`, `pushConfigured=false`.

Stage 3 is still not closed: activate approved production TURN and Firebase configuration per STAGE_3_PRODUCTION_SETUP.md, verify real Android incoming delivery and hardware/lifecycle behavior, then transition to the supplied Stage 4 prompt. At that checkpoint no Stage 4 branch or APK had been created. Independent demo messaging work subsequently continued on stage-4-messaging-numbers; see STAGE_4_ACCEPTANCE.md. Stage 3 remains open.
