import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { deflateSync, deflateRawSync } from 'node:zlib';
import { Script } from 'node:vm';
import worker, { makeArchive, readArchive, crc32, validatePng } from '../worker/index.js';

const ORIGIN = 'https://community.example', ADMIN = 'fixture-admin-key-not-a-production-secret';
const manifest = (name = 'Jogo de ação') => ({ format: 'winxclipse-community-config', schemaVersion: 1,
  metadata: { gameName: name, author: 'Player', device: 'Galaxy S24 FE', notes: 'Use o preset exportado' },
  container: { wineVersion: 'Proton 10-arm64', graphicsDriverConfig: 'version=Quasar 67', box64Version: '0.4.5' },
  contents: [{ role: 'wineRuntime', versionName: 'Proton 10', arch: 'arm64' }] });

export function environment() {
  const sqlite = new DatabaseSync(':memory:');
  sqlite.exec(readFileSync(new URL('../drizzle/0000_community.sql', import.meta.url), 'utf8'));
  const files = new Map();
  const DB = { prepare(sql) {
    const statement = sqlite.prepare(sql); let values = [];
    return { bind(...args) { values = args; return this; },
      async first() { return statement.get(...values) || null; },
      async all() { return { results: statement.all(...values) }; },
      async run() { const result = statement.run(...values); return { meta: { changes: Number(result.changes) } }; }
    };
  }};
  const BUCKET = { failPut: false,
    async put(key, data) { if (this.failPut) throw Error('fixture-storage-failure'); files.set(key, new Uint8Array(data)); },
    async get(key) { const data = files.get(key); if (!data) return null; return { body: new Blob([data]).stream(), async arrayBuffer() { return data.slice().buffer; } }; },
    async delete(key) { files.delete(key); }
  };
  return { DB, BUCKET, ADMIN_TOKEN: ADMIN, RATE_SALT: 'fixture-rate-salt', sqlite, files };
}
const request = (env, path, method = 'GET', data, headers = {}) => worker.fetch(new Request(ORIGIN + path, {
  method, headers: { 'CF-Connecting-IP': '192.0.2.10', ...headers }, body: data
}), env);
async function register(env) { const response = await request(env, '/api/v1/clients', 'POST'); assert.equal(response.status, 201); return (await response.json()).token; }
async function upload(env, key, value = manifest(), cover = null) {
  return request(env, '/api/v1/configs', 'POST', makeArchive(value, cover), { Authorization: 'Bearer ' + key, 'Content-Type': 'application/zip' });
}
const adminHeaders = revision => ({ Authorization: 'Bearer ' + ADMIN, 'If-Match': '"' + revision + '"', 'Content-Type': 'application/json' });
function chunk(name, data) {
  const result = new Uint8Array(data.length + 12), view = new DataView(result.buffer);
  view.setUint32(0, data.length); result.set(new TextEncoder().encode(name), 4); result.set(data, 8);
  view.setUint32(result.length - 4, crc32(result.subarray(4, result.length - 4))); return result;
}
function png(width = 1, height = 1, raw = new Uint8Array([0, 12, 34, 56, 255])) {
  const header = new Uint8Array(13), view = new DataView(header.buffer); view.setUint32(0, width); view.setUint32(4, height); header[8] = 8; header[9] = 6;
  return new Uint8Array(Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', header), chunk('IDAT', deflateSync(raw)), chunk('IEND', new Uint8Array())]));
}
function deflatedZip(value) {
  // Independently built DEFLATE fixture, including the data descriptor emitted by Java ZipOutputStream.
  const name = Buffer.from('config.txt'), data = Buffer.from(JSON.stringify(value)), compressed = deflateRawSync(data), crc = crc32(data);
  const local = Buffer.alloc(30 + name.length); local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x808, 6); local.writeUInt16LE(8, 8); local.writeUInt16LE(name.length, 26); name.copy(local, 30);
  const descriptor = Buffer.alloc(16); descriptor.writeUInt32LE(0x08074b50); descriptor.writeUInt32LE(crc, 4); descriptor.writeUInt32LE(compressed.length, 8); descriptor.writeUInt32LE(data.length, 12);
  const central = Buffer.alloc(46 + name.length); central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0x808, 8); central.writeUInt16LE(8, 10); central.writeUInt32LE(crc, 16); central.writeUInt32LE(compressed.length, 20); central.writeUInt32LE(data.length, 24); central.writeUInt16LE(name.length, 28); name.copy(central, 46);
  const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50); end.writeUInt16LE(1, 8); end.writeUInt16LE(1, 10); end.writeUInt32LE(central.length, 12); end.writeUInt32LE(local.length + compressed.length + descriptor.length, 16);
  return new Uint8Array(Buffer.concat([local, compressed, descriptor, central, end]));
}

test('stored ZIP round-trip preserves UTF-8, exact dependencies and selected image', async () => {
  const value = manifest(), image = png(), decoded = await readArchive(makeArchive(value, image));
  assert.deepEqual(decoded.manifest, value); assert.deepEqual(decoded.cover, image);
});
test('reads independently generated DEFLATE ZIP with Java-style data descriptor', async () => {
  assert.deepEqual((await readArchive(deflatedZip(manifest()))).manifest, manifest());
});
test('rejects ZIP traversal/unknown entries instead of serving executable payloads', async () => {
  const data = makeArchive(manifest(), null);
  const replaced = Buffer.from(data).toString('latin1').replaceAll('config.txt', '../bad.exe');
  await assert.rejects(readArchive(new Uint8Array(Buffer.from(replaced, 'latin1'))), /unexpected_zip_entry/);
});
test('rejects wrong CRC, truncated archive and forged offsets', async () => {
  let bytes = makeArchive(manifest(), null); bytes[40] ^= 1;
  await assert.rejects(readArchive(bytes), /invalid_zip_checksum/);
  await assert.rejects(readArchive(bytes.subarray(0, bytes.length - 1)), /invalid_zip/);
  bytes = makeArchive(manifest(), null); new DataView(bytes.buffer).setUint32(bytes.length - 6, 0xffffffff, true);
  await assert.rejects(readArchive(bytes), /invalid_zip/);
});
test('rejects invalid schemas and generic container names', async () => {
  for (const value of [{...manifest(), schemaVersion: 999}, {...manifest(), format: 'other'}, {...manifest(), container: null}, manifest('Container 4')])
    await assert.rejects(readArchive(makeArchive(value, null)), /invalid_config|game_name_required/);
});
test('PNG validation bounds actual decompression, dimensions and CRC', async () => {
  await validatePng(png());
  await assert.rejects(validatePng(png(9999, 1)), /invalid_cover/);
  await assert.rejects(validatePng(png(1, 1, new Uint8Array(1000))), /file_too_large/);
  const broken = png(); broken[broken.length - 1] ^= 1; await assert.rejects(validatePng(broken), /invalid_cover/);
});
test('registration stores only a hash and returns a scoped upload credential', async t => {
  const env = environment(); t.after(() => env.sqlite.close()); const key = await register(env);
  assert.match(key, /^[A-Za-z0-9_-]{43}$/); const stored = env.sqlite.prepare('SELECT * FROM clients').get();
  assert.notEqual(stored.token_hash, key); assert.equal(stored.token_hash.length, 64);
});
test('public uploads cannot self-verify or access moderation with their token', async t => {
  const env = environment(); t.after(() => env.sqlite.close()); const key = await register(env), value = manifest(); value.metadata.verified = true; value.verified = true;
  const response = await upload(env, key, value); assert.equal(response.status, 201); const item = await response.json();
  assert.equal(item.verified, false); assert.match(item.id, /^[0-9a-f-]{36}$/);
  assert.equal((await request(env, '/api/admin/configs', 'GET', undefined, { Authorization: 'Bearer ' + key })).status, 401);
});
test('upload retry returns the same stable ID and does not duplicate files', async t => {
  const env = environment(); t.after(() => env.sqlite.close()); const key = await register(env);
  const first = await (await upload(env, key)).json(), second = await (await upload(env, key)).json();
  assert.equal(first.id, second.id); assert.equal(env.files.size, 1); assert.equal(env.sqlite.prepare('SELECT COUNT(*) AS n FROM configs').get().n, 1);
});
test('catalog metadata is bounded and does not publish arbitrary manifest fields', async t => {
  const env = environment(); t.after(() => env.sqlite.close()); const key = await register(env), value = manifest();
  value.metadata.privatePayload = 'x'.repeat(2 * 1024 * 1024);
  const response = await upload(env, key, value); assert.equal(response.status, 201);
  const item = await response.json(); assert.equal(item.metadata.privatePayload, undefined);
  assert.ok(JSON.stringify(item).length < 2000);
  value.metadata.device = 'x'.repeat(257);
  assert.equal((await upload(env, key, value)).status, 400);
});
test('catalog never leaks manifests, client credentials or admin token', async t => {
  const env = environment(); t.after(() => env.sqlite.close()); const key = await register(env); await upload(env, key);
  const response = await request(env, '/api/v1/catalog'), output = await response.text();
  assert.equal(response.status, 200); assert.ok(!output.includes(key)); assert.ok(!output.includes(ADMIN));
  assert.equal(JSON.parse(output).items[0].manifest, undefined);
});
test('published download matches SHA-256 and exact dependency manifest', async t => {
  const env = environment(); t.after(() => env.sqlite.close()); const key = await register(env), item = await (await upload(env, key, manifest(), png())).json();
  const response = await worker.fetch(new Request(item.downloadUrl), env), bytes = new Uint8Array(await response.arrayBuffer());
  const hash = Buffer.from(await crypto.subtle.digest('SHA-256', bytes)).toString('hex'); assert.equal(hash, item.sha256);
  assert.deepEqual((await readArchive(bytes)).manifest.contents, manifest().contents);
});
test('owner edits name and verification with optimistic revision protection', async t => {
  const env = environment(); t.after(() => env.sqlite.close()); const key = await register(env), item = await (await upload(env, key)).json();
  let response = await request(env, '/api/admin/configs/' + item.id, 'PATCH', JSON.stringify({gameName:'Novo nome',verified:true}), adminHeaders(1));
  assert.equal(response.status, 200); const updated = await response.json(); assert.equal(updated.revision, 2); assert.equal(updated.verified, true);
  response = await request(env, '/api/admin/configs/' + item.id, 'PATCH', JSON.stringify({gameName:'Stale'}), adminHeaders(1)); assert.equal(response.status, 409);
  const exported = await worker.fetch(new Request(updated.downloadUrl), env); const decoded = await readArchive(new Uint8Array(await exported.arrayBuffer()));
  assert.equal(decoded.manifest.metadata.gameName, 'Novo nome'); assert.deepEqual(decoded.manifest.container, manifest().container); assert.equal(env.files.size, 1);
});
test('owner cover changes are reflected in both catalog and ZIP', async t => {
  const env = environment(); t.after(() => env.sqlite.close()); const key = await register(env), item = await (await upload(env, key)).json(), image = png();
  const response = await request(env, '/api/admin/configs/' + item.id + '/cover', 'PUT', image, adminHeaders(1)); assert.equal(response.status, 200);
  const updated = await response.json(); const exported = await worker.fetch(new Request(updated.downloadUrl), env);
  assert.deepEqual((await readArchive(new Uint8Array(await exported.arrayBuffer()))).cover, image);
});
test('removed configs disappear from downloads but keep a catalog tombstone', async t => {
  const env = environment(); t.after(() => env.sqlite.close()); const key = await register(env), item = await (await upload(env, key)).json();
  assert.equal((await request(env, '/api/admin/configs/' + item.id, 'DELETE', undefined, adminHeaders(1))).status, 200);
  assert.equal((await worker.fetch(new Request(item.downloadUrl), env)).status, 404); assert.equal(env.files.size, 0);
  assert.equal((await (await request(env, '/api/v1/catalog')).json()).items[0].deleted, true);
  assert.equal((await upload(env, key)).status, 409);
});
test('storage limit rejects publication and removes the uncommitted object', async t => {
  const env = environment(); t.after(() => env.sqlite.close()); env.MAX_STORAGE_BYTES = 1; const key = await register(env);
  assert.equal((await upload(env, key)).status, 507); assert.equal(env.files.size, 0); assert.equal(env.sqlite.prepare('SELECT COUNT(*) AS n FROM configs').get().n, 0);
});
test('failed file storage does not publish a database record', async t => {
  const env = environment(); t.after(() => env.sqlite.close()); const key = await register(env); env.BUCKET.failPut = true;
  assert.equal((await upload(env, key)).status, 500); assert.equal(env.sqlite.prepare('SELECT COUNT(*) AS n FROM configs').get().n, 0);
});
test('anonymous, forged token and cross-origin uploads are rejected', async t => {
  const env = environment(); t.after(() => env.sqlite.close());
  assert.equal((await upload(env, 'a'.repeat(43))).status, 401);
  assert.equal((await request(env, '/api/v1/configs', 'POST', makeArchive(manifest(), null))).status, 401);
  assert.equal((await request(env, '/api/v1/clients', 'POST', undefined, { Origin:'https://attacker.example' })).status, 403);
});
test('registration and publishing are rate limited', async t => {
  const env = environment(); t.after(() => env.sqlite.close()); const key = await register(env);
  for (let i=0;i<5;i++) assert.equal((await upload(env,key,manifest('Game '+i))).status,201);
  assert.equal((await upload(env,key,manifest('Game 6'))).status,429);
});
test('owner import refuses arbitrary network sources', async t => {
  const env=environment();t.after(()=>env.sqlite.close());
  for(const url of ['http://127.0.0.1/private','https://attacker.example/a.zip','https://github.com/other/repo/a.zip'])
    assert.equal((await request(env,'/api/admin/import','POST',JSON.stringify({url}),adminHeaders(1))).status,400);
});
test('only the owner can import a normalized legacy ZIP with its official source identity', async t => {
  const env = environment(); t.after(() => env.sqlite.close());
  const source = 'https://github.com/avavo/WinXclipse/releases/download/community-configs/Game.zip';
  const headers = {Authorization:'Bearer '+ADMIN,'Content-Type':'application/zip','X-Source-Url':source};
  let response = await request(env,'/api/admin/import','POST',makeArchive(manifest(),png()),headers);
  assert.equal(response.status,201);const first=await response.json();
  assert.equal(first.verified,true);assert.equal(first.sourceUrl,source);
  response = await request(env,'/api/admin/import','POST',makeArchive(manifest(),png()),headers);
  assert.equal((await response.json()).id,first.id);assert.equal(env.files.size,2);
  assert.equal((await request(env,'/api/admin/import','POST',makeArchive(manifest(),null),{...headers,Authorization:'Bearer invalid'})).status,401);
  assert.equal((await request(env,'/api/admin/import','POST',makeArchive(manifest(),null),{...headers,'X-Source-Url':source+'?target=other'})).status,400);
});
test('public page emits valid browser JavaScript without embedding credentials', async () => {
  const pages = [await worker.fetch(new Request(ORIGIN), {})];
  for (const response of pages) {
    const html = await response.text(); assert.ok(!html.includes(ADMIN));
    const script = html.match(/<script>([\s\S]*?)<\/script>/)[1]; assert.doesNotThrow(() => new Script(script));
  }
});
