const MAX_ZIP = 20 * 1024 * 1024, MAX_CONFIG = 5 * 1024 * 1024, MAX_COVER = 8 * 1024 * 1024;
const encoder = new TextEncoder(), decoder = new TextDecoder('utf-8', { fatal: true });
const GITHUB_PREFIX = 'https://github.com/avavo/WinXclipse/releases/download/community-configs/';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
class ApiError extends Error { constructor(status, code) { super(code); this.status = status; } }
const fail = (status, code) => { throw new ApiError(status, code); };
const json = (value, status = 200, headers = {}) => new Response(JSON.stringify(value), {
  status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers }
});
const hex = bytes => [...new Uint8Array(bytes)].map(x => x.toString(16).padStart(2, '0')).join('');
const sha256 = async bytes => hex(await crypto.subtle.digest('SHA-256', typeof bytes === 'string' ? encoder.encode(bytes) : bytes));
const token = () => btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32)))).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
const time = () => Date.now();
const plain = value => value !== null && typeof value === 'object' && !Array.isArray(value);
function text(value, maximum, required = false) {
  if (value == null && !required) return '';
  if (typeof value !== 'string' || value.trim().length > maximum || required && !value.trim()) fail(400, 'invalid_metadata');
  return value.trim();
}
async function bounded(stream, maximum) {
  if (!stream) return new Uint8Array();
  const reader = stream.getReader(), chunks = []; let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read(); if (done) break;
      size += value.byteLength;
      if (size > maximum) { await reader.cancel(); fail(413, 'file_too_large'); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const result = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; }
  return result;
}
async function body(request, maximum) {
  const length = request.headers.get('content-length');
  if (length && (!/^\d+$/.test(length) || Number(length) > maximum)) fail(413, 'file_too_large');
  return bounded(request.body, maximum);
}
async function readJson(request, maximum = 16384) {
  try { return JSON.parse(decoder.decode(await body(request, maximum))); }
  catch (error) { if (error instanceof ApiError) throw error; fail(400, 'invalid_json'); }
}
function sameOrigin(request) {
  const origin = request.headers.get('origin');
  if (origin && origin !== new URL(request.url).origin) fail(403, 'origin_not_allowed');
}
function admin(request, env) {
  const supplied = request.headers.get('authorization') || '', expected = 'Bearer ' + (env.ADMIN_TOKEN || '');
  if (!env.ADMIN_TOKEN || supplied.length !== expected.length) fail(401, 'admin_required');
  let mismatch = 0; for (let i = 0; i < expected.length; i++) mismatch |= supplied.charCodeAt(i) ^ expected.charCodeAt(i);
  if (mismatch) fail(401, 'admin_required');
}
async function client(request, env) {
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.get('authorization') || '');
  if (!match) fail(401, 'client_required');
  const row = await env.DB.prepare('SELECT id FROM clients WHERE token_hash = ?').bind(await sha256(match[1])).first();
  if (!row) fail(401, 'client_required'); return row.id;
}
async function rate(env, key, maximum, period) {
  const bucket = Math.floor(time() / period), expires = (bucket + 1) * period;
  const result = await env.DB.prepare('INSERT INTO rate_limits(key,hits,expires) VALUES(?,1,?) ON CONFLICT(key) DO UPDATE SET hits=hits+1 WHERE hits < ?')
    .bind(key + ':' + bucket, expires, maximum).run();
  if (!result.meta.changes) fail(429, 'rate_limited');
}
async function ipKey(request, env) {
  if (!env.RATE_SALT) fail(503, 'service_not_configured');
  return sha256(env.RATE_SALT + ':' + (request.headers.get('cf-connecting-ip') || 'unknown'));
}
const crcTable = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; table[n] = c >>> 0; }
  return table;
})();
export function crc32(bytes) {
  let crc = 0xffffffff; for (const byte of bytes) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}
async function parseArchive(bytes) {
  if (!(bytes instanceof Uint8Array) || bytes.length > MAX_ZIP || bytes.length < 22) fail(400, 'invalid_zip');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let end = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--)
    if (view.getUint32(i, true) === 0x06054b50 && i + 22 + view.getUint16(i + 20, true) === bytes.length) { end = i; break; }
  if (end < 0 || view.getUint16(end + 4, true) || view.getUint16(end + 6, true)) fail(400, 'invalid_zip');
  const count = view.getUint16(end + 10, true), central = view.getUint32(end + 16, true), centralSize = view.getUint32(end + 12, true);
  if (!count || count > 4 || count !== view.getUint16(end + 8, true) || central + centralSize !== end) fail(400, 'invalid_zip');
  const files = new Map(), ranges = []; let offset = central;
  for (let i = 0; i < count; i++) {
    if (offset + 46 > end || view.getUint32(offset, true) !== 0x02014b50) fail(400, 'invalid_zip');
    const flags = view.getUint16(offset + 8, true), method = view.getUint16(offset + 10, true);
    const crc = view.getUint32(offset + 16, true), compressed = view.getUint32(offset + 20, true), size = view.getUint32(offset + 24, true);
    const nameLength = view.getUint16(offset + 28, true), extraLength = view.getUint16(offset + 30, true), comment = view.getUint16(offset + 32, true);
    const local = view.getUint32(offset + 42, true), next = offset + 46 + nameLength + extraLength + comment;
    if (next > end || !nameLength || flags & 1 || ![0, 8].includes(method) || local + 30 > central || view.getUint16(offset + 34, true)) fail(400, 'invalid_zip');
    const nameBytes = bytes.slice(offset + 46, offset + 46 + nameLength), name = decoder.decode(nameBytes).toLowerCase();
    if (!['config.txt', 'manifest.json', 'cover.png'].includes(name) || files.has(name) || (name !== 'cover.png' && files.has(name === 'config.txt' ? 'manifest.json' : 'config.txt'))) fail(400, 'unexpected_zip_entry');
    const maximum = name === 'cover.png' ? MAX_COVER : MAX_CONFIG;
    if (!size || size > maximum || compressed > MAX_ZIP || local + 30 > central || view.getUint32(local, true) !== 0x04034b50
      || view.getUint16(local + 6, true) !== flags || view.getUint16(local + 8, true) !== method) fail(400, 'invalid_zip');
    const localNameLength = view.getUint16(local + 26, true), localExtra = view.getUint16(local + 28, true);
    const start = local + 30 + localNameLength + localExtra, stop = start + compressed;
    if (stop > central || localNameLength !== nameLength || !nameBytes.every((byte, index) => byte === bytes[local + 30 + index])
      || ranges.some(([a, b]) => local < b && stop > a)) fail(400, 'invalid_zip');
    ranges.push([local, stop]);
    let data = bytes.slice(start, stop);
    if (method === 8) {
      try { data = await bounded(new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw')), maximum); }
      catch (error) { if (error instanceof ApiError) throw error; fail(400, 'invalid_zip'); }
    }
    if (data.length !== size || crc32(data) !== crc) fail(400, 'invalid_zip_checksum');
    files.set(name, data); offset = next;
  }
  if (offset !== end) fail(400, 'invalid_zip');
  let manifest;
  try { manifest = JSON.parse(decoder.decode(files.get('config.txt') || files.get('manifest.json'))); }
  catch { fail(400, 'invalid_config'); }
  if (!plain(manifest) || manifest.format !== 'winxclipse-community-config' || manifest.schemaVersion !== 1
    || !plain(manifest.container) || !plain(manifest.metadata) || manifest.contents != null && (!Array.isArray(manifest.contents) || manifest.contents.length > 64)) fail(400, 'invalid_config');
  manifest.metadata.gameName = text(manifest.metadata.gameName, 160, true);
  if (/^container[- _]*\d+$/i.test(manifest.metadata.gameName)) fail(400, 'game_name_required');
  text(manifest.metadata.author, 160); text(manifest.metadata.notes, 8192);
  const cover = files.get('cover.png') || null;
  if (cover) await validatePng(cover);
  return { manifest, cover };
}
export async function readArchive(bytes) {
  try { return await parseArchive(bytes); }
  catch (error) { if (error instanceof ApiError) throw error; fail(400, 'invalid_zip'); }
}
export async function validatePng(bytes) {
  const signature = [137, 80, 78, 71, 13, 10, 26, 10];
  if (bytes.length < 57 || bytes.length > MAX_COVER || !signature.every((b, i) => bytes[i] === b)) fail(400, 'invalid_cover');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength), compressed = [];
  let offset = 8, width = 0, height = 0, channels = 0, ended = false, sawData = false, afterData = false;
  while (offset + 12 <= bytes.length) {
    const length = view.getUint32(offset), end = offset + 12 + length;
    if (end > bytes.length) fail(400, 'invalid_cover');
    const type = String.fromCharCode(...bytes.slice(offset + 4, offset + 8));
    if (!/^[A-Za-z]{4}$/.test(type) || type[2] !== type[2].toUpperCase()) fail(400, 'invalid_cover');
    if (crc32(bytes.subarray(offset + 4, end - 4)) !== view.getUint32(end - 4)) fail(400, 'invalid_cover');
    if (offset === 8 && type !== 'IHDR') fail(400, 'invalid_cover');
    if (type === 'IHDR') {
      if (width || length !== 13) fail(400, 'invalid_cover');
      width = view.getUint32(offset + 8); height = view.getUint32(offset + 12);
      const color = bytes[offset + 17]; channels = color === 6 ? 4 : color === 2 ? 3 : color === 0 ? 1 : color === 4 ? 2 : 0;
      if (!width || !height || width > 1280 || height > 1280 || bytes[offset + 16] !== 8 || !channels
        || bytes[offset + 18] || bytes[offset + 19] || bytes[offset + 20]) fail(400, 'invalid_cover');
    } else if (type === 'IDAT') {
      if (afterData) fail(400, 'invalid_cover'); sawData = true; compressed.push(bytes.slice(offset + 8, end - 4));
    } else if (type === 'IEND') {
      if (length || !sawData || end !== bytes.length) fail(400, 'invalid_cover'); ended = true; break;
    } else {
      if (sawData) afterData = true;
      if (type[0] === type[0].toUpperCase() && type !== 'PLTE') fail(400, 'invalid_cover');
    }
    offset = end;
  }
  if (!ended) fail(400, 'invalid_cover');
  const expected = (width * channels + 1) * height;
  let pixels;
  try { pixels = await bounded(new Blob(compressed).stream().pipeThrough(new DecompressionStream('deflate')), expected); }
  catch (error) { if (error instanceof ApiError) throw error; fail(400, 'invalid_cover'); }
  if (pixels.length !== expected) fail(400, 'invalid_cover');
  for (let row = 0; row < height; row++) if (pixels[row * (width * channels + 1)] > 4) fail(400, 'invalid_cover');
}
export function makeArchive(manifest, cover) {
  const entries = [['config.txt', encoder.encode(JSON.stringify(manifest, null, 2))]];
  if (cover) entries.push(['cover.png', cover]);
  const parts = [], central = []; let offset = 0, centralSize = 0;
  for (const [name, data] of entries) {
    const filename = encoder.encode(name), local = new Uint8Array(30 + filename.length), view = new DataView(local.buffer);
    const crc = crc32(data); view.setUint32(0, 0x04034b50, true); view.setUint16(4, 20, true); view.setUint16(6, 2048, true);
    view.setUint16(12, 33, true); view.setUint32(14, crc, true); view.setUint32(18, data.length, true); view.setUint32(22, data.length, true);
    view.setUint16(26, filename.length, true); local.set(filename, 30);
    const directory = new Uint8Array(46 + filename.length), d = new DataView(directory.buffer);
    d.setUint32(0, 0x02014b50, true); d.setUint16(4, 20, true); d.setUint16(6, 20, true); d.setUint16(8, 2048, true); d.setUint16(14, 33, true);
    d.setUint32(16, crc, true); d.setUint32(20, data.length, true); d.setUint32(24, data.length, true); d.setUint16(28, filename.length, true); d.setUint32(42, offset, true);
    directory.set(filename, 46); central.push(directory); centralSize += directory.length;
    parts.push(local, data); offset += local.length + data.length;
  }
  const end = new Uint8Array(22), e = new DataView(end.buffer); e.setUint32(0, 0x06054b50, true);
  e.setUint16(8, entries.length, true); e.setUint16(10, entries.length, true); e.setUint32(12, centralSize, true); e.setUint32(16, offset, true);
  const output = new Uint8Array(offset + centralSize + end.length); let cursor = 0;
  for (const part of [...parts, ...central, end]) { output.set(part, cursor); cursor += part.length; }
  if (output.length > MAX_ZIP) fail(413, 'file_too_large'); return output;
}

const PUBLIC_FIELDS = 'id,revision,game_name,author,metadata,zip_key,cover_key,sha256,zip_bytes,verified,deleted,source_url,created_at,updated_at';
function publicMetadata(metadata) {
  const result = {};
  for (const [key, maximum] of Object.entries({ gameName:160, author:160, discord:160,
    device:256, model:128, soc:256, gpu:256, fps:64, ram:64, date:64, notes:8192, compatibility:64 })) {
    if (metadata[key] != null) result[key] = text(metadata[key], maximum, key === 'gameName');
  }
  return result;
}
function item(row, origin) {
  const value = { id: row.id, revision: row.revision, gameName: row.game_name, metadata: JSON.parse(row.metadata), verified: !!row.verified, deleted: !!row.deleted,
    sourceUrl: row.source_url || null, createdAt: row.created_at, updatedAt: row.updated_at,
    downloadUrl: origin + '/api/v1/configs/' + row.id + '/archive?r=' + row.revision,
    coverUrl: row.cover_key ? origin + '/api/v1/configs/' + row.id + '/cover?r=' + row.revision : null, sha256: row.sha256, size: row.zip_bytes };
  return value;
}
async function find(env, id, deleted = false) {
  if (!UUID.test(id)) fail(404, 'not_found');
  const row = await env.DB.prepare('SELECT * FROM configs WHERE id = ?').bind(id).first();
  if (!row || !deleted && row.deleted) fail(404, 'not_found'); return row;
}
async function save(env, manifest, cover, existing = null, verified = false, sourceUrl = null, submissionKey = null) {
  const id = existing?.id || crypto.randomUUID(), revision = (existing?.revision || 0) + 1;
  const namespace = 'configs/' + id + '/' + crypto.randomUUID(), zipKey = namespace + '.zip', coverKey = cover ? namespace + '.png' : null;
  const metadata = JSON.stringify(publicMetadata(manifest.metadata));
  const archive = makeArchive(manifest, cover), hash = await sha256(archive), now = time();
  await env.BUCKET.put(zipKey, archive, { httpMetadata: { contentType: 'application/zip' } });
  let committed = false;
  try {
    if (cover) await env.BUCKET.put(coverKey, cover, { httpMetadata: { contentType: 'image/png' } });
    let result;
    if (existing) {
      result = await env.DB.prepare('UPDATE configs SET revision=?,game_name=?,author=?,metadata=?,manifest=?,zip_key=?,cover_key=?,sha256=?,zip_bytes=?,verified=?,updated_at=? WHERE id=? AND revision=? AND deleted=0')
        .bind(revision, manifest.metadata.gameName, text(manifest.metadata.author, 160), metadata, JSON.stringify(manifest), zipKey, coverKey, hash, archive.length, verified ? 1 : 0, now, id, existing.revision).run();
    } else {
      result = await env.DB.prepare('INSERT INTO configs(id,revision,game_name,author,metadata,manifest,zip_key,cover_key,sha256,zip_bytes,verified,source_url,submission_key,created_at,updated_at) SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,? WHERE (SELECT COUNT(*) FROM configs WHERE deleted=0) < 5000 AND (SELECT COALESCE(SUM(zip_bytes),0) FROM configs WHERE deleted=0) + ? <= ?')
        .bind(id, revision, manifest.metadata.gameName, text(manifest.metadata.author, 160), metadata, JSON.stringify(manifest), zipKey, coverKey, hash, archive.length, verified ? 1 : 0, sourceUrl, submissionKey, now, now,
          archive.length, Number(env.MAX_STORAGE_BYTES || 536870912)).run();
    }
    if (!result.meta.changes) fail(existing ? 409 : 507, existing ? 'revision_conflict' : 'catalog_capacity');
    committed = true;
    if (existing) { await env.BUCKET.delete(existing.zip_key).catch(() => {}); if (existing.cover_key) await env.BUCKET.delete(existing.cover_key).catch(() => {}); }
    return await find(env, id);
  } finally {
    if (!committed) { await env.BUCKET.delete(zipKey).catch(() => {}); if (coverKey) await env.BUCKET.delete(coverKey).catch(() => {}); }
  }
}
function checkRevision(request, row) {
  if (request.headers.get('if-match') !== '"' + row.revision + '"') fail(409, 'revision_conflict');
}
async function previousCover(env, row) {
  if (!row.cover_key) return null;
  const object = await env.BUCKET.get(row.cover_key); if (!object) fail(503, 'file_unavailable');
  return new Uint8Array(await object.arrayBuffer());
}
async function audit(env, id, action, detail) {
  try { await env.DB.prepare('INSERT INTO moderation_log(id,config_id,action,detail,created_at) VALUES(?,?,?,?,?)').bind(crypto.randomUUID(), id, action, detail, time()).run(); }
  catch { console.error('Moderation audit write failed'); }
}
async function routes(request, env) {
  const url = new URL(request.url), path = url.pathname, method = request.method, origin = url.origin;
  if (path === '/' && method === 'GET') return new Response(publicPage(), { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
  if (!env.DB || !env.BUCKET) fail(503, 'storage_not_configured');
  if (path === '/api/v1/health' && method === 'GET') { await env.DB.prepare('SELECT COUNT(*) AS count FROM configs').first(); return json({ status: 'ok', apiVersion: 1 }); }
  if (path.startsWith('/api/admin/')) admin(request, env); else sameOrigin(request);
  if (path === '/api/v1/clients' && method === 'POST') {
    await rate(env, 'register:' + await ipKey(request, env), 20, 3600000);
    await env.DB.prepare('DELETE FROM rate_limits WHERE expires < ?').bind(time() - 86400000).run();
    await env.DB.prepare('DELETE FROM clients WHERE created_at < ?').bind(time() - 365 * 86400000).run();
    const value = token();
    const inserted = await env.DB.prepare('INSERT INTO clients(token_hash,id,created_at) SELECT ?,?,? WHERE (SELECT COUNT(*) FROM clients) < 100000').bind(await sha256(value), crypto.randomUUID(), time()).run();
    if (!inserted.meta.changes) fail(507, 'client_capacity');
    return json({ token: value }, 201);
  }
  if (path === '/api/v1/catalog' && method === 'GET') {
    const cursor = Number(url.searchParams.get('cursor') || 0); if (!Number.isSafeInteger(cursor) || cursor < 0 || cursor > 50000) fail(400, 'invalid_cursor');
    const result = await env.DB.prepare('SELECT ' + PUBLIC_FIELDS + ' FROM configs ORDER BY created_at,id LIMIT 200 OFFSET ?').bind(cursor).all();
    return json({ apiVersion: 1, items: result.results.map(row => item(row, origin)), nextCursor: result.results.length === 200 ? cursor + 200 : null });
  }
  if (path === '/api/v1/configs' && method === 'POST') {
    const identity = await client(request, env);
    await rate(env, 'requests:' + identity, 30, 3600000);
    const bytes = await body(request, MAX_ZIP), submissionKey = await sha256(identity + ':' + await sha256(bytes));
    const previous = await env.DB.prepare('SELECT * FROM configs WHERE submission_key=?').bind(submissionKey).first();
    if (previous) { if (previous.deleted) fail(409, 'submission_removed'); return json(item(previous, origin), 201); }
    await rate(env, 'upload:' + identity, 5, 86400000); await rate(env, 'upload-ip:' + await ipKey(request, env), 20, 86400000);
    const { manifest, cover } = await readArchive(bytes);
    return json(item(await save(env, manifest, cover, null, false, null, submissionKey), origin), 201);
  }
  const file = /^\/api\/v1\/configs\/([^/]+)\/(archive|cover)$/.exec(path);
  if (file && (method === 'GET' || method === 'HEAD')) {
    const row = await find(env, file[1]); if (url.searchParams.has('r') && Number(url.searchParams.get('r')) !== row.revision) fail(404, 'revision_unavailable');
    const key = file[2] === 'cover' ? row.cover_key : row.zip_key; if (!key) fail(404, 'not_found');
    const object = await env.BUCKET.get(key); if (!object) fail(503, 'file_unavailable');
    const headers = { 'Content-Type': file[2] === 'cover' ? 'image/png' : 'application/zip', 'Cache-Control': 'public,max-age=60,must-revalidate', ETag: '"' + row.revision + '-' + row.sha256 + '"' };
    if (file[2] === 'archive') headers['Content-Disposition'] = "attachment; filename*=UTF-8''" + encodeURIComponent(row.game_name.replace(/[\r\n/\\]/g, '-') + '.zip');
    return new Response(method === 'HEAD' ? null : object.body, { headers });
  }
  if (path === '/api/admin/configs' && method === 'GET') {
    const cursor = Number(url.searchParams.get('cursor') || 0); if (!Number.isSafeInteger(cursor) || cursor < 0 || cursor > 50000) fail(400, 'invalid_cursor');
    const result = await env.DB.prepare('SELECT ' + PUBLIC_FIELDS + ' FROM configs WHERE deleted=0 ORDER BY created_at,id LIMIT 200 OFFSET ?').bind(cursor).all();
    return json({ items: result.results.map(row => item(row, origin)), nextCursor: result.results.length === 200 ? cursor + 200 : null });
  }
  if (path === '/api/admin/import' && method === 'POST') {
    const prepared = request.headers.get('content-type')?.split(';')[0] === 'application/zip';
    const source = text(prepared ? request.headers.get('x-source-url') : (await readJson(request)).url, 2048, true);
    if (!source.startsWith(GITHUB_PREFIX)) fail(400, 'invalid_source');
    const parsed = new URL(source); if (parsed.origin !== 'https://github.com' || parsed.username || parsed.password || parsed.search || parsed.hash
      || !parsed.pathname.startsWith('/avavo/WinXclipse/releases/download/community-configs/')) fail(400, 'invalid_source');
    const previous = await env.DB.prepare('SELECT * FROM configs WHERE source_url=?').bind(source).first();
    if (previous) return json(item(previous, origin, true));
    let bytes;
    if (prepared) bytes = await body(request, MAX_ZIP);
    else {
      const remote = await fetch(source, { headers: { 'User-Agent': 'WinXclipse-Community/1' }, signal: AbortSignal.timeout(30000) });
      if (!remote.ok) fail(502, 'source_unavailable');
      bytes = await bounded(remote.body, MAX_ZIP);
    }
    const { manifest, cover } = await readArchive(bytes);
    return json(item(await save(env, manifest, cover, null, true, source), origin, true), 201);
  }
  const edit = /^\/api\/admin\/configs\/([^/]+)(\/cover)?$/.exec(path);
  if (edit && ['PATCH', 'PUT', 'DELETE'].includes(method)) {
    const row = await find(env, edit[1]); checkRevision(request, row);
    if (method === 'DELETE' && !edit[2]) {
      const result = await env.DB.prepare('UPDATE configs SET deleted=1,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND deleted=0').bind(time(), row.id, row.revision).run();
      if (!result.meta.changes) fail(409, 'revision_conflict');
      await env.BUCKET.delete(row.zip_key).catch(() => {}); if (row.cover_key) await env.BUCKET.delete(row.cover_key).catch(() => {});
      await audit(env, row.id, 'remove', '{}'); return json({ id: row.id, deleted: true });
    }
    const manifest = JSON.parse(row.manifest); let cover = await previousCover(env, row), verified = !!row.verified;
    if (method === 'PUT' && edit[2]) { cover = await body(request, MAX_COVER); await validatePng(cover); }
    else if (method === 'PATCH' && !edit[2]) {
      const patch = await readJson(request);
      if (!plain(patch) || Object.keys(patch).some(k => !['gameName', 'verified', 'removeCover'].includes(k))) fail(400, 'invalid_patch');
      if ('gameName' in patch) manifest.metadata.gameName = text(patch.gameName, 160, true);
      if ('verified' in patch) { if (typeof patch.verified !== 'boolean') fail(400, 'invalid_patch'); verified = patch.verified; }
      if ('removeCover' in patch && typeof patch.removeCover !== 'boolean') fail(400, 'invalid_patch');
      if (patch.removeCover) cover = null;
    } else fail(405, 'method_not_allowed');
    const updated = await save(env, manifest, cover, row, verified);
    await audit(env, row.id, 'edit', JSON.stringify({ revision: updated.revision, gameName: updated.game_name, verified }));
    return json(item(updated, origin, true));
  }
  fail(404, 'not_found');
}
function publicPage() {
  return `<!doctype html><html lang="pt-BR"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>WinXclipse · Comunidade</title>
<style>:root{color-scheme:dark;font:16px system-ui;background:#100e16;color:#f7f4ff}body{max-width:1100px;margin:auto;padding:32px 20px}h1{font-size:clamp(30px,5vw,52px);margin-bottom:8px}p{color:#c4bdd5;line-height:1.6}header{border-bottom:1px solid #393047;padding-bottom:24px}.mark{color:#bf91ff;letter-spacing:.18em;font-size:12px}button,input{font:inherit;padding:12px;border-radius:10px;border:1px solid #695081;background:#21182e;color:inherit}button{cursor:pointer;background:#7046ae}button:disabled{opacity:.5}input[type=search]{width:100%;box-sizing:border-box;margin:24px 0}#grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:18px}.card{border:1px solid #393047;border-radius:16px;overflow:hidden;background:#1b1722}.card img{width:100%;height:250px;object-fit:cover;background:#292133}.info{padding:18px}.info h2{font-size:19px;margin:10px 0}.badge{font-size:12px;color:#a8e0c0}a{color:#d8b4ff}section{margin:28px 0}.upload{padding:24px;border-radius:16px;border:1px solid #4f386c}#status{min-height:1.5em}small{color:#c4bdd5}button:focus-visible,input:focus-visible,a:focus-visible{outline:3px solid #bf91ff;outline-offset:3px}</style>
<header><div class="mark">WINXCLIPSE / COMUNIDADE</div><h1>Uma boa configuração.<br>Mais gente jogando.</h1><p>Configs compartilhadas pela comunidade, com versões e dependências preservadas.</p></header>
<section class="upload"><h2>Compartilhar uma config</h2><p>Exporte o ZIP pelo menu do container. O envio aparece sem verificação até a revisão do mantenedor.</p><input id="zip" type="file" accept=".zip,application/zip" aria-label="Configuração ZIP"> <button id="send">Enviar configuração</button><p id="status" role="status" aria-live="polite"></p></section>
<input id="search" type="search" placeholder="Buscar jogo ou aparelho" aria-label="Buscar jogo ou aparelho"><main id="grid"></main><p id="empty" role="status">Carregando configurações…</p>
<script>
let items=[];const grid=document.getElementById('grid'),empty=document.getElementById('empty');
function paint(){grid.replaceChildren();let query=document.getElementById('search').value.toLocaleLowerCase();for(const item of items.filter(x=>!x.deleted&&(x.gameName+' '+(x.metadata.device||'')+' '+(x.metadata.model||'')).toLocaleLowerCase().includes(query))){let card=document.createElement('article');card.className='card';if(item.coverUrl){let image=document.createElement('img');image.src=item.coverUrl;image.alt='Capa de '+item.gameName;image.loading='lazy';card.append(image)}let info=document.createElement('div');info.className='info';let badge=document.createElement('span');badge.className='badge';badge.textContent=item.verified?'✓ Verificada':'Envio da comunidade';let title=document.createElement('h2');title.textContent=item.gameName;let phone=document.createElement('p');phone.textContent=item.metadata.device||item.metadata.model||'Aparelho não informado';let link=document.createElement('a');link.href=item.downloadUrl;link.textContent='Baixar configuração ↗';info.append(badge,title,phone,link);card.append(info);grid.append(card)}empty.textContent=grid.children.length?'':'Nenhuma configuração encontrada.'}
async function load(){items=[];let cursor=0;do{let response=await fetch('/api/v1/catalog?cursor='+cursor);if(!response.ok)throw Error('catalog');let data=await response.json();items.push(...data.items);cursor=data.nextCursor}while(cursor!==null);paint()}
document.getElementById('search').oninput=paint;
document.getElementById('send').onclick=async()=>{let button=document.getElementById('send'),status=document.getElementById('status'),file=document.getElementById('zip').files[0];if(!file){status.textContent='Escolha o ZIP exportado pelo app.';return}if(file.size>20971520){status.textContent='O ZIP deve ter até 20 MB.';return}button.disabled=true;status.textContent='Enviando configuração…';try{let key=localStorage.getItem('winxclipse-client');if(!key){let response=await fetch('/api/v1/clients',{method:'POST'});if(!response.ok)throw Error('register');key=(await response.json()).token;localStorage.setItem('winxclipse-client',key)}let response=await fetch('/api/v1/configs',{method:'POST',headers:{Authorization:'Bearer '+key,'Content-Type':'application/zip'},body:file});let result=await response.json();if(!response.ok){if(response.status===401)localStorage.removeItem('winxclipse-client');throw Error(result.error)}status.textContent='Configuração enviada. Ela começa sem selo de verificação.';await load()}catch(error){status.textContent=error.message==='rate_limited'?'Limite de envios atingido. Tente novamente mais tarde.':'Não foi possível enviar. Confira se é um ZIP de configuração válido e tente novamente.'}finally{button.disabled=false}};
load().catch(()=>empty.textContent='Não foi possível carregar o catálogo. Tente novamente.');
</script></html>`;
}
export default {
  async fetch(request, env) {
    try {
      const response = await routes(request, env);
      response.headers.set('X-Content-Type-Options', 'nosniff'); response.headers.set('Referrer-Policy', 'no-referrer');
      response.headers.set('X-Frame-Options', 'DENY'); response.headers.set('Permissions-Policy', 'camera=(),microphone=(),geolocation=()');
      if ((response.headers.get('content-type') || '').startsWith('text/html')) response.headers.set('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
      return response;
    } catch (error) {
      if (error instanceof ApiError) return json({ error: error.message }, error.status, error.status === 429 ? { 'Retry-After': '3600' } : {});
      console.error('Community request failed', error?.name || 'Error'); return json({ error: 'server_error' }, 500);
    }
  }
};
