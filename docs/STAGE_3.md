# Stage 3 — WebRTC calling

Branch: stage-3-calling-webrtc

Scope: calling domain, call lifecycle, Android audio and incoming-call experience, WebRTC signaling and peer-to-peer audio when signaling and network infrastructure are available, and call history.

Stage 3 must not claim that ordinary PSTN numbers are callable before a licensed provider and number routing are configured. The demo transport never dials real numbers.

## Incremental gates
1. Call model, transitions and unit tests.
2. Persisted call history, call screen, call controls.
3. Signaling authentication, scoped sessions, expiry and replay protection.
4. WebRTC audio engine, permission handling, audio focus and foreground service.
5. Incoming call notifications and lock screen behavior, Android 8+ compatibility.
6. Integration tests, security and rights review, CI green.

No APK in this stage. After Stage 5, a separate cross-stage integration phase is planned.
