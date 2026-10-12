import { spawn } from 'node:child_process';
import { IceProvider, iceConfiguration } from '../src/ice.mjs';
import { randomUUID } from 'node:crypto';

try {
  const credentials=await new IceProvider(iceConfiguration()).credentials(randomUUID());
  console.log(JSON.stringify({event:'turn_credentials_issued',ttl:credentials.expiresInSeconds}));
  for(const transport of ['udp','tcp','tls']) {
    const code=await new Promise(resolve=>{
      const child=spawn(process.execPath,['scripts/probe-turn.mjs'],{
        env:{...process.env,TURN_PROBE_TRANSPORT:transport},stdio:'inherit'});
      const timeout=setTimeout(()=>child.kill('SIGKILL'),45000);
      child.on('error',()=>{clearTimeout(timeout);resolve(1);});
      child.on('exit',code=>{clearTimeout(timeout);resolve(code ?? 1);});
    });
    console.log(JSON.stringify({event:'turn_media_validation',transport,passed:code===0}));
    if(code!==0)process.exitCode=1;
  }
}catch {
  console.log(JSON.stringify({event:'turn_credentials_failed'}));process.exitCode=1;
}
