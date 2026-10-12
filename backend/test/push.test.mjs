import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { encryptPushToken,decryptPushToken,pushConfiguration,FcmSender } from '../src/push.mjs';
test('push token encryption authenticates ciphertext and rejects wrong keys',()=>{
 const cipher=encryptPushToken('registration-token-123456789','secret');
 assert.ok(!cipher.includes('registration-token'));
 assert.equal(decryptPushToken(cipher,'secret'),'registration-token-123456789');
 assert.throws(()=>decryptPushToken(cipher,'different'));
 const bytes=Buffer.from(cipher,'base64');bytes[29]^=1;
 assert.throws(()=>decryptPushToken(bytes.toString('base64'),'secret'));
});
test('absent/mismatched Firebase configuration is disabled',()=>{
 assert.equal(pushConfiguration({}),null);
 assert.equal(pushConfiguration({FCM_SERVICE_ACCOUNT_JSON:'invalid'}),null);
 assert.equal(pushConfiguration({FCM_SERVICE_ACCOUNT_JSON:JSON.stringify({project_id:'one'}),FCM_ANDROID_CONFIG:JSON.stringify({projectId:'two'})}),null);
});
test('FCM v1 sender uses OAuth, high priority, minimal metadata and cached short-lived auth',async()=>{
 const {privateKey}=generateKeyPairSync('rsa',{modulusLength:2048,privateKeyEncoding:{type:'pkcs8',format:'pem'},publicKeyEncoding:{type:'spki',format:'pem'}});
 const requests=[];
 const sender=new FcmSender({account:{project_id:'test-project',client_email:'test@test-project.iam.gserviceaccount.com',private_key:privateKey}},{fetchImpl:async(url,options)=>{
  requests.push({url,options});return {ok:true,json:async()=>url.includes('oauth2')?{access_token:'access',expires_in:3600}:{name:'delivered'}};
 }});
 await sender.send('device-token','session-id',1234);await sender.send('device-token','another-session',2345);
 assert.equal(requests.length,3);
 const body=JSON.parse(requests[1].options.body);
 assert.deepEqual(body.message.data,{type:'incoming_call',sessionId:'session-id',expiresAt:'1234'});
 assert.equal(body.message.android.priority,'HIGH');
 assert.equal(body.message.android.ttl,'60s');
 assert.equal(requests[1].options.headers.authorization,'Bearer access');
});
test('unregistered FCM targets return a bounded safe error',async()=>{
 const sender=new FcmSender({account:{project_id:'test-project'}},{fetchImpl:async()=>({ok:false,status:404,json:async()=>({error:{details:[{errorCode:'UNREGISTERED'}]}})})});
 sender.access={token:'cached',until:Date.now()+3600000};
 await assert.rejects(sender.send('token','session'),/push_unregistered/);
});
