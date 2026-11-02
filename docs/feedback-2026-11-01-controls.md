# Revisão de interface, presets e inicialização — 01/11/2026

Os botões com superfície usam cantos de 16 dp e a paleta roxa nos temas claro e
escuro, nos diálogos nativos/AppCompat e nas páginas embutidas da lateral.
As ações compostas apenas por ícones continuam sem fundo. Engrenagens e downloads
mantêm o contorno solicitado, com recuo de 3 dp, sem reduzir a área clicável nem
alterar o padding dos ícones. O botão flutuante de confirmação conserva seu fundo
circular e tint próprios. Não se acrescenta uma segunda moldura aos cards da lateral.

Os campos de exportação têm rótulos separados, altura mínima de 52 dp e margens
entre rótulo, campo e próxima opção. As ações de capa usam o estilo de botão do app.
Switches e ajudas compartilham colunas; o preview do ícone alternativo não é
redimensionado como se fosse ajuda. O seletor de presets usa a largura inteira,
com as ações em uma linha própria. Cada jogador tem switch e nome do dispositivo
em linhas separadas, e as ações abaixo podem quebrar linha em telas estreitas.

O backdrop cobre toda a lateral por center-crop, sobre uma base opaca e dentro
do recorte arredondado. Não usa a posição global do painel em movimento, que
abria faixas transparentes quando a superfície do jogo não cobria a lateral.
Capturas atrasadas continuam impedidas de substituir um fundo já visível.

## Presets e variáveis

A remoção de um preset registra a exclusão dos padrões. Uma biblioteca vazia não
é mais tratada como primeira instalação. Apagar todos pede confirmação e inclui
presets personalizados. Restaurar padrões recupera os ausentes, sem sobrescrever
layouts personalizados ou padrões já editados. Colisões de IDs recebem um novo ID.
Nenhum preset real do usuário foi apagado nos testes: os testes de remoção usam
pastas temporárias isoladas.

Uma migração anterior restaurava todas as variáveis padrão em containers antigos.
A lista salva passa a ser autoritativa, inclusive vazia. Remover DXVK_CONFIG_FILE
persiste nos padrões e nos containers. Isso não desliga o switch independente de
RAM Fix nem altera uma configuração DXVK importada.

## Catálogo FEX

Foram baixados e descompactados os 15 WCPs publicados em runtime-fexcore-v0.8.
Os manifests reais estão em `app/src/test/resources/fex-manifests/`. Todos os
arquivos declarados pelos manifests existem e têm conteúdo. A comparação de
identidade continua estrita; o catálogo foi corrigido em vez de ignorar versões.

| Arquivo | Versão no manifest | Código |
| --- | --- | ---: |
| 2412.wcp | 241214 | 0 |
| 2507.wcp | 250708 | 0 |
| 2508.1.wcp | 2508 | 1 |
| 2609.wcp | FEXCore-2609 | 2064 |

2607/2608 usam os nomes publicados atuais. O link online de 2610 retornava 404 e
foi retirado; o runtime 2610 incluído no APK continua disponível. O cache do
catálogo recebeu uma nova versão para descartar identidades remotas antigas.

## Wine/Proton e DisplayX

A preparação bloqueia apenas nela o winemenubuilder.exe, acionado pelo RunServices
do [Wine 9](https://github.com/wine-mirror/wine/blob/wine-9.0/loader/wine.inf.in),
sem alterar o ambiente final de jogos/instaladores. A validação de registros lê
linha a linha; apenas um registro sem marcador de arquitetura precisa ser
carregado inteiro para reparo. Arquivos válidos continuam byte a byte intactos.

O recibo dos testes Vulkan usa runtime, build do Android, ICD selecionado e API/
camadas. Alterar formato de apresentação, VSync ou limite de RAM não repete esses
testes. Trocar o driver/API ou atualizar o Android invalida o recibo. Os tempos
individuais de preparação do prefixo são registrados em BionicStartup. Não foi
medida uma redução de tempo do boot completo da Proton 9 neste lote.

A alocação DisplayX segue a combinação CPU_READ_RARELY/CPU_WRITE_RARELY usada por
Mesa/Quasar no pedido de gralloc linear, sem copiar pixels pela CPU. Formato,
dimensões e stride retornados pelo alocador são validados.

No SM-S926B, Android 37, foram aprovados quatro testes isolados sob o UID do app:

| Produtor Vulkan | Imagens | Resultado |
| --- | --- | --- |
| System — Samsung Xclipse 940 | UNORM | vermelho, verde e azul corretos |
| System — Samsung Xclipse 940 | sRGB mutável | vermelho, verde e azul corretos |
| JimVulkan RADV v1.8.0 — MAGELLAN CARL | UNORM | vermelho, verde e azul corretos |
| JimVulkan RADV v1.8.0 — MAGELLAN CARL | sRGB mutável | vermelho, verde e azul corretos |

A biblioteca RADV selecionada foi confirmada em /proc/self/maps, sem aceitar
fallback silencioso para System. O transporte usa a camada, AHardwareBuffers,
fences, socket e compositor reais. Só a criação da superfície X11 é substituída.
O primeiro quadro usa transfer; os demais usam render pass com attachment e layout
PRESENT. O teste não é um jogo DXVK nem uma execução do RE9. O usuário pediu que
a revisão seguinte do DisplayX prossiga por código, sem abrir o jogo; a imagem
do RE9 permanece sem validação.

## Verificações

- 555 testes JVM: 553 aprovados, dois skips de symlink no Windows, sem falhas.
- 25 cenários nativos de protocolo DisplayX aprovados.
- 12 quadros reais de apresentação no S24+ aprovados nos quatro casos acima.
- 15 arquivos FEX descompactados e manifests/payloads conferidos.
- PT/ES: 1.400 strings e 11 arrays de exibição consistentes.
- APK compilado e assinado com a chave de testes locais; nenhuma release pública criada.
