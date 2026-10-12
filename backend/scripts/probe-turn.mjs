// Non-destructive external TURN media probe. No owner/bootstrap/database access.
import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
import { createHmac, randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import http from 'node:http';
import { iceConfiguration, IceProvider } from '../src/ice.mjs';

let browser;
let origin;
let stage='configuration';
try {
  let iceServers;
  if(process.env.TURN_PROVIDER==='cloudflare') {
    const env={...process.env};
    if(env.CLOUDFLARE_TURN_TOKEN_FILE)env.CLOUDFLARE_TURN_API_TOKEN=(await readFile(env.CLOUDFLARE_TURN_TOKEN_FILE,'utf8')).trim();
    iceServers=(await new IceProvider(iceConfiguration(env)).credentials(randomUUID())).iceServers;
    const transport=process.env.TURN_PROBE_TRANSPORT;
    if(transport) {
      assert.ok(['udp','tcp','tls'].includes(transport));
      iceServers=iceServers.map(server=>({...server,urls:server.urls.filter(url=>transport==='tls'?url.startsWith('turns:'):url.startsWith('turn:') && url.includes('transport='+transport))})).filter(server=>server.urls.length);
      assert.ok(iceServers.length);
    }
  }else {
  const urls=(process.env.TURN_URLS || '').split(',').map(x=>x.trim()).filter(Boolean);
  assert.ok(urls.length && urls.every(x=>/^turns?:[^\s]+$/.test(x)));
  const secret=process.env.TURN_SECRET_FILE
    ? (await readFile(process.env.TURN_SECRET_FILE,'utf8')).trim()
    : process.env.TURN_SHARED_SECRET;
  assert.ok(secret && secret.length>=24);
  const username=`${Math.floor(Date.now()/1000)+300}:${randomUUID()}`;
  const credential=createHmac('sha1',secret).update(username).digest('base64');
  iceServers=[{urls,username,credential}];
  }
  stage='browser';
  origin=http.createServer((_request,response)=>{response.writeHead(200,{'content-type':'text/html'});response.end('<!doctype html><title>Nawslk TURN acceptance</title>');});
  await new Promise(resolve=>origin.listen(0,'127.0.0.1',resolve));
  browser=await chromium.launch({args:['--autoplay-policy=no-user-gesture-required']});
  const pages=[await browser.newPage(),await browser.newPage()];
  for(const page of pages)await page.goto('http://127.0.0.1:'+origin.address().port);
  stage='negotiation';
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
      const source=window.audio.createMediaStreamSource(e.streams[0]);
      window.analyser=window.audio.createAnalyser();source.connect(window.analyser);
      const silence=window.audio.createGain();silence.gain.value=0;
      window.analyser.connect(silence);silence.connect(window.audio.destination);
    };
  },{iceTransportPolicy:'relay',iceServers});
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
      const spectrum=new Uint8Array(window.analyser?.frequencyBinCount || 0);
      window.analyser?.getByteFrequencyData(spectrum);
      return {energy:Math.max(0,...spectrum),bytes:audio?.bytesReceived || 0,
        relay:pair ? report.get(pair.localCandidateId)?.candidateType==='relay' : false};
    });
  }
  let measured;
  stage='media';
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
  console.error('FAIL: TURN audio probe did not pass at '+stage+'. Check credentials, DNS, certificate, relay ports and connectivity.');
  process.exitCode=1;
} finally {
  await browser?.close();
  if(origin)await new Promise(resolve=>origin.close(resolve));
}
