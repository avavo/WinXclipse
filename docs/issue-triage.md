# Triagem de issues — 01/11/2026

Lidos todos os quatro relatos reais e seus comentários na coleção pública do
repositório. Entradas #1/#6/#7/#8/#9 são pull requests, não bugs adicionais.
Não foram publicados comentários nem alterados estados das issues.

| Issue | Relato e estado consultado | Investigação/correção relacionada | Limite da conclusão |
| --- | --- | --- | --- |
| [#2](https://github.com/avavo/WinXclipse/issues/2) | Wine/Proton 9.20 x86_64 não abre container; fechada, mantenedor informou correção na 0.9.1 | Seleção de ABI, módulos do runtime, preparação do prefixo, recibos e validação de payloads; ver prefix-startup.md e graphics-wrapper-installation.md | O relato antigo não fornece log de uma nova falha. Testes verificam falhas de instalação e mudanças de runtime; não repetem aquele Wine no Android. |
| [#3](https://github.com/avavo/WinXclipse/issues/3) | Pedido de Lossless Scaling; fechada, comentário relata artefatos na Xclipse | Revisão FSR/FG e compositor Vulkan; histórico temporal invalidado ao mudar dimensões/pausar | Pedido de recurso, não reprodução de crash. FG próprio não é declarado idêntico ao Lossless Scaling comercial; artefatos precisam de imagem e teste real. |
| [#4](https://github.com/avavo/WinXclipse/issues/4) | FSR não funciona, mover para C: falha e app encerra; aberta, sem comentários | FSR legado/escala e caminho Vulkan corrigidos; transferências verificadas/SAF/rollback e fallback FUSE; primeiro boot e wrappers validados | Não há jogo, log ou reprodução de todos os crashes. Testes cobrem causas verificáveis, sem declarar todos os encerramentos resolvidos. |
| [#5](https://github.com/avavo/WinXclipse/issues/5) | S23 FE/RE7: USB mouse + teclado Bluetooth conflitam com Relative Mouse; aberta, sem comentários | Fontes completas de input, cliques distintos, captura cancelável, liberação de teclas e escala de deltas; hardware-input.md descreve causas e testes | O defeito após três segundos ainda requer sessão física prolongada com esses periféricos. Não fechada com base apenas em testes JVM. |

O [PR #9](https://github.com/avavo/WinXclipse/pull/9), Steam, foi mergeado em
35804c7, que é ancestral do main revisado. O [PR #7](https://github.com/avavo/WinXclipse/pull/7)
está fechado sem merge; as correções locais de DirectDraw/bitness têm sua própria
implementação e testes. A interface do PR #8 permanece preservada em consoleUi,
excluída da 0.9.6, conforme solicitado.

O encerramento quando Wine realmente fica vazio foi conservado. Preparação,
falha de inicialização e retorno do start.exe são tratados separadamente do
processo real do jogo. Sem um aparelho/log da reprodução, não se atribui a um
único wrapper toda falha de jogo, nem se desativa o encerramento para escondê-la.

Na checagem final, `dumpsys activity exit-info` do S24+ conectado mostrou 13
registros do processo principal: nove remoções de tarefa, duas saídas LOW_MEMORY
com importância 400 e duas limpezas de processos cached/empty pelo sistema.
Os eventos LOW_MEMORY são de processo em background, sem correlação demonstrada
com o shutdown do jogo. Esse histórico não comprova crash nativo, wrapper quebrado
ou a causa do executável parado em 0 MB. O APK instalado não foi substituído.
