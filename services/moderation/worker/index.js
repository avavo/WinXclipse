const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' } });
async function readBody(request, maximum) {
  const reader = request.body?.getReader(); if (!reader) return null;
  let size = 0; const chunks = [];
  try {
    while (true) { const { done, value } = await reader.read(); if (done) break; size += value.length;
      if (size > maximum) { await reader.cancel(); throw new Error('body_too_large'); } chunks.push(value); }
  } finally { reader.releaseLock(); }
  const output = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { output.set(chunk, offset); offset += chunk.length; } return output;
}
export async function route(request, env, upstream = fetch) {
  const url = new URL(request.url), path = url.pathname, origin = new URL(env.COMMUNITY_ORIGIN || 'https://invalid.example').origin;
  if (!origin.startsWith('https://') || !env.ADMIN_TOKEN) return json({ error: 'service_not_configured' }, 503);
  if (path === '/' && request.method === 'GET') return new Response(page(origin), { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
  // The Site access policy must allow only the owner and explicitly authorized
  // moderators. Sites dispatch validates browser/service access before this Worker.
  let target = null;
  if (path === '/api/configs' && request.method === 'GET') {
    const cursor = Number(url.searchParams.get('cursor') || 0);
    if (!Number.isSafeInteger(cursor) || cursor < 0 || cursor > 50000) return json({ error: 'invalid_cursor' }, 400);
    target = '/api/admin/configs?cursor=' + cursor;
  } else if (path === '/api/import' && request.method === 'POST') target = '/api/admin/import';
  else {
    const match = /^\/api\/configs\/([^/]+)(\/cover)?$/.exec(path);
    if (match && UUID.test(match[1]) && (match[2] ? request.method === 'PUT' : ['PATCH', 'DELETE'].includes(request.method)))
      target = '/api/admin/configs/' + match[1] + (match[2] || '');
  }
  if (!target) return json({ error: 'not_found' }, 404);
  if (request.method !== 'GET' && request.headers.get('origin') !== url.origin) return json({ error: 'origin_not_allowed' }, 403);
  const headers = { Authorization: 'Bearer ' + env.ADMIN_TOKEN };
  if (request.headers.has('if-match')) headers['If-Match'] = request.headers.get('if-match');
  if (request.headers.has('content-type')) headers['Content-Type'] = request.headers.get('content-type');
  if (target === '/api/admin/import' && request.headers.has('x-source-url')) headers['X-Source-Url'] = request.headers.get('x-source-url');
  let data;
  const preparedImport = target === '/api/admin/import' && request.headers.get('content-type')?.split(';')[0] === 'application/zip';
  try { data = request.method === 'GET' ? undefined : await readBody(request, preparedImport ? 20 * 1024 * 1024 : target.endsWith('/cover') ? 8 * 1024 * 1024 : 16384); }
  catch { return json({ error: 'file_too_large' }, 413); }
  // Workers supports follow/manual, unlike browsers' additional "error" mode.
  const response = await upstream(origin + target, { method: request.method, headers, body: data, redirect: 'manual', signal: AbortSignal.timeout(45000) });
  if (response.status >= 300 && response.status < 400) return json({ error: 'upstream_redirect_rejected' }, 502);
  // Forward only the bounded API JSON, never upstream credentials or arbitrary headers.
  const bytes = await readBody(response, 8 * 1024 * 1024);
  return new Response(bytes, { status: response.status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' } });
}
function page(origin) {
  return `<!doctype html><html lang="pt-BR"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>WinXclipse · Moderação</title>
<style>:root{color-scheme:dark;font:16px system-ui;background:#100e16;color:#f7f4ff}body{max-width:1150px;margin:auto;padding:32px 20px}.mark{font-size:12px;letter-spacing:.18em;color:#bc91ff}h1{font-size:clamp(32px,5vw,48px);margin:12px 0}p,small{color:#c4bdd5;line-height:1.6}header{border-bottom:1px solid #443350;padding-bottom:24px}a{color:#d7b5ff}button,input,select{font:inherit;padding:11px;border:1px solid #695081;border-radius:9px;color:inherit;background:#21182e}button{cursor:pointer;background:#7046ae}button:disabled{opacity:.5;cursor:wait}button.danger{background:#44202e;border-color:#8a4861}button.secondary{background:transparent}.toolbar{display:flex;gap:12px;flex-wrap:wrap;margin:24px 0}.toolbar input{flex:1;min-width:180px}#stats{color:#cfaaff}.card{display:grid;grid-template-columns:120px minmax(0,1fr);gap:20px;padding:22px;margin:18px 0;background:#1c1724;border:1px solid #443350;border-radius:16px}.cover{width:120px;height:180px;object-fit:cover;background:#302438;border-radius:9px}.name{width:100%;box-sizing:border-box;font-weight:600}.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}.badge{display:inline-block;color:#a8e0c0;margin-bottom:10px}.status{min-height:1.5em;white-space:pre-wrap}.import{border:1px solid #443350;border-radius:14px;padding:20px;margin-top:24px}.import input{width:min(620px,90%)}button:focus-visible,input:focus-visible,select:focus-visible,a:focus-visible{outline:3px solid #bf91ff;outline-offset:3px}@media(max-width:560px){.card{grid-template-columns:1fr}.cover{width:100%;height:220px}.actions>*{flex:1}}</style>
<header><div class="mark">WINXCLIPSE / PAINEL PRIVADO</div><h1>Moderação da comunidade</h1><p>Revise os envios, ajuste nome e capa e controle o selo de verificação.</p><a href="${origin}" target="_blank" rel="noopener">Abrir catálogo público ↗</a></header>
<div class="toolbar"><input id="search" type="search" placeholder="Buscar jogo, aparelho ou autor" aria-label="Buscar configuração"><select id="filter" aria-label="Filtrar verificação"><option value="all">Todas</option><option value="pending">Sem verificação</option><option value="verified">Verificadas</option></select><button id="refresh" class="secondary">Atualizar</button></div><p id="stats"></p><p id="status" role="status" class="status" aria-live="polite"></p><main id="list"></main>
<section class="import"><h2>Trazer config existente do GitHub</h2><p>As configs importadas da release oficial recebem o selo de verificação.</p><input id="source" type="url" placeholder="URL do ZIP na release community-configs" aria-label="URL do ZIP oficial"> <button id="import">Importar</button></section>
<script>
let items=[],busy=false;const drafts=new Map(),status=document.getElementById('status'),list=document.getElementById('list');
async function api(path,options={}){let response=await fetch(path,options);let data=await response.json();if(!response.ok)throw Error(data.error||'request_failed');return data}
function setStatus(message){status.textContent=message}
async function load(){setStatus('Carregando configurações…');let cursor=0,result=[];do{let data=await api('/api/configs?cursor='+cursor);result.push(...data.items);cursor=data.nextCursor}while(cursor!==null);items=result.sort((a,b)=>b.updatedAt-a.updatedAt);paint();setStatus('')}
function button(label,action,kind=''){let element=document.createElement('button');element.textContent=label;element.className=kind;element.onclick=action;element.disabled=busy;return element}
async function change(item,method,payload,suffix='',contentType='application/json'){if(busy)return;busy=true;paint();setStatus('Salvando alteração…');try{let data=await api('/api/configs/'+item.id+suffix,{method,headers:{'If-Match':'"'+item.revision+'"','Content-Type':contentType},body:payload});if(method==='DELETE')items=items.filter(x=>x.id!==item.id);else items=items.map(x=>x.id===item.id?data:x);drafts.delete(item.id);setStatus('Alteração salva. O catálogo já pode receber a atualização.')}catch(error){setStatus(error.message==='revision_conflict'?'Essa config mudou em outra aba. Atualize a lista antes de editar novamente.':'Não foi possível salvar. Tente novamente.')}finally{busy=false;paint()}}
function paint(){list.replaceChildren();document.getElementById('stats').textContent=items.length+' configs · '+items.filter(x=>!x.verified).length+' sem verificação';let search=document.getElementById('search').value.toLocaleLowerCase(),filter=document.getElementById('filter').value;
for(const item of items){if(filter==='pending'&&item.verified||filter==='verified'&&!item.verified||!(item.gameName+' '+(item.metadata.device||'')+' '+(item.metadata.author||'')).toLocaleLowerCase().includes(search))continue;
let card=document.createElement('article');card.className='card';let image=document.createElement('img');image.className='cover';image.alt='Capa de '+item.gameName;if(item.coverUrl)image.src=item.coverUrl;let body=document.createElement('div'),badge=document.createElement('span');badge.className='badge';badge.textContent=item.verified?'✓ Verificada':'Envio sem verificação';let name=document.createElement('input');name.className='name';name.maxLength=160;name.value=drafts.get(item.id)||item.gameName;name.setAttribute('aria-label','Nome do jogo');name.oninput=()=>drafts.set(item.id,name.value);name.disabled=busy;let detail=document.createElement('p');detail.textContent=(item.metadata.device||item.metadata.model||'Aparelho não informado')+' · '+(item.metadata.author||item.metadata.discord||'Autor não informado');let notes=document.createElement('p');notes.textContent=item.metadata.notes||'';let actions=document.createElement('div');actions.className='actions';
actions.append(button('Salvar nome',()=>change(item,'PATCH',JSON.stringify({gameName:name.value}))),button(item.verified?'Retirar verificação':'Dar verificação',()=>change(item,'PATCH',JSON.stringify({verified:!item.verified})),'secondary'));
let pick=document.createElement('input');pick.type='file';pick.accept='image/*';pick.hidden=true;pick.onchange=async()=>{let file=pick.files[0];if(!file)return;if(file.size>16777216){setStatus('Escolha uma imagem de até 16 MB.');return}let bitmap;try{bitmap=await createImageBitmap(file);if(bitmap.width*bitmap.height>64000000)throw Error();let scale=Math.min(1,1280/Math.max(bitmap.width,bitmap.height));let canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.round(bitmap.width*scale));canvas.height=Math.max(1,Math.round(bitmap.height*scale));canvas.getContext('2d').drawImage(bitmap,0,0,canvas.width,canvas.height);let blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));if(!blob)throw Error();await change(item,'PUT',blob,'/cover','image/png')}catch{setStatus('Não foi possível usar essa imagem. Escolha outra.')}finally{if(bitmap)bitmap.close()}};
actions.append(button('Trocar capa',()=>pick.click(),'secondary'),button('Remover capa',()=>change(item,'PATCH',JSON.stringify({removeCover:true})),'secondary'),button('Remover config',()=>{if(confirm('Remover '+item.gameName+' do catálogo público?'))change(item,'DELETE')},'danger'));body.append(badge,name,detail,notes,actions,pick);card.append(image,body);list.append(card)}if(!list.children.length){let message=document.createElement('p');message.textContent='Nenhuma configuração para este filtro.';list.append(message)}}
document.getElementById('search').oninput=paint;document.getElementById('filter').onchange=paint;document.getElementById('refresh').onclick=()=>{if(!busy)load().catch(()=>setStatus('Não foi possível atualizar a lista.'))};
document.getElementById('import').onclick=async()=>{if(busy)return;let source=document.getElementById('source').value.trim();if(!source){setStatus('Cole a URL de um ZIP da release oficial.');return}busy=true;paint();setStatus('Importando configuração do GitHub…');try{await api('/api/import',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:source})});await load();document.getElementById('source').value='';setStatus('Configuração importada e verificada.')}catch(error){setStatus('Não foi possível importar. Confira o endereço e o formato do ZIP.')}finally{busy=false;paint()}};
load().catch(()=>setStatus('Não foi possível carregar o painel. Atualize a página para tentar novamente.'));
</script></html>`;
}
export default {
  async fetch(request, env) {
    try {
      const response = await route(request, env);
      response.headers.set('X-Content-Type-Options', 'nosniff'); response.headers.set('Referrer-Policy', 'no-referrer'); response.headers.set('Cache-Control', 'no-store');
      response.headers.set('X-Frame-Options', 'DENY');
      const origin = new URL(env.COMMUNITY_ORIGIN || 'https://invalid.example').origin;
      response.headers.set('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: " + origin + "; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
      return response;
    } catch (error) {
      console.error('Community proxy failure', error.name, error.message);
      return json({ error: 'upstream_unavailable' }, 502);
    }
  }
};
