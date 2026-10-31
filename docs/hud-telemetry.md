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
