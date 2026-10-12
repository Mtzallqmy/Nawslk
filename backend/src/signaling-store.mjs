import { randomUUID } from 'node:crypto';
import { transaction, uuid } from './devices.mjs';
export async function ensureSignalingSchema(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS signaling_sessions (
    id UUID PRIMARY KEY, caller_id UUID NOT NULL, callee_id UUID NOT NULL,
    state TEXT NOT NULL DEFAULT 'ringing', created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '2 minutes',
    CHECK (caller_id <> callee_id), CHECK (state IN ('ringing','accepted','ended')))`);
  await pool.query(`CREATE TABLE IF NOT EXISTS signaling_messages (
    id BIGSERIAL PRIMARY KEY, session_id UUID NOT NULL REFERENCES signaling_sessions(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL, kind TEXT NOT NULL, payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), CHECK (kind IN ('offer','answer','ice')))`);
  await pool.query('ALTER TABLE signaling_sessions ADD COLUMN IF NOT EXISTS end_reason TEXT');
  await pool.query('ALTER TABLE signaling_messages ADD COLUMN IF NOT EXISTS message_id UUID');
  await pool.query('CREATE UNIQUE INDEX IF NOT EXISTS signal_replay_guard ON signaling_messages(session_id,sender_id,message_id)');
  await pool.query('CREATE INDEX IF NOT EXISTS signal_poll ON signaling_messages(session_id,id)');
  await pool.query('CREATE INDEX IF NOT EXISTS call_inbox ON signaling_sessions(callee_id,expires_at)');
}
export async function createSession(pool, callerId, calleeId) {
  if (callerId === calleeId) throw new Error('self_call_not_allowed');
  return transaction(pool, async db => {
    // Lock both participant IDs in stable order, covering simultaneous cross-calls.
    for (const id of [callerId,calleeId].sort()) await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [id]);
    const busy = await db.query(`SELECT id FROM signaling_sessions WHERE state <> 'ended' AND expires_at>NOW()
      AND (caller_id=ANY($1::uuid[]) OR callee_id=ANY($1::uuid[]))`, [[callerId,calleeId]]);
    if (busy.rowCount) throw new Error('participant_busy');
    const result = await db.query('INSERT INTO signaling_sessions(id,caller_id,callee_id) VALUES($1,$2,$3) RETURNING *', [randomUUID(),callerId,calleeId]);
    return result.rows[0];
  });
}
export async function getSession(pool, sessionId, participantId) {
  const result = await pool.query(`SELECT *, (expires_at <= NOW() AND state <> 'ended') AS expired FROM signaling_sessions
    WHERE id=$1 AND (caller_id=$2 OR callee_id=$2)`, [sessionId,participantId]);
  return result.rows[0] ?? null;
}
export async function transitionSession(pool, id, actor, action) {
  return transaction(pool, async db => {
    const result = await db.query('SELECT * FROM signaling_sessions WHERE id=$1 AND (caller_id=$2 OR callee_id=$2) FOR UPDATE',[id,actor]);
    const s = result.rows[0];
    if (!s) throw new Error('session_not_found');
    if (action !== 'end' && (s.state !== 'ringing' || new Date(s.expires_at).getTime() <= Date.now())) throw new Error('invalid_call_state');
    if (action !== 'end' && s.callee_id !== actor) throw new Error('forbidden');
    const accepted = action === 'accept';
    const update = await db.query(`UPDATE signaling_sessions SET state=$2,end_reason=$3,
      expires_at=CASE WHEN $2='accepted' THEN NOW()+INTERVAL '2 minutes' ELSE expires_at END WHERE id=$1 RETURNING *`,
      [id,accepted ? 'accepted' : 'ended', accepted ? null : action === 'reject' ? 'rejected' : 'hangup']);
    return update.rows[0];
  });
}
export function validateSignal(kind,payload) {
  if (!['offer','answer','ice'].includes(kind)) throw new Error('invalid_signal_kind');
  if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) throw new Error('invalid_signal_payload');
  if (Buffer.byteLength(JSON.stringify(payload)) > 16000) throw new Error('signal_too_large');
  if (kind !== 'ice' && (typeof payload.sdp !== 'string' || payload.sdp.length < 10 || !payload.sdp.startsWith('v=0'))) throw new Error('invalid_signal_sdp');
  if (kind === 'ice' && (typeof payload.candidate !== 'string' || payload.candidate.length > 4096 || !Number.isInteger(payload.sdpMLineIndex) || payload.sdpMLineIndex < 0 || typeof payload.sdpMid !== 'string')) throw new Error('invalid_signal_ice');
}
export async function appendSignal(pool, session, senderId, kind, payload, messageId) {
  validateSignal(kind,payload);
  if (!uuid(messageId)) throw new Error('invalid_signal_message_id');
  return transaction(pool, async db => {
    const current = await db.query(`SELECT * FROM signaling_sessions WHERE id=$1 AND (caller_id=$2 OR callee_id=$2)
      AND state='accepted' AND expires_at>NOW() FOR UPDATE`,[session.id,senderId]);
    const s = current.rows[0];
    if (!s) throw new Error('invalid_call_state');
    if (kind === 'offer' && senderId !== s.caller_id || kind === 'answer' && senderId !== s.callee_id) throw new Error('forbidden');
    const prior = await db.query('SELECT id,kind,payload FROM signaling_messages WHERE session_id=$1 AND sender_id=$2 AND message_id=$3',[s.id,senderId,messageId]);
    if (prior.rowCount) {
      if (prior.rows[0].kind !== kind || JSON.stringify(prior.rows[0].payload) !== JSON.stringify(JSON.parse(JSON.stringify(payload)))) {
        // JSONB key ordering differs: compare canonical keys.
        const canonical = v => JSON.stringify(Object.keys(v).sort().map(k=>[k,v[k]]));
        if (prior.rows[0].kind !== kind || canonical(prior.rows[0].payload) !== canonical(payload)) throw new Error('signal_replay_conflict');
      }
      return { id: prior.rows[0].id, duplicate: true };
    }
    if (kind === 'answer') {
      const offer = await db.query("SELECT id FROM signaling_messages WHERE session_id=$1 AND kind='offer' LIMIT 1",[s.id]);
      if (!offer.rowCount) throw new Error('invalid_call_state');
    }
    if (kind !== 'ice') {
      const old = await db.query('SELECT id FROM signaling_messages WHERE session_id=$1 AND kind=$2 LIMIT 1',[s.id,kind]);
      if (old.rowCount) throw new Error('signal_replay_conflict');
    }
    const count = await db.query('SELECT COUNT(*)::int AS n FROM signaling_messages WHERE session_id=$1',[s.id]);
    if (count.rows[0].n >= 512) throw new Error('signal_limit');
    const result = await db.query('INSERT INTO signaling_messages(session_id,sender_id,kind,payload,message_id) VALUES($1,$2,$3,$4,$5) RETURNING id',[s.id,senderId,kind,JSON.stringify(payload),messageId]);
    return result.rows[0];
  });
}
export async function listSignals(pool, sessionId, afterId, participantId) {
  const result = await pool.query(`SELECT m.id,m.sender_id,m.kind,m.payload FROM signaling_messages m
    JOIN signaling_sessions s ON s.id=m.session_id WHERE m.session_id=$1 AND m.id>$2
    AND (s.caller_id=$3 OR s.callee_id=$3) AND s.state='accepted' AND s.expires_at>NOW()
    AND m.sender_id<>$3 ORDER BY m.id LIMIT 50`,[sessionId,afterId,participantId]);
  return result.rows;
}
