# Integração da 0.9.6 — 01/11/2026

Revisão final local das Partes 1–13, até o lote 38. O código permanece na versão
**0.9.6, versionCode 27**, com a interface clássica ativa. ConsoleUi está preservado
e não é um source set registrado. Não foi gerado APK/AAB, tag ou nova release.

## Verificações executadas

- `:app:testWinxclipseDebugUnitTest`: **495 casos, 493 aprovados, zero falhas/erros,
  dois ignorados por permissão de symlink no Windows**. Compilação Java/Kotlin,
  recursos e código real de widgets em Robolectric API 28/31 incluídos.
- `tools/validate_locales.py`: EN/PT/ES com **1373 strings e 11 listas** por idioma;
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

As execuções produzem intermediários de JVM e módulos/objetos de validação, não
um pacote Android instalável. As compilações nativas independentes ficam fora do
repositório. Fontes de teste Windows não são empacotados no app.

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

O S24+ conectado na checagem final tem um APK 0.9.6/27 instalado anteriormente,
sem as últimas alterações desta revisão. A restrição de não gerar APK foi mantida
e esse app não foi atualizado. O probe não valida interface, primeiro boot, jogos
ou apresentação do compositor. Resultados e limites em [device-probe.md](device-probe.md).

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
