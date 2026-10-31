# VulkanRT 1.3.283.0 — preparação e instalação por prefixo

## Pacote e primeiro boot do app

`assets/dependencies/vulkanrt-1.3.283.0.zip` contém o **instalador original** fornecido
pelo mantenedor, sem modificações, dois `vulkaninfo.exe`, dois loaders e a licença,
extraídos com 7-Zip sem executar o EXE no Windows do computador. SHA-256 do instalador:
`a00e79a390b08565129ee81aaa8bf48e1f87e2144ef5d2601e376c9eb7140f8a`.

O primeiro boot prepara esses arquivos no armazenamento privado, junto à extração
das dependências básicas. Uma atualização prepara o pacote sem reextrair ImageFS.
Cada arquivo é verificado por SHA-256; pacotes incompletos são reparados em staging,
com commit por diretório. ZIP com entrada extra, duplicada, caminho externo ou tamanho
excessivo é recusado. Nenhum executável Windows é executado nessa etapa Android.

O app inicial ainda não dispõe do XServer/driver de um container para criar um
dispositivo Vulkan. Por isso o instalador e os testes reais rodam na preparação do
primeiro prefixo usado, **antes do jogo**, dentro do Wine/Proton selecionado. Não se
cria um prefixo-base de outra geração para copiá-lo depois a outros runtimes.

## Fluxo dentro do Wine

1. Depois de terminar o `wineboot`, executar o `vulkaninfo` original com o loader
   builtin e `+loaddll`. Exigir GPU, driver e evidência de `winevulkan` builtin.
2. Registrar o ICD de Wine com seu `regsvr32` para as arquiteturas disponíveis.
3. Parar o wineserver do prefixo, criar backup persistente dos loaders e quebrar
   links para os arquivos compartilhados do runtime. Isso evita que uma gravação
   do NSIS altere um builtin compartilhado por outros containers.
4. Executar o instalador original `/S` somente pelo Wine. Exigir saída zero e os
   SHA-256 dos loaders efetivamente instalados, com o wineserver já encerrado.
5. Preservar cópias dos loaders oficiais e diagnósticos em
   `C:\Program Files\VulkanRT\1.3.283.0\WinXclipse\x86|x64` e **restaurar os loaders
   originais do Wine nos diretórios de sistema**. Os jogos mantêm sua integração
   com o driver Android e o caminho Vulkan 1.4 suportado pelo runtime selecionado.
6. Testar o loader oficial ao lado de `vulkaninfo`, identificando DLL nativa e ICD
   builtin realmente carregados. Limpar os overrides de ICD apenas no comando
   Windows de diagnóstico; comparar GPU e propriedades do driver com a referência.
   Divergência ou falha não recebe recibo de instalação verificada.
7. Encerrar o wineserver e gravar `.verified` somente após todas as verificações.
   O recibo inclui versão, protocolo, fingerprint do Wine e configuração do driver;
   arquivos presentes continuam sendo verificados antes de reutilizá-lo.

O executável oficial cria um `VkDevice` em `AppGpu` antes de imprimir o resumo e
falha quando essa criação falha. Isso foi conferido no código
[Vulkan-Tools 1.3.283.0](https://github.com/KhronosGroup/Vulkan-Tools/blob/vulkan-sdk-1.3.283.0/vulkaninfo/vulkaninfo.h),
não inferido somente pelo fato de o programa abrir. O registro do ICD segue a
[implementação de Wine](https://github.com/wine-mirror/wine/blob/wine-9.0/dlls/winevulkan/loader.c).

## Falhas, retomada e arquiteturas

- x86 usa `system32`; win64 usa `system32` para x64 e `syswow64` para x86 quando a
  ponte de 32 bits existe. ARM64/ARM64EC continuam no Wine nativo e usam seus
  tradutores de PE. Um teste incompatível recusa a instalação; não troca o runtime.
- Verificações têm timeout de 20 s, registro 15 s, instalador 90 s. Cancelamento
  encerra o filho e ainda limpa o wineserver; não dispara callback de fim do jogo.
- Falha opcional preserva o caminho original do jogo, sem recibo de sucesso. Uma
  tentativa recusada não se repete a cada abertura de jogo no mesmo processo;
  reiniciar o app ou mudar runtime/driver permite nova tentativa.
- Se o app morrer durante instalação, a próxima preparação encerra o wineserver e
  recupera os loaders antes de qualquer teste novo. Backup danificado ou servidor
  que não encerra bloqueia o lançamento com erro explícito; não descarta recuperação.
- Logs por fase ficam em `.winxclipse-vulkanrt-logs` no prefixo; o último erro em
  `Program Files/VulkanRT/1.3.283.0/WinXclipse/.last-error.txt`. Durações por fase vão
  ao logcat, separando o instalador dos testes e da espera pelo wineserver.

## Validação

Cobertura de código: payload original e reparo, ZIP inválido, identificação de
loader/driver, x86/win64, instalação uma vez por identidade, timeout, payload
instalado truncado, driver divergente, recuperação e isolamento de hardlinks.
Os testes de fluxo usam executor simulado: **o instalador não foi executado no
computador e ainda não houve validação no Android**. Adoção real depende dos testes
automáticos acima passarem no aparelho; não se marca sucesso apenas por copiar o
pacote. Tempo de instalação e compatibilidade ARM64EC precisam ser medidos no aparelho.
