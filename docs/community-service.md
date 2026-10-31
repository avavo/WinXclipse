# Comunidade hospedada

Serviços publicados em 31/10/2026, em Workers com D1/R2 pelo Sites:

- [Catálogo e envio público](https://winxclipse-community.silky-moss-5631.chatgpt.site).
- [Painel privado de moderação](https://winxclipse-moderation.silky-moss-5631.chatgpt.site).

O PC do mantenedor pode ficar desligado. O painel usa **Continuar com o ChatGPT**;
o acesso é restrito à conta proprietária do Site e aos e-mails explicitamente
autorizados no painel de acesso do Sites. Cada pessoa autorizada nesse Site pode
moderar o catálogo. Manter essa lista restrita: o papel de visualizador do Sites
permite usar o painel, mas não editar o código ou os secrets do projeto.
Não há cadastro de senha próprio da aplicação.

## Operação

No app, exportar a config pelo menu do container, revisar nome/capa e salvar.
Escolher **Publicar na comunidade** na confirmação. O mesmo ZIP também pode ser
compartilhado por qualquer app ou enviado na página pública. Ele não contém jogo
nem saves; autor, aparelho, notas e configuração exportados ficam públicos.

O painel permite filtrar, buscar, verificar/desverificar, editar nome/capa e
remover. As 25 configs já existentes na release GitHub foram importadas com selo.
Novos envios ficam sem selo até a revisão. Autoria é declarada pelo usuário;
o selo depende exclusivamente da moderação.

Os arquivos antigos do GitHub foram preservados. Na migração, JPEGs com nomes
variados e PNGs com dados extras foram convertidos para `cover.png` canônico.
Os 25 manifestos publicados conferem integralmente com os originais normalizados,
incluindo container, presets e dependências. Identidade `sourceUrl` conecta cada
item à sua origem; edições/remoções no painel prevalecem sobre a cópia do GitHub
quando o app sincroniza. Um catálogo offline reflete a última sincronização.

## App e protocolo

O catálogo baixa páginas de metadados e mantém snapshot atômico completo para uso
offline. ZIPs são baixados apenas ao inspecionar a configuração ou instalar,
com revisão, tamanho e SHA-256 obrigatórios. Cache público fica separado do cache
curado antigo; nenhum campo `verified` do ZIP concede verificação. Mudança de capa
altera a chave do cache de arte. Downloads interrompidos não viram arquivos finais.

Credencial de envio é privada por instalação e só autoriza publicar. Não permite
editar/remover ou verificar configs. O servidor guarda apenas seu hash e aplica
limites de registro/envios. Um retry do mesmo ZIP é idempotente; uma instalação
nova tem outra identidade. Tokens inválidos são renovados uma vez.

O Android rejeita origens/caminhos de arquivos divergentes, não segue redirects
com credenciais e limita respostas. O backend valida ZIP, CRC, schema e PNG com
descompressão limitada. Operações de moderação usam revisão otimista para evitar
sobrescrever uma edição recente. Excluir revoga os arquivos e mantém tombstone;
a exclusão pela interface é permanente. Há registro das operações no banco.

## Manutenção

Fontes em `services/community` e `services/moderation`, com IDs de hospedagem
fixados nos manifestos `.openai/hosting.json`. Cada Site também tem seu repositório
de publicação próprio. Publicar sempre a fonte revisada e o artefato correspondente
pelo workflow da skill Sites; não criar Sites substitutos a cada atualização.

`ADMIN_TOKEN` e `RATE_SALT` ficam nos secrets da API; o painel recebe `ADMIN_TOKEN`
e `COMMUNITY_ORIGIN`. Não entram no Git, no APK, no HTML ou nos logs. A lista de
contas permitidas fica na política de acesso do Sites. Não tornar o painel público.
Ao autorizar alguém, essa pessoa passa a ter permissão de moderação.

Migrações D1 aplicadas são imutáveis. Para mudanças de schema, adicionar nova
migração/journal. Capacidade inicial: até 5000 configs ativas e 512 MiB de arquivos
ativos, limite ajustável por `MAX_STORAGE_BYTES`. Backups/retenção dos dados são
responsabilidade operacional da hospedagem, não do computador do mantenedor.

## Verificação

25 testes Node com SQLite real: arquivos, autenticação, rate limit, idempotência,
edição, conflito, remoção, rollback e proxy privado. 270 casos JUnit, zero falhas
e um ignorado por permissão de symlink no Windows. Java/Kotlin/recursos e PT/ES
(1169 strings, 11 arrays) passaram, sem APK/release.

Em produção: health, recusa de admin anônimo, proteção do painel privado, envio,
verificação, edição de nome/capa, SHA-256/dependências e remoção com revogação
conferidos. Os 25 ZIPs e capas publicados foram baixados e comparados. Página
pública e aviso de arquivo ausente conferidos no navegador; a sessão disponível
exigiu login no painel privado, cujo fluxo visual autenticado e os diálogos
Android ainda precisam de conferência pelo mantenedor/aparelho.
