# Revisão de dependências na importação

A importação da 0.9.6 mantém a identidade de cada pacote até a configuração
salva e sua reabertura nos editores de container e atalho.

## Falhas corrigidas

- O atalho de correspondência com componentes embarcados comparava somente o
  nome. Uma referência a outro build com o mesmo nome do padrão podia ser
  ignorada. Referências com `versionCode` ou `contentId` agora passam pela
  resolução do pacote, inclusive quando seu nome coincide com o padrão.
- Configs antigas sem referências completas agora recuperam Wine/Proton,
  Box64, FEXCore, DXVK e VKD3D dos seletores salvos. Um seletor como
  `2610-2246` é comparado como entrada com build, sem tratá-lo como `verName`.
- Wine e Proton com nome/build iguais continuam sendo tipos diferentes. Tipo
  contraditório com a função e referências duplicadas interrompem a importação.
- Referências embarcadas também são aplicadas; uma seleção antiga conflitante
  no objeto do container não prevalece sobre uma referência explícita.
- Um wrapper recuperado com sucesso passa a ser a seleção salva. O driver
  continua usando seu ID local validado, mantendo versão/hash quando fornecidos.
- DXVK e FEX listavam apenas o nome, embora a importação salvasse nome e build.
  Abrir e confirmar o editor podia selecionar a primeira versão padrão. Os
  seletores agora incluem o build; DXVK, FEX, Box64, Wine/Proton e VKD3D preservam
  uma seleção explícita que tenha desaparecido da lista. Isso não marca o pacote
  ausente como instalado: a validação do lançamento continua exigindo o payload.

## Revisão e criação

O resumo apresenta as sete categorias, as versões disponíveis e as pendências.
Alternativas continuam exigindo escolha explícita. Antes da criação, outra
revisão mostra os valores que serão salvos e destaca cada substituição. A prévia
e a aplicação usam a mesma função de seleção; a prévia não instala presets nem
altera o manifesto.

Os downloads mantêm a fila, pausa e retomada existentes. O manifesto pendente
agora é gravado em UTF-8 por substituição do arquivo completo. Antes de criar,
uma tarefa fora da thread da interface confere novamente os perfis instalados
e as identidades de driver/wrapper. A criação tem um estado retido `APPLYING`:
recriar a Activity ou receber uma segunda confirmação não inicia outro container.

Prefixo e configuração são preparados em uma pasta temporária fora dos nomes
`xuser-*`. Só depois de salvar ambos a pasta é publicada no caminho definitivo,
sem substituir um destino existente. Assim outra instância do gerenciador não
recupera um prefixo parcial com o runtime padrão. Falhas normais removem o staging
e retornam ao fluxo de repetição; callbacks de criação usam a thread principal.

Os recibos de aplicação de DLLs/componentes do exportador são descartados,
incluindo os marcadores de integridade DXVK/DirectDraw e BCN. Preferências do
usuário, HUD e RAM Fix são preservados.

## Recuperação após encerramento do processo

O lote 29 fecha a janela entre publicar a pasta do container e apagar o recibo
da importação. `pending-config-import.json` agora guarda um envelope versionado,
com UUID da operação e manifesto UTF-8. Antes de criar a pasta temporária, o
journal grava os nomes de staging/destino, ambos filhos diretos do `home` privado.
Uma marca vazia com o UUID no nome identifica a pasta antes da extração.

Prefixo e `.container` completos são publicados juntos por renomeação no mesmo
filesystem, sem substituir destino existente. Ao retomar a importação, uma tarefa
fora da UI consulta o journal **antes** de resolver/baixar dependências:

- Se o destino tem a marca da operação e configuração válida para aquele ID,
  a importação já terminou. O recibo é encerrado, sem resolver dependências ou
  criar outro container; edições posteriores do usuário são preservadas.
- Se só existe staging incompleto da operação, ele é removido sem seguir links
  do prefixo. O manifesto é mantido para nova resolução/revisão. Pastas de outras
  operações e destinos de criações concorrentes não são adotados ou apagados.
- Uma interrupção antes do `mkdir`, ou entre ele e a criação da marca, também é
  recuperável. Sem marca, somente o diretório vazio reservado pode ser removido;
  conteúdo não identificado é retido e informado como erro.
- Destino identificado com configuração inválida interrompe a retomada e mantém
  os dados/recibo, em vez de criar uma segunda cópia. Caminhos inválidos, symlinks
  na raiz de staging/destino e versões de journal desconhecidas são recusados.
- Finalização/cancelamento removem o recibo. Se a remoção falhar, gravam um estado
  terminal persistente para não repetir a operação no próximo processo.
  Cancelamento de uma tarefa ativa espera seu worker antes de liberar o recibo
  para outra importação, evitando recriação tardia do arquivo cancelado.

Recibos antigos contendo somente o manifesto são migrados sem mudar a versão
pedida. Não é possível deduzir retroativamente qual container uma importação
antiga sem UUID já teria publicado; o journal não usa semelhança de nome para isso.

## Validação e limites

- Testes JVM de aplicação e persistência JSON com build/ABI, alternativas,
  referências legadas, tipos, marcadores do prefixo e seleção após reabertura.
- Testes do estado retido com confirmação repetida, destruição da tela, falha
  de criação, retomada após reiniciar o processo e manifesto UTF-8.
- Vinte casos adicionais do journal/estado retido cobrem checkpoints de interrupção,
  publicação, preservação de dados, migração, colisão, recibo terminal e cancelamento.
- Controle negativo: desativar temporariamente o reconhecimento do destino já
  publicado faz o teste de reinício oferecer a importação novamente; a versão
  corrigida reconhece a conclusão sem chamar o resolvedor. O guard foi restaurado
  antes da suíte completa.
- Os testes JSON usam `org.json:json:20240303` apenas em `testImplementation`.
  O app continua usando o JSON do Android; APIs novas dessa biblioteca não são
  usadas no código do app.
- Compilação Java/Kotlin/recursos e suíte JUnit, sem empacotar APK/AAB.

A proteção cobre concorrência no processo, recriação da Activity e retomada após
encerramento abrupto com o checkpoint persistido. Os testes simulam interrupções
nos mesmos caminhos de publicação/recuperação; não equivalem a encerrar o app em
um aparelho real nem garantem recuperação após perda de energia/corrupção do
filesystem. Visual, lifecycle Android completo, instalação dos pacotes e abertura
real de jogos continuam na validação da Parte 12.
