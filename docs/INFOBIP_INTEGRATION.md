# Infobip integration: staged activation

Branch `integration-infobip` derives from the existing Stage 4 branch, preserving owner/PIN/Keystore, PostgreSQL data, demo messaging and the native internal calling engine with live Cloudflare TURN. No APK, new Android project, duplicate database, number purchase or automatic paid operation.

## Implemented

- `InfobipProviderAdapter` separates SMS, read-only Calls REST access and the explicitly unintegrated RTC transport. Native fetch, fixed Infobip HTTPS hosts only, App authorization, rejected redirects, 10-second timeout, 64 KiB response bound, safe failure codes and no upstream content in diagnostics.
- Authenticated GET `/v1/providers/infobip/status`; owner-only POST `/v1/providers/infobip/check` performs **GET only** on `/sms/3/logs?limit=1` and `/calls/1/calls`. Only sanitized check outcomes are persisted. A 2xx confirms that read permission, not SMS-send permission, call initiation or WebRTC activation. 403 can mean missing scope/product access; it is not proof that the key is invalid. No destructive delivery-report polling.
- Owner-only POST `/v1/providers/infobip/sms-tests` calls official `/sms/3/messages` **only after** deployment opt-in, target allowlisting and request consent. One operation per owner per 24 hours; stable UUID and keyed request hash prevent concurrent duplicate sends. The claim commits before external I/O. Accepted is not delivered. Timeout, 5xx or lost response becomes UNKNOWN and is never automatically resent. Stale SENDING becomes UNKNOWN after two minutes; reconcile before retrying.
- POST `/v1/providers/infobip/delivery` requires raw-body HMAC-SHA256 in `X-Hub-Signature`. Hex, sha256-prefixed hex or base64 encoding is supported; compare is constant-time. The Infobip notification profile must be configured with matching HMAC settings. Missing secret fails closed. Semantic receipt hashes deduplicate retries; signed events are time bounded, scoped to recorded Infobip operations and cannot downgrade terminal states. Unsolicited/unrecognized IDs do not create records. This is a symmetric signing-secret validation, not IP-based source guessing.
- PostgreSQL migration 003 adds provider checks, encrypted outbound test operations and delivery receipt metadata in the same database. Call-operation kind is reserved for future Voice activation; no external call record is falsely created. GET `/v1/providers/infobip/operations` exposes scoped metadata, not message bodies or secrets. These explicit operator tests are separate from existing demo conversations; normal messaging synchronization is not silently switched to SMS.
- Android Numbers → “حالة Infobip واختباره” opens Arabic status/read-only diagnostics. It contains no provider credential, cannot send SMS, and clearly states Infobip RTC/Voice are not integrated. Internal Nawslk calls retain their existing controls and media engine.

## Required Railway settings

Existing project `789d86be-99df-48ec-9c5c-8c9749cc9f10`, production `ee24148c-bf68-45ab-bf20-ea44c37b33f6`, API `7b5dc694-8a65-4583-a16c-bd5ded336f4f`.

For read-only verification, add `INFOBIP_API_KEY` and `INFOBIP_BASE_URL=https://nd5wxj.api.infobip.com` directly in Railway Variables. Do not paste key values into GitHub/chat/reports. At the initial inventory, both API variables and production shared variables had **no Infobip key**. OAuth values are redacted; once names exist, provider verification must happen on the deployed server. Runtime startup picks up changes on redeployment.

For an explicitly approved future SMS test only:

| Setting | Meaning |
|---|---|
| INFOBIP_SMS_TEST_ENABLED | `true` only after number ownership, verified recipient and explicit approval of trial/paid consumption |
| INFOBIP_APPROVED_TEST_NUMBERS | Up to five E.164 destinations, comma separated; empty disables sending |
| INFOBIP_SMS_SENDER | Account-authorized sender, e.g. trial ServiceSMS when permitted; never invented sender rights |
| INFOBIP_WEBHOOK_SECRET | At least 32 characters, same signing key in Infobip HMAC-SHA256 authentication settings |
| INFOBIP_SMS_WEBHOOK_URL | `https://nawslk-api-production.up.railway.app/v1/providers/infobip/delivery`; matching standalone notification profile must apply signing |

The adapter does not create subscriptions, provision numbers or enable paid service automatically. A live HMAC callback must be tested before enabling ongoing real traffic. Raw message IDs/destinations remain in private database rows, not application logs. No API key is stored in PostgreSQL or sent to Android.

## RTC SDK compatibility gate

Reviewed the official SDK and Maven Central release `com.infobip:infobip-rtc:2.5.41`. Its POM brings `com.infobip:google-webrtc:1.0.47976t`; inspecting that AAR found 380 `org/webrtc` classes, including PeerConnection. The existing application already uses `io.github.webrtc-sdk:android:125.6422.07`. Adding both unmodified creates overlapping class/native-library ownership. Blindly excluding either WebRTC dependency would assume unverified Java/JNI ABI compatibility. The official SDK was therefore **not added**, and no Infobip call/RTC success is claimed.

Next RTC work must choose a single compatible WebRTC distribution or a safely isolated build/transport, preserve one active-call coordinator, verify native linkage and lifecycle, then implement server-issued subscriber tokens and Calls routing. It also needs actual WebRTC/Voice channel activation and account/application details. REST credentials remain server-only; short-lived subscriber tokens are not API keys. This gate is an unresolved implementation task, not a completed SDK integration.

## Sources and limits

- https://www.infobip.com/docs/tutorials/send-your-first-sms-message-using-infobip-api
- https://www.infobip.com/docs/tutorials/receive-sms-delivery-report-using-infobip-api
- https://www.infobip.com/docs/subscriptions/subscription-components
- https://www.infobip.com/docs/essentials/api-essentials/api-authorization
- https://www.infobip.com/docs/voice-and-video/calls/troubleshooting
- https://github.com/infobip/infobip-rtc-android
- https://repo.maven.apache.org/maven2/com/infobip/infobip-rtc/2.5.41/infobip-rtc-2.5.41.pom

Infobip's self-signup SMS guide excludes Yemen from supported destinations; contact Infobip support for an account-specific approval rather than assuming +967 delivery. Trial limits, available numbers and Voice/RTC entitlement must be confirmed on the real account. No live SMS/call has been attempted.
