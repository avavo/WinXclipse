# DisplayX: integração com as janelas Wine — 2026-11-02

A tela preta no RE9 foi relatada com System e RADV. A pedido do usuário, este
lote revisa o código sem abrir o jogo ou executar novos probes no aparelho.
Os quadros isolados aprovados no lote anterior não confirmam a execução do RE9.

## Falhas encontradas e correções

- ReparentWindow descartava os dois INT16 de coordenadas e apenas trocava o pai.
  Uma janela Vulkan movida do pai dummy para uma janela visível podia ficar fora
  da lista de composição ou conservar sua posição anterior. Agora o pedido
  preserva XID/drawable e aplica coordenadas, empilhamento e notificações.
- Uma janela mapeada passa pela sequência Unmap/Reparent/Map, respeitando o
  redirecionamento do novo pai. Janelas não mapeadas continuam não mapeadas.
  Mudanças de pai notificam também o compositor, inclusive sem resize.
- Mover a janela principal atualizava apenas seu próprio drawable. Agora a
  posição absoluta de todas as filhas é atualizada, junto com o mapa de entrada
  de tela cheia. Isso inclui as janelas onde o Wine apresenta Vulkan.
- Janelas InputOnly entravam na lista de imagens com content nulo. Agora são
  excluídas da composição, mantendo a árvore usada pela entrada.
- Reparent inválido (ciclo, raiz, pai InputOnly para janela InputOutput) falha
  antes de alterar a árvore. O pedido adquire também o lock de entrada, pois
  Unmap/Map podem alterar o foco.

Essas falhas são verificáveis no código e independem do ICD escolhido. A ligação
com a tela preta específica do RE9 é uma hipótese; este lote não declara que a
imagem do jogo foi recuperada.

## Referências e escopo

O [Wine 9 cria a superfície Vulkan usando a janela cliente X11](https://github.com/wine-mirror/wine/blob/wine-9.0/dlls/winex11.drv/vulkan.c).
Seu [código de janelas cria/reparenta essa janela cliente](https://github.com/wine-mirror/wine/blob/wine-9.0/dlls/winex11.drv/window.c).
A sequência de eventos segue o [protocolo X11, ReparentWindow/ReparentNotify](https://www.x.org/releases/X11R7.7/doc/xproto/x11protocol.html).
As referências foram lidas; nenhum código Wine foi incorporado.

O alocador AHB, seleção de driver e dados do container não são alterados por este
lote. Os ajustes anteriores de ícones permanecem: ações simples sem contorno,
engrenagens/downloads com contornos menores e ícones centralizados.

## Verificação

Os testes exercitam o código real de pedidos/eventos X11 e a coleta/posição da
cena. O carregamento da biblioteca Android de Drawable e a criação da superfície
XServerView são suprimidos no JVM; as tarefas de cena são executadas na mesma
thread do teste. Não há GPU nem app/jogo executado. Cobrem janela Vulkan em pai oculto,
reparent para pai oculto/visível, coordenadas negativas, identidade do drawable,
movimento de descendentes, InputOnly, empilhamento, erros e bytes dos eventos.

Resultado: 567 testes JVM, 565 aprovados, dois skips de symlink no Windows,
sem falhas/erros; inclui 12 casos novos de hierarquia/eventos/cena. APK compilado.
Nenhum teste do DisplayX no aparelho ou do RE9 foi executado neste lote.
