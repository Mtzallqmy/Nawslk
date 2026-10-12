# Stage 3 — internal WebRTC calling (IN PROGRESS)

Canonical branch: `stage-3-calling-webrtc`. No APK, release, main merge, or Stage 4 work.

## Existing baseline preserved

Android Kotlin/Compose RTL owner setup, PIN/PBKDF2/throttling, DataStore, Keystore token encryption, local/demo call models and state-machine tests. Node/PostgreSQL owner bootstrap and bearer authentication. Existing signaling tables migrated in place; no duplicate project or database.

## Implemented increment

- One-use trusted-device invitations, device identity separate from owner account, owner-only management, revocation and scoped participant access.
- Transactional call concurrency, callee accept/reject, session expiry/heartbeat, constrained SDP/ICE, replay IDs, request and message limits.
- Audio-only WebRTC SDK `io.github.webrtc-sdk:android:125.6422.07` (Maven Central; BSD upstream licensing). No video, recording, or PSTN transport. See https://github.com/webrtc-sdk/android and its LICENSE.
- Process-wide Android call runtime, real PeerConnection negotiation, ICE buffering, serialized outbound signaling with stable retry IDs, mute, speaker and connected-headset route controls.
- User-granted microphone access; foreground microphone service with persistent notification and hang-up action. Media resources are released before network shutdown. Audio-focus interruption ends the call; no phone-state permission requested.
- Arabic RTL incoming/ringing/connecting/active UI, separate preserved demo screen, readable failures, local history with peer/direction/time/connected duration/status/reason. History retains 100 entries in the existing JSON store; Room would add a second storage migration without current query/sync needs.
- HTTPS-only Android clients, redirects disabled for credentials, corrupted token ciphertext safely cleared, saved-session verification, system dark/light schemes.
- API/PostgreSQL concurrency and authorization tests. Dedicated Chromium bidirectional tone test transports audio through RTCPeerConnection/DTLS-SRTP with API SDP/ICE, checks received RTP/audio energy, mute/unmute and resource shutdown. It is not an Android device microphone test.

## Android support and limits

minSdk 26, target/compileSdk 35. Android 8+ notification channel; Android 11+ microphone service type; Android 13 notification prompt; Android 14 microphone FGS permission. The service is started from user actions on a visible screen after permission. If an outgoing call is accepted after the caller leaves the app, Android can deny a foreground microphone start; the call reports failure rather than starting capture secretly. Android 15 audio focus requires foreground state; device validation is still pending.

Wired/USB routes and Bluetooth route selection are coded; API 31+ requests optional BLUETOOTH_CONNECT, older versions use SCO. Physical headset behavior, phone interruption, lock screen, OEM battery restrictions, permission denial, process death and Android 8–15+ runtime checks remain acceptance tasks. No full-screen intent, background microphone startup, wake guarantees, or automatic audio recording.

## Network/recovery policy

Polling is bounded and retries transient server loss; WebRTC disconnected state is allowed 45 seconds to recover, then resources close. Signaling failures stop the call after eight failures. Each outbound signal retry keeps its message ID. No ICE restart renegotiation after FAILED is claimed: a new call is required. Reliable cross-network operation requires TURN; configuration is mandatory before the Android media engine starts.

## Infrastructure

Existing Railway project/environment/API/PostgreSQL reused. The original deployment targeted a README-only default branch and /health returned 502. Source was pinned to a Stage 3 commit, Dockerfile selected, healthcheck enabled, and PORT fixed at 3000 to match the public domain. Production startup compares BOOTSTRAP_SECRET's SHA-256 to BOOTSTRAP_SECRET_SHA256 and fails closed on missing/mismatched configuration. OAuth hides values; only the runtime consistency result is reported, never secrets.

## Gate still open

TURN credentials/infrastructure are missing. Incoming wake-up/push delivery for an idle/killed application is not implemented. A browser CI tone test does not prove Android microphones, speaker/Bluetooth, real network handover, or receiving under Android background restrictions. Stage 3 remains IN PROGRESS until those items and the acceptance matrix are verified. CI compilation deliberately does not package an APK.
