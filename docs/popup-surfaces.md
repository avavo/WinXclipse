# Pop-ups e variáveis — 0.9.6

Revisão baseada nos três prints enviados em 31/10/2026. A implementação pertence
à interface clássica; `consoleUi` continua preservada e excluída da 0.9.6.

## Alinhamento e tamanho

- Botões de download usam uma seta simples, desenho de 24 dp e área de toque de
  48 dp, centralizados ao lado dos seletores. O binding do catálogo usa o mesmo
  ícone; pausar e retomar mantêm seus ícones e callbacks próprios.
- O catálogo deixa de ocupar 90% da tela. Listas curtas ajustam a altura ao
  conteúdo; listas longas têm limite de 74% da área visível/560 dp e rolagem,
  mantendo cabeçalho e ação de instalação acessíveis. A largura fica limitada a
  520 dp e às margens da janela atual.
- Progresso fica abaixo do nome do pacote. Título e metadados têm altura natural,
  sem dividir uma linha horizontal com um painel de transferência de largura fixa.
- Formulários deixam de impor colunas de 300/350/400 dp dentro de janelas maiores.
  Diálogos gerais usam o corpo disponível, com limite de largura de 520 dp;
  progresso usa até 400 dp. Ações secundárias de Display ficam sem o bloco grosso.

## Superfícies translúcidas

`PopupStyle` centraliza diálogos de conteúdo, alertas nativos/AppCompat, catálogo,
progresso, menus ancorados e caixas de ajuda. `GlassSpinner` mantém objetos e
callbacks dos adaptadores e aplica a aparência aos dois modos de seleção.

As janelas têm borda roxa discreta e fundo claro/escuro translúcido. No Android 12
ou posterior, quando o sistema permite, usam blur da janela: 60 px no fundo dos
diálogos, 12 px atrás deles e 28 px atrás das listas ancoradas. Listeners de
disponibilidade são removidos quando a janela se desconecta. Sem suporte ou com
blur desativado, o fundo fica mais opaco para preservar a leitura, sem fundo preto
chapado. A API e a necessidade de fallback seguem a
[documentação do Android](https://source.android.com/docs/core/display/window-blurs?hl=en).

A barra lateral pertence à janela OpenGL do jogo. Ao abri-la, `SidebarBackdrop`
usa PixelCopy para uma única captura de no máximo 320 px no maior lado, suavizada
em um worker. O desenho fica alinhado à Surface original e recebe a camada de cor.
Fechar a barra invalida resultados pendentes; destruir a Activity encerra o worker.
Não há captura periódica, filtro aplicado ao renderer ou leitura contínua da GPU.
O fundo da barra é uma imagem do momento da abertura; não é vídeo com blur ao vivo.

## Variáveis e presets

Linhas de variáveis deixam de acomodar um seletor de 48 dp em um pai de 42 dp.
Cada editor fica dentro da própria linha, com espaço entre as linhas. O nome usa
rolagem horizontal por gesto, sem mudar seu identificador nem o valor exportado.
Valores de texto continuam usando o editor de linha única.

Variáveis e presets FEX/Box usam o seletor roxo compacto animado do restante do app,
com tint herdado removido para evitar o desenho azul antigo. Nomes dos presets
também cabem na largura útil, e os rótulos não desenham retângulos pretos no vidro.

## Verificação e limites

Dezenove testes novos em Robolectric: alinhamento dos botões, nomes extensos,
separação entre linhas, fonte ampliada, catálogos curtos/longos, progresso, seleção
de objetos, callbacks e abertura/fechamento de janelas. Há casos em API 28 e 31.
Os testes de nomes extensos usam identificadores maiores para ultrapassar a
largura simulada pela medição de texto do modo gráfico Legacy.

Suíte completa: 452 casos, 450 aprovados, zero falhas/erros e dois ignorados por
permissão de symlink no Windows. Java/Kotlin/recursos e EN/PT/ES aprovados.
Não foi produzido APK/AAB, tag ou release.

Os testes locais verificam medidas e comportamento de Views. Aparência final,
PixelCopy/blur reais, Samsung/Exynos, alternância de economia de bateria, teclado,
rotação e efeito sobre o tempo de quadro ainda precisam de conferência no aparelho.
