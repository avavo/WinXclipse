# Compositor Vulkan e seleção de vídeo

O seletor **Vídeo** do container/atalho escolhe o compositor Android: **Vulkan**
(padrão) ou **OpenGL**. A engrenagem abre as configurações de vídeo e a seleção
anterior, identificada como **Renderizador do Wine**. Driver Vulkan e wrapper
continuam nos campos próprios. Alterar o compositor não troca DXVK/VKD3D,
WineD3D, FEX/Box ou o driver escolhido pelo usuário.

O compositor usa a extra `displayRenderer` (`vulkan`/`gl`); a extra `renderer`
e o registro `Software\Wine\Direct3D\renderer` conservam o significado anterior.
Configs existentes preservam o renderer do Wine, e não usam esse valor como se
fosse uma escolha de compositor. Atalhos herdam o compositor do container quando
não possuem override. A alteração vale no próximo lançamento da sessão.

## Implementação

- `DisplayRenderer` e `SceneRenderer` compartilham controles, cena, seleção de
  janelas, mapeamento de entrada, HUDs, medições e relógio do FG. `GLRenderer`
  conserva o caminho GLES. `VulkanRenderer` não cria um contexto EGL para compor.
- `winx_vulkan` cria dispositivo Vulkan 1.1 com fila gráfica/compute e swapchain
  Android FIFO. A apresentação respeita o pedido de refresh existente, sem
  prometer que o Android atenderá à taxa solicitada.
- Composição direta sem efeitos não aloca imagens temporais nem buffers de
  pós-processamento. EASU/RCAS e os demais efeitos usam alvos próprios somente
  quando necessários. Desligar FG libera seu histórico e os vetores de movimento.
- EASU/RCAS, cor, CRT, FXAA, HDR, NTSC e Toon são shaders Vulkan. O exportador
  conserva os algoritmos existentes, explicita descritores/push constants e
  converte coordenadas GLES para imagens Vulkan com linhas de cima para baixo.
  Não é uma cópia da saída GLES apresentada por Vulkan.
- FG usa compute Vulkan e duas imagens reais, com vetores em ping-pong. Fast,
  Balanced e Quality mantêm as escalas 1/8, 1/4 e 1/2 do caminho de referência;
  a regra é compartilhada com GLES. Cursor é desenhado depois do FG, fora do
  histórico. Mudanças de cena, perfil, tamanho, pausa e efeitos invalidam amostras.
- A fence da submissão anterior é esperada antes de alterar staging, descritores
  ou imagens. Semáforos de apresentação pertencem às imagens da swapchain.
  Recursos saem do cache após a fence; `DeviceWaitIdle` fica restrito a
  recriação/destruição. Quadros recusados por resize não contam como apresentados.
- Pré-rotação é aplicada apenas na apresentação, incluindo cursor e scissor;
  os efeitos e o histórico usam coordenadas lógicas. Reverse landscape também
  pode ser detectado consultando a transformação a cada 120 pedidos de quadro.
- Pixels de janela já pertencem ao X server depois de `Present.copyArea`.
  Staging faz a cópia com stride validado. Não se importa para Vulkan um AHB
  que o código de Wine mantém bloqueado permanentemente para CPU: isso exige
  um contrato de ownership/sincronização que esse protocolo ainda não fornece.
- VirGL pode criar seu contexto guest sem compartilhar EGL com o compositor.
  Nesse caso lê o frontbuffer no contexto do próprio VirGL e entrega pixels ao
  X server; a composição/efeitos continuam Vulkan. Esse caminho tem custo de
  readback e precisa ser comparado separadamente. A espera por compartilhamento
  EGL usa latch com prazo, eliminando o risco de notificação anterior ao `wait`.
- OpenXR mantém seu backend GLES existente, pois usa swapchains OpenGL próprias.
  O log de lançamento e o relatório de shutdown registram o compositor efetivo.
- Falha Vulkan é registrada e exibida; não há troca silenciosa da opção salva.
  OpenGL permanece disponível no seletor para uma nova sessão.

O formulário da engrenagem foi corrigido para uma coluna vertical: os campos
de vídeo estavam aninhados acidentalmente numa linha horizontal. Confirmar
aplica a escolha do Wine; cancelar preserva o valor anterior.

## Validação e limites

Java/Kotlin/recursos e testes locais cobrem escolhas separadas, persistência,
cancelamento/confirmação, layout estreito, parâmetros EASU/RCAS, filtros,
histórico NTSC e escalas de movimento. O módulo CMake compilou seus 15 estágios
GLSL para SPIR-V e vinculou `libwinx_vulkan.so` para ARM64/API 26. O exportador
possui testes de atualização, interpolação Java, coordenadas e limites de layout.

Não foi gerado APK/AAB nem executado jogo em Android. ADB não encontrou aparelho
conectado nesta etapa. Imagem, rotação, PixelCopy/blur, retomada, uso de memória,
driver, FSR/FG e desempenho sustentado continuam exigindo teste no aparelho.
Vulkan não implica ganho automático de FPS.

Referências: [sincronização Vulkan](https://docs.vulkan.org/guide/latest/synchronization_examples.html),
[pré-rotação Android](https://developer.android.com/games/optimize/vulkan-prerotation)
e [ownership de Android Hardware Buffers](https://docs.vulkan.org/spec/latest/chapters/memory.html#memory-external-android-hardware-buffer).
