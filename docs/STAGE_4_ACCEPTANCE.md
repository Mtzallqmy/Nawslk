# Stage 4 acceptance — 2026-10-12

Decision: **independent demo messaging/number foundation accepted in CI and deployed**. Real telecom service is not activated. Stage 3 production calling acceptance stays open; no APK, main merge or Stage 5 transition.

## Tested code

Commit: `fb0a2efc6d7db36f3e485ea696f84cdae4b7870b` on `stage-4-messaging-numbers`.

| Evidence | Result |
|---|---|
| Android https://github.com/Mtzallqmy/Nawslk/actions/runs/38194939113 | Compile succeeded; 30 tests, zero failures/errors, verified from uploaded JUnit XML |
| Room migration and persistence | API 26 and 35 native SQLite Robolectric tests passed; v1 → v2 keeps drafts/messages |
| Compose UI | Four tests across API 26/35 passed: offline queue labels and honest unconfigured-number screen |
| Sync retry | Failed requests do not become SENT; explicit retry preserves the idempotency key |
| Node/PostgreSQL https://github.com/Mtzallqmy/Nawslk/actions/runs/38194939110 | 23 tests passed, zero skipped |
| Message API | Cross-account isolation, concurrent idempotency, consent, Unicode validation, encryption, 50-row cursor paging, 20 new messages/minute and cascade deletion passed |
| Generic webhook adapter | Signed bytes, timestamp bounds, nonce replay, duplicate event IDs and out-of-order terminal state protection passed with fixture-only SMS data |
| Stage 3 regression | Bidirectional Chromium audio, mute/unmute, actual UDP loss/recovery and forced coturn relay using API-issued credentials passed |
| Room schema | CI-exported version 2 matched the schema committed under app/schemas |
| Credentials scan | No matching private-key, GitHub PAT or OpenRouter/OpenAI secret patterns in tracked source; real secrets were not printed |

## Production deployment

Existing Railway project `789d86be-99df-48ec-9c5c-8c9749cc9f10`, production `ee24148c-bf68-45ab-bf20-ea44c37b33f6`, API `7b5dc694-8a65-4583-a16c-bd5ded336f4f`, PostgreSQL `b1973c05-4cba-4cfa-bb0e-4876b1f214a3` reused. No service/database/project duplication.

Deployment `01c60cf6-9548-496e-82dd-4ce5e303aca3` succeeded and is pinned to the tested code commit. Numbered migrations applied during startup without replacing owner/device/call tables. HTTPS health returned 200 with PostgreSQL connected and bootstrap checksum verified. Unauthenticated conversations returned 401. The unconfigured production vendor webhook returned 503.

## Delivered behavior

Arabic Compose conversation list, separate creation dialog usable on small screens, explicit contact consent, offline draft persistence, queued history, deterministic retry keys, manual authenticated demo synchronization, isolated account/local namespaces and access to pre-link local drafts. Messages are explicitly described as demo or unsent; no phone SMS permissions were added. The numbers screen connects to the authenticated API and does not invent available numbers.

Server message contents use authenticated encryption; local Room content is private app storage with backup disabled, not E2EE. Server deletion endpoint and 90-day terminal-message retention are implemented. UI local deletion is labeled accurately and can be re-imported from the server during later synchronization; it is not advertised as remote deletion.

## Open real-service gates

- Updated after this demo checkpoint: Cloudflare TURN is activated and live UDP/TCP/TLS audio passed; `/health` reports `turnConfigured=true`, `pushConfigured=false`. See TURN_CLOUDFLARE_ACCEPTANCE.md. Firebase and actual background incoming delivery to Android remain unverified.
- Physical Android microphone, headset/Bluetooth, phone interruption, process death and OEM background behavior remain device acceptance tasks. Robolectric and browser tests are scoped evidence.
- SMS/voice number provisioning, real sending/receiving and vendor-specific signature adapters need an approved licensed provider account, documented rights and explicit cost approval. The generic licensed adapter stays disabled; no real number or SMS delivery is claimed.

No release is produced from these results. Source and reports are committed to GitHub; Stage 5/6 acceptance remains future work.
