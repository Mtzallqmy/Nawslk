# Stage 1 security boundaries

The owner email is an unverified local label, not an authentication credential. Stage 1 must not expose backend privileges based on it. PIN derivation uses PBKDF2-HMAC-SHA256 with random salt; use secure local persistence and Android Keystore wrapping before production readiness. Never store telecom credentials in the APK. Disable Android backup of local owner state. Require explicit recovery design before marking stage 1 complete. No payments, no SMS sending, no live calls in Stage 1.
