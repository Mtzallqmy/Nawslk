// Non-destructive external TURN media probe. No owner/bootstrap/database access.
import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
import { createHmac, randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';

let browser;
try {
  const urls=(process.env.TURN_URLS || '').split(',').map(x=>x.trim()).filter(Boolean);
  assert.ok(urls.length && urls.every(x=>/^turns?:[^\s]+$/.test(x)));
  const secret=process.env.TURN_SECRET_FILE
    ? (await readFile(process.env.TURN_SECRET_FILE,'utf8')).trim()
    : process.env.TURN_SHARED_SECRET;
  assert.ok(secret && secret.length>=24);
  const username=`${Math.floor(Date.now()/1000)+300}:${randomUUID()}`;
  const credential=createHmac('sha1',secret).update(username).digest('base64');
  browser=await chromium.launch({args:['--autoplay-policy=no-user-gesture-required']});
  const pages=[await browser.newPage(),await browser.newPage()];
  for(const page of pages) await page.evaluate(async config=>{
    window.pc=new RTCPeerConnection(config);
    window.candidates=[];
    window.pc.onicecandidate=e=>{if(e.candidate)window.candidates.push(e.candidate.toJSON());};
    window.audio=new AudioContext();await window.audio.resume();
    const tone=window.audio.createOscillator();tone.frequency.value=440;
    const output=window.audio.createMediaStreamDestination();tone.connect(output);tone.start();
    window.track=output.stream.getAudioTracks()[0];window.pc.addTrack(window.track,output.stream);
    window.pc.ontrack=e=>{
      const element=document.createElement('audio');element.autoplay=true;element.volume=0;
      element.srcObject=e.streams[0];document.body.appendChild(element);element.play().catch(()=>{});
    };
  },{iceTransportPolicy:'relay',iceServers:[{urls,username,credential}]});
  const [a,b]=pages;
  const offer=await a.evaluate(async()=>{const s=await window.pc.createOffer();await window.pc.setLocalDescription(s);return {type:s.type,sdp:s.sdp};});
  const answer=await b.evaluate(async s=>{await window.pc.setRemoteDescription(s);const r=await window.pc.createAnswer();await window.pc.setLocalDescription(r);return {type:r.type,sdp:r.sdp};},offer);
  await a.evaluate(s=>window.pc.setRemoteDescription(s),answer);
  let indices=[0,0];
  async function transfer() {
    for(let i=0;i<2;i++) {
      const batch=await pages[i].evaluate(n=>window.candidates.slice(n),indices[i]);
      indices[i]+=batch.length;
      for(const candidate of batch) await pages[1-i].evaluate(c=>window.pc.addIceCandidate(c),candidate);
    }
  }
  async function stats(page) {
    return page.evaluate(async()=>{
      const report=await window.pc.getStats();
      const audio=[...report.values()].find(s=>s.type==='inbound-rtp' && s.kind==='audio');
      const pair=[...report.values()].find(s=>s.type==='candidate-pair' && s.state==='succeeded' && s.nominated);
      return {energy:audio?.totalAudioEnergy || 0,bytes:audio?.bytesReceived || 0,
        relay:pair ? report.get(pair.localCandidateId)?.candidateType==='relay' : false};
    });
  }
  let measured;
  const deadline=Date.now()+30000;
  while(Date.now()<deadline) {
    await transfer();measured=await Promise.all(pages.map(stats));
    if(measured.every(s=>s.relay && s.energy>0 && s.bytes>0))break;
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  assert.ok(measured?.every(s=>s.relay && s.energy>0 && s.bytes>0));
  for(const page of pages) await page.evaluate(()=>{window.track.stop();window.pc.close();return window.audio.close();});
  console.log('PASS: bidirectional generated audio measured over forced TURN; media resources released.');
} catch {
  // Library exceptions can contain ICE configuration. Never print them.
  console.error('FAIL: TURN audio probe did not pass. Check credentials, DNS, certificate, relay ports and connectivity.');
  process.exitCode=1;
} finally {
  await browser?.close();
}
