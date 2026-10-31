# Telemetria do HUD

A leitura ocorre no HandlerThread compartilhado da sessão, a cada 1,5 segundo.
HUD e gerenciador de tarefas observam o mesmo snapshot; as telas não abrem um
segundo leitor nem calculam ocupação a partir de frequência.

- CPU: diferenças dos contadores `/proc/stat`, total e por núcleo. Não somar
  guest/guest_nice novamente, pois estão incluídos em user/nice. Primeiro intervalo,
  contadores reiniciados, iowait decrescente ou acesso bloqueado resultam em N/A.
- Frequência CPU: sysfs CPUFreq em kHz, convertida em MHz; não é ocupação.
- Nome do núcleo: MIDR por CPU ou implementer/part daquele CPU em `/proc/cpuinfo`.
  Somente IDs conhecidos são nomeados; não se deduz Cortex pela frequência máxima.
- GPU: uso em porcentagem e `devfreq/cur_freq`/KGSL gpuclk em Hz, convertido em MHz.
  A busca devfreq é restrita a nós GPU/g3d/Mali/KGSL/Xclipse, sem ler carga de outro
  dispositivo como se fosse GPU. Sensores bloqueados continuam N/A.
- RAM: MemTotal e MemAvailable, com GB decimais ou porcentagem usada/livre.
  O aviso de pressão continua baseado na porcentagem usada, independentemente
  do formato escolhido para exibição.
- Bateria: BatteryManager CURRENT_NOW em µA e tensão do broadcast em mV. Não há
  heurística que transforme uma corrente pequena em mA. Porcentagem usa level/scale.
  CHARGE_COUNTER em µAh fornece mAh restantes; na ausência, charge_full em µAh
  multiplicado pela porcentagem fornece uma estimativa, identificada por ≈.
  Não há capacidade de bateria inventada a partir do nome do aparelho.
- Temperatura não reutiliza zonas térmicas numeradas sem identificação de CPU/GPU.
  A bateria não é apresentada como temperatura da CPU.

Referências primárias:
[BatteryManager](https://developer.android.com/reference/android/os/BatteryManager),
[Linux proc](https://docs.kernel.org/filesystems/proc.html),
[CPUFreq](https://docs.kernel.org/admin-guide/pm/cpufreq.html),
[power_supply e unidades](https://docs.kernel.org/power/power_supply_class.html),
[IDs MIDR](https://github.com/torvalds/linux/blob/master/arch/arm64/include/asm/cputype.h).

Os testes cobrem contadores, hotplug/reset, dados ausentes, unidades, formatos e
identificadores. Acesso real aos sensores depende do kernel/SELinux do aparelho;
sem aparelho conectado, não foi comprovada a disponibilidade por modelo.

## Painéis e MangoHUD 1

MangoHUD 1 segue as duas referências visuais fornecidas pelo mantenedor: painel
escuro com colunas, rótulos coloridos, unidades menores, renderer/FPS/frametime e
gráfico verde de intervalos reais. O valor de VRAM fica N/A se não houver sensor
real de alocações da GPU; não usa RAM do Android com outro rótulo. A linha pode
ser ocultada. O renderer pode aparecer como DXVK, VKD3D, OpenGL ou Vulkan conforme
a configuração efetiva, sem ativar o HUD interno do DXVK para desenhar esse painel.

É uma apresentação Android própria para Bionic, conforme o esclarecimento de que
o objetivo é o visual das imagens. O pacote separado do CMOD
[winlator_mangohud_glibc_v1](https://github.com/coffincolors/winlator/releases/tag/winlator_mangohud_glibc_v1)
foi inspecionado: sua libMangoHud depende de libc.so.6, libm.so.6 e libgcc_s.so.1.
Esse binário GLIBC não foi injetado no processo Bionic, nem foi substituído pelo
FrameRating/HUD do DXVK do CMOD. Não se declara portado o binário original.

O preset anterior permanece como MangoHUD 2, mantendo o identificador antigo
`mangohud`. O seletor permite DXVK + Winlator/MangoHUD 1/MangoHUD 2. A injeção do
DXVK depende dessa escolha explícita e só muda no próximo lançamento do processo.

O editor permite criar, selecionar, renomear e remover painéis adicionais. Cada
um tem preferências de campos, formato, núcleos, posição, escala, opacidade e
estilo em um namespace próprio. Trocar de estilo preserva máscara/RAM/escala e
opacidade anteriores. Todos usam o mesmo leitor de sensores da sessão; esconder
um painel não desliga os outros nem cria um leitor por painel.

O gráfico mantém no máximo 120 intervalos em ordem, ignora timestamps repetidos
e separa pausas. O FPS inicial agora mede um intervalo iniciado na ativação, sem
contar dois segundos de frames dentro do primeiro segundo reportado. Há suporte
a drawables filhos de janelas Wine, comum em renderização GL/DX9. Os dados são
atualizações X11 observadas; a validação por jogo continua na Parte 12.

Nenhuma captura do app em aparelho foi produzida neste lote. Foram validados a
compilação, recursos e testes de política de modos, histórico, unidades e cálculos.
