# Probe nativo no S24+ — 01/11/2026

Um dispositivo apareceu no ADB durante a checagem final: SM-S926B, SoC s5e9945,
Android API 37. O pacote instalado é com.win.xclipse, 0.9.6/27, atualizado antes
das últimas alterações desta revisão e não debuggable. Não foi substituído,
reinstalado ou iniciado pelo teste, nem foram acessados seus dados privados.

`tools/device_native_probe.cpp` foi compilado como executável ARM64/API 26, sem
APK, junto de Server.cpp e do loop de áudio usados no app. O executável e os dois
módulos recém-compilados foram copiados para um diretório temporário próprio em
/data/local/tmp. Após o ensaio, os três arquivos e o diretório vazio foram removidos.

## Resultados

| Verificação | Resultado observado |
| --- | --- |
| Driver stock | Samsung Xclipse 940, Vulkan 1.3.304 |
| Extensões | Android AHB, queue-family foreign e external-fence-fd disponíveis |
| RGBA | AHB alocado; formato Vulkan 37; memória externa importada e vinculada a VkImage |
| BGRA | AHB alocado; formato Vulkan 44; importação/bind aprovados, sem reinterpretar RGBA |
| Socket anônimo | Protocolo/SCM_RIGHTS transferiu um descritor real; handle AHB recebido e reimportado no Vulkan |
| Fences | Exportação SYNC_FD de submissão Vulkan e conclusão aprovadas |
| Áudio | Pausado conservou os dados; retomado drenou a fila; 0,205 ms de CPU da thread em 150 ms de pausa na execução final |
| SurfaceControl | Símbolos de criação, setBuffer, OnComplete e previous-release-fence disponíveis |
| Módulos novos | libwinx_vulkan.so e libwinx_displayx_layer.so carregaram com RTLD_NOW |
| Transporte IPC | Ignorado: bind de socket em /data/local/tmp retornou EACCES para o UID shell |
| Tela e gameplay | Não exercitados; não há prova de imagem, scanout direto, FSR/FG ou FPS |

O teste do socket não foi contornado com root, alteração de SELinux ou mudança do
protocolo do app. Precisa ser repetido no UID do aplicativo. Extensão disponível,
importação e dlopen não demonstram que o layer interceptou um jogo nem que o
SurfaceFlinger apresentou corretamente um frame.

O dumpsys display reportou modos de 120 Hz na resolução ativa e taxa de 60 Hz
fora do WinXclipse. Isso é inventário da tela, não falha/conclusão do pedido de
120 Hz dentro da versão alterada. Nenhuma configuração de refresh foi modificada.

O histórico ApplicationExitInfo foi lido por completo: 13 registros do processo
principal, nove remoções de tarefa, dois LOW_MEMORY em importância 400 e duas
limpezas cached/empty. Esses eventos de background não identificam a causa do
shutdown durante um jogo. Não havia processo WinXclipse ativo na consulta.

## Logs dos jogos e permissões do wineserver

Também lidos os 73 arquivos já existentes em Download/WinXclipse/logs, de
27–29/10, sem alterar os originais. Há 14 relatórios de lifecycle: 13 registram
parada não automática enquanto o jogo ainda existia e um registra launcher sem
aplicação após dois minutos. Nesse último, Container-1_copy_ em 27/10 às 21:31:06,
o wineserver saiu com código 1 por permissões abertas em `.wine/.wineserver`.
Nenhum desses arquivos contém DEVICE_LOST, falta de memória Vulkan ou SIGSEGV;
isso não exclui falhas não capturadas. Há avisos de cache VKD3D e três registros
SURFACE_LOST, sem cronologia suficiente para atribuir a eles a causa da saída.

O contrato de diretório privado é explícito no
[Wine server/request.c](https://github.com/wine-mirror/wine/blob/master/server/request.c):
0700, diretório real e pertencente ao usuário. WineServerDirectory repara apenas
esse diretório antes de cada lançamento, sem apagar lock/socket nem tocar saves,
recusar erro de chmod ou saltar o reparo por um recibo de runtime já válido.
Se a restrição não puder ser aplicada, o launcher informa falha antes do jogo.

O helper Java real foi compilado para DEX e executado em ART via app_process,
sem APK: **0771 → 0700**, segunda chamada, preservação do lock e recusa de
arquivo/symlink aprovados. Usou uma fixture própria em /data/local/tmp, depois
removida com o JAR temporário. Os dados do pacote instalado não foram modificados.
Isso comprova o reparo da condição observada; o mesmo jogo com o app atualizado
ainda não foi executado. Não prova a causa da regressão de FPS em outros jogos.

## Repetição

Compilar o probe com clang++ do NDK para aarch64-linux-android26, C++17,
`-static-libstdc++`, tools/device_native_probe.cpp e
app/src/main/cpp/winlator/renderer/displayx/Server.cpp, vinculando android, vulkan,
log e dl. Todos os caminhos de saída devem ficar fora do repositório. O probe
aceita caminhos de módulos como argumentos opcionais para conferir dlopen.
Executar somente no diretório temporário do ensaio; ele não requer Activity,
permissões de arquivos pessoais nem pacote Android. A restrição de socket pode
variar pelo contexto de segurança do processo.
