# Preparação de DXVK, VKD3D e DirectDraw

A revisão encontrou defeitos no caminho de preparação anterior ao Wine:

- A verificação de DXVK conferia somente a existência de DXGI/D3D11; um `d3d9.dll`
  ausente, vazio ou truncado passava despercebido.
- Erros de extração/cópia eram ignorados. DLLs de uma versão anterior podiam fazer
  uma instalação incompleta parecer pronta e receber o marcador da versão nova.
- Pacotes de perfil eram aplicados diretamente no prefixo, sem o remapeamento
  `syswow64 → system32` dos arquivos embarcados para Wine puramente x86.
- DirectDraw registrava sucesso mesmo após extração falha e podia deixar
  `dxwrapper.dll` da opção Dd7To9 ao trocar de modo.

Os pacotes agora são extraídos/copiados para staging no diretório do container.
DXVK exige D3D9/D3D11/DXGI; VKD3D selecionado exige D3D12. Prefixos Win64 conferem
também o conjunto x86 de WOW64. Pacotes sem essa arquitetura precisam de uma
variante compatível; a falha aparece antes do lançamento, sem reutilizar uma
versão anterior como se fosse a escolhida. DirectDraw e nGlide validam seus
arquivos, incluindo o companion `dxwrapper.dll` quando Dd7To9 está selecionado.

`GraphicsWrapperFiles` lê o cabeçalho PE, o tamanho dos headers/tabela de seções
e os limites das seções no arquivo. Rejeita DLL vazia, truncada ou com bitness
incompatível com o diretório. Aceita os tipos PE64 usados nos pacotes x64 e ARM;
isso não demonstra, sozinho, compatibilidade de ABI entre um jogo e um runtime.
O formato segue a [documentação PE da Microsoft](https://learn.microsoft.com/en-us/windows/win32/debug/pe-format).

Após validar o staging completo, cada arquivo anterior é movido para backup e
substituído por rename. Falha de IO restaura os anteriores e as remoções do lote;
se a restauração também falhar, o backup é preservado e o lançamento é interrompido.
Essa substituição não escreve através de hardlinks/symlinks em DLLs compartilhadas
com outros prefixos. As operações no processo são serializadas. A transação é
para erros de IO em execução; não há promessa de commit único de vários arquivos
através de queda de energia/encerramento forçado do processo.

Desativar VKD3D e voltar ao WineD3D prepara as DLLs do Wine selecionado no mesmo
lote. DLLs opcionais inexistentes nesse Wine são removidas para evitar sobras
nativas. A configuração original do container/shortcut continua escolhendo a
versão; os marcadores de aplicação só avançam após sucesso. Uma revisão interna
força a preparação completa uma vez em prefixos antigos. Arquivos ausentes ou
inválidos voltam a disparar reparo na abertura seguinte.

Validação: 284 casos JUnit, zero falhas e um ignorado por symlink no Windows.
Quatorze casos novos cobrem PE32/PE32+, corpos truncados, bitness, D3D9/WOW64, mapeamento
de perfil x86, rollback, remoção seletiva, arquivos inválidos e hardlinks. Também
foram inspecionadas as 48 DLLs reais dos arquivos embarcados DXVK/D8VK/DirectDraw/
nGlide, sem executá-las. Compilação Java/Kotlin/recursos e PT/ES (1170 strings,
11 arrays) passaram. Sem APK/release.

Gameplay D3D8/9/10/11/12, ARM64EC e retomada após interrupção real seguem na Parte 12.
Esse achado é uma causa concreta possível de falha de início; não prova que seja
a causa de todos os encerramentos ou da regressão sustentada de FPS.
