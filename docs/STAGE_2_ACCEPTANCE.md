# Stage 2 acceptance — current evidence

## Verified
- PostgreSQL service and persistent volume provisioned on Railway.
- API service deployment reported SUCCESS.
- GitHub Actions backend integration checks with PostgreSQL: SUCCESS on commit 7ad100ea.
- Android Kotlin compile and unit checks: SUCCESS on commit 7ad100ea.
- Backend URL configured in Android; client code uses HTTPS.
- Bearer tokens encrypted by Android Keystore helper.

## Not yet verified — release gates
- HTTPS endpoint response from outside Railway: independent request did not succeed through available browser tooling.
- Real first-owner bootstrap: disabled by sentinel bootstrap hash; requires owner-provided secure secret provisioning and controlled enrollment.
- Real Android device bootstrap and token persistence/restore, including reinstall and offline scenarios.
- Remote session revocation, rotation, request throttling, and production monitoring.
- Security review of owner PIN storage, which currently uses DataStore for verifier metadata.

**Stage 2 is not accepted until all release gates are satisfied. Stage 3 must not be treated as active implementation before this sign-off. No APK until Stage 5.**
