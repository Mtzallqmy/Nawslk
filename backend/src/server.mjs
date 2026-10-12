import http from 'node:http';
import { iceConfiguration, IceProvider } from './ice.mjs';
import { migrateMessaging, createConversation, listConversations, listMessages, sendMessage, listNumbers, applyWebhook } from './messaging.mjs';
import { ensurePushSchema, registerPushToken, pushConfiguration, FcmSender, dispatchPush } from './push.mjs';
import { Pool } from 'pg';
import { randomUUID } from 'node:crypto';
import { ensureDevices, authenticate, issueInvite, redeemInvite, authorizedPeer, uuid, transaction } from './devices.mjs';
import { RateLimiter } from './limits.mjs';
import { hashSecret, secureEqualHash, newToken, bearer, validOwnerEmail } from './auth.mjs';
import { ensureSignalingSchema, createSession, getSession, appendSignal, listSignals, transitionSession } from './signaling-store.mjs';

const port = Number(process.env.PORT || 3000);
const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 5 });
const bootstrapHash = process.env.BOOTSTRAP_SECRET_SHA256;
if (!process.env.DATABASE_URL || !/^[a-f0-9]{64}$/.test(bootstrapHash || '')) {
  throw new Error('DATABASE_URL and BOOTSTRAP_SECRET_SHA256 are required');
}
await pool.query('CREATE TABLE IF NOT EXISTS owner_device (id UUID PRIMARY KEY, email TEXT NOT NULL, token_hash TEXT NOT NULL, created_at TIMESTAMPTZ DEFAULT NOW(), revoked_at TIMESTAMPTZ)');
if (process.env.NODE_ENV === 'production' && !process.env.BOOTSTRAP_SECRET) throw new Error('bootstrap_configuration_missing');
if (process.env.BOOTSTRAP_SECRET && !secureEqualHash(hashSecret(process.env.BOOTSTRAP_SECRET), bootstrapHash)) {
  throw new Error('bootstrap_configuration_mismatch');
}
await ensureDevices(pool);
await ensureSignalingSchema(pool);
await ensurePushSchema(pool);
await migrateMessaging(pool);
const pushConfig=pushConfiguration();
const iceConfig=iceConfiguration();
const iceProvider=new IceProvider(iceConfig);
const pushSender=pushConfig ? new FcmSender(pushConfig) : null;
let pushing=false;
const pushWorker=setInterval(async()=>{
  if(pushing) return;
  pushing=true;
  try { await dispatchPush(pool,pushSender,process.env.BOOTSTRAP_SECRET); }
  catch { console.warn('{"event":"call_push_worker_failed"}'); }
  finally { pushing=false; }
},3000).unref();
const authLimit = new RateLimiter({ max: 10 });
const requestLimit = new RateLimiter({ max: 240 });
const cleanup = setInterval(() => {
  pool.query("DELETE FROM signaling_sessions WHERE expires_at < NOW() - INTERVAL '1 day'").catch(() => {});
  pool.query("DELETE FROM messages WHERE created_at < NOW()-INTERVAL '90 days' AND status IN ('SENT','DELIVERED','FAILED','RECEIVED')").catch(() => {});
  pool.query('DELETE FROM webhook_nonces WHERE expires_at<NOW()').catch(() => {});
  pool.query("DELETE FROM device_invites WHERE expires_at < NOW() - INTERVAL '1 day'").catch(() => {});
}, 60000).unref();
async function send(res, status, data) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(data));
}
async function readBody(req) {
  const chunks=[];let bytes=0;
  for await (const chunk of req) {
    bytes+=chunk.length;
    if(bytes>24576)throw new Error('body_too_large');
    chunks.push(chunk);
  }
  const parsed=JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
  if(!parsed || typeof parsed!=='object' || Array.isArray(parsed))throw new SyntaxError('invalid_json_object');
  return parsed;
}
const server = http.createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://localhost').pathname;
    const publicAuth = path === '/v1/bootstrap' || path === '/v1/devices/redeem';
    // Socket identity cannot be spoofed via arbitrary Forwarded headers. A shared ingress
    // bucket may be restrictive behind Railway; identity limiter is separate after auth.
    if (publicAuth && !authLimit.allow(req.socket.remoteAddress || 'ingress')) return send(res,429,{error:'rate_limited'});
    if (req.method === 'GET' && path === '/health') {
      await pool.query('SELECT 1');
      return send(res, 200, { status: 'ok', database: 'connected', bootstrapConfigVerified: Boolean(process.env.BOOTSTRAP_SECRET), calling: { turnConfigured: Boolean(iceConfig), pushConfigured: Boolean(pushConfig) } });
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
    if (req.method === 'POST' && path === '/v1/devices/redeem') {
      const { invite, label } = await readBody(req);
      return send(res,201,await redeemInvite(pool,invite,label));
    }
    if (req.method==='POST' && path==='/v1/providers/licensed-adapter/webhook') {
      if(process.env.NODE_ENV!=='test' || !process.env.WEBHOOK_TEST_SECRET) return send(res,503,{error:'provider_not_configured'});
      let raw=Buffer.alloc(0);
      for await(const chunk of req){raw=Buffer.concat([raw,chunk]);if(raw.length>8192)throw new Error('body_too_large');}
      return send(res,200,await applyWebhook(pool,raw,{timestamp:req.headers['x-webhook-timestamp'],nonce:req.headers['x-webhook-nonce'],signature:req.headers['x-webhook-signature']},process.env.WEBHOOK_TEST_SECRET));
    }
    if (path.startsWith('/v1/')) {
      const owner = await authenticate(pool,bearer(req.headers.authorization));
      if (!owner) return send(res,401,{error:'unauthorized'});
      if (!requestLimit.allow(owner.id)) return send(res,429,{error:'rate_limited'});
      const participantId = owner.id;
      if(req.method==='GET' && path==='/v1/numbers') return send(res,200,await listNumbers(pool,owner));
      if(req.method==='GET' && path==='/v1/conversations') return send(res,200,await listConversations(pool,owner,new URL(req.url,'http://localhost').searchParams.get('before') || '0'));
      if(req.method==='POST' && path==='/v1/conversations') return send(res,201,await createConversation(pool,owner,await readBody(req)));
      const conversationRoute=/^\/v1\/conversations\/([0-9a-f-]{36})(?:\/(messages))?$/.exec(path);
      if(conversationRoute) {
        if(!uuid(conversationRoute[1]))return send(res,400,{error:'invalid_conversation'});
        if(req.method==='GET' && conversationRoute[2]==='messages') return send(res,200,await listMessages(pool,owner,conversationRoute[1],new URL(req.url,'http://localhost').searchParams.get('after') || '0',process.env.BOOTSTRAP_SECRET));
        if(req.method==='POST' && conversationRoute[2]==='messages') return send(res,201,await sendMessage(pool,owner,conversationRoute[1],await readBody(req),process.env.BOOTSTRAP_SECRET));
        if(req.method==='DELETE' && !conversationRoute[2]) {
          const removed=await pool.query('DELETE FROM conversations WHERE id=$1 AND owner_id=$2 RETURNING id',[conversationRoute[1],owner.owner_id]);
          return send(res,removed.rowCount?200:404,removed.rowCount?{status:'deleted'}:{error:'conversation_not_found'});
        }
        return send(res,405,{error:'method_not_allowed'});
      }
      if (req.method === 'GET' && path === '/v1/me') return send(res,200,{deviceId:owner.id,ownerId:owner.owner_id,email:owner.email,isOwner:owner.is_owner});
      if (req.method === 'POST' && path === '/v1/devices/invites') {
        if (!owner.is_owner) return send(res,403,{error:'forbidden'});
        return send(res,201,await issueInvite(pool,owner.id));
      }
      if (req.method === 'GET' && path === '/v1/push/config') {
        if(!pushConfig) return send(res,503,{error:'push_not_configured'});
        return send(res,200,pushConfig.android);
      }
      if (req.method === 'PUT' && path === '/v1/devices/push-token') {
        const {token}=await readBody(req);
        if(!process.env.BOOTSTRAP_SECRET) return send(res,503,{error:'push_not_configured'});
        await registerPushToken(pool,participantId,token,process.env.BOOTSTRAP_SECRET);
        return send(res,200,{status:'registered'});
      }
      if (req.method === 'DELETE' && path === '/v1/devices/push-token') {
        await pool.query('DELETE FROM device_push_tokens WHERE device_id=$1',[participantId]);
        return send(res,200,{status:'unregistered'});
      }
      if (req.method === 'GET' && path === '/v1/devices') {
        const result = await pool.query(`SELECT id,label FROM trusted_devices WHERE owner_id=$1 AND revoked_at IS NULL
          UNION ALL SELECT id,'المالك' AS label FROM owner_device WHERE id=$1 AND revoked_at IS NULL`,[owner.owner_id]);
        return send(res,200,{devices:result.rows});
      }
      const revoke = /^\/v1\/devices\/([0-9a-f-]{36})$/.exec(path);
      if (req.method === 'DELETE' && revoke) {
        if (!uuid(revoke[1])) return send(res,400,{error:'invalid_device'});
        if (!owner.is_owner && revoke[1] !== owner.id) return send(res,403,{error:'forbidden'});
        const revoked = await transaction(pool, async db => {
          await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',[revoke[1]]);
          const result = await db.query('UPDATE trusted_devices SET revoked_at=NOW() WHERE id=$1 AND owner_id=$2 RETURNING id',[revoke[1],owner.owner_id]);
          if (result.rowCount) await db.query('DELETE FROM device_push_tokens WHERE device_id=$1',[revoke[1]]);
          if (result.rowCount) await db.query("UPDATE signaling_sessions SET state='ended',end_reason='device_revoked' WHERE caller_id=$1 OR callee_id=$1",[revoke[1]]);
          return result;
        });
        if (!revoked.rowCount) return send(res,404,{error:'device_not_found'});
        return send(res,200,{status:'revoked'});
      }
      if (req.method === 'GET' && path === '/v1/calls/incoming') {
        const result = await pool.query("SELECT * FROM signaling_sessions WHERE callee_id=$1 AND state='ringing' AND expires_at>NOW() ORDER BY created_at",[participantId]);
        return send(res,200,{sessions:result.rows});
      }
      if (req.method === 'GET' && path === '/v1/calls/ice-config') {
        return send(res,200,await iceProvider.credentials(owner.id));
      }
      if (req.method === 'POST' && path === '/v1/calls/sessions') {
        const { peerId } = await readBody(req);
        if (!uuid(peerId)) {
          return send(res, 400, { error: 'invalid_peer' });
        }
        const known = await authorizedPeer(pool, owner, peerId);
        if (!known) return send(res, 404, { error: 'peer_not_found' });
        if (peerId === participantId) return send(res, 400, { error: 'self_call_not_allowed' });
        return send(res, 201, await createSession(pool, participantId, peerId, owner.owner_id));
      }
      const match = /^\/v1\/calls\/sessions\/([0-9a-f-]{36})(?:\/(signals|accept|reject|heartbeat))?$/.exec(path);
      if (!match) return send(res, 404, { error: 'not_found' });
      if (!uuid(match[1])) return send(res,400,{error:'invalid_session'});
      const session = await getSession(pool, match[1], participantId);
      if (!session) return send(res, 404, { error: 'session_not_found' });
      if (req.method === 'GET' && !match[2]) return send(res, 200, session);
      if (req.method === 'DELETE' && !match[2]) {
        await transitionSession(pool,session.id,participantId,'end');
        return send(res, 200, { status: 'ended' });
      }
      if (req.method === 'POST' && ['accept','reject'].includes(match[2])) return send(res,200,await transitionSession(pool,session.id,participantId,match[2]));
      if (req.method === 'POST' && match[2] === 'heartbeat') {
        const result = await pool.query("UPDATE signaling_sessions SET expires_at=NOW()+INTERVAL '2 minutes' WHERE id=$1 AND state='accepted' AND expires_at>NOW() RETURNING id",[session.id]);
        if (!result.rowCount) return send(res,409,{error:'invalid_call_state'});
        return send(res,200,{status:'ok'});
      }
      if (req.method === 'POST' && match[2] === 'signals') {
        const { kind, payload, messageId } = await readBody(req);
        try {
          return send(res, 201, await appendSignal(pool, session, participantId, kind, payload, messageId));
        } catch (error) {
          if (/^invalid_signal|^signal_too_large/.test(error.message)) return send(res, 400, { error: error.message });
          throw error;
        }
      }
      if (req.method === 'GET' && match[2] === 'signals') {
        const after = new URL(req.url, 'http://localhost').searchParams.get('after') || '0';
        if (!/^\d{1,16}$/.test(after)) return send(res, 400, { error: 'invalid_cursor' });
        return send(res, 200, { messages: await listSignals(pool, session.id, after, participantId) });
      }
      return send(res, 405, { error: 'method_not_allowed' });
    }
    return send(res, 404, { error: 'not_found' });
  } catch (error) {
    if (error.message === 'body_too_large') return send(res, 413, { error: 'body_too_large' });
    if (error instanceof SyntaxError) return send(res, 400, { error: 'invalid_json' });
    if(['invalid_message','invalid_conversation','invalid_cursor','invalid_webhook'].includes(error.message))return send(res,400,{error:error.message});
    if(error.message==='conversation_not_found' || error.message==='message_not_found')return send(res,404,{error:error.message});
    if(error.message==='idempotency_conflict' || error.message==='webhook_replay')return send(res,409,{error:error.message});
    if(error.message==='invalid_webhook_signature' || error.message==='consent_required')return send(res,403,{error:error.message});
    if(['provider_not_configured','message_storage_unconfigured','turn_not_configured','turn_unavailable'].includes(error.message))return send(res,503,{error:error.message});
    if(error.message==='message_rate_limited')return send(res,429,{error:error.message});
    if(error.message==='invalid_push_token') return send(res,400,{error:'invalid_push_token'});
    if(error.code==='23505') return send(res,409,{error:'identity_conflict'});
    if (error.message === 'invalid_invite') return send(res,400,{error:'invalid_invite'});
    if (error.message === 'forbidden') return send(res,403,{error:'forbidden'});
    if (['participant_busy','invalid_call_state','signal_replay_conflict','signal_limit'].includes(error.message)) return send(res,409,{error:error.message});
    if (error.message === 'session_not_found') return send(res,404,{error:'session_not_found'});
    console.error('request_failed');
    return send(res, 500, { error: 'internal_error' });
  }
});
server.listen(port, '0.0.0.0');

server.requestTimeout = 15000;
server.headersTimeout = 10000;
process.on('SIGTERM', () => { clearInterval(cleanup); clearInterval(pushWorker); server.close(() => pool.end()); });
