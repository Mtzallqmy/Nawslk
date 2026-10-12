import { randomUUID } from 'node:crypto';
import { hashSecret, newToken } from './auth.mjs';
export const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
export async function transaction(pool, fn) {
  const db = await pool.connect();
  try { await db.query('BEGIN'); const result = await fn(db); await db.query('COMMIT'); return result; }
  catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error; }
  finally { db.release(); }
}
export async function ensureDevices(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS trusted_devices (
    id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES owner_device(id),
    label TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`);
  await pool.query(`CREATE TABLE IF NOT EXISTS device_invites (
    token_hash TEXT PRIMARY KEY, owner_id UUID NOT NULL REFERENCES owner_device(id),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '10 minutes',
    consumed_at TIMESTAMPTZ)`);
}
export async function authenticate(pool, token) {
  if (!token) return null;
  const result = await pool.query(`SELECT id, id AS owner_id, email, TRUE AS is_owner FROM owner_device
    WHERE token_hash=$1 AND revoked_at IS NULL UNION ALL
    SELECT d.id,d.owner_id,o.email,FALSE AS is_owner FROM trusted_devices d
    JOIN owner_device o ON o.id=d.owner_id WHERE d.token_hash=$1 AND d.revoked_at IS NULL AND o.revoked_at IS NULL`, [hashSecret(token)]);
  return result.rows[0] ?? null;
}
export async function issueInvite(pool, ownerId) {
  const invite = newToken();
  await pool.query('INSERT INTO device_invites(token_hash,owner_id) VALUES($1,$2)', [hashSecret(invite),ownerId]);
  return { invite, expiresInSeconds: 600 };
}
export async function redeemInvite(pool, invite, label) {
  if (typeof invite !== 'string' || invite.length !== 43 || typeof label !== 'string' || !label.trim() || label.length > 80) throw new Error('invalid_invite');
  return transaction(pool, async db => {
    const result = await db.query(`UPDATE device_invites SET consumed_at=NOW() WHERE token_hash=$1
      AND consumed_at IS NULL AND expires_at>NOW() AND EXISTS
      (SELECT 1 FROM owner_device o WHERE o.id=owner_id AND o.revoked_at IS NULL) RETURNING owner_id`, [hashSecret(invite)]);
    if (!result.rowCount) throw new Error('invalid_invite');
    const token = newToken(), deviceId = randomUUID();
    await db.query('INSERT INTO trusted_devices(id,owner_id,label,token_hash) VALUES($1,$2,$3,$4)', [deviceId,result.rows[0].owner_id,label.trim(),hashSecret(token)]);
    return { deviceId, token };
  });
}
export async function authorizedPeer(pool, participant, peerId) {
  const result = await pool.query(`SELECT id FROM owner_device WHERE id=$1 AND id=$2 AND revoked_at IS NULL
    UNION ALL SELECT d.id FROM trusted_devices d JOIN owner_device o ON o.id=d.owner_id
    WHERE d.id=$1 AND d.owner_id=$2 AND d.revoked_at IS NULL AND o.revoked_at IS NULL`, [peerId,participant.owner_id]);
  return result.rowCount > 0 && peerId !== participant.id;
}
