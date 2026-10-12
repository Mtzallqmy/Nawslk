import { createHash, createHmac } from 'node:crypto';
import { transaction, uuid } from './devices.mjs';
import { encryptMessage } from './messaging.mjs';
import { fail, authenticInfobipWebhook, infobipDelivery } from './infobip.mjs';

export const publicOperation=row=>({id:row.id,kind:row.kind,status:row.status,failureCode:row.failure_code,createdAt:row.created_at,updatedAt:row.updated_at});
export async function recordCheck(pool,actor,result) {
  await pool.query(`INSERT INTO provider_checks(owner_id,provider_key,result) VALUES($1,'infobip',$2)
    ON CONFLICT(owner_id,provider_key) DO UPDATE SET result=$2,checked_at=NOW()`,[actor.owner_id,JSON.stringify(result)]);
  return result;
}
export async function infobipStatus(pool,actor,adapter) {
  const c=adapter.config;
  const last=await pool.query("SELECT result FROM provider_checks WHERE owner_id=$1 AND provider_key='infobip'",[actor.owner_id]);
  return {provider:'infobip',configured:Boolean(c),readOnly:true,
    smsTestEnabled:Boolean(c?.smsEnabled && c?.numbers.length && c?.sender && c?.webhookKey.length>=32 && c?.webhookUrl),
    voiceTestEnabled:false,webrtcIntegrated:false,verification:last.rows[0]?.result || null};
}
export async function runSmsTest(pool,actor,input,adapter,secret) {
  if(!secret)throw fail('message_storage_unconfigured',503);
  if(!actor.is_owner)throw fail('forbidden',403);
  if(!uuid(input.id))throw fail('invalid_message');
  adapter.assertSmsApproved(input);
  const hash=createHmac('sha256',secret).update(JSON.stringify([input.to,input.text])).digest('hex');
  const claimed=await transaction(pool,async db=>{
    await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',['infobip-test:'+actor.owner_id]);
    const old=await db.query('SELECT * FROM provider_operations WHERE id=$1',[input.id]);
    if(old.rowCount) {
      if(old.rows[0].owner_id!==actor.owner_id || old.rows[0].request_hash!==hash)throw fail('idempotency_conflict',409);
      return {row:old.rows[0],fresh:false};
    }
    const quota=await db.query("SELECT COUNT(*)::int AS n FROM provider_operations WHERE owner_id=$1 AND provider_key='infobip' AND created_at>NOW()-INTERVAL '1 day'",[actor.owner_id]);
    if(quota.rows[0].n>=1)throw fail('infobip_daily_test_limit',429);
    const row=await db.query(`INSERT INTO provider_operations(id,owner_id,provider_key,kind,destination,request_hash,ciphertext,status)
      VALUES($1,$2,'infobip','SMS',$3,$4,$5,'SENDING') RETURNING *`,[input.id,actor.owner_id,input.to,hash,encryptMessage(input.text,secret)]);
    return {row:row.rows[0],fresh:true};
  });
  if(!claimed.fresh)return {...publicOperation(claimed.row),duplicate:true};
  // Commit the claim BEFORE any external request. An ambiguous outcome is never automatically resent.
  try {
    const receipt=await adapter.sms.send(input);
    const updated=await pool.query(`UPDATE provider_operations SET status=$2,provider_reference=$1,updated_at=NOW()
      WHERE id=$1 AND status='SENDING' RETURNING *`,[input.id,receipt.status]);
    if(updated.rowCount)return publicOperation(updated.rows[0]);
    const current=await pool.query('SELECT * FROM provider_operations WHERE id=$1',[input.id]);return publicOperation(current.rows[0]);
  }catch(error) {
    const definitive=Number.isInteger(error.upstreamStatus) && [400,401,403,404,422,429].includes(error.upstreamStatus);
    const row=await pool.query(`UPDATE provider_operations SET status=$2,failure_code=$3,updated_at=NOW()
      WHERE id=$1 AND status='SENDING' RETURNING *`,[input.id,definitive?'FAILED':'UNKNOWN',definitive?'HTTP_'+error.upstreamStatus:'OUTCOME_UNKNOWN']);
    if(row.rowCount)return publicOperation(row.rows[0]);
    const current=await pool.query('SELECT * FROM provider_operations WHERE id=$1',[input.id]);return publicOperation(current.rows[0]);
  }
}
export async function receiveInfobipDelivery(pool,raw,signature,key) {
  if(!key || key.length<32)throw fail('infobip_delivery_not_configured',503);
  if(!authenticInfobipWebhook(raw,signature,key))throw fail('invalid_webhook_signature',403);
  const data=JSON.parse(raw.toString('utf8'));
  if(!Array.isArray(data.results) || data.results.length>50)throw fail('invalid_webhook');
  const parsed=data.results.map(event=>{
    const time=Date.parse(event.doneAt || event.sentAt);
    const status=infobipDelivery(event.status);
    if(!uuid(event.messageId) || !Number.isFinite(time) || time>Date.now()+300000 || status==='UNKNOWN')throw fail('invalid_webhook');
    const hash=createHash('sha256').update(JSON.stringify([event.messageId,status,time])).digest('hex');
    return {id:event.messageId,time,status,hash};
  });
  return transaction(pool,async db=>{
    for(const event of parsed) {
      const found=await db.query("SELECT * FROM provider_operations WHERE id=$1 AND provider_key='infobip' AND kind='SMS' FOR UPDATE",[event.id]);
      if(!found.rowCount)continue;
      const row=found.rows[0];
      // Ignore expired, pre-creation or out-of-order events; no terminal status downgrade.
      if(event.time<new Date(row.created_at).getTime()-300000 || event.time<Date.now()-7*86400000 ||
        row.event_at && event.time<new Date(row.event_at).getTime())continue;
      const inserted=await db.query(`INSERT INTO provider_receipts(provider_key,event_hash,operation_id)
        VALUES('infobip',$1,$2) ON CONFLICT DO NOTHING RETURNING event_hash`,[event.hash,event.id]);
      if(!inserted.rowCount || ['DELIVERED','FAILED'].includes(row.status))continue;
      await db.query('UPDATE provider_operations SET status=$2,event_at=$3,updated_at=NOW(),provider_reference=$1 WHERE id=$1',[event.id,event.status,new Date(event.time)]);
    }
    return {status:'accepted'};
  });
}
