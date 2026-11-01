# Comparação dos forks — revisão da 0.9.6

Revisão de fontes em 01/11/2026. Commits fixados abaixo tornam a comparação
repetível; títulos como “performance” não comprovam ganhos no WinXclipse.
Nenhum APK foi usado para medir FPS ou validar essas adaptações.

## Referências e decisões

| Área | Fonte examinada | Resultado no WinXclipse |
| --- | --- | --- |
| Prefixo e primeiro jogo | [Mali 1282922](https://github.com/GunaCharanTeja/WinlatorMali/commit/1282922), incorporado em [428bd94](https://github.com/GunaCharanTeja/WinlatorMali/tree/428bd945be92c2063fe01daf770fa7310126e1b2); launchers do Ludashi 1042ba5 | Prefixo próprio, preparação e reaplicação das DLLs já implementados. Usamos `wineboot -u`, espera limitada e falha explícita; não copiamos a chamada `-h`, continuação após erro ou encerramento global de processos da referência. Ver prefix-startup.md. |
| Tradutores e ABI | Mesmo launcher Mali; [Plus 2508ccc](https://github.com/squalle0nhart/winlator_ludashi_plus/commit/2508ccc3ff9531cdd2ee0b9951d7fb38a0d6ef10) e ContentsManager | Validação de todos os payloads e recibos já presentes. Lote 38 acrescenta reconciliação das quatro bibliotecas FEX Unix conhecidas: trocar para pacote DLL-only remove sobras, preserva módulos do Wine e falha antes do jogo se não conseguir reconciliar. A limpeza só ocorre após copiar com sucesso o pacote escolhido. |
| DirectDraw/DX9 e wrappers | [CMOD d5313d6](https://github.com/coffincolors/winlator/commit/d5313d6588eaace1d02c6db0f4065664988e31fe), d58a184; Mali `egl.cpp`/GPUImage; Plus renderer | Registro Wine do renderer, reinstalação quando necessário e transação das DLLs já tratados. Não se mistura DLL x86/ARM64EC nem se substitui um wrapper escolhido por outro. Vulkan/OpenGL do compositor é uma escolha distinta do renderer Wine dentro da engrenagem. Ver graphics-wrapper-installation.md e vulkan-compositor.md. |
| DisplayX e apresentação | [Ludashi 1042ba5](https://github.com/StevenMXZ/Winlator-Ludashi/tree/1042ba5), [Plus 1022eb9](https://github.com/squalle0nhart/winlator_ludashi_plus/commit/1022eb9), 7646bd7 e patch de lifecycle do listener | Implementação própria MIT de AHB/fences/slots; FIFO e mailbox distintos, workers limitados, encerramento de socket/thread e release de buffer após fence. Nenhum binário guest sem proveniência foi copiado. O layer alternativo LGPL e o projeto standalone sem licença não foram incorporados. Ver displayx.md. |
| FSR e frame generation | Mali 1080322 e dc96de5; Plus 2508ccc e LSFG probe/shaders | Mantidos os algoritmos e efeitos do app, migrados para compositor Vulkan próprio com testes de parâmetros/shaders e reset temporal. A reescrita EGL/X11/ALSA do Plus exigiria trocar conjuntamente sua ABI e protocolos: não é uma otimização isolada portável. LSFG com dependências/licenças distintas e DLL externa não é apresentado como o FG próprio. Comparação de imagem/artefatos fica na matriz Android. |
| Memória e CPU | Mali b4dd06a, 1c9ce2d, 1dcb6f7 e RamBooster; Ludashi afinidade/CPU topology | RAM Fix geral mantido. Limpador opt-in adaptado com orçamento/reserva/prazo e sem ondas de vários GiB durante gameplay; Aggressive Android usa o alocador, sem varrer mappings vivos. Sync with Wine já implementado, sem renomeá-lo como execução assíncrona. Ver memory-cpu.md. |
| Input | Mali cc7952b/1dcb6f7; CMOD ef12a1c/d58a184; [Plus 5b167f8](https://github.com/squalle0nhart/winlator_ludashi_plus/commit/5b167f8656b9e92c4a276db57424199f03f90018) e 2508ccc | Classificação por fontes completas, cliques independentes, deltas e captura já corrigidos. Nosso botão B não abre a sidebar; Home/Mode têm tratamento separado. Não se transplanta evshim/winebus ou XInput2 do outro protocolo sem adaptar produtor e consumidor juntos. Mantidos os testes de regressão da issue #5 e o controle de EOF para evitar polling ocupado. |
| Áudio | CMOD cmod_bionic 49bfb90/ef12a1c e ALSAClient; remoção do protocolo ALSA Java no Plus 2508ccc | Mantida a ABI ALSA embarcada. Revisão local encontrou loop ocupado ao pausar o pacer: lote 38 espera em condition variable, reseta o relógio ao retomar e libera contextos mesmo sem thread criada. Writes inválidos/maiores que a capacidade não esperam indefinidamente. Não foi atribuída a esse defeito toda a regressão de FPS. |
| Arquivos e instalação | FileUtils dos três forks, CMOD f1ea28a, Ludashi/Plus instaladores | As referências examinadas ainda fazem cópias com transferTo único ou dependem do fluxo antigo de conteúdos. Mantidas nossas cópias verificadas com fallback FUSE, staging/rollback, instalador sparse e resolução exata de dependências. Não se substitui o pacote do usuário por “parecido” sem revisão. |
| Logs e diagnóstico | Mali 03bfdce/0eab0e6; ProcessHelper e LogManager; CMOD PEB log | WinXclipse já mantém geração de sessão, saída stdout/stderr/exit, diagnóstico DXVK/VKD3D e relatório de falha/encerramento. Evitado transplantar tela/armazenamento completos de logs como suposta correção de crashes. Precisamos dos logs de uma reprodução para atribuir encerramentos específicos a Wine, driver, Android ou processo vazio. |

CMOD foi examinado na branch pedida **cmod_bionic**, HEAD
[49bfb90](https://github.com/coffincolors/winlator/tree/49bfb90), não em uma branch
GLIBC diferente. Ludashi Plus foi examinado até 5b167f8, de 30/10/2026. As raízes
dos três projetos usam MIT, mas componentes de terceiros conservam suas próprias
licenças; MIT na raiz não autoriza copiar todo binário/algoritmo transitivo.
A adaptação limitada do limpador tem atribuição em assets/licenses/winlator-mali.txt.
A reconciliação FEX foi implementada aqui a partir do problema observado, sem
copiar a rotina que apaga bibliotecas antes de verificar a cópia.

## Verificação e limites

Testes reais de arquivos verificam troca FEX, conservação dos módulos do Wine,
pacote incompleto e resquícios em um pacote já aplicado. A ferramenta
`tools/test_audio_pacer.py` executa o mesmo loop C de produção com eventos de
pausa, wake espúrio, underrun, retomada e término. O código de áudio compila para
ARM64/API 26; IPC/scanout e shaders têm suas verificações separadas.

Antes/depois local descreve comportamento e integridade, não FPS medido:
antes o pacer girava pausado e a troca FEX podia conservar Unixlibs antigas;
depois ele aguarda um evento e o conjunto aplicado deve corresponder ao manifesto.
Medir refaults, CPU/latência de áudio, frametime, consumo e sessões longas permanece
na Parte 13, com as mesmas versões, jogo/save e configurações em cada comparação.
