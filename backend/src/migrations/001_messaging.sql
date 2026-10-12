CREATE TABLE IF NOT EXISTS provider_accounts (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES owner_device(id),
 provider_key TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 licensed BOOLEAN NOT NULL DEFAULT FALSE, source_url TEXT, rights JSONB NOT NULL DEFAULT '{}',
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE(owner_id,provider_key)
);
CREATE TABLE IF NOT EXISTS virtual_numbers (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES owner_device(id),
 provider_account_id UUID NOT NULL REFERENCES provider_accounts(id),
 external_id TEXT NOT NULL, e164 TEXT NOT NULL CHECK (e164 ~ '^\+[1-9][0-9]{7,14}$'),
 status TEXT NOT NULL CHECK(status IN ('AVAILABLE','RESERVED','ACTIVE','SUSPENDED','EXPIRED','RELEASED')),
 capabilities TEXT[] NOT NULL DEFAULT '{}', expires_at TIMESTAMPTZ,
 source_url TEXT NOT NULL, rights JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 UNIQUE(provider_account_id,external_id), UNIQUE(id,owner_id)
);
CREATE TABLE IF NOT EXISTS number_assignments (
 id UUID PRIMARY KEY, number_id UUID NOT NULL UNIQUE, owner_id UUID NOT NULL,
 assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), expires_at TIMESTAMPTZ, rights_snapshot JSONB NOT NULL,
 FOREIGN KEY(number_id,owner_id) REFERENCES virtual_numbers(id,owner_id)
);
CREATE TABLE IF NOT EXISTS number_events (
 id BIGSERIAL PRIMARY KEY, event_id UUID NOT NULL UNIQUE, number_id UUID NOT NULL REFERENCES virtual_numbers(id),
 from_status TEXT, to_status TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS conversations (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES owner_device(id), cursor_seq BIGSERIAL NOT NULL UNIQUE,
 peer_e164 TEXT NOT NULL CHECK(peer_e164 ~ '^\+[1-9][0-9]{7,14}$'), display_label TEXT NOT NULL,
 mode TEXT NOT NULL CHECK(mode IN ('DEMO','SMS')), consent BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE(id,owner_id)
);
CREATE INDEX IF NOT EXISTS conversations_owner_cursor ON conversations(owner_id,cursor_seq DESC);
CREATE TABLE IF NOT EXISTS messages (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL, conversation_id UUID NOT NULL,
 cursor_seq BIGSERIAL NOT NULL UNIQUE, idempotency_key UUID NOT NULL, request_hash TEXT NOT NULL,
 ciphertext TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('DRAFT','QUEUED','SENDING','SENT','DELIVERED','FAILED','RECEIVED')),
 direction TEXT NOT NULL CHECK(direction IN ('OUTGOING','INCOMING')), mode TEXT NOT NULL CHECK(mode IN ('DEMO','SMS')),
 provider_key TEXT NOT NULL, provider_reference TEXT, failure_code TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 UNIQUE(owner_id,idempotency_key), FOREIGN KEY(conversation_id,owner_id) REFERENCES conversations(id,owner_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS messages_owner_conversation_cursor ON messages(owner_id,conversation_id,cursor_seq);
