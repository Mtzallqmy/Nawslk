import test from 'node:test';
import assert from 'node:assert/strict';
import { RateLimiter } from '../src/limits.mjs';
import { uuid } from '../src/devices.mjs';
import { validateSignal } from '../src/signaling-store.mjs';
test('rate limits expire, isolate identities and bound memory',()=>{
 let now=0; const limiter=new RateLimiter({now:()=>now,max:2,windowMs:100,capacity:2});
 assert.ok(limiter.allow('a')); assert.ok(limiter.allow('a')); assert.equal(limiter.allow('a'),false);
 assert.ok(limiter.allow('b')); assert.equal(limiter.allow('c'),false); now=101; assert.ok(limiter.allow('c'));
});
test('UUID input rejects malformed PostgreSQL casts',()=>{assert.equal(uuid('-'.repeat(36)),false);assert.ok(uuid('cfd922ec-dde6-49a8-a7b0-57076df9b138'));});
test('SDP and ICE validation reject malformed and oversized byte payloads',()=>{
 assert.throws(()=>validateSignal('offer',{sdp:'junk'}),/invalid_signal_sdp/);
 assert.throws(()=>validateSignal('ice',{candidate:'x',sdpMid:'0',sdpMLineIndex:-1}),/invalid_signal_ice/);
 assert.throws(()=>validateSignal('offer',{sdp:'v=0'+ 'ع'.repeat(9000)}),/signal_too_large/);
 validateSignal('ice',{candidate:'candidate:1',sdpMid:'0',sdpMLineIndex:0});
});
