import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { iceConfiguration, IceProvider } from '../src/ice.mjs';

const env={TURN_PROVIDER:'cloudflare',CLOUDFLARE_TURN_KEY_ID:'a'.repeat(32),CLOUDFLARE_TURN_API_TOKEN:'fixture_token_'.repeat(4)};
const body={iceServers:[{urls:['stun:stun.cloudflare.com:3478']},{urls:['turn:turn.cloudflare.com:3478?transport=udp','turn:turn.cloudflare.com:53?transport=udp','turns:turn.cloudflare.com:5349?transport=tcp'],username:'short-lived-user',credential:'short-lived-password'}]};
test('Cloudflare credentials use bounded HTTPS and cache independently per device',async()=>{
  let now=100000,calls=0;
  const provider=new IceProvider(iceConfiguration(env),{now:()=>now,fetcher:async(url,options)=>{
    calls++;assert.equal(url,'https://rtc.live.cloudflare.com/v1/turn/keys/'+env.CLOUDFLARE_TURN_KEY_ID+'/credentials/generate-ice-servers');
    assert.equal(options.headers.authorization,'Bearer '+env.CLOUDFLARE_TURN_API_TOKEN);
    assert.equal(options.redirect,'error');assert.deepEqual(JSON.parse(options.body),{ttl:3600});
    return Response.json(body);
  }});
  const [a,b]=await Promise.all([provider.credentials('device-a'),provider.credentials('device-a')]);
  assert.deepEqual(a,b);assert.equal(calls,1);assert.equal(a.expiresInSeconds,3600);
  assert.ok(!JSON.stringify(a).includes(env.CLOUDFLARE_TURN_API_TOKEN));
  assert.equal(a.iceServers[1].urls.length,2);
  now+=5000;assert.equal((await provider.credentials('device-a')).expiresInSeconds,3595);
  await provider.credentials('device-b');assert.equal(calls,2);
  now+=3600000;await provider.credentials('device-a');assert.equal(calls,3);
});
test('Upstream failures and malformed responses fail closed with safe retryable errors',async()=>{
  for(const fetcher of [async()=>new Response(env.CLOUDFLARE_TURN_API_TOKEN,{status:401}),
    async()=>{throw new Error(env.CLOUDFLARE_TURN_API_TOKEN);},
    async()=>Response.json({iceServers:[{urls:['https://unsafe.example'],username:'x',credential:'x'}]}),
    async()=>Response.json({iceServers:[{urls:['stun:stun.cloudflare.com:3478']}]}),
    async()=>new Response('x'.repeat(32769))]) {
    const provider=new IceProvider(iceConfiguration(env),{fetcher});
    await assert.rejects(provider.credentials('a'),error=>error.status===503 && error.message==='turn_unavailable');
    assert.equal(provider.pending.size,0);assert.equal(provider.cached.size,0);
  }
});
test('Coturn compatibility and missing provider configuration remain explicit',async()=>{
  assert.equal(iceConfiguration({}),null);
  assert.equal(iceConfiguration({...env,CLOUDFLARE_TURN_API_TOKEN:''}),null);
  assert.equal(iceConfiguration({TURN_PROVIDER:'unsupported'}),null);
  await assert.rejects(new IceProvider(null).credentials('a'),error=>error.status===503);
  const config=iceConfiguration({TURN_URLS:'turn:relay.example:3478?transport=udp',TURN_SHARED_SECRET:'fixture-only-secret'});
  const data=await new IceProvider(config,{now:()=>100000}).credentials('device');
  assert.equal(data.iceServers[0].username,'3700:device');
  assert.equal(data.iceServers[0].credential,createHmac('sha1',config.secret).update('3700:device').digest('base64'));
});
