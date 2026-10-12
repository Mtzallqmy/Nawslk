# Stage 4 — messages and numbers (independent implementation)

Branch: `stage-4-messaging-numbers`, derived from Stage 3 code and its acceptance record. Independent messaging implementation continues while Stage 3's production TURN/Firebase/device acceptance stays explicitly open. This is not a Stage 3 completion claim or permission to publish a release. No APK, main merge, Stage 5 or paid provider activation.

## Scope implemented

- Kotlin/Compose Arabic RTL conversations, local drafts, queued messages, explicit demo synchronization, failure/retry and bounded history views. Light/dark follows the existing app theme.
- Room database `nawslk_messages.db`, version 2, non-destructive `MIGRATION_1_2`, exported schema committed in `app/schemas`. Account namespaces are isolated; the last authenticated scope remains available offline when its device token is stored. Local-only drafts are never silently uploaded into a newly linked account.
- Local writes and synchronization run off the UI thread. Last draft edits survive leaving a Compose tab; queueing flushes the draft writer. Room transactions plus unique idempotency keys prevent duplicate local queue entries; network retries reuse the same key. Failed and offline items are not described as successfully sent.
- PostgreSQL numbered, checksum-verified migrations under `backend/src/migrations`. They run under an advisory lock and retain existing Stage 1–3 owner/device/call data. Provider accounts, numbers, assignments/events, conversations, messages, delivery events and replay nonces use the existing database.
- Authenticated owner-account-scoped API, deterministic cursor pagination, explicit consent and E.164 validation, maximum 1600 Unicode code points, bounded HTTP body, account request limits and 20 new messages/minute/account. Message contents are AES-GCM encrypted on the server with a domain-separated key derived from the bootstrap secret. Raw bodies, destinations, tokens and provider credentials are not logged.
- MockMessageProvider makes no network request and returns `simulated:true` / `demo:` references. No number is fabricated or provisioned. Licensed adapters are guarded/unconfigured; adding a vendor requires verified account rights and explicit cost approval.
- Generic signed webhook adapter: exact-byte HMAC, timestamp window, nonce replay rejection, duplicate event IDs and terminal-state protection. Authoritative delivery events can advance through pending intermediate states if they arrive out of order. No live vendor webhook endpoint is enabled in production.
- Server deletion API removes message records and associated delivery events; terminal messages have a 90-day server retention window. UI local deletion is explicitly labeled local and does not claim remote erasure. Local Room history/drafts remain private app storage with Android app backup disabled, not end-to-end encryption.

## API contract

All ordinary endpoints require an active owner or trusted-device bearer. Delegated devices share only their owner's account data; a separate owner's rows are hidden.

| Method | Path | Contract |
|---|---|---|
| GET | /v1/numbers | Verified enabled licensed-provider rows only; currently empty, provider unconfigured |
| GET | /v1/conversations?before=0 | Latest 50 rows, descending sequence cursor; nextCursor or null |
| POST | /v1/conversations | `{id:UUID,peerE164,label,mode:"DEMO",consent:true}`; stable ID retry is checked |
| GET | /v1/conversations/:id/messages?after=0 | Next 50 messages, ascending sequence cursor; owner scoped |
| POST | /v1/conversations/:id/messages | `{id:UUID,idempotencyKey:UUID,body}`; same request returns the same ID, changed body conflicts |
| DELETE | /v1/conversations/:id | Owner-scoped deletion; message/delivery-event cascade |
| POST | /v1/providers/licensed-adapter/webhook | Disabled (503) in production. Test fixture only with NODE_ENV=test and WEBHOOK_TEST_SECRET |

Webhook test headers: `x-webhook-timestamp` (epoch seconds), `x-webhook-nonce` (16–128 safe characters), `x-webhook-signature` (hex HMAC-SHA256 of `timestamp + "." + nonce + "." + rawBody`). This is the generic adapter contract, not a claim of compatibility with a specific telecom vendor's signature scheme.

Message states: DRAFT → QUEUED → SENDING → SENT → DELIVERED; QUEUED/SENDING/SENT can fail; explicit retry can move FAILED → QUEUED; RECEIVED and DELIVERED are terminal. Demo SENT is always rendered as simulated, never as an SMS delivered to the destination.

Number states: AVAILABLE, RESERVED, ACTIVE, SUSPENDED, EXPIRED, RELEASED, with validated transitions. Provider metadata/rights and assignment models exist; provisioning has no enabled operation or invented inventory.

## Verification and remaining gates

Node unit/API/PostgreSQL tests cover singleton owner preservation, permissions, cross-account isolation, encrypted content, concurrent idempotency, consent, disabled real sending, cursor validation, signed/replayed/duplicate/out-of-order webhooks and cascade deletion. Android tests cover domain validation, Room migration, offline persistence, duplicate local queue keys and account isolation on API 26 and 35; Compose flow tests distinguish queued demo items and unconfigured numbers. See STAGE_4_ACCEPTANCE.md for exact latest CI evidence.

Real SMS sending, receiving, actual numbers and vendor-specific webhooks remain disabled until an approved account/adapter and cost authorization exist. No READ_SMS/SEND_SMS permission was introduced. Stage 3 production activation remains a separate open gate; see STAGE_3_PRODUCTION_SETUP.md.
