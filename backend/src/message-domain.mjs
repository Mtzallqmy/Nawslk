import { createHmac, timingSafeEqual } from 'node:crypto';
export const validE164=value=>typeof value==='string' && /^\+[1-9][0-9]{7,14}$/.test(value);
export const messageTransitions={DRAFT:['QUEUED'],QUEUED:['SENDING','FAILED'],SENDING:['SENT','FAILED'],SENT:['DELIVERED','FAILED'],DELIVERED:[],FAILED:['QUEUED'],RECEIVED:[]};
export const numberTransitions={AVAILABLE:['RESERVED','RELEASED'],RESERVED:['ACTIVE','AVAILABLE','EXPIRED','RELEASED'],ACTIVE:['SUSPENDED','EXPIRED','RELEASED'],SUSPENDED:['ACTIVE','EXPIRED','RELEASED'],EXPIRED:['RELEASED'],RELEASED:[]};
export function transition(current,next,table=messageTransitions) {
 if(current===next) return next;
 if(!table[current]?.includes(next)) throw new Error('invalid_transition');
 return next;
}
export function validMessageBody(body) { return typeof body==='string' && body.trim().length>0 && [...body].length<=1600 && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(body); }
export class MockMessageProvider {
 key='local-demo'; enabled=true; licensed=false;
 async send({messageId}) { return {status:'SENT',providerReference:'demo:'+messageId,simulated:true}; }
}
export class MockNumberProvider {
 key='local-demo'; licensed=false;
 async availableNumbers(){return [];}
 async provision(){throw new Error('provider_not_configured');}
}
export class LicensedProviderAdapter {
 // No vendor/account or billing capability is invented. A vetted adapter replaces this guard.
 enabled=false; licensed=true;
 async send(){throw new Error('provider_not_configured');}
 async availableNumbers(){return [];}
 async provision(){throw new Error('provider_not_configured');}
}
export function verifyWebhook(raw,{timestamp,nonce,signature,secret,now=Date.now()}) {
 if(!secret || !/^\d{10}$/.test(timestamp || '') || Math.abs(now-Number(timestamp)*1000)>300000 || !/^[a-zA-Z0-9_-]{16,128}$/.test(nonce || '') || !/^[a-f0-9]{64}$/.test(signature || '')) return false;
 const expected=createHmac('sha256',secret).update(timestamp+'.'+nonce+'.').update(raw).digest();
 return timingSafeEqual(expected,Buffer.from(signature,'hex'));
}
