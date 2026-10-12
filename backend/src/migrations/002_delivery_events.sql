CREATE TABLE IF NOT EXISTS delivery_events (
 id BIGSERIAL PRIMARY KEY, provider_key TEXT NOT NULL, event_id TEXT NOT NULL,
 message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
 status TEXT NOT NULL, event_at TIMESTAMPTZ NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 UNIQUE(provider_key,event_id)
);
CREATE TABLE IF NOT EXISTS webhook_nonces (
 provider_key TEXT NOT NULL, nonce TEXT NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(provider_key,nonce)
);
