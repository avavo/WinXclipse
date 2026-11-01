# Revisão da interface clássica — 01/11/2026

Revisão dos fontes/layouts ativos, estados e transições da 0.9.6. A interface
futura continua em consoleUi, fora da configuração compilada. Esta revisão não
é uma inspeção de screenshots de uma build no aparelho.

| Fluxo | Hierarquia, estado e comportamento conferidos | Verificação local |
| --- | --- | --- |
| Containers e atalhos | FAB com três ações; título com modelo; campos/seções legíveis; opções avançadas roláveis; confirmação separada do corpo; sem Big Picture/favoritos | Layouts clássicos, CPU responsiva, callbacks e fontes ampliadas |
| Catálogo/downloads | Wine/Proton juntos, ícone simples alinhado, lista curta sem altura vazia, lista longa limitada e rolável; progresso/pausa/retry separados do nome | PopupLayoutTest, PopupStyleTest, fila e retomada HTTP |
| Variáveis/FEX/Box | Nome longo arrastável, valor e remoção em colunas próprias, switches compactos roxos; tokens internos independentes da tradução | Testes de colisão, alteração do toggle e conservação dos valores |
| Vídeo e efeitos | Escolha Vulkan/OpenGL do compositor; engrenagem mantém renderer Wine; campos em uma coluna, descrições e seleções FSR localizadas | VideoRendererSettingsTest e LocalizedVideoOptionsTest; cancelamento não grava opções |
| Sidebar, CPU/GPU e controles | Largura útil, corpos roláveis até a última opção, slider horizontal compatível com gesto vertical; feedback sem loop durante gameplay | ClassicInterfaceLayoutTest, InterfaceMotionTest, fontes/temas/idiomas |
| Comunidade e exportação | Busca/estado vazio, progresso e falhas explícitas; dependências e capa revisadas antes de criar/publicar; proteção contra duplo envio/criação | Testes de importação/journal/dependências e serviço; Android completo permanece pendente |
| Arquivos, backups e saves | Seleção de locais, caminho em linha própria, espaço usado/livre; progresso/cancelamento; resultados verdadeiros, sem sucesso após falha | Transferências locais/SAF, rollback, exportação e cópia sobre si mesmo |
| Ajuda, créditos, seletores, logs e alertas | Fundo centralizado, largura limitada; créditos deixam a largura fixa de 320 dp; menus preservam seleção e callbacks | PopupStyleTest API 28/31 e inspeção dos chamadores ativos |
| HUD e medições | Painéis independentes, opção de reduzir informação, estados indisponíveis explícitos; botão start/stop com resultado e exportação | Testes de máscaras, formato, telemetria e cálculo; aparência/sensores reais pendentes |

## Correções finais de uniformidade

Alguns chamadores ainda reaplicavam backgrounds e blur próprios depois de
`show()`. Isso anulava a política comum, inclusive a opacidade quando o sistema
desativa blur. Removidos os overrides de comunidade, capas, exportação, escolhas,
logs, atalhos, saves, avisos e Input Controls. `PopupStyle` também configura um
fundo legível antes da primeira travessia da janela, habilitando blur na conexão.
Um teste reproduz o seletor de escolha única, confere o fundo de fallback e
confirma o callback/fechamento.

Seletores usam a transição nativa de lista ou diálogo correspondente. Abertura,
fechamento e cancelamento mantêm a seleção salva somente quando confirmada.
Animações de pressionar e dos switches/checkboxes são curtas; a preferência do
Android é respeitada. Não foram acrescentados timers de animação durante o jogo.
As janelas compartilham as configurações do tema e não impõem efeitos diferentes
em cada fluxo. Novas mudanças de movimento devem passar pelos helpers comuns.

As melhorias adicionais adotadas na revisão foram feedback de erro verdadeiro
nos backups, opacidade correta no primeiro frame/fallback e créditos adaptáveis.
WinetricksFloatingView e BigPictureActivity não são pontos de entrada ativos da
0.9.6; sua presença no repositório não significa que uma tela nova esteja exposta.

Conferência visual final continua na Parte 13: retrato/paisagem, janelas estreitas,
escala de fonte, teclado aberto, tema claro/escuro, redução de animações, blur
habilitado/desabilitado, Samsung/Exynos e overlay durante o jogo. Testes JVM de
geometria e eventos não comprovam acabamento gráfico nem custo de composição.


## Retorno visual do primeiro APK — lote 39

Os prints de 01/11 demonstraram que habilitar a API de blur não garantia o efeito
observado, que backgrounds pretos internos ainda o cobriam e que seletores
wrap_content ficavam menores que os textos. A revisão usa snapshots desfocados
em resolução limitada por abertura, sem loop de readback durante o jogo. Os
controles e painéis deixam o fundo aparecer; o fallback inicial permanece legível.

A sidebar hospeda os editores, terminal e logs, e mantém sua pilha de navegação;
X/voltar cancelam o editor atual. O terminal usa o mesmo executor da tela avulsa,
encerra seu worker ao fechar e ignora saídas tardias. A captura de botões para
atribuição de controle físico é encaminhada ao editor incorporado. CPU e RAM voltam a cartões verticais e todos os processos participam
da rolagem do painel. Ações de seletor compartilham 48dp, ícone de 24dp e intervalo
de 2dp. O Wine fica fora e o compositor dentro da engrenagem, conforme o pedido
mais recente. Ajuda DisplayX deixa de ocupar o formulário. MangoHUD 1 usa 65% das
dimensões anteriores e a prévia de capa usa fitCenter.

Essas alterações são verificadas por testes de geometria, callbacks e seleção.
A aprovação visual dos novos pixels no aparelho continua distinta desses testes.
