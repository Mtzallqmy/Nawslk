# Stage 3 signaling contract

All endpoints require a bearer token issued by the owner bootstrap.

- POST /v1/calls/sessions — body {peerId: UUID}
- GET /v1/calls/sessions/:id
- DELETE /v1/calls/sessions/:id
- POST /v1/calls/sessions/:id/signals — body {kind: offer|answer|ice, payload: JSON object}
- GET /v1/calls/sessions/:id/signals?after=0 — ordered signaling messages

The backend uses PostgreSQL and 2-minute session expiry. Access is scoped to the session's two participants. ICE candidate content is untrusted and should be validated before passing to a WebRTC engine. The server currently has a single-owner bootstrap; no second participant can enroll, so a two-party end-to-end call is not yet possible.

Remaining work: authenticated multi-device/peer enrollment, rate limiting, signaling message validation, message lifecycle, WebRTC audio media engine, STUN/TURN configuration, incoming call UX, notifications and foreground service. Never claim that PSTN numbers are dialable without a licensed telecom provider.
