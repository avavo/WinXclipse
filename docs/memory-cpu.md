# Memória e sincronização de CPU — 0.9.6

## Sync with Wine

Confirmado pelo mantenedor: o pedido de “async CPU” se refere a **Sync with Wine**,
do Ludashi. Referência: StevenMXZ/Winlator-Ludashi `1042ba5`,
`XServerDisplayActivity.java`, `Container.java` e seletores de afinidade.

Container e atalho têm a opção em Avançado, desligada por padrão. O atalho herda
o container e só grava uma exceção quando diverge. A configuração faz parte do
JSON do container e, portanto, da exportação/importação.

Ao iniciar, a lista principal de núcleos gera `WINE_CPU_TOPOLOGY=quantidade:ids`.
Exemplo: núcleos 4 e 6 → `2:4,6`. IDs esparsos são preservados; listas e intervalos
são validados integralmente, ordenados e deduplicados. A afinidade para processos
de 32 bits continua separada, como na origem. A variável é aplicada após os merges
de ambiente; desligar a opção preserva um override explícito do usuário. Requer
um Wine que implemente essa variável; não habilita processamento assíncrono.

## RAM Aggressive

A varredura antiga de `/proc/self/maps` aceitava heaps e mappings anônimos grandes,
incluindo stacks sem nome. Mesmo preservando o conteúdo, `MADV_PAGEOUT` em memória
ativa pode exigir refaults e prejudicar o renderizador/interface do próprio app.
Esse caminho foi retirado da compilação Android.

Agora os perfis Android usam `mallopt(M_PURGE)` e, no agressivo, tentam
`M_PURGE_ALL` com fallback. O próprio alocador identifica memória não utilizada.
Um `malloc_trim` disponível também é aceito; ausência de mecanismo suportado
retorna `ROPT_ERR_NODEV`. Não se altera decay persistente, prioridade de threads,
memória do Wine nem o RAM Fix global. A execução continua explicitamente opt-in,
uma vez na preparação da sessão, fora da UI. O HUD não aciona paginação.

Semântica das opções confirmada no header do NDK 27 e no
[header oficial Bionic](https://github.com/aosp-mirror/platform_bionic/blob/main/libc/include/malloc.h).
O código portátil fora de Android conserva sua implementação anterior.

## Preparação baseada no Winlator Mali

Referência MIT: GunaCharanTeja/WinlatorMali `428bd94`, `RamBoosterComponent.java`
e `rambooster.cpp`. Licença e origem incluídas em `assets/licenses/winlator-mali.txt`.

A origem faz pressão por alocação, inclusive ondas de vários GiB e repetição
durante a sessão. A adaptação é uma opção independente, experimental e desligada
por padrão: ocorre depois da preparação do prefix e antes do lançamento real.

- Ignora a operação se houver Wine rodando, memória baixa ou leitura inválida.
- Orçamento máximo de 256 MiB, também limitado a 1/16 da memória disponível e a
  1/4 da folga acima da reserva. Reserva mínima de 1 GiB ou 1/8 da RAM física.
- Consulta `MemAvailable` a cada bloco de até 8 MiB. Cancela entre blocos e a cada
  MiB tocado. Prazo cooperativo de 750 ms; uma falta de página do kernel pode
  ultrapassar esse prazo, que não é garantia de latência em tempo real.
- Libera todos os mappings próprios em qualquer saída. Não segura ondas extras,
  não muda o nice da thread que iniciará o jogo e não toca mappings existentes.
- Não roda novamente durante gameplay e falhar nessa preparação opcional não
  impede o lançamento. O log registra bytes tocados e duração, sem chamar isso de
  RAM recuperada do jogo ou prometer ganho de FPS.

## Verificação e limites

Testes de listas esparsas/ranges inválidos, preservação do ambiente e orçamento de
memória, compilação Java/recursos e compilação C ARM64 API 26 passaram. Traduções
EN/PT/ES incluídas. A comparação de FPS, refaults, major faults e duração de boot
com as opções ligadas/desligadas permanece pendente no aparelho. Nenhum APK ou
release foi gerado.
