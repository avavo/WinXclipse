import test from 'node:test';
import assert from 'node:assert/strict';
import { Script } from 'node:vm';
import { route as moderation } from '../worker/index.js';
const ORIGIN = 'https://community.example', ADMIN = 'fixture-admin-key-not-a-production-secret';

test('moderation proxy keeps the server secret out of HTML and enforces origin', async () => {
  const env={ADMIN_TOKEN:ADMIN,COMMUNITY_ORIGIN:ORIGIN}; const html=await moderation(new Request('https://private.example/'),env);
  const page = await html.text(); assert.ok(!page.includes(ADMIN));
  assert.doesNotThrow(() => new Script(page.match(/<script>([\s\S]*?)<\/script>/)[1]));
  const denied=await moderation(new Request('https://private.example/api/import',{method:'POST',headers:{Origin:'https://attacker.example'},body:'{}'}),env);
  assert.equal(denied.status,403);
});
test('moderation proxy authorizes only fixed routes and injects credentials server-side', async () => {
  let called=false;
  const response=await moderation(new Request('https://private.example/api/configs'),{ADMIN_TOKEN:ADMIN,COMMUNITY_ORIGIN:ORIGIN},async(url,options)=>{
    called=true;assert.equal(url,ORIGIN+'/api/admin/configs?cursor=0');assert.equal(options.headers.Authorization,'Bearer '+ADMIN);return Response.json({items:[]});
  });assert.equal(response.status,200);assert.ok(called);
  assert.equal((await moderation(new Request('https://private.example/api/proxy?url=https://attacker.example'),{ADMIN_TOKEN:ADMIN,COMMUNITY_ORIGIN:ORIGIN})).status,404);
});
test('moderation never follows an upstream redirect with the server credential', async () => {
  const response = await moderation(new Request('https://panel.example/api/configs'),
    { COMMUNITY_ORIGIN:ORIGIN,ADMIN_TOKEN:ADMIN }, async (url, options) => {
      assert.equal(options.redirect, 'manual');
      return new Response(null,{status:302,headers:{Location:'https://attacker.example'}});
    });
  assert.equal(response.status,502);assert.equal((await response.json()).error,'upstream_redirect_rejected');
});
