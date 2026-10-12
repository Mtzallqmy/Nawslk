import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, statSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

function fixture() {
  const dir=mkdtempSync(join(tmpdir(),'nawslk-turn-'));
  const cert=join(dir,'cert.pem'),key=join(dir,'key.pem'),output=join(dir,'private');
  writeFileSync(cert,'fixture certificate');writeFileSync(key,'fixture key');
  const args=['../infra/turn/prepare.py','--public-ip','8.8.8.8','--realm','turn.example.com',
    '--cert-file',cert,'--key-file',key,'--output-dir',output];
  return {dir,output,args,run:extra=>spawnSync('python3',[...args,...extra],{encoding:'utf8'})};
}
test('TURN deployment writes matching private secrets, bounded relay and private-peer exclusions',()=>{
  const f=fixture();try {
    const result=f.run([]);assert.equal(result.status,0);
    const secret=readFileSync(join(f.output,'shared-secret'),'utf8').trim();
    assert.match(secret,/^[a-f0-9]{64}$/);
    const config=readFileSync(join(f.output,'turnserver.conf'),'utf8');
    assert.ok(config.includes('static-auth-secret='+secret));
    assert.ok(readFileSync(join(f.output,'railway.env'),'utf8').includes('TURN_SHARED_SECRET='+secret));
    for(const line of ['min-port=49160','max-port=49260','no-cli','no-tcp-relay','use-auth-secret',
      'denied-peer-ip=169.254.0.0-169.254.255.255'])assert.ok(config.includes(line));
    assert.ok(!config.includes('allow-loopback-peers'));
    assert.equal(statSync(f.output).mode&0o777,0o700);
    for(const file of ['shared-secret','turnserver.conf','railway.env'])assert.equal(statSync(join(f.output,file)).mode&0o777,0o600);
    assert.ok(!(result.stdout+result.stderr).includes(secret));
    assert.notEqual(f.run([]).status,0);
    assert.equal(readFileSync(join(f.output,'shared-secret'),'utf8').trim(),secret);
  }finally {rmSync(f.dir,{recursive:true,force:true});}
});
test('TURN generator refuses private public addresses and injected configuration',()=>{
  for(const override of [['--public-ip','10.0.0.1'],['--realm','host\nno-auth'],['--cert-file','/missing/cert']]) {
    const f=fixture();try {assert.notEqual(f.run(override).status,0);}finally {rmSync(f.dir,{recursive:true,force:true});}
  }
});
