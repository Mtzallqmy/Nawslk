CREATE TABLE IF NOT EXISTS provider_checks (
 owner_id UUID NOT NULL REFERENCES owner_device(id), provider_key TEXT NOT NULL,
 result JSONB NOT NULL, checked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 PRIMARY KEY(owner_id,provider_key)
);
CREATE TABLE IF NOT EXISTS provider_operations (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES owner_device(id),
 provider_key TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('SMS','VOICE')),
 destination TEXT NOT NULL, request_hash TEXT NOT NULL, ciphertext TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('SENDING','ACCEPTED','DELIVERED','FAILED','UNKNOWN')),
 provider_reference TEXT, failure_code TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), event_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS provider_operations_owner ON provider_operations(owner_id,created_at DESC);
CREATE TABLE IF NOT EXISTS provider_receipts (
 provider_key TEXT NOT NULL, event_hash TEXT NOT NULL,
 operation_id UUID NOT NULL REFERENCES provider_operations(id), received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 PRIMARY KEY(provider_key,event_hash)
);
