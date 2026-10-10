# Stage 2 — Railway backend (in progress)

Scope: PostgreSQL-backed owner bootstrap, authenticated API, Railway service, Android secure client. No telecom provider activation, payments, APK builds or Stage 3 work.

## Security design
- First email is an **unverified identifier**, never proof of identity.
- Bootstrap requires a high-entropy `BOOTSTRAP_SECRET` stored only in Railway variables and delivered to the owner out of band.
- A PostgreSQL transaction with advisory lock ensures exactly one bootstrap.
- Bootstrap issues a random bearer token; store it using Android Keystore-backed encryption, never plain preferences.
- Subsequent API calls use bearer authentication.
- For a lost device/token, reset ownership through an authenticated administrative recovery procedure, not email alone.
- Never log bearer tokens, bootstrap secret or user payloads.
- Deploy behind Railway TLS. Use PostgreSQL persistence rather than ephemeral filesystem.

## Routes
- GET /health — no private data
- POST /v1/bootstrap — requires X-Bootstrap-Secret and {email}; one-time
- GET /v1/me — requires Authorization: Bearer <token>

## Remaining gates
- Create Railway project and PostgreSQL service in chosen account.
- Deploy and test HTTPS health, bootstrap and authenticated endpoint.
- Android client integration, Keystore persistence and offline handling.
- Integration tests with PostgreSQL and CI; secret rotation/revocation.
- Verify Stage 1 branch and Stage 2 checks green.
