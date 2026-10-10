# Railway deployment — Moataz1

Project: nawslk
Project ID: 789d86be-99df-48ec-9c5c-8c9749cc9f10
Environment: production
Services: Postgres, nawslk-api
API health: https://nawslk-api-production.up.railway.app/health
Source: Mtzallqmy/Nawslk branch stage-2-backend-railway

The backend uses `DATABASE_URL=${{Postgres.DATABASE_URL}}` in Railway.
The bootstrap endpoint is disabled in practice until the owner sets a valid SHA-256 hash in `BOOTSTRAP_SECRET_SHA256`.
Generate a 32+ character random secret locally; compute SHA-256 locally and enter **only the digest** in Railway variables. Keep the original secret private. Do not post it in GitHub or chat.

This stage does not buy numbers, place calls, send SMS or produce APK.
