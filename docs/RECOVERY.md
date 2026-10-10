# Owner recovery policy — Stage 1

This is a single-device offline prototype. The owner email is not verified and must not grant access to any remote service. There is no remote reset or email-based recovery.

If the PIN is lost, the supported recovery is **Android Settings → Apps → Nawslk → Storage → Clear storage** (or reinstall when application data is removed). This erases the locally stored owner identity and PIN verifier. The next launch permits a new owner bootstrap. This does not recover data; before storing real calls, messages, or purchased numbers, implement authenticated server ownership, encrypted backup and a recovery mechanism.

Threat model: this prototype is not suitable for storing telecom credentials, account balances or sensitive call records. A local PIN alone cannot defend against a rooted device or an attacker with device-level access.
