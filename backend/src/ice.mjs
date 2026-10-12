import { createHmac } from 'node:crypto';

export function iceConfiguration(env=process.env) {
  const mode=env.TURN_PROVIDER || 'coturn';
  if(mode==='cloudflare') {
    if(!/^[a-f0-9]{32}$/.test(env.CLOUDFLARE_TURN_KEY_ID || '') || !/^[a-zA-Z0-9_-]{32,256}$/.test(env.CLOUDFLARE_TURN_API_TOKEN || ''))return null;
    return {provider:mode,keyId:env.CLOUDFLARE_TURN_KEY_ID,apiToken:env.CLOUDFLARE_TURN_API_TOKEN};
  }
  if(mode!=='coturn')return null;
  const urls=(env.TURN_URLS || '').split(',').map(s=>s.trim()).filter(s=>/^turns?:[^\s]+$/.test(s));
  if(!urls.length || !env.TURN_SHARED_SECRET)return null;
  const stun=(env.STUN_URLS || '').split(',').map(s=>s.trim()).filter(s=>/^stuns?:[^\s]+$/.test(s));
  return {provider:mode,urls,stun,secret:env.TURN_SHARED_SECRET};
}

function unavailable() {return Object.assign(new Error('turn_unavailable'),{status:503});}

export class IceProvider {
  constructor(config,{fetcher=fetch,now=Date.now}={}) {
    this.config=config;this.fetcher=fetcher;this.now=now;
    this.cached=new Map();this.pending=new Map();
  }
  async credentials(deviceId) {
    if(!this.config)throw Object.assign(new Error('turn_not_configured'),{status:503});
    const ttl=3600;
    if(this.config.provider==='coturn') {
      const username=`${Math.floor(this.now()/1000)+ttl}:${deviceId}`;
      const credential=createHmac('sha1',this.config.secret).update(username).digest('base64');
      return {iceServers:[{urls:this.config.urls,username,credential},...(this.config.stun.length?[{urls:this.config.stun}]:[])],expiresInSeconds:ttl};
    }
    const cached=this.cached.get(deviceId);
    if(cached && cached.until-this.now()>60000) return {...cached.value,expiresInSeconds:Math.floor((cached.until-this.now())/1000)};
    if(this.pending.has(deviceId))return this.pending.get(deviceId);
    const request=this.generate(ttl).then(value=>{
      // Small bounded cache; never share temporary usernames across devices.
      if(this.cached.size>=100)this.cached.delete(this.cached.keys().next().value);
      this.cached.set(deviceId,{value,until:this.now()+ttl*1000});return value;
    }).finally(()=>this.pending.delete(deviceId));
    this.pending.set(deviceId,request);return request;
  }
  async generate(ttl) {
    try {
      const response=await this.fetcher(`https://rtc.live.cloudflare.com/v1/turn/keys/${this.config.keyId}/credentials/generate-ice-servers`,{
        method:'POST',redirect:'error',signal:AbortSignal.timeout(5000),
        headers:{authorization:`Bearer ${this.config.apiToken}`,'content-type':'application/json'},body:JSON.stringify({ttl})});
      if(!response.ok)throw unavailable();
      // Bound response bytes before parsing so malformed upstream data cannot consume unbounded memory.
      if(Number(response.headers.get('content-length') || 0)>32768)throw unavailable();
      const reader=response.body.getReader();const chunks=[];let length=0;
      try {while(true){const {value,done}=await reader.read();if(done)break;length+=value.length;if(length>32768){await reader.cancel();throw unavailable();}chunks.push(Buffer.from(value));}}
      finally {reader.releaseLock();}
      const data=JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if(!Array.isArray(data.iceServers) || data.iceServers.length>8)throw unavailable();
      const iceServers=data.iceServers.map(server=>{
        const input=Array.isArray(server.urls)?server.urls:[server.urls];
        if(input.length>16 || input.some(url=>typeof url!=='string' || url.length>512 || !/^stuns?:[^\s]+$|^turns?:[^\s]+$/.test(url)))throw unavailable();
        // Port 53 is blocked by browsers; do not advertise unusable alternatives.
        const urls=input.filter(url=>!/:53(?:\?|$)/.test(url));
        const result={urls};
        if(urls.some(url=>/^turns?:/.test(url))) {
          for(const key of ['username','credential']) {
            if(typeof server[key]!=='string' || !server[key].length || server[key].length>1024 || server[key].includes(this.config.apiToken))throw unavailable();
            result[key]=server[key];
          }
        }
        return result;
      }).filter(server=>server.urls.length);
      if(!iceServers.some(server=>server.urls.some(url=>/^turns?:/.test(url))))throw unavailable();
      return {iceServers,expiresInSeconds:ttl};
    }catch {throw unavailable();}
  }
}
