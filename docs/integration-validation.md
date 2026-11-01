# Integração da 0.9.6 — 01/11/2026

Revisão final local das Partes 1–13, até o lote 39. O código permanece na versão
**0.9.6, versionCode 27**, com a interface clássica ativa. ConsoleUi está preservado
e não é um source set registrado. O pedido posterior autorizou APKs locais de teste;
não foi criada tag ou release pública.

## Verificações executadas

- `:app:testWinxclipseDebugUnitTest`: **509 casos, 507 aprovados, zero falhas/erros,
  dois ignorados por permissão de symlink no Windows**. Compilação Java/Kotlin,
  recursos e código real de widgets em Robolectric API 28/31 incluídos.
- `tools/validate_locales.py`: EN/PT/ES com **1376 strings e 11 listas** por idioma;
  cobertura, placeholders e correspondência dos índices aprovados.
- DisplayX: **23 cenários nativos** de protocolo, descritores, dimensões, limites,
  seleção de plano e fences com falhas injetadas. Host incorporado a winx_vulkan
  e layer separado compilados/vinculados com CMake/NDK 27, ARM64/API 26.
- Áudio: **seis cenários nativos** executam o loop C usado pelo app, verificando
  pausa com dados, wake espúrio, retomada, underrun e encerramento. `alsa_client.c`
  compila para ARM64/API 26 com avisos tratados como erros, exceto parâmetros e
  helper legado não usados.
- Os 15 estágios SPIR-V do compositor já foram compilados pelo glslc no lote 35,
  com cinco testes do exportador; não sofreram alterações nos lotes 37/38.
- Novos testes reais de arquivos cobrem troca FEX, preservação dos módulos Wine,
  pacote incompleto, cópia sobre si mesmo, fallback FUSE e falhas de exportação.
- Logs existentes revelaram recusa real do wineserver por permissões. Reparo
  de `.wineserver` antes de cada lançamento aprovado em quatro casos JVM e no
  helper Java executado em ART no S24+, sem APK nem alteração do prefixo instalado.
- Revisados diff, finais de linha e whitespace; preservados arquivos e ajustes
  existentes. O merge Steam 35804c7 é ancestral do main. Nenhum workflow de build
  de APK/release foi acrescentado.
- Na checagem final surgiu um S24+ via ADB. O probe nativo independente confirmou
  ciclo pausa/retomada do áudio, importação AHB RGBA/BGRA, SYNC_FD e carregamento
  dos módulos recém-compilados; protocolo/SCM_RIGHTS e handles AHB passaram por
  socket anônimo real. Socket de arquivo foi recusado para o UID shell
  e ficou explicitamente ignorado; apresentação em tela não foi exercitada.

Os testes produzem intermediários de JVM e módulos/objetos de validação. O lote
39 também gera um APK local para o feedback autorizado pelo usuário. As compilações
nativas independentes ficam fora do repositório. Fontes de teste Windows não são
empacotados no app.

## Resultado da revisão por área

| Partes | Resultado local | Confirmação ainda necessária |
| --- | --- | --- |
| 1–2 | Instalação/prefixo/tradutores/dependências, recibos, downloads retomáveis e preparação VulkanRT implementados/testados | Primeiro boot e runtime dentro do Wine; processo 0 MB e shutdown de cada jogo |
| 3–4 | Driver selecionado/nome/extensões, ABI de wrappers, DX9, compositor Vulkan/OpenGL, FSR/FG, refresh solicitado | Stock/Quasar/RADV, DX9/11/12, imagem, latência e Hz efetivamente observados |
| 5 | Traduções, seleção/auto idioma, tabela regional Exynos, formatos e atualização única | Identificação real e atualização sobre APK antigo sem perder dados |
| 6 | Serviço hospedado, moderação, exportação/capas e dependências exatas; testes de recuperação por journal | Interação Android completa e encerramento durante importação real |
| 7–8 | Revisão dos fluxos ativos, blur/fallback, rolagem, arquivos locais/SAF e resultados de backup | Acabamento visual/gestos, USB/SD, permissões e velocidade no Android |
| 9–10 | HUDs, formatação, métricas, sensores indisponíveis e cálculos/relatórios | Aparência, sensores disponíveis e comparação com sessão real |
| 11 | RAM Fix geral, Aggressive sem pageout de heaps vivos, Mali bounded e Sync with Wine; comparação dos forks concluída | Sessão longa com refaults, memória, consumo e frametime |
| 12 | Transporte AHB, fences, SurfaceFlinger e composição Vulkan/fallback implementados e compilados | Compatibilidade Vulkan/ABI, imagem/cor, resize, background, rotação e desempenho |
| 13 | Suítes e revisão local integradas; documentação/release notes atualizados | Matriz física abaixo |

## Matriz física que permanece aberta

O usuário instalou o primeiro APK local e enviou os prints que orientaram o lote 39.
O hash do pacote instalado corresponde ao APK entregue de 081cd71. As consultas
nativas de drivers e sensores foram executadas separadamente, sem substituir o
app durante esses testes. A build com as correções de interface ainda requer
conferência visual. Resultados e limites em [device-probe.md](device-probe.md).

1. Instalação limpa e atualização por cima: dados, idioma, mensagem única,
   dependências, catálogos e restauração por arquivo/URI.
2. Container novo e existente; trocar Wine/Proton x86/x64/ARM64/ARM64EC compatíveis;
   primeiro shortcut, cancelamento, runtime faltante e relançamento após reparo.
3. Repetir jogo/save/config original por tempo suficiente para a queda de FPS;
   capturar saída, processos e razão de encerramento. Conservar RAM Fix e a
   detecção Wine vazio; distinguir falha de processo, driver, Android e memória.
4. Stock/Quasar/RADV com wrappers selecionados, DX9/11/12 e as opções FSR/FG;
   comparar OpenGL/Vulkan, DisplayX ligado/desligado, pacing, cor e rotação.
5. DisplayX: fullscreen/janela, cursor, múltiplas janelas, FSR/FG, sidebar/blur,
   ida/volta do background, resize e saída. Confirmar que a reutilização aguarda
   o consumidor e que a falta de extensão volta para a apresentação original.
6. PT/ES/EN, claro/escuro, fonte ampliada, teclado, baixa largura, economia de
   bateria e blur desativado; mouse USB + teclado Bluetooth do relato #5.
7. Downloads/importações interrompidos, USB/SD/SAF, falta de espaço, cancelamento,
   publicação/moderação e recuperação após encerramento real do processo.
8. Conferir HUDs/medições com uma sessão real e comparar Aggressive/limpador
   ligados/desligados, sem combinar várias alterações no mesmo ensaio.

Triagem: [issue-triage.md](issue-triage.md). Referências/adaptações:
[fork-comparison.md](fork-comparison.md). Revisão visual em fontes:
[interface-review.md](interface-review.md). Checklist detalhado:
[0.9.6-roadmap.md](0.9.6-roadmap.md).


Lote 39: build APK Java/Kotlin/C++ completa aprovada; 509 casos JVM, 507 aprovados,
zero falhas/erros, dois ignorados por symlink no Windows. APK contém
libc++_shared.so e seu aviso de licença. Regressões cobrem marcação/seleção,
callbacks de editor inline/terminal, alturas/alinhamento, MangoHUD compacto, cpuidle,
frequências Samsung e preservação dos pacotes na atualização ImageFS.
A assinatura para atualizar diretamente a 0.9.5 fornecida permanece pendente da
chave original: ver signing-update-compatibility.md.


Lote 40: 522 casos JVM, 520 aprovados, zero falhas/erros, dois ignorados por
symlink no Windows. Java/Kotlin/C++/APK completos. PT/ES: 1.387 strings/11 arrays.
Protocolo DisplayX: 23 cenários aprovados. ART no S24+: XZ 28.270 ms e Zstandard
2.178 ms com SHA-256 idêntico do TAR; quatro quadros Vulkan em superfícies
descartáveis, BGRA/RGBA e apresentação precisa ligada/desligada. Escopo e limitações
em [feedback-2026-11-01.md](feedback-2026-11-01.md).


## Retorno do lote 41

A revisão mais recente está em [feedback-2026-11-01-followup.md](feedback-2026-11-01-followup.md).
JVM: 528 casos, 526 aprovados, dois ignorados e zero falhas.
No S24+, a camada DisplayX real entregou três frames AHB com cores verificadas
na saída do compositor. Não é validação de gameplay Proton/DXVK.
