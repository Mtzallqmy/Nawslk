# Stage 3 production activation

Existing Nawslk Railway project/API/PostgreSQL reused. Cloudflare TURN was activated with the user's TURN app; no additional Railway service or database was provisioned. See TURN_CLOUDFLARE_ACCEPTANCE.md for live relay evidence.

## Managed Cloudflare TURN

Cloudflare TURN uses its credential-generation API rather than the coturn shared-secret algorithm. The Android ICE endpoint stays unchanged. Set `TURN_PROVIDER=cloudflare`, `CLOUDFLARE_TURN_KEY_ID` and `CLOUDFLARE_TURN_API_TOKEN` on the existing API service. Keep the long-lived API token exclusively on the server; never use it as an Android ICE password. The server requests one-hour credentials over fixed HTTPS, rejects redirects, bounds upstream response bytes/time, strips blocked port-53 alternatives, coalesces concurrent requests and caches independently per authenticated device. Upstream failure returns safe 503; `turnConfigured=true` only means settings are usable, not external media acceptance. Omit TURN_PROVIDER or use `coturn` for the original shared-secret path.

`infra/turn/Probe.Dockerfile` and `backend/scripts/validate-turn.mjs` provide a disposable no-database job for production credential issuance and forced-relay generated audio on UDP, TCP and TLS separately. It needs only the Cloudflare TURN variables, has no public HTTP endpoint, and must use restartPolicy NEVER and be removed when results have been collected. It does not replace authorized two-Android-device testing. Logs contain outcomes/transport only, never long-lived or temporary credentials.

## Alternate coturn and optional FCM settings

Set these on the existing Railway API service, using Railway's variables UI (do not commit them):

| Variable | Meaning |
|---|---|
| TURN_URLS | Coturn-compatible approved TURN URLs, comma separated, e.g. `turn:HOST:3478?transport=udp,turns:HOST:5349?transport=tcp` (HOST is a placeholder, not a configured server) |
| TURN_SHARED_SECRET | REST-auth shared secret for that TURN deployment |
| STUN_URLS | Optional approved `stun:` / `stuns:` discovery URLs; TURN remains required |
| FCM_SERVICE_ACCOUNT_JSON | Firebase service account JSON with permission to send messages using FCM HTTP v1 |
| FCM_ANDROID_CONFIG | JSON with public Firebase client identifiers: `projectId`, `applicationId`, `apiKey`, `gcmSenderId` |

Register Android package `com.nawslk.app` in the Firebase project. Map `project_info.project_id` to `projectId`, `client.client_info.mobilesdk_app_id` to `applicationId`, `client.api_key.current_key` to `apiKey`, and `project_info.project_number` to `gcmSenderId` from that app's Google services config. Restrict the Firebase API key appropriately. `FCM_SERVICE_ACCOUNT_JSON.project_id` must match the Android configuration's project. Enable the Firebase Cloud Messaging v1 API.

Neither the service-account private key nor permanent TURN secret is shipped to Android. The Firebase client config is authenticated metadata cached on the device for Firebase initialization after process restart. Device push tokens are encrypted using AES-GCM with a domain-separated key derived from the server bootstrap secret; rotating that secret requires re-registering tokens.

## Push lifecycle

1. Authenticated Android profile setup fetches `/v1/push/config` and obtains the Firebase registration token.
2. `PUT /v1/devices/push-token` associates it only with that authenticated device. Duplicate token ownership is rejected. DELETE unregisters it; device revocation removes the registration.
3. Creating a call inserts its outbox row in the same PostgreSQL transaction as its signaling session.
4. A bounded worker leases eligible ringing sessions and sends high-priority data messages using service-account OAuth and FCM HTTP v1. Tokens and message contents are never logged. Delivery is retried up to five attempts and stale sessions are skipped. Unregistered tokens are removed.
5. FirebaseMessagingService validates the call UUID and expiry and posts a private call notification. It never starts the microphone. The notification opens the Activity; local PIN and live authenticated server checks still apply before reply.
6. FCM duplicate delivery updates the same notification rather than authorizing another media session. Forced stop, notification denial, missing Play services, offline devices and OEM restrictions can prevent delivery. No unconditional wake-up guarantee is made.

## Verified versus pending

CI uses a disposable coturn container, fixed test-only credential material, forced relay candidates and actual Chromium audio transport. It tests production-style HMAC TURN credentials without creating a production TURN host. A temporary CI-only UDP firewall drop is measured using stopped RTP counts and audio recovery after restoring delivery; the rule is removed in finally cleanup. Chromium CDP packet-loss emulation did not affect established transport in the first test, so no recovery claim was based on that attempt. Physical Android microphone/headset/lifecycle and production FCM delivery still require device verification.

`GET /health` reports boolean `calling.turnConfigured` and `calling.pushConfigured`, without exposing secrets. Presence/parse checks do not prove external connectivity. Production Cloudflare TURN is now configured and externally verified on UDP/TCP/TLS; Firebase remains unconfigured. Physical-device and background delivery acceptance remain open.

## Phase ordering

Stage 3 production acceptance remains conditional on device tests and the original background incoming-call requirement. Independent Stage 4 demo messaging work is deployed on `stage-4-messaging-numbers`; see STAGE_4_ACCEPTANCE.md. TURN is activated; this does not close all of Stage 3, activate a telecom provider, authorize Stage 5/6 release, or package an APK.
