# Isolated short-lived validation job. No API/DB access; no secrets during build.
FROM mcr.microsoft.com/playwright:v1.58.2-noble
WORKDIR /probe
COPY backend/package.json backend/package-lock.json ./
RUN npm ci
COPY backend/src/ice.mjs ./src/ice.mjs
COPY backend/scripts/probe-turn.mjs backend/scripts/validate-turn.mjs ./scripts/
CMD ["node", "scripts/validate-turn.mjs"]
