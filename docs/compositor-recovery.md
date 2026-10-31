# Recuperação após falha do pós-processamento

O compositor tratava qualquer exceção retirando apenas o LSFG, mesmo quando o
efeito que falhou era FSR, HDR ou outro shader. Esse efeito continuava na lista:
o quadro seguinte tentava novamente e podia compilar o mesmo shader, desenhar
a cena e registrar outra stack trace a cada Present. Framebuffers incompletos
também eram revisitados sem suspender a cadeia.

A cadeia agora é suspensa para a combinação de contexto GL, configuração e
tamanho da superfície que falhou. Os quadros seguintes usam a imagem direta,
sem novas alocações/passagens opcionais. Os buffers de pós-processamento são
liberados na thread GL; os objetos dos efeitos e suas preferências permanecem.
Alterar a cadeia/FSR, recriar o contexto ou mudar o tamanho permite nova tentativa.

O fallback restaura framebuffer zero, scissor, blend, viewport, tamanho do alvo
e cursor antes de desenhar a cena. Erros posteriores do próprio caminho direto
também são registrados uma vez. Falha de outro efeito não é reportada como falha
de frame generation quando não existe LSFG na cadeia.

`ShaderMaterial` conserva uma rejeição de compilação por contexto, evitando
recompilar a cada chamada. Um programa zero não é usado como programa válido.
Destruição, novo contexto ou uma repetição explícita do compositor liberam a
falha dos materiais opcionais. Programas válidos continuam reutilizados.

Validação: sete novos casos simulam rejeição da cadeia e do builder de shader,
300 quadros subsequentes, mudança de tamanho/contexto/efeitos, fallback tardio
com erro e diagnóstico sem repetição. A suíte completa passou com 332 casos,
zero falhas/erros e um ignorado por symlink no Windows. Java/Kotlin/recursos
compilaram; não houve APK instalável, AAB ou release.

Esses testes exercitam o fluxo de recuperação com etapas de GL substituídas.
Não demonstram compilação ou recuperação em uma GPU real, nem provam que este
era o gatilho da queda de FPS relatada após alguns minutos. Essa comparação
continua dependendo dos logs e da reprodução controlada no aparelho.
