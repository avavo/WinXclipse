# Recursos gráficos, FSR e falhas de importação

Revisão da [issue #4](https://github.com/avavo/WinXclipse/issues/4) e dos caminhos
de FSR/FG. Os achados abaixo são defeitos do código; a relação com cada crash ou
queda de FPS relatada ainda exige reprodução no aparelho.

## Contexto EGL recriado

O app preserva o contexto ao pausar quando possível, mas o Android pode recriá-lo.
Antes, somente os buffers do compositor eram refeitos. Os programas de janela,
cursor e efeitos, as localizações de uniforms, o VBO do quad e as texturas de
drawables podiam continuar usando nomes do contexto perdido.

Cada criação recebe agora uma identidade por thread de renderização. Materiais,
texturas, imports de AHardwareBuffer e buffers de efeitos invalidam os recursos
antigos. Os pixels/vertices em CPU são preservados para reconstrução, mesmo sem
um novo frame enviado pelo jogo. Nomes antigos não são apagados no contexto novo,
pois um ID numérico pode ter sido reutilizado. O FG também reinicia compute,
histórico e temporização sem reutilizar suas texturas anteriores.

Uniforms são resolvidos novamente após reconstruir o programa. Um uniform
otimizado pelo compilador é armazenado como ausente, evitando repetir a consulta
a cada frame. Atributo `position` fixado antes de linkar o programa; falhas de
compilação/link liberam shaders e programas parciais.

Trocar/desligar FSR e demais efeitos aposenta os programas na thread GL. A UI não
executa sua destruição. Reativar o mesmo efeito antes da próxima renderização
preserva seu material; quando nenhum efeito permanece, os buffers intermediários
também são liberados.

## Propriedade dos buffers nativos

`gpu_image.c` adquiria uma referência extra de AHardwareBuffer ao importar uma
EGLImage. Todos os caminhos de erro a liberavam, mas o caminho de sucesso não.
A referência temporária agora é liberada também após sucesso. A propriedade do
buffer pelo Java/Wine e a propriedade mantida pelo EGL continuam independentes.

Outra falha: quando a importação EGL falhava, `GPUImage.allocateTexture` liberava
o buffer cuja memória ainda estava exposta em `Drawable.data`/`virtualData`.
O buffer agora permanece válido até a destruição normal do drawable. O app
prepara uma textura comum e usa o envio de pixels existente, respeitando o stride,
em vez de manter ByteBuffers que apontam para memória nativa já liberada.

Referências primárias:

- [Android GLSurfaceView.Renderer: perda de contexto e reconstrução](https://developer.android.com/reference/android/opengl/GLSurfaceView.Renderer).
- [Khronos EGL_ANDROID_get_native_client_buffer: vida útil do client buffer](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_get_native_client_buffer.txt).
- [Khronos EGL_ANDROID_image_native_buffer](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_image_native_buffer.txt).
- [AMD FSR1: referência EASU/RCAS consultada na revisão](https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/master/ffx-fsr/ffx_fsr1.h).

## Validação e pendências

Suíte final: 266 casos JUnit, zero falhas e um ignorado por permissão de symlink no
Windows; Java/Kotlin/recursos aprovados. Sete testes novos cobrem reconstrução/locais de uniforms com backend GL simulado,
uniforms ausentes, destruição normal/perda de contexto, invalidação de textura,
preservação de vertices e independência entre threads. Compilação do código nativo
para ARM64/API 26. Esses testes não executam um driver Android nem demonstram
qualidade visual, ganho de FPS ou estabilidade em gameplay.

No aparelho: alternar app e recriar superfície com FSR/FG ligados/desligados;
trocar efeitos repetidamente; acompanhar memória de buffers durante sessão longa;
verificar importação/fallback, proporção, cores, nitidez e input. A auditoria
matemática completa de FSR, DX9 e do pipeline de FG permanece na Parte 4.
