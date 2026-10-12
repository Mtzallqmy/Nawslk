import { readFile } from 'node:fs/promises';
import { createHash, createHmac, createCipheriv, createDecipheriv, randomBytes, randomUUID } from 'node:crypto';
import { transaction, uuid } from './devices.mjs';
import { validE164, validMessageBody, transition, deliveryTransition, MockMessageProvider, LicensedProviderAdapter, verifyWebhook } from './message-domain.mjs';
export async function migrateMessaging(pool) {
 return transaction(pool,async db=>{
  await db.query("SELECT pg_advisory_xact_lock(hashtextextended('nawslk-schema-migrations',0))");
  await db.query('CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL,applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW())');
  for(const [version,name] of [[1,'001_messaging.sql'],[2,'002_delivery_events.sql'],[3,'003_infobip.sql']]) {
   const sql=await readFile(new URL('./migrations/'+name,import.meta.url),'utf8');
   const checksum=createHash('sha256').update(sql).digest('hex');
   const old=await db.query('SELECT checksum FROM schema_migrations WHERE version=$1',[version]);
   if(old.rowCount){if(old.rows[0].checksum!==checksum)throw new Error('migration_checksum_mismatch');continue;}
   await db.query(sql);await db.query('INSERT INTO schema_migrations(version,checksum) VALUES($1,$2)',[version,checksum]);
  }
 });
}
const contentKey=secret=>createHash('sha256').update('nawslk-message-v1\0'+secret).digest();
export function encryptMessage(body,secret){
 if(!secret)throw new Error('message_storage_unconfigured');
 const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',contentKey(secret),iv);
 const encrypted=Buffer.concat([cipher.update(body,'utf8'),cipher.final()]);
 return Buffer.concat([iv,cipher.getAuthTag(),encrypted]).toString('base64');
}
export function decryptMessage(ciphertext,secret){
 const bytes=Buffer.from(ciphertext,'base64'),cipher=createDecipheriv('aes-256-gcm',contentKey(secret),bytes.subarray(0,12));
 cipher.setAuthTag(bytes.subarray(12,28));return Buffer.concat([cipher.update(bytes.subarray(28)),cipher.final()]).toString('utf8');
}
export function cursor(value='0') { if(!/^\d{1,16}$/.test(value)) throw new Error('invalid_cursor'); return value; }
const toMessage=(row,secret)=>({...row,body:decryptMessage(row.ciphertext,secret),ciphertext:undefined,request_hash:undefined,simulated:row.mode==='DEMO'});
export async function createConversation(pool,actor,{id=randomUUID(),peerE164,label='',mode='DEMO',consent}) {
 if(!uuid(id) || !validE164(peerE164) || typeof label!=='string' || label.length>80 || consent!==true) throw new Error('invalid_conversation');
 if(mode!=='DEMO')throw new Error('provider_not_configured');
 const result=await pool.query(`INSERT INTO conversations(id,owner_id,peer_e164,display_label,mode,consent) VALUES($1,$2,$3,$4,$5,TRUE)
  ON CONFLICT(id) DO NOTHING RETURNING *`,[id,actor.owner_id,peerE164,label.trim()||peerE164,mode]);
 if(result.rowCount)return result.rows[0];
 const old=await pool.query('SELECT * FROM conversations WHERE id=$1 AND owner_id=$2',[id,actor.owner_id]);
 const row=old.rows[0];
 if(!row || row.peer_e164!==peerE164 || row.mode!==mode || row.display_label!==(label.trim()||peerE164))throw new Error('idempotency_conflict');
 return row;
}
export async function getConversation(pool,actor,id) {
 const result=await pool.query('SELECT * FROM conversations WHERE id=$1 AND owner_id=$2',[id,actor.owner_id]);
 return result.rows[0] ?? null;
}
export async function listConversations(pool,actor,before='0') {
 const result=await pool.query(`SELECT * FROM conversations WHERE owner_id=$1 AND ($2::bigint=0 OR cursor_seq<$2) ORDER BY cursor_seq DESC LIMIT 50`,[actor.owner_id,cursor(before)]);
 return {conversations:result.rows,nextCursor:result.rows.length===50?String(result.rows.at(-1).cursor_seq):null};
}
export async function listMessages(pool,actor,id,after,secret) {
 if(!await getConversation(pool,actor,id))throw new Error('conversation_not_found');
 const result=await pool.query('SELECT * FROM messages WHERE conversation_id=$1 AND owner_id=$2 AND cursor_seq>$3 ORDER BY cursor_seq LIMIT 50',[id,actor.owner_id,cursor(after)]);
 return {messages:result.rows.map(row=>toMessage(row,secret)),nextCursor:result.rows.length===50?String(result.rows.at(-1).cursor_seq):null};
}
export async function sendMessage(pool,actor,conversationId,{id=randomUUID(),idempotencyKey,body},secret) {
 if(!secret)throw new Error("message_storage_unconfigured");
 if(!uuid(id) || !uuid(idempotencyKey) || !validMessageBody(body))throw new Error('invalid_message');
 const requestHash=createHmac('sha256',contentKey(secret)).update(JSON.stringify([conversationId,body])).digest('hex');
 return transaction(pool,async db=>{
  await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',['messages:'+actor.owner_id]);
  const conversation=await getConversation(db,actor,conversationId);
  if(!conversation)throw new Error('conversation_not_found');
  if(!conversation.consent)throw new Error('consent_required');
  const old=await db.query('SELECT * FROM messages WHERE owner_id=$1 AND idempotency_key=$2',[actor.owner_id,idempotencyKey]);
  if(old.rowCount){if(old.rows[0].request_hash!==requestHash)throw new Error('idempotency_conflict');return {...toMessage(old.rows[0],secret),duplicate:true};}
  const recent=await db.query("SELECT COUNT(*)::int AS n FROM messages WHERE owner_id=$1 AND created_at>NOW()-INTERVAL '1 minute'",[actor.owner_id]);
  if(recent.rows[0].n>=20)throw new Error('message_rate_limited');
  const provider=conversation.mode==='DEMO'?new MockMessageProvider():new LicensedProviderAdapter();
  if(!provider.enabled)throw new Error('provider_not_configured');
  await db.query(`INSERT INTO messages(id,owner_id,conversation_id,idempotency_key,request_hash,ciphertext,status,direction,mode,provider_key)
   VALUES($1,$2,$3,$4,$5,$6,'QUEUED','OUTGOING',$7,$8)`,[id,actor.owner_id,conversationId,idempotencyKey,requestHash,encryptMessage(body,secret),conversation.mode,provider.key]);
  // Demo provider performs no network call. Real sending requires a separate leased outbox, not a DB transaction network request.
  transition('QUEUED','SENDING');
  const receipt=await provider.send({messageId:id});transition('SENDING',receipt.status);
  const sent=await db.query('UPDATE messages SET status=$2,provider_reference=$3,updated_at=NOW() WHERE id=$1 RETURNING *',[id,receipt.status,receipt.providerReference]);
  await db.query('UPDATE conversations SET updated_at=NOW() WHERE id=$1',[conversationId]);
  return toMessage(sent.rows[0],secret);
 });
}
export async function listNumbers(pool,actor) {
 const result=await pool.query(`SELECT n.* FROM virtual_numbers n JOIN provider_accounts p ON p.id=n.provider_account_id
  WHERE n.owner_id=$1 AND p.owner_id=$1 AND p.enabled=TRUE AND p.licensed=TRUE ORDER BY n.updated_at DESC LIMIT 100`,[actor.owner_id]);
 return {numbers:result.rows,provider:{configured:false,mode:'UNCONFIGURED',realSmsEnabled:false},simulated:false};
}
export async function applyWebhook(pool,raw,headers,secret) {
 // Generic adapter contract for tests. No vendor route is enabled without explicit licensed integration approval.
 if(!verifyWebhook(raw,{timestamp:headers.timestamp,nonce:headers.nonce,signature:headers.signature,secret}))throw new Error('invalid_webhook_signature');
 let event;try{event=JSON.parse(raw);}catch{throw new Error('invalid_webhook');}
 if(event.providerKey!=='licensed-adapter' || typeof event.eventId!=='string' || !/^[A-Za-z0-9_-]{1,128}$/.test(event.eventId) || !uuid(event.messageId) || !['SENT','DELIVERED','FAILED'].includes(event.status) || (!Number.isFinite(Date.parse(event.eventAt)) || Date.parse(event.eventAt)>Date.now()+300000))throw new Error('invalid_webhook');
 return transaction(pool,async db=>{
  const replay=await db.query("INSERT INTO webhook_nonces(provider_key,nonce,expires_at) VALUES($1,$2,NOW()+INTERVAL '10 minutes') ON CONFLICT DO NOTHING RETURNING nonce",[event.providerKey,headers.nonce]);
  if(!replay.rowCount)throw new Error('webhook_replay');
  const message=await db.query("SELECT * FROM messages WHERE id=$1 AND provider_key=$2 AND mode='SMS' FOR UPDATE",[event.messageId,event.providerKey]);
  if(!message.rowCount)throw new Error('message_not_found');
  const receipt=await db.query('INSERT INTO delivery_events(provider_key,event_id,message_id,status,event_at) VALUES($1,$2,$3,$4,$5) ON CONFLICT DO NOTHING RETURNING id',[event.providerKey,event.eventId,event.messageId,event.status,event.eventAt]);
  if(!receipt.rowCount)return {duplicate:true};
  const current=message.rows[0].status;
  const latest=await db.query('SELECT MAX(event_at) AS last FROM delivery_events WHERE message_id=$1',[event.messageId]);
  // Late events are retained for audit; they cannot downgrade delivered/received state.
  if(current==='DELIVERED' || Date.parse(event.eventAt)<new Date(latest.rows[0].last).getTime())return {ignored:true};
  let next;try{next=deliveryTransition(current,event.status);}catch{return {ignored:true};}
  await db.query('UPDATE messages SET status=$2,updated_at=NOW() WHERE id=$1',[event.messageId,next]);
  return {status:next};
 });
}
