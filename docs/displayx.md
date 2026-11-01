# DisplayX — apresentação AHB

Parte 12 da 0.9.6; implementação no lote 36. A revisão integrada passou para a
Parte 13. A opção **DisplayX (AHB / Vulkan)** fica na engrenagem de Vídeo, salva
por container/shortcut, inicialmente desativada. Usa o compositor Vulkan.

## Caminhos efetivos

- Um layer Vulkan ARM64 próprio cria imagens apoiadas em AHardwareBuffer. Envia
  handles e fences ao app por socket privado, identificando janela e swapchain.
  Os pixels do jogo não passam pelo `Present.copyArea` nem por upload da CPU.
- Uma única janela opaca, inteira na região visível, sem efeitos/FG, cursor
  emulado ou troca de canais pode usar `ASurfaceControl`: o mesmo AHB é entregue
  ao SurfaceFlinger. A escolha de overlay físico ou composição pelo Android
  pertence ao sistema; não se promete ausência de trabalho de GPU no Android.
- Com FSR, frame generation, cursor, menus Wine ou magnifier, o compositor
  importa o AHB como imagem Vulkan e mantém os efeitos existentes. Isso evita
  cópias de pixels pela CPU, mas continua tendo composição na GPU.
- OpenGL/OpenXR, formatos ou extensões não atendidos usam o caminho anterior.
  Falha de alocação/importação desconecta o transporte, sinaliza swapchain fora
  de data e desativa novas intercepções nesse dispositivo Vulkan. O jogo pode
  recriar seu swapchain com a WSI original. Falha geral do compositor continua
  sendo exibida ao usuário; não há mudança silenciosa da opção salva.

X11 continua responsável pela árvore de janelas e entrada. Um callback leve
notifica a chegada de frames para HUD/medições, sem transportar pixels em Java.
O log distingue `SurfaceFlinger direct AHB`, `AHB Vulkan composition` e
`X11 pixel upload`. Redesenhar o cursor ou o fundo não conta como novo frame do jogo.

## Sincronização e ciclo de vida

- Cada swapchain tem sua conexão e até quatro buffers; servidor limita conexões,
  geometria e orçamento de memória, valida o UID e usa socket com permissão 0600.
  Protocolo possui magic/versão e não depende de padding variável.
- O produtor exporta uma fence SYNC_FD depois do render e da transferência de
  ownership para FOREIGN. O receptor aguarda a fence antes de publicar o frame.
  Exportação de fence sem suporte/erro usa espera limitada antes do envio.
- Composição Vulkan adquire ownership FOREIGN, amostra a imagem e o devolve
  antes de concluir a submissão. A fence da submissão antecede a devolução do
  slot ao produtor. O último frame permanece retido para redesenhos.
- SurfaceFlinger conserva uma referência independente. O callback da transação
  consulta a fence de liberação do **buffer anterior**. Um worker limitado
  aguarda essa fence antes de devolver o slot. `OnCommit` e retorno de
  `setBuffer` não são tratados como liberação.
- Ao sair da apresentação direta, o app retira o plano e espera suas referências
  antes de adquirir o mesmo AHB para efeitos. Um quadro transitório não alimenta
  o histórico de FG. A barra lateral solicita composição antes de seu PixelCopy,
  mantendo o fundo disponível para o blur.
- FIFO conserva a ordem; mailbox/immediate descartam somente frames pendentes,
  já finalizados pelo produtor. O limitador de FPS e a preferência de VSync
  também chegam ao transporte; a espera de pacing ocorre no processo guest.
- Resize retira o swapchain antigo sem entregar handles sintéticos à WSI real.
  Threads de conexão são encerradas/recolhidas. Mensagens fragmentadas e fds
  extras/truncados são rejeitados com fechamento dos descritores recebidos.

## Compatibilidade

O layer requer instância Vulkan 1.1, importação AHB, FOREIGN ownership e exportação
de fences. Atende RGBA8/BGRA8 UNORM com colorspace sRGB e views SRGB compatíveis
quando solicitadas por mutable-format. BGRA só é usado se a alocação e a consulta
Vulkan confirmarem BGRA; memória RGBA não é reinterpretada como BGRA.

Barreiras/eventos e render passes traduzem PRESENT_SRC para GENERAL apenas para
as imagens AHB. Render passes compatíveis são selecionados por framebuffer;
swapchains X11 do mesmo processo conservam seus layouts. Apresentações mistas
remapeiam arrays de IDs/regiões/device masks.

`VK_KHR_present_wait` não é anunciado enquanto DisplayX está solicitado: uma
fence do compositor não comprova apresentação física. O jogo usa seu pacing
normal por acquire/FIFO. Se solicitar present-wait por outra rota, ou comandos
de manutenção/timing ainda não atendidos, esse dispositivo fica com WSI original.
Não se emula `vkWaitForPresentKHR` com um sinal de GPU que possa mentir ou travar.

## Origem e verificação

Ludashi e Ludashi Plus foram referências de arquitetura/protocolo, não binários
copiados. O layer e o transporte novos são fonte MIT neste repositório. Referências:

- [Ludashi 1042ba5](https://github.com/StevenMXZ/Winlator-Ludashi/tree/1042ba5)
  e [Plus 5b167f8](https://github.com/squalle0nhart/winlator_ludashi_plus/tree/5b167f8656b9e92c4a276db57424199f03f90018).
- [DAC de TripleJ, f7a5d34](https://github.com/TripleJ160/Winlator-Ludashi-Plus/tree/f7a5d3422d99c7de8ad4e32806b672de5bcfe7e0):
  implementação alternativa; seu layer/WSI LGPL não foi incorporado.
- [Contrato NDK SurfaceControl](https://developer.android.com/ndk/reference/group/native-activity),
  [AHardwareBuffer](https://developer.android.com/ndk/reference/group/a-hardware-buffer)
  e [ownership Vulkan](https://docs.vulkan.org/spec/latest/chapters/memory.html#memory-external-android-hardware-buffer).

Compilação independente ARM64/API 26 dos módulos de transporte, layer e compositor
aprovada, sem APK. O transporte é incorporado à biblioteca do compositor para
compartilhar um único runtime C++ e as mesmas referências de buffers; o layer
guest continua separado. O transporte não importa símbolos SurfaceControl de API 29 no
carregamento: resolve-os por `dlsym` após verificar o Android.

Seis testes JVM novos cobrem implantação/atualização do manifesto, preservação dos
outros layers e confirmação/cancelamento. A ferramenta
`tools/test_displayx_protocol.py` executa o código C++ real com falhas injetadas
nas syscalls: 23 cenários de bounds, fragmentação, EINTR, EOF, descritores, seleção
do plano direto e erros terminais de fences. POLLERR/HUP/NVAL encerram a espera
com desconexão do produtor, sem busy loop nem liberação antecipada do slot.
Não simula um driver Vulkan nem comprova scanout Android.

Ainda falta aparelho para confirmar imagem/cor, rotação, alternância com efeitos
e blur, retorno de background, recriação/teardown, ABI/driver e desempenho em jogo.
Não há garantia dos 80–100 FPS citados nem de alívio de 100% da CPU.
