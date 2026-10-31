# WinXclipse Community API

Serviço hospedado em Cloudflare Workers pelo plugin Sites, com banco D1 e arquivos
R2. O computador do mantenedor não participa da execução do serviço publicado.
O painel de moderação é outro Site, restrito aos mantenedores autorizados, em
`../moderation`. URLs e operação estão em `../../docs/community-service.md`.

O manifesto `.openai/hosting.json` guarda somente a identidade do Site e os nomes
dos bindings. `ADMIN_TOKEN` e `RATE_SALT` são secrets configurados no ambiente
de hospedagem, nunca no código, APK, manifesto ou JavaScript enviado ao navegador.
`MAX_STORAGE_BYTES` é opcional; padrão de 512 MiB para o catálogo ativo.

## API v1

- `GET /api/v1/health`: verifica acesso à tabela do catálogo.
- `POST /api/v1/clients`: credencial de envio por instalação. O banco guarda hash.
- `GET /api/v1/catalog?cursor=0`: itens paginados, revisões e tombstones de remoção.
- `POST /api/v1/configs`: ZIP binário, `Authorization: Bearer <credencial do cliente>`.
- `GET /api/v1/configs/{id}/archive?r={revision}`: ZIP completo e SHA-256 no catálogo.
- `GET /api/v1/configs/{id}/cover?r={revision}`: capa PNG.

Envios públicos começam sem verificação. Repetir o mesmo ZIP com a mesma credencial
retorna o mesmo ID; o autor não controla o selo. Campos administrativos nunca são
extraídos do manifesto. Os metadados de autoria são declarações do usuário.

Administração requer credencial exclusiva do servidor de moderação:

- `GET /api/admin/configs?cursor=0`.
- `POST /api/admin/import`, JSON `{ "url": "URL do ZIP da release community-configs" }`.
- `PATCH /api/admin/configs/{id}`, JSON com `gameName`, `verified` e/ou `removeCover`.
- `PUT /api/admin/configs/{id}/cover`, PNG binário.
- `DELETE /api/admin/configs/{id}`.

Edições/remoções exigem `If-Match: "<revision>"`. Um conflito retorna 409 e preserva
a edição mais recente. Alterar nome/capa recompõe o ZIP e seu hash sem mudar as
dependências da configuração. Remover revoga os downloads e deixa um tombstone
para que o app também retire a ficha, inclusive quando ela veio do GitHub.

Para a migração de formatos de capa antigos, o proprietário pode enviar um ZIP
canônico em `POST /api/admin/import`, `Content-Type: application/zip` e a URL oficial
em `X-Source-Url`, mantendo a mesma autorização. `scripts/normalize-legacy.ps1`
prepara os ZIPs no Windows a partir de uma lista JSON de URLs oficiais e um diretório
de saída dedicado. Copia os bytes de `config.txt` sem alteração e normaliza somente
a imagem; usa codecs do Windows, sem executar conteúdo dos arquivos. É uma tarefa
de migração pontual, sem depender desse script/PC durante o funcionamento do serviço.

## Validação de arquivos e propriedade

ZIP de até 20 MiB, somente `config.txt` ou `manifest.json` e `cover.png` opcional.
Aceita STORE e DEFLATE, inclusive data descriptor usado por ZipOutputStream.
Verifica diretórios central/local, limites, sobreposição, tamanho expandido e CRC.
Não extrai caminhos no servidor nem executa arquivos recebidos.

Manifesto schema 1 de até 5 MiB, capa até 8 MiB e 1280 por eixo. A capa precisa ser
PNG 8 bits, sem interlace, com CRCs válidos e descompressão limitada ao tamanho
real declarado pelos pixels; o app prepara imagens nesse formato. Envios/registro
têm limites por credencial e por hash do IP. O catálogo tem limite de capacidade.

Arquivos de uma revisão são gravados antes de publicar sua referência no banco.
Falha antes do commit limpa os objetos novos; revisão conflitante não substitui
a atual. As versões anteriores são removidas após o novo commit. O banco e o
bucket precisam de backup/configuração de retenção conforme a hospedagem escolhida.

## Desenvolvimento e publicação

Node 24+, sem dependências npm:

```sh
node --test tests/community.test.mjs
node scripts/build.mjs
node scripts/validate-artifact.mjs
```

O build inclui `.openai/drizzle` para aplicação das migrações pelo Sites. Migrações
já aplicadas são imutáveis; adicionar uma nova para mudanças futuras. Testes usam
SQLite real em memória e um bucket simulado; não alteram dados de produção.

Publicar pelo workflow da skill Sites. O checkout hospedado tem seu próprio
repositório de origem; esta pasta mantém a cópia revisável junto do app. Preservar
os IDs já gravados. O Site da API deve ser público para o app; o painel de moderação
deve continuar restrito aos mantenedores autorizados. Não usar o token de acesso
privado no APK.

Referências: [storage Sites](https://developers.cloudflare.com/workers/runtime-apis/bindings/),
[Compression Streams do Workers](https://developers.cloudflare.com/workers/runtime-apis/web-standards/#compression-streams).
