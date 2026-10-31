# Moderação privada do WinXclipse

Site separado da API pública. Manter o acesso do Sites **restrito aos mantenedores**.
O login é gerenciado pelo Sites/ChatGPT; não há senha administrativa no app nem
uma segunda implementação de sessões/OAuth neste código. A conta proprietária e
os e-mails explicitamente autorizados pela política de acesso do Sites podem
usar a moderação. Não conceder acesso a pessoas sem essa permissão.

O navegador usa somente rotas da própria origem. O Worker encaminha operações
permitidas ao serviço público com `ADMIN_TOKEN` obtido do ambiente secreto do
servidor. Essa credencial nunca entra no HTML/JavaScript nem é devolvida nas
respostas. `COMMUNITY_ORIGIN` identifica a API pública e não é segredo.

Funções: listar/filtrar envios, verificar/desverificar, editar nome, trocar/remover
capa, remover config e importar ZIPs da release oficial. Imagens escolhidas pelo
proprietário são reduzidas a até 1280 pixels e convertidas a PNG no navegador.
Edições usam revisão otimista; conflitos pedem atualizar a lista.

```sh
node --test tests/moderation.test.mjs
node scripts/build.mjs
node scripts/validate-artifact.mjs
```

Os testes do proxy e do JavaScript estão em `tests/moderation.test.mjs`.
Publicar pelo workflow Sites preservando
o `project_id` e o acesso privado. Este serviço deve rodar na hospedagem, nunca
depender do PC do mantenedor ligado.
