import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { validE164, validMessageBody, transition, deliveryTransition, numberTransitions, MockMessageProvider, MockNumberProvider, LicensedProviderAdapter, verifyWebhook } from '../src/message-domain.mjs';
import { encryptMessage,decryptMessage,cursor } from '../src/messaging.mjs';
test('message and number states reject impossible transitions',()=>{
 assert.equal(transition('SENDING','SENT'),'SENT');assert.throws(()=>transition('QUEUED','DELIVERED'),/invalid_transition/);
 assert.throws(()=>transition('DELIVERED','SENT'),/invalid_transition/);
 assert.equal(transition('AVAILABLE','RESERVED',numberTransitions),'RESERVED');assert.throws(()=>transition('RELEASED','ACTIVE',numberTransitions));
});
test('destinations, unicode content and cursors are bounded',()=>{
 assert.ok(validE164('+967700000001'));assert.equal(validE164('7000'),false);assert.equal(validE164('+0012345678'),false);
 assert.ok(validMessageBody('مرحبا'));assert.equal(validMessageBody(' '),false);assert.equal(validMessageBody('🙂'.repeat(1601)),false);assert.equal(validMessageBody('hello\u0000'),false);
 assert.throws(()=>cursor('-1'));assert.throws(()=>cursor('99999999999999999'));assert.equal(cursor('12'),'12');
});
test('mock sending explicitly simulates and never provisions phone numbers',async()=>{
 const sent=await new MockMessageProvider().send({messageId:'id'});assert.equal(sent.simulated,true);assert.equal(sent.providerReference,'demo:id');
 assert.deepEqual(await new MockNumberProvider().availableNumbers(),[]);
 await assert.rejects(new LicensedProviderAdapter().send(),/provider_not_configured/);
});
test('message ciphertext is authenticated and domain separated',()=>{
 const cipher=encryptMessage('نص خاص','server-key');assert.equal(decryptMessage(cipher,'server-key'),'نص خاص');
 assert.throws(()=>decryptMessage(cipher,'other-key'));assert.throws(()=>encryptMessage('body',''));
});
test('signed webhooks bind exact bytes, timestamp and nonce',()=>{
 const raw=Buffer.from('{"status":"DELIVERED"}'),timestamp='1700000000',nonce='nonce_12345678901234',secret='test-secret';
 const signature=createHmac('sha256',secret).update(timestamp+'.'+nonce+'.').update(raw).digest('hex');
 const headers={timestamp,nonce,secret,signature,now:1700000000000};
 assert.ok(verifyWebhook(raw,headers));assert.equal(verifyWebhook(Buffer.from('{}'),headers),false);
 assert.equal(verifyWebhook(raw,{...headers,now:1700000600000}),false);assert.equal(verifyWebhook(raw,{...headers,signature:'a'}),false);
});

test('out-of-order provider delivery advances pending stages without reopening terminals',()=>{
 assert.equal(deliveryTransition('SENDING','DELIVERED'),'DELIVERED');
 assert.equal(deliveryTransition('QUEUED','DELIVERED'),'DELIVERED');
 assert.equal(deliveryTransition('DELIVERED','SENT'),'DELIVERED');
 assert.equal(deliveryTransition('FAILED','DELIVERED'),'FAILED');
});
