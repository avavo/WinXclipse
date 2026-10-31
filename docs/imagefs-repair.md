# Reparo da instalação básica — 0.9.6

## Defeitos encontrados

- `installIfNeeded` tratava a falta do runtime embarcado como motivo para limpar
  e extrair novamente o ImageFS completo, mesmo com recibo e base atuais.
- A limpeza preservava `home`, mas apagava `opt`, incluindo runtimes antigos em
  `opt/installed-wine` e `opt/<identificador>`.
- O Proton era extraído diretamente sobre a instalação anterior. Uma falha podia
  deixar launcher, servidor e bibliotecas de extrações diferentes.
- O catálogo aceitava somente a existência de `bin/wine` como prova de instalação
  do Proton embarcado. Uma instalação interrompida podia ser listada como válida
  e contar como sua própria alternativa na verificação do boot.
- Cada chamada criava um executor. Não havia serialização global entre duas
  preparações após recriação da Activity, nem entre duas instalações de WCPs
  embarcados que usavam os mesmos arquivos temporários.

## Comportamento

`ImageFsMaintenance` confere o estado dentro de uma seção serializada:

1. Base ausente/desatualizada, recibo inválido ou reinstalação explícita: invalidar
   recibo, atualizar a base preservando `home` e `opt`, instalar o Proton em staging,
   preparar dependências e atualizar metadados. Publicar o recibo por último.
2. Base atual e Proton incompleto, sem outra instalação válida: reparar somente o
   runtime. Conservar a base, recibo, seleção de emuladores e metadados dos containers.
3. Instalação pronta ou remoção intencional do Proton embarcado com substituto:
   preparar/verificar o payload das dependências, sem extrair novamente a base.

A validação do Proton ARM64EC embarcado exige arquivos não vazios e contidos no
pacote: `wine`, `wine-preloader`, `wineserver`, `wine.inf`, `ntdll.so` AArch64 e
`ntdll.dll`/`kernel32.dll` AArch64 e i386. Diretórios com esses nomes não servem.
O catálogo, a resolução do runtime e o reparo compartilham essa verificação. Ela é
específica do pacote ARM64EC incluído neste APK; não impõe seu layout aos WCPs
externos. Presença desses arquivos não equivale a executar/validar toda a ABI.

A extração ocorre em pasta exclusiva, no mesmo filesystem. Somente depois da
validação `DirectoryInstall` publica o novo diretório, com rollback de falha de
rename. O anterior permanece disponível durante a extração. Staging abandonado
por interrupção é descartado no próximo boot apenas pelo prefixo reservado e UUID.

ImageFS e WCPs usam seus respectivos executores compartilhados, e a segunda
preparação volta a verificar o estado após a primeira. Callbacks de Activity
encerrada são descartados. O diálogo aparece quando há extração; o boot normal
não abre esse diálogo. Progresso idêntico deixa de enfileirar uma atualização da
interface por entrada do tar. Metadado `imgVersion` malformado de um container
também deixa de abortar toda a instalação básica.

## Evidência e limites

- Controle negativo: os dois testes Android de detecção falham com a antiga
  checagem `bin/wine.isFile()`, reproduzindo a aceitação da instalação incompleta.
- Os testes de arquivos cobrem reparo sem reextrair a base, saves/runtimes legados,
  extração incompleta, falhas de dependências/metadados, recibos inválidos,
  exclusão recusada, novo boot concorrente, remoção intencional e staging abandonado.
- Suíte completa: 348 casos, 347 aprovados, nenhuma falha/erro e um ignorado pela
  permissão de symlink do Windows. São 16 casos novos neste lote. Java/Kotlin e
  recursos compilaram; PT/ES mantêm 1179 strings e 11 listas por idioma.
- Inspeção completa dos dois assets: ImageFS tem 10.894 entradas, com apenas o
  diretório `home` nessa área e `opt/winetricks` em `opt`. O Proton contém os arquivos
  centrais exigidos. Nenhum executável Windows foi iniciado no computador.

A atualização completa da base ainda ocorre no destino e depende do recibo
inválido para repetir após interrupção. A publicação do diretório do runtime tem
rollback de erros comuns de I/O, sem journal durável contra morte do processo
durante os renames. Backups de rollback podem permanecer para recuperação.
Reinstalar com staging exige espaço temporário para o novo Proton além da cópia
anterior; falta de espaço deve falhar mantendo aquela cópia. Não foi medido tempo
de instalação em Android. Boot limpo/atualização e execução real dos jogos seguem
na validação física das Partes 1, 2 e 12.
