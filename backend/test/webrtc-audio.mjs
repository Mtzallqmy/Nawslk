// Explicit integration command, separate from unit tests. Uses a disposable PostgreSQL DB.
// A generated tone crosses DTLS-SRTP between two isolated browser contexts.
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { Pool } from 'pg';
import { randomUUID } from 'node:crypto';
import { hashSecret } from '../src/auth.mjs';
import assert from 'node:assert/strict';
const db = new Pool({connectionString:process.env.TEST_DATABASE_URL});
assert.ok(process.env.TEST_DATABASE_URL,'Disposable TEST_DATABASE_URL is required');
await db.query('DROP TABLE IF EXISTS signaling_messages,signaling_sessions,device_invites,trusted_devices,owner_device CASCADE');
const secret='webrtc-integration-bootstrap-secret';
const child=spawn(process.execPath,['src/server.mjs'],{env:{...process.env,DATABASE_URL:process.env.TEST_DATABASE_URL,BOOTSTRAP_SECRET:secret,BOOTSTRAP_SECRET_SHA256:hashSecret(secret),PORT:'3100'},stdio:'ignore'});
let browser;
const api=async(path,token,method='GET',body,bootstrap)=>{
 const res=await fetch('http://127.0.0.1:3100'+path,{method,headers:{'content-type':'application/json',...(token?{authorization:'Bearer '+token}:{}),...(bootstrap?{'x-bootstrap-secret':secret}:{})},body:body?JSON.stringify(body):undefined});
 const data=await res.json(); assert.ok(res.ok,`${method} ${path}: HTTP ${res.status}`); return data;
};
const wait=async(fn,label)=>{for(let i=0;i<100;i++){if(await fn())return;await new Promise(r=>setTimeout(r,100));}throw new Error('Timeout: '+label);};
try {
 await wait(async()=>{try{return (await api('/health')).status==='ok';}catch{return false;}},'startup');
 const owner=await api('/v1/bootstrap',null,'POST',{email:'audio@example.com'},true);
 const invite=await api('/v1/devices/invites',owner.token,'POST');
 const peer=await api('/v1/devices/redeem',null,'POST',{invite:invite.invite,label:'audio peer'});
 const session=await api('/v1/calls/sessions',owner.token,'POST',{peerId:peer.deviceId});
 const path='/v1/calls/sessions/'+session.id;
 await api(path+'/accept',peer.token,'POST');
 browser=await chromium.launch({args:['--autoplay-policy=no-user-gesture-required']});
 const a=await browser.newPage(),b=await browser.newPage();
 for(const page of [a,b]) {
  await page.goto('http://127.0.0.1:3100/health');
  await page.evaluate(async()=>{
   window.pc=new RTCPeerConnection(); window.ice=[];
   window.pc.onicecandidate=e=>{if(e.candidate)window.ice.push(e.candidate.toJSON());};
   window.audio=new AudioContext(); await window.audio.resume();
   const oscillator=window.audio.createOscillator(); oscillator.frequency.value=440;
   const output=window.audio.createMediaStreamDestination(); oscillator.connect(output); oscillator.start();
   window.track=output.stream.getAudioTracks()[0]; window.pc.addTrack(window.track,output.stream);
   window.pc.ontrack=e=>{
    // Attach a media element as well as the analyser so Chromium starts RTP playout.
    const playback=document.createElement('audio'); playback.autoplay=true; playback.volume=0;
    playback.srcObject=e.streams[0]; document.body.appendChild(playback);
    playback.play().catch(()=>{});
    const source=window.audio.createMediaStreamSource(e.streams[0]);
    window.analyser=window.audio.createAnalyser(); source.connect(window.analyser);
    const sink=window.audio.createGain(); sink.gain.value=0; window.analyser.connect(sink); sink.connect(window.audio.destination);
   };
  });
 }
 const offer=await a.evaluate(async()=>{const o=await window.pc.createOffer();await window.pc.setLocalDescription(o);return {sdp:o.sdp};});
 await api(path+'/signals',owner.token,'POST',{kind:'offer',payload:offer,messageId:randomUUID()});
 const remoteOffer=(await api(path+'/signals',peer.token)).messages[0].payload;
 const answer=await b.evaluate(async o=>{await window.pc.setRemoteDescription({type:'offer',sdp:o.sdp});const ans=await window.pc.createAnswer();await window.pc.setLocalDescription(ans);return {sdp:ans.sdp};},remoteOffer);
 await api(path+'/signals',peer.token,'POST',{kind:'answer',payload:answer,messageId:randomUUID()});
 const remoteAnswer=(await api(path+'/signals',owner.token)).messages[0].payload;
 await a.evaluate(ans=>window.pc.setRemoteDescription({type:'answer',sdp:ans.sdp}),remoteAnswer);
 for(const [page,token] of [[a,owner.token],[b,peer.token]]) {
  await wait(()=>page.evaluate(()=>window.pc.iceGatheringState==='complete'),'ICE gathering');
  const candidates=await page.evaluate(()=>window.ice);
  assert.ok(candidates.length);
  for(const candidate of candidates) await api(path+'/signals',token,'POST',{kind:'ice',payload:{candidate:candidate.candidate,sdpMid:candidate.sdpMid,sdpMLineIndex:candidate.sdpMLineIndex},messageId:randomUUID()});
 }
 for(const [page,token] of [[a,owner.token],[b,peer.token]]) {
  const signals=(await api(path+'/signals',token)).messages;
  for(const m of signals.filter(x=>x.kind==='ice')) await page.evaluate(c=>window.pc.addIceCandidate(c),m.payload);
 }
 const energy=page=>page.evaluate(()=>{
  if(!window.analyser)return 0;
  const data=new Uint8Array(window.analyser.frequencyBinCount);window.analyser.getByteFrequencyData(data);return Math.max(...data);
 });
 for(const page of [a,b]) {
  await wait(()=>page.evaluate(()=>window.pc.connectionState==='connected'),'DTLS connected');
  await wait(async()=>await energy(page)>50,'received audio energy');
  const stats=await page.evaluate(async()=>{const all=await window.pc.getStats();return [...all.values()].filter(s=>s.type==='inbound-rtp' && s.kind==='audio').map(s=>({bytesReceived:s.bytesReceived,packetsReceived:s.packetsReceived}));});
  assert.ok(stats.some(s=>s.bytesReceived>0 && s.packetsReceived>0));
 }
 await a.evaluate(()=>{window.track.enabled=false;});
 await wait(async()=>await energy(b)<10,'mute removes remote audio');
 await a.evaluate(()=>{window.track.enabled=true;});
 await wait(async()=>await energy(b)>50,'unmute restores remote audio');
 await api(path,owner.token,'DELETE');
 assert.equal((await api(path,peer.token)).state,'ended');
 for(const page of [a,b]) {
  const state=await page.evaluate(async()=>{window.track.stop();window.pc.close();await window.audio.close();return {track:window.track.readyState,peer:window.pc.connectionState};});
  assert.deepEqual(state,{track:'ended',peer:'closed'});
 }
 console.log('PASS: authorized bidirectional DTLS-SRTP audio, mute/unmute, signaling hangup and track cleanup');
 console.log('Scope: generated tone in Chromium on CI loopback; Android microphone, Bluetooth, external NAT/TURN and device interruptions remain unverified.');
} catch(error) {
 if(browser) for(const context of browser.contexts()) for(const page of context.pages()) {
  console.log('Media diagnostics:',await page.evaluate(async()=>({audioState:window.audio?.state,connection:window.pc?.connectionState,hasAnalyser:!!window.analyser,trackState:window.track?.readyState,stats:window.pc?[...(await window.pc.getStats()).values()].filter(s=>s.type==='inbound-rtp' && s.kind==='audio').map(s=>({bytes:s.bytesReceived,energy:s.totalAudioEnergy,samples:s.totalSamplesReceived})):[]})).catch(()=>({unavailable:true})));
 }
 throw error;
} finally {
 await browser?.close(); child.kill('SIGTERM'); if(child.exitCode===null) await new Promise(r=>child.once('exit',r)); await db.end();
}
