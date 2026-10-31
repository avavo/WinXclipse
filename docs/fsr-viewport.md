# FSR e mudanças de geometria

Na comparação com o [FSR1 de referência da AMD](https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/master/ffx-fsr/ffx_fsr1.h),
a matemática dos kernels EASU/RCAS foi mantida. A falha reproduzida estava nos
callbacks de amostragem do port: o clamp da textura incluía as faixas pretas da
composição, não apenas o viewport do jogo. O filtro podia misturar preto na
primeira/última linha e coluna do conteúdo, mesmo com a área do jogo uniforme.

O EASU agora limita seus 12 taps aos centros dos texels do viewport de entrada,
antes de converter V-down para coordenadas GL. O RCAS limita a vizinhança ao
viewport na resolução de saída; pixels externos preservam a imagem sem sharpening.
Tela cheia e XR usam a textura inteira. Mapeamento considera os offsets de
letterbox/pillarbox e funciona também no modo de sharpening sem upscale.

Bounds são atualizados em cada passagem, sem alocação por frame. O código mantém
a redução real do guest e um único upscale. A nitidez, qualidade e preferências
anteriores continuam iguais. Esse ajuste não transforma imagem já renderizada
em resolução maior em redução de custo do jogo.

Na revisão de frame generation também foi encontrado histórico inválido após
mudar o tamanho da superfície ou alternar tela cheia. O compositor podia escolher
um frame gerado antes de aplicar a nova geometria. Agora essas mudanças invalidam
o histórico antes da escolha; trocar passes/modo FSR também invalida as imagens
temporais. A geração espera duas novas capturas e não conta a mudança estrutural
como um Present do jogo, preservando um Present que já estava pendente.

Validação: 292 casos JUnit, zero falhas e um ignorado por symlink no Windows.
Seis novos casos cobrem centros de texel, letterbox/pillarbox, clipping, rotação
e dimensões degeneradas; dois cobrem a recuperação do histórico/contadores.
Java/Kotlin/recursos compilaram. A ferramenta em `tools/fsr-qa` executou os shaders
extraídos dos fontes em WebGL e aprovou oito verificações: compilação/link,
desenho, bordas, barras e comparação RCAS/CPU com erro máximo de 0.512/255.
O controle sem o clamp reproduziu a contaminação de borda que o shader corrigido
elimina. O teste e a reprodução ficam disponíveis no repositório.

WebGL não substitui a GPU Android. Aparência nos jogos, qualidade temporal,
motion estimation, pacing e desempenho sustentado continuam na matriz de
aparelhos da Parte 12. Nenhum APK/release foi gerado.
