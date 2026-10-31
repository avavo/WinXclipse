# Interface clássica: limites, CPUs e feedback de toque

Este lote revisa o diálogo compartilhado, Input Controls, ajustes de cor,
barra lateral e grade de afinidade de CPU da build 0.9.6. `consoleUi` permanece
preservada e fora do source set ativo.

## Bugs reproduzidos e mudanças

Os testes Android em JVM reproduziram dois problemas na implementação anterior:

- Com cinco ações visíveis e largura de 288 px em mdpi, um botão terminava em
  `x=392`, fora da janela. A linha de ações agora quebra em linhas, conserva os
  IDs/callbacks e usa botões com altura mínima de 48 dp. O texto de status e a
  toolbar opcional têm linhas próprias. O corpo usa a largura disponível.
- `CPUListView.setCheckedCPUList("0")` seguido de `setCheckedCPUList(0, 1)`
  lançava `UnsupportedOperationException` ao limpar uma lista de tamanho fixo.
  O intervalo agora cria uma seleção mutável, limitada aos CPUs existentes.

A grade adapta a quantidade de colunas à largura e à fonte, conservando as
marcações feitas na tela quando reorganizada. A célula inteira alterna seu
checkbox; o checkbox continua oferecendo estado e nome à acessibilidade.

Os ajustes de cor deixaram de exigir 520 dp: usam rolagem e uma coluna em janelas
menores, mantendo duas colunas a partir de `w600dp`. Os IDs e o comportamento dos
sliders/perfis foram mantidos. O botão de configurações de Input Controls ganhou
alvo de toque de 48 dp e descrição de acessibilidade.

A NavigationView usa a largura disponível, com teto de 380 dp, em vez de impor
380 dp a uma janela menor. No gerenciador embutido, CPU/GPU ocupam a largura
inteira e a RAM fica em uma faixa separada. A faixa calcula sua altura e as duas
linhas de texto pelas métricas da fonte, sem posições fixas que cortem as letras.

O feedback de pressão foi estendido aos cards clicáveis da interface que já usam
`InterfaceMotion`. Reaplicar o helper conserva o animator existente. Switches e
checkboxes mantêm sua animação nativa/de drawable; o FAB mantém o comportamento
Material. Desativar animações encerra o feedback controlado pelo helper e restaura
a escala de um botão pressionado. O helper não substitui listeners de ação nem
cria timers ou animações contínuas.

## Verificação

`ClassicInterfaceLayoutTest` e `InterfaceMotionTest` usam Robolectric 4.14.1,
Android API 28 e os XMLs/widgets ativos. A dependência é apenas de teste, conforme
a [integração Gradle do Robolectric](https://robolectric.org/getting-started/).

Os 17 casos cobrem:

- ações dentro da janela, incluindo fontes de 160%, EN/PT/ES e ambos os temas;
- rolagem até a última opção de Input Controls e a linha da GPU;
- gesto vertical atravessando o DrawerLayout/NavigationView reais;
- ajuste horizontal do slider de brilho dentro do novo scroller;
- grade de dez CPUs, mudança de largura, seleção por intervalo e toque na célula;
- ajustes de cor em layouts estreito e largo;
- métricas e posições desenhadas da RAM com fontes de 100%, 160% e 200%;
- pressão com animações desligadas, callbacks preservados e rebinding sem reinício.

A suíte completa passou com 325 casos, zero falhas/erros e um ignorado por
permissão de symlink no Windows. Compilação Java/Kotlin/recursos e cobertura
PT/ES (1179 strings e 11 listas por idioma) passaram. Foram produzidos somente
os intermediários de compilação/teste, sem APK instalável, AAB ou release.

Os testes medem geometria e eventos do Android em JVM; não comprovam aparência
final, antialiasing, gestos em hardware ou desempenho da GPU. Não há dispositivo
ADB conectado. A revisão visual completa do app e os cenários físicos da Parte 12
continuam pendentes.

## Mensagens e idioma — lote 32

File Manager, saves, atalhos, alerta de RAM, estados exibidos do FG e ações de
logs usam recursos EN/PT/ES. Menus de arquivos deixaram de impor rótulos em inglês;
datas/números das propriedades usam o locale do contexto, inclusive com idioma
manual diferente do sistema. IDs de ação, dados de saves/atalhos, configurações,
caminhos e estados internos de FG não são traduzidos.

Detalhes técnicos de falha permanecem nos logs; as mensagens de ação exibidas ao
usuário são localizadas. A cobertura chegou a 1315 strings e 11 listas por idioma.
Outros diálogos de controles/container/sessão continuam na auditoria da Parte 5,
e a revisão visual em aparelho segue na Parte 12.

## Diálogos e perfis de gamepad — lote 33

Perfis, vibração, USB, comparação de wrappers, bloqueio de entrada durante uma
instalação, limite de FPS e erro de arquivos do terminal usam recursos EN/PT/ES.
O corpo de configuração do gamepad usa a largura disponível, em vez de 340 dp
fixos. A revisão visual completa continua pendente.

O seletor guarda o nome real do perfil separado do rótulo traduzido da lista
vazia. Um perfil chamado `-- No Profiles --` ou com o próprio texto traduzido
continua selecionável. Sem perfis, carregar fica desabilitado.

A leitura confere todos os mapeamentos antes de aplicá-los: índice inválido,
fracionário, repetido ou ausente não deixa uma aplicação parcial. Arquivos ausentes
ou maiores que 64 KiB mostram erro e mantêm os mapeamentos. A gravação usa UTF-8
e substituição por arquivo temporário; só depois de gravar adiciona o nome à lista.
Falhas não mostram confirmação de sucesso. Nomes/caminhos que escapam da pasta
privada de perfis são recusados. O formato JSON e os nomes de botões permanecem
compatíveis com os perfis existentes.

`GamepadProfileDialogTest` exercita arquivos reais e os callbacks dos botões em
Robolectric, sem iniciar Wine. Isso verifica os fluxos de erro e preservação dos
dados; não comprova interação física com um controle nem a aparência final.

## Superfícies e variáveis — lote 34

A revisão dos três prints acrescenta superfícies translúcidas/blur compartilhadas,
seletores e menus, catálogo compacto, botões de download alinhados e correção das
linhas/nomes de variáveis. Os switches de variáveis e presets FEX/Box seguem o
desenho roxo compacto do app. [Implementação, verificações e limites](popup-surfaces.md).
