# Infobip integration checkpoint — 2026-10-13 Asia/Aden

Decision: **PARTIALLY IMPLEMENTED, LIVE ACTIVATION BLOCKED**. No successful live SMS, Infobip call or Infobip SDK integration is claimed.

## Saved implementation

Independent branch `integration-infobip`, derived from `stage-4-messaging-numbers`. Commits:

- `52f8fdbc50936cad87751543e20896329cf3c780`: gated SMS v3 adapter, durable PostgreSQL test operations, authenticated delivery webhook and Arabic Android diagnostics.
- `8a461fce4db81e964eadbaaaa81053317784e2de`: safe startup read-access probe and documented configuration/SDK gates.
- `f6f4c20fab851e4b8100f1ccf8ea973fa6e5e32a`: short test message cap to avoid accidental multi-segment billing.

Owner/PIN/Keystore, existing messaging/Room, internal calls and Cloudflare TURN preserved. The standard messaging UI remains demo/offline; the new operator SMS test API is distinct, consented and disabled. No APK, main merge, new project/service/database or purchased number.

## Tests and evidence

| Evidence | Result |
|---|---|
| Backend + disposable PostgreSQL https://github.com/Mtzallqmy/Nawslk/actions/runs/38314047314 | 32 passed, zero failures/skips; provider HTTP is mocked |
| Android latest https://github.com/Mtzallqmy/Nawslk/actions/runs/38314047413 | Kotlin compilation and all JVM/Robolectric tests succeeded, without APK packaging |
| Android uploaded JUnit XML https://github.com/Mtzallqmy/Nawslk/actions/runs/38313715448 | Same Android source: 32 tests, zero failures/errors; includes Infobip UI on API 26 and 35 |
| Infobip SMS fixture | Correct `/sms/3/messages` payload and stable ID; missing approval/allowlist/consent prevents any upstream call |
| PostgreSQL provider tests | Concurrent duplicate creates one external fixture request; changed payload/cross-owner ID rejected; one/day quota; encrypted text; safe status persistence |
| Delivery fixtures | Raw-body HMAC verification, invalid signature rejection, durable replay deduplication and no terminal downgrade passed |
| Network uncertainty | Lost response remains UNKNOWN; repeated stable ID does not send again |
| Calling regression | Real generated Chromium audio, mute/unmute/cleanup, UDP outage/recovery and forced disposable coturn relay passed |
| Infobip network reachability | HTTPS GET to the supplied `/sms/3/logs?limit=1` without credentials returned 401; TLS verified. This proves reachability, not API-key validity |

## Railway

Existing project `789d86be-99df-48ec-9c5c-8c9749cc9f10`, production `ee24148c-bf68-45ab-bf20-ea44c37b33f6`, API `7b5dc694-8a65-4583-a16c-bd5ded336f4f`, original PostgreSQL reused.

Deployment `ae2779aa-32e4-4706-9edc-dd920df384b0`: **SUCCESS**, pinned to `f6f4c20fab851e4b8100f1ccf8ea973fa6e5e32a`. Migration 003 runs in startup before serving requests. Standard Dockerfile used; no browser/probe image. `INFOBIP_BASE_URL` set to the supplied URL and `INFOBIP_SMS_TEST_ENABLED=false`; no Infobip API key existed in API or shared production variables at inventory, including a recheck before deployment. No secrets were printed, generated or placed in source. Variable values are redacted by Railway OAuth, so live verification will run inside the service after the user adds the key and redeploys.

Post-deploy HTTPS checks: `/health` returned 200 with PostgreSQL connected, bootstrap checksum verified, Cloudflare TURN configured and push unconfigured. Unauthenticated provider status returned 401; delivery POST without configured signing/provider credentials returned 503. These are security/readiness checks, not a live provider delivery report.

## Remaining required work

1. Add `INFOBIP_API_KEY` in the existing Railway API Variables and redeploy. The startup probe performs only GET reads and stores/logs sanitized outcomes. Prove key validity and read scopes; do not equate read success with send/Voice/RTC activation. Account services remain UNKNOWN now.
2. Supply an owned, Infobip-verified destination and explicitly approve one short test SMS with its permitted free/paid consumption. Configure authorized sender and HMAC notification profile before enabling the test flag. No live test has run. Yemen is excluded by the self-signup SMS guide; account-specific support approval may be required.
3. Resolve the official SDK dependency collision: Infobip RTC 2.5.41 brings another org.webrtc distribution (380 overlapping-package classes inspected). Neither duplicate engines nor unverified JNI substitutions were added. Official Android RTC transport, subscriber token issuance, Calls routing, external call persistence and real call acceptance are **not implemented** at this checkpoint.
4. Integrate ongoing SMS conversation synchronization and an approved test-sending UI after activation. The current Android provider screen is read-only; it does not turn demo messages into actual SMS.
5. Perform real provider callbacks/SMS and a consented trial voice call only after the preceding inputs and transport work. Android hardware/background acceptance from Stage 3 also remains open.

Implementation details and official sources: INFOBIP_INTEGRATION.md. This checkpoint is not an assertion that the user's full Infobip success conditions are complete.
