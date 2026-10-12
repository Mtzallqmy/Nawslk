import { createHmac, timingSafeEqual } from 'node:crypto';
import { validE164, validMessageBody } from './message-domain.mjs';
import { uuid } from './devices.mjs';

export const fail=(message,status=400)=>Object.assign(new Error(message),{status});
export function infobipConfiguration(env=process.env) {
  const baseUrl=env.INFOBIP_BASE_URL || 'https://nd5wxj.api.infobip.com';
  if(!/^https:\/\/[a-z0-9-]+\.api\.infobip\.com\/?$/.test(baseUrl))return null;
  const key=env.INFOBIP_API_KEY;
  if(typeof key!=='string' || !key.trim() || key.length>512 || /\s/.test(key))return null;
  const numbers=(env.INFOBIP_APPROVED_TEST_NUMBERS || '').split(',').map(x=>x.trim()).filter(Boolean);
  if(numbers.length>5 || numbers.some(x=>!validE164(x)))return null;
  return {baseUrl:baseUrl.replace(/\/$/,''),key,sender:env.INFOBIP_SMS_SENDER || '',numbers,
    smsEnabled:env.INFOBIP_SMS_TEST_ENABLED==='true',
    webhookKey:env.INFOBIP_WEBHOOK_SECRET || '',
    webhookUrl:env.INFOBIP_SMS_WEBHOOK_URL || ''};
}
export function infobipDelivery(status) {
  switch(Number(status?.groupId)) {
    case 1:return 'ACCEPTED';case 3:return 'DELIVERED';
    case 2:case 4:case 5:return 'FAILED';default:return 'UNKNOWN';
  }
}
export function authenticInfobipWebhook(raw,header,key) {
  if(!key || key.length<32 || typeof header!=='string')return false;
  const value=header.replace(/^sha256=/,'');
  let signature;
  if(/^[a-fA-F0-9]{64}$/.test(value))signature=Buffer.from(value,'hex');
  else if(/^[A-Za-z0-9+/]{43}=$/.test(value))signature=Buffer.from(value,'base64');
  else return false;
  const expected=createHmac('sha256',key).update(raw).digest();
  return signature.length===expected.length && timingSafeEqual(signature,expected);
}

export class InfobipProviderAdapter {
  constructor(config,{fetcher=fetch}={}) {
    this.config=config;this.fetcher=fetcher;
    this.sms={send:input=>this.sendSms(input)};
    this.voice={listActive:()=>this.request('GET','/calls/1/calls')};
    // SDK/media is deliberately separate from REST and the existing internal engine.
    this.webrtc={integration:'NOT_INTEGRATED',activation:'UNKNOWN'};
  }
  async request(method,path,body) {
    if(!this.config)throw fail('infobip_not_configured',503);
    try {
      const response=await this.fetcher(this.config.baseUrl+path,{method,redirect:'error',signal:AbortSignal.timeout(10000),
        headers:{authorization:'App '+this.config.key,accept:'application/json',...(body?{'content-type':'application/json'}:{})},body:body?JSON.stringify(body):undefined});
      if(!response.ok) {
        await response.body?.cancel();
        const error=fail('infobip_request_failed',502);error.upstreamStatus=response.status;throw error;
      }
      const reader=response.body.getReader();const chunks=[];let bytes=0;
      try {for(;;){const {value,done}=await reader.read();if(done)break;bytes+=value.length;if(bytes>65536){await reader.cancel();throw fail('infobip_response_invalid',502);}chunks.push(Buffer.from(value));}}
      finally {reader.releaseLock();}
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    }catch(error) {
      if(error.message==='infobip_request_failed')throw error;
      throw fail('infobip_outcome_unknown',502);
    }
  }
  async checkReadAccess() {
    const probes=[['smsLogs','/sms/3/logs?limit=1'],['voiceCalls','/calls/1/calls']];
    const results=await Promise.all(probes.map(async([channel,path])=>{
      try {await this.request('GET',path);return [channel,{state:'ACCESS_CONFIRMED',httpStatus:200}];}
      catch(error){return [channel,{state:error.upstreamStatus===401?'KEY_REJECTED':error.upstreamStatus===403?'FORBIDDEN':error.upstreamStatus===429?'RATE_LIMITED':'UNVERIFIED',httpStatus:error.upstreamStatus || null}];}
    }));
    const channels=Object.fromEntries(results);
    return {authentication:results.some(([,v])=>v.state==='KEY_REJECTED')?'REJECTED':results.some(([,v])=>v.state==='ACCESS_CONFIRMED')?'CONFIRMED':'UNKNOWN',
      channels:{...channels,smsSend:{state:'UNVERIFIED'},voiceSend:{state:'UNVERIFIED'},webrtc:{state:'NOT_INTEGRATED'}},checkedAt:new Date().toISOString()};
  }
  assertSmsApproved({to,text,consent}) {
    const c=this.config;
    if(!c)throw fail('infobip_not_configured',503);
    if(!c.smsEnabled || !c.numbers.includes(to) || consent!==true)throw fail('infobip_test_not_approved',403);
    if(!validE164(to) || !validMessageBody(text))throw fail('invalid_message');
    // A consented operator test must not silently turn into many billable segments.
    if(text.length>70)throw fail('infobip_test_text_too_long');
    if(!/^[A-Za-z0-9+ -]{1,32}$/.test(c.sender) || c.webhookKey.length<32 || !/^https:\/\/[a-z0-9.-]+\/v1\/providers\/infobip\/delivery$/.test(c.webhookUrl))throw fail('infobip_delivery_not_configured',503);
  }
  async sendSms(input) {
    if(!uuid(input.id))throw fail('invalid_message');
    this.assertSmsApproved(input);
    const data=await this.request('POST','/sms/3/messages',{messages:[{sender:this.config.sender,
      destinations:[{to:input.to.slice(1),messageId:input.id}],content:{text:input.text},
      webhooks:{delivery:{url:this.config.webhookUrl},contentType:'application/json'}}]});
    const message=data.messages?.[0];
    if(message?.messageId!==input.id)throw fail('infobip_outcome_unknown',502);
    return {reference:message.messageId,status:infobipDelivery(message.status)};
  }
}
