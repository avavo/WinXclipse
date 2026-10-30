# Preparação do prefixo — 0.9.6

## Problemas encontrados no código

O template embarcado `proton-9.0-arm64ec_container_pattern.tzst` contém
`.wine/dosdevices/z:` apontando para `/`. O extrator genérico recusa corretamente
links que saem do destino, mas o criador de containers tratava essa recusa como
falha do template. A segunda tentativa usava o mesmo template; a última copiava
todo `home/xuser`, que pode estar associado ao container ativado anteriormente.
Isso permitia herdar arquivos, registros e o marcador `disable` de outro prefixo.

O reparo anterior também substituía registros não vazios com cabeçalho inválido
por registros mínimos e trocava `#arch=win64` por `win32`, sem converter os módulos
ou dados existentes. Essa alteração podia perder configurações e esconder uma
incompatibilidade de arquitetura.

O launcher iniciava diretamente a cadeia do jogo. Não havia uma etapa própria
que concluísse a atualização do prefixo com o runtime selecionado antes de abrir
o executável. O caminho observado no WinlatorMali local também prepara o prefixo
antes da sessão; ele foi usado como referência de investigação, sem copiar seu
encerramento global de processos ou seu comportamento de continuar após falha.

## Fluxo implementado

1. Extrair o template específico ou prefix pack em staging. Ignorar seus mapeamentos
   de drives e reconstruí-los pelas opções do container. O extrator genérico mantém
   suas restrições. Sem um pack, criar um prefixo mínimo com a arquitetura selecionada
   e sem copiar outro container ou um template de outra versão do Proton.
2. Validar todos os registros antes de alterar qualquer um. Criar registros ausentes
   ou vazios; preservar e recusar registros não vazios inválidos ou de outra arquitetura.
3. Executar o `wineboot -u` do runtime selecionado, com FEX/Box64 e o ambiente da
   sessão. Não solicitar instaladores online de Mono/Gecko durante essa preparação.
4. Encerrar e aguardar o wineserver daquele prefixo com `-k` e `-w` antes de editar
   novamente seus registros. Não usar uma varredura global para essa migração.
5. Reaplicar os componentes configurados pelo app depois da atualização do Wine.
   Só então gravar o recibo e executar a cadeia normal do jogo. A detecção de sessão
   vazia é mantida; seu cronômetro começa após a preparação.
6. Não repetir a preparação quando o recibo corresponde ao runtime e os módulos
   centrais continuam presentes e não vazios. A identidade inclui o pacote, caminho,
   tamanho, data e SHA-256 do launcher e do `wine.inf`.

Falha ou timeout de preparação interrompe o lançamento, mantém o recibo inválido
para permitir nova tentativa e mostra o erro. A saída de cada fase vai ao log de
processos. Sair da sessão cancela a preparação; comandos de limpeza ainda podem
encerrar o servidor do prefixo. O launcher também deixou de criar um `event0` vazio;
o produtor de eventos continua responsável pelos dispositivos de controle.
Na leitura bloqueante desses arquivos, EOF agora aguarda 4 ms antes de repetir.
Antes, `poll()` retornava imediatamente para arquivos regulares, mantendo o loop
ocupado mesmo sem eventos. A semântica está documentada em
[poll(2)](https://man7.org/linux/man-pages/man2/poll.2.html). Leituras de tamanho zero
e descritores inválidos também deixam de entrar nesse loop. A latência e os
controles ainda precisam de validação física; o transporte não foi substituído.

As opções usadas foram conferidas no código do Wine:
[wineboot](https://github.com/wine-mirror/wine/blob/master/programs/wineboot/wineboot.c)
e [wineserver](https://github.com/wine-mirror/wine/blob/master/server/main.c).
`-u` força a atualização mesmo com um timestamp `disable`; `-h` somente pede ajuda.

## Validação e limites

Testes de arquivos e de orquestração cobrem preservação de registros/saves,
arquitetura incompatível, instalação incompleta, troca de runtime, recibos,
comandos com espaços, timeout, interrupção, espera do servidor e falha ao reaplicar
componentes. Compilação Java, 145 testes aprovados (um cenário de symlink ignorado
por limitação do worker Windows), validação dos recursos PT/ES e compilação do
`fakeinput.cpp` para ARM64/API 26 passaram sem gerar APK. O arquivo embarcado foi
inspecionado até o fim: 418 entradas, dois mapeamentos de drive e três registros.

Ainda é necessário testar em aparelho: instalação limpa, atualização de APK,
primeiro shortcut de um container novo, troca entre runtimes compatíveis, Wine
x86/x86_64/ARM64EC e cancelamento durante o boot. Os defeitos acima são verificáveis
no código; ainda não foi comprovado que explicam todos os jogos em 0 MB ou todos
os encerramentos relatados. VulkanRT automático e a revisão completa de drivers,
FSR e processos continuam nos respectivos itens do plano.
