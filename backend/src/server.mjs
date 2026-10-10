import http from 'node:http';
import { Pool } from 'pg';
import { randomUUID } from 'node:crypto';
import { hashSecret, secureEqualHash, newToken, bearer, validOwnerEmail } from './auth.mjs';

const port = Number(process.env.PORT || 3000);
const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 5 });
const bootstrapHash = process.env.BOOTSTRAP_SECRET_SHA256;
if (!process.env.DATABASE_URL || !/^[a-f0-9]{64}$/.test(bootstrapHash || '')) {
  throw new Error('DATABASE_URL and BOOTSTRAP_SECRET_SHA256 are required');
}
await pool.query('CREATE TABLE IF NOT EXISTS owner_device (id UUID PRIMARY KEY, email TEXT NOT NULL, token_hash TEXT NOT NULL, created_at TIMESTAMPTZ DEFAULT NOW(), revoked_at TIMESTAMPTZ)');
async function send(res, status, data) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(data));
}
async function readBody(req) {
  let body = '';
  for await (const chunk of req) {
    body += chunk;
    if (body.length > 8192) throw new Error('body_too_large');
  }
  return JSON.parse(body || '{}');
}
const server = http.createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://localhost').pathname;
    if (req.method === 'GET' && path === '/health') {
      await pool.query('SELECT 1');
      return send(res, 200, { status: 'ok', database: 'connected' });
    }
    if (req.method === 'POST' && path === '/v1/bootstrap') {
      const supplied = req.headers['x-bootstrap-secret'];
      if (typeof supplied !== 'string' || !secureEqualHash(hashSecret(supplied), bootstrapHash)) {
        return send(res, 403, { error: 'forbidden' });
      }
      const { email } = await readBody(req);
      if (!validOwnerEmail(email)) return send(res, 400, { error: 'invalid_email' });
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        await client.query('LOCK TABLE owner_device IN EXCLUSIVE MODE');
        const existing = await client.query('SELECT id FROM owner_device LIMIT 1');
        if (existing.rowCount) {
          await client.query('ROLLBACK');
          return send(res, 409, { error: 'already_initialized' });
        }
        const token = newToken();
        const deviceId = randomUUID();
        await client.query('INSERT INTO owner_device(id,email,token_hash) VALUES($1,$2,$3)',
          [deviceId, email.trim(), hashSecret(token)]);
        await client.query('COMMIT');
        return send(res, 201, { deviceId, token });
      } catch (error) {
        await client.query('ROLLBACK').catch(() => {});
        throw error;
      } finally { client.release(); }
    }
    if (req.method === 'GET' && path === '/v1/me') {
      const token = bearer(req.headers.authorization);
      if (!token) return send(res, 401, { error: 'unauthorized' });
      const result = await pool.query('SELECT id,email,token_hash FROM owner_device WHERE revoked_at IS NULL');
      const owner = result.rows.find(row => secureEqualHash(row.token_hash, hashSecret(token)));
      if (!owner) return send(res, 401, { error: 'unauthorized' });
      return send(res, 200, { deviceId: owner.id, email: owner.email });
    }
    return send(res, 404, { error: 'not_found' });
  } catch (error) {
    if (error.message === 'body_too_large') return send(res, 413, { error: 'body_too_large' });
    if (error instanceof SyntaxError) return send(res, 400, { error: 'invalid_json' });
    console.error('request failed:', error.message);
    return send(res, 500, { error: 'internal_error' });
  }
});
server.listen(port, '0.0.0.0');
