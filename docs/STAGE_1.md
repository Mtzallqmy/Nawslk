# Stage 1 — Foundation

Status: IN PROGRESS. Do not begin stage 2.

## Product constraints
- Android only, minSdk 26, Arabic-first RTL.
- Owner-only local email identifier; no email verification or login.
- No payments or production telecom API calls.
- Never interpret owner email as authentication for a remote server.
- Current owner setup is a prototype; production security requires PIN, Android Keystore, recovery and device-bound server authorization.
- Demo UI must clearly label unavailable features.

## Acceptance gates (not yet passed)
1. Android Gradle build succeeds on CI.
2. Fresh install shows email setup and persists valid email.
3. Relaunch skips setup.
4. Dialer never places a real call.
5. All five navigation destinations render on Android 8+.
6. Add secure PIN and local persistence architecture before marking Stage 1 complete.
7. Repository privacy set to Private before any operational integration.

## Later phases
Stage 2: Railway/backend; Stage 3: VoIP; Stage 4: SMS/numbers; Stage 5: release.
