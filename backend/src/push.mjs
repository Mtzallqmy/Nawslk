import { createCipheriv, createDecipheriv, createHash, randomBytes, createSign } from 'node:crypto';
import { transaction } from './devices.mjs';

const key = secret => createHash('sha256').update('nawslk-push-v1\0'+secret).digest();
export function encryptPushToken(token, secret) {
  if (!secret) throw new Error('push_storage_unconfigured');
  const iv=randomBytes(12), cipher=createCipheriv('aes-256-gcm',key(secret),iv);
  const ciphertext=Buffer.concat([cipher.update(token,'utf8'),cipher.final()]);
  return Buffer.concat([iv,cipher.getAuthTag(),ciphertext]).toString('base64');
}
export function decryptPushToken(value, secret) {
  const bytes=Buffer.from(value,'base64');
  const decipher=createDecipheriv('aes-256-gcm',key(secret),bytes.subarray(0,12));
  decipher.setAuthTag(bytes.subarray(12,28));
  return Buffer.concat([decipher.update(bytes.subarray(28)),decipher.final()]).toString('utf8');
}
export function pushConfiguration(env=process.env) {
  try {
    const account=JSON.parse(env.FCM_SERVICE_ACCOUNT_JSON || 'null');
    const android=JSON.parse(env.FCM_ANDROID_CONFIG || 'null');
    if (!account || !android || !/^[a-z0-9-]{4,63}$/.test(account.project_id) || account.project_id!==android.projectId ||
      !account.client_email?.endsWith('.iam.gserviceaccount.com') || !account.private_key?.includes('PRIVATE KEY') ||
      typeof android.applicationId!=='string' || !android.applicationId.startsWith('1:') ||
      typeof android.apiKey!=='string' || !/^\d+$/.test(android.gcmSenderId || '')) return null;
    return {account,android:{projectId:android.projectId,applicationId:android.applicationId,apiKey:android.apiKey,gcmSenderId:android.gcmSenderId}};
  } catch { return null; }
}
export class FcmSender {
  constructor(config,{fetchImpl=fetch,now=Date.now}={}) { Object.assign(this,{config,fetchImpl,now}); this.access=null; }
  async accessToken() {
    if(this.access && this.access.until>this.now()+60000) return this.access.token;
    const account=this.config.account, second=Math.floor(this.now()/1000);
    const encode=o=>Buffer.from(JSON.stringify(o)).toString('base64url');
    const assertion=encode({alg:'RS256',typ:'JWT'})+'.'+encode({iss:account.client_email,scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',iat:second,exp:second+3600});
    const signature=createSign('RSA-SHA256').update(assertion).sign(account.private_key,'base64url');
    const response=await this.fetchImpl('https://oauth2.googleapis.com/token',{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:assertion+'.'+signature}),signal:AbortSignal.timeout(5000)});
    const data=await response.json();
    if(!response.ok || typeof data.access_token!=='string') throw new Error('push_auth_failed');
    this.access={token:data.access_token,until:this.now()+Number(data.expires_in || 3600)*1000};
    return data.access_token;
  }
  async send(token,sessionId,expiresAt=Date.now()+60000) {
    const access=await this.accessToken();
    const response=await this.fetchImpl(`https://fcm.googleapis.com/v1/projects/${this.config.account.project_id}/messages:send`,{method:'POST',headers:{authorization:'Bearer '+access,'content-type':'application/json'},body:JSON.stringify({message:{token,data:{type:'incoming_call',sessionId,expiresAt:String(expiresAt)},android:{priority:'HIGH',ttl:'60s',collapse_key:sessionId}}}),signal:AbortSignal.timeout(5000)});
    if(response.status===401) this.access=null;
    if(!response.ok) {
      const data=await response.json().catch(()=>({}));
      if(data.error?.details?.some(d=>d.errorCode==='UNREGISTERED')) throw new Error('push_unregistered');
      throw new Error('push_send_failed');
    }
  }
}
export async function ensurePushSchema(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS device_push_tokens (
    device_id UUID PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, ciphertext TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`);
  await pool.query(`CREATE TABLE IF NOT EXISTS call_push_outbox (
    session_id UUID PRIMARY KEY REFERENCES signaling_sessions(id) ON DELETE CASCADE,
    device_id UUID NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), delivered_at TIMESTAMPTZ)`);
}
export async function registerPushToken(pool,deviceId,token,secret) {
  if(typeof token!=='string' || token.length<20 || token.length>4096 || /\s/.test(token)) throw new Error('invalid_push_token');
  const tokenHash=createHash('sha256').update(token).digest('hex');
  await pool.query(`INSERT INTO device_push_tokens(device_id,token_hash,ciphertext) VALUES($1,$2,$3)
    ON CONFLICT(device_id) DO UPDATE SET token_hash=EXCLUDED.token_hash,ciphertext=EXCLUDED.ciphertext,updated_at=NOW()`,[deviceId,tokenHash,encryptPushToken(token,secret)]);
}
export async function dispatchPush(pool,sender,secret) {
  if(!sender || !secret) return;
  const item=await transaction(pool,async db=>{
    const result=await db.query(`SELECT q.session_id,q.device_id,p.ciphertext,s.expires_at FROM call_push_outbox q
      JOIN signaling_sessions s ON s.id=q.session_id JOIN device_push_tokens p ON p.device_id=q.device_id
      WHERE q.delivered_at IS NULL AND q.attempts<5 AND q.next_attempt_at<=NOW()
      AND s.state='ringing' AND s.expires_at>NOW() ORDER BY q.next_attempt_at
      FOR UPDATE OF q SKIP LOCKED LIMIT 1`);
    const row=result.rows[0];
    if(row) await db.query("UPDATE call_push_outbox SET attempts=attempts+1,next_attempt_at=NOW()+INTERVAL '15 seconds' WHERE session_id=$1",[row.session_id]);
    return row;
  });
  if(!item) return;
  try {
    await sender.send(decryptPushToken(item.ciphertext,secret),item.session_id,new Date(item.expires_at).getTime());
    await pool.query('UPDATE call_push_outbox SET delivered_at=NOW() WHERE session_id=$1',[item.session_id]);
  } catch(error) {
    if(error.message==='push_unregistered') await pool.query('DELETE FROM device_push_tokens WHERE device_id=$1 AND ciphertext=$2',[item.device_id,item.ciphertext]);
    // No payload, Firebase token, SDP, bearer, or service-account detail in logs.
    console.warn(JSON.stringify({event:'call_push_failed',reason:error.message==='push_unregistered'?'unregistered':'delivery_unavailable'}));
  }
}
