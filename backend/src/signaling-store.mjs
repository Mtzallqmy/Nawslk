import { randomUUID } from 'node:crypto';

export async function ensureSignalingSchema(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS signaling_sessions (
    id UUID PRIMARY KEY, caller_id UUID NOT NULL, callee_id UUID NOT NULL,
    state TEXT NOT NULL DEFAULT 'ringing',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '2 minutes',
    CHECK (caller_id <> callee_id),
    CHECK (state IN ('ringing', 'accepted', 'ended'))
  )`);
  await pool.query(`CREATE TABLE IF NOT EXISTS signaling_messages (
    id BIGSERIAL PRIMARY KEY, session_id UUID NOT NULL REFERENCES signaling_sessions(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL, kind TEXT NOT NULL, payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (kind IN ('offer', 'answer', 'ice'))
  )`);
}
export async function createSession(pool, callerId, calleeId) {
  if (callerId === calleeId) throw new Error('self_call_not_allowed');
  const id = randomUUID();
  const result = await pool.query(
    'INSERT INTO signaling_sessions(id, caller_id, callee_id) VALUES($1,$2,$3) RETURNING id,state,expires_at',
    [id, callerId, calleeId]
  );
  return result.rows[0];
}
export async function getSession(pool, sessionId, participantId) {
  const result = await pool.query(
    `SELECT * FROM signaling_sessions WHERE id=$1
     AND (caller_id=$2 OR callee_id=$2) AND expires_at > NOW() AND state <> 'ended'`,
    [sessionId, participantId]
  );
  return result.rows[0] ?? null;
}
export async function appendSignal(pool, session, senderId, kind, payload) {
  if (!['offer','answer','ice'].includes(kind)) throw new Error('invalid_signal_kind');
  if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) throw new Error('invalid_signal_payload');
  if (JSON.stringify(payload).length > 16000) throw new Error('signal_too_large');
  const result = await pool.query(
    'INSERT INTO signaling_messages(session_id,sender_id,kind,payload) VALUES($1,$2,$3,$4) RETURNING id',
    [session.id, senderId, kind, JSON.stringify(payload)]
  );
  return result.rows[0];
}
export async function listSignals(pool, sessionId, afterId) {
  const result = await pool.query(
    'SELECT id,sender_id,kind,payload FROM signaling_messages WHERE session_id=$1 AND id>$2 ORDER BY id LIMIT 50',
    [sessionId, afterId]
  );
  return result.rows;
}
