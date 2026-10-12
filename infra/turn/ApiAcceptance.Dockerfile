# Temporary pre-deploy validation image for plans with no spare service slots.
# Restore the normal root Dockerfile after collecting acceptance results.
FROM mcr.microsoft.com/playwright:v1.58.2-noble
WORKDIR /app
COPY backend/package.json backend/package-lock.json ./
RUN npm ci
COPY backend/ ./
EXPOSE 3000
CMD ["node", "src/server.mjs"]
