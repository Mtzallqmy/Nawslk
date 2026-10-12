import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac,randomUUID } from 'node:crypto';
import { InfobipProviderAdapter,infobipConfiguration,infobipDelivery,authenticInfobipWebhook } from '../src/infobip.mjs';

const env={INFOBIP_API_KEY:'fixture-only-api-key',INFOBIP_BASE_URL:'https://nd5wxj.api.infobip.com'};
test('Infobip configuration restricts destinations and credential-bearing HTTPS host',()=>{
    assert.equal(infobipConfiguration({}),null);
    for(const url of ['http://host.api.infobip.com','https://user:pass@host.api.infobip.com','https://evil.example','https://host.api.infobip.com/path'])assert.equal(infobipConfiguration({...env,INFOBIP_BASE_URL:url}),null);
    assert.equal(infobipConfiguration({...env,INFOBIP_APPROVED_TEST_NUMBERS:'invalid'}),null);
    assert.equal(infobipConfiguration(env).smsEnabled,false);
});
test('Read checks only GET, retain safe outcomes and do not infer send permissions',async()=>{
    const methods=[];
    const adapter=new InfobipProviderAdapter(infobipConfiguration(env),{fetcher:async(url,options)=>{
        methods.push(options.method);assert.equal(options.headers.authorization,'App '+env.INFOBIP_API_KEY);assert.equal(options.redirect,'error');
        return url.includes('sms/3/logs')?Response.json({results:[{text:'private response must not escape'}]}):new Response('secret',{status:403});
    }});
    const result=await adapter.checkReadAccess();assert.deepEqual(methods,['GET','GET']);
    assert.equal(result.authentication,'CONFIRMED');assert.equal(result.channels.voiceCalls.state,'FORBIDDEN');
    assert.equal(result.channels.smsSend.state,'UNVERIFIED');
    assert.ok(!JSON.stringify(result).includes(env.INFOBIP_API_KEY));assert.ok(!JSON.stringify(result).includes('private response'));
});
test('SMS requires explicit deployment approval, target allowlist and request consent',async()=>{
    let calls=0;
    const adapter=new InfobipProviderAdapter(infobipConfiguration(env),{fetcher:async()=>{calls++;}});
    await assert.rejects(adapter.sms.send({id:randomUUID(),to:'+15550000001',text:'fixture',consent:true}),e=>e.status===403);
    assert.equal(calls,0);
    const ready={...env,INFOBIP_SMS_TEST_ENABLED:'true',INFOBIP_APPROVED_TEST_NUMBERS:'+15550000001',INFOBIP_SMS_SENDER:'ServiceSMS',INFOBIP_WEBHOOK_SECRET:'fixture-hook-secret-32-bytes-long',INFOBIP_SMS_WEBHOOK_URL:'https://nawslk-api-production.up.railway.app/v1/providers/infobip/delivery'};
    const id=randomUUID();
    const live=new InfobipProviderAdapter(infobipConfiguration(ready),{fetcher:async(url,options)=>{
        calls++;assert.equal(url,env.INFOBIP_BASE_URL+'/sms/3/messages');
        const data=JSON.parse(options.body);assert.equal(data.messages[0].destinations[0].messageId,id);assert.equal(data.messages[0].content.text,'fixture');
        return Response.json({messages:[{messageId:id,status:{groupId:1}}]});
    }});
    await assert.rejects(live.sms.send({id,to:'+15550000002',text:'fixture',consent:true}));
    await assert.rejects(live.sms.send({id,to:'+15550000001',text:'fixture',consent:false}));
    assert.equal((await live.sms.send({id,to:'+15550000001',text:'fixture',consent:true})).status,'ACCEPTED');assert.equal(calls,1);
});
test('Infobip HMAC authenticates exact body and delivery classification is conservative',()=>{
    const raw=Buffer.from('{"results":[]}'),key='fixture-hook-secret-32-bytes-long';
    const mac=createHmac('sha256',key).update(raw).digest();
    for(const signature of [mac.toString('hex'),'sha256='+mac.toString('hex'),mac.toString('base64')])assert.equal(authenticInfobipWebhook(raw,signature,key),true);
    assert.equal(authenticInfobipWebhook(Buffer.from('{"results": []}'),mac.toString('hex'),key),false);
    assert.equal(authenticInfobipWebhook(raw,'invalid',key),false);
    assert.equal(infobipDelivery({groupId:1}),'ACCEPTED');assert.equal(infobipDelivery({groupId:3}),'DELIVERED');
    assert.equal(infobipDelivery({groupId:5}),'FAILED');assert.equal(infobipDelivery({groupId:99}),'UNKNOWN');
});
