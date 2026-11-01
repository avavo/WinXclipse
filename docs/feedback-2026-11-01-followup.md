# Feedback de 01/11/2026 — lote 41

## Vídeo e DisplayX

O seletor OpenGL mostra **Zink / Automático**. Automático deixa a biblioteca Mesa
selecionar o driver sem forçar Zink; não representa outro driver específico.
O valor persistido `native` continua válido, com o mesmo comportamento.

DisplayX exige compositor Vulkan e renderizador Wine Vulkan. Trocar Wine para
GL/EGL desmarca e desabilita DisplayX imediatamente. Voltar ao Vulkan deixa a
ativação a cargo do usuário. Configurações antigas incompatíveis são bloqueadas
também no início da sessão, não apenas pelo formulário.

A camada anunciava extensões que exigem handles nativos de swapchain, embora
retivesse essas funções no ICD. DXVK podia habilitá-las e tornar a swapchain AHB
inelegível. A enumeração agora filtra todas essas extensões quando DisplayX é
solicitado; pedidos explícitos incompatíveis ainda conservam o fallback normal.

O desktop GDI do Wine continua usando X11. O HUD distingue `DisplayX pronto ·
X11/GDI` de `DisplayX`, reservado a frames realmente importados desse transporte.
Não se altera o indicador para fingir que os frames GDI passaram pela camada.

## Barra lateral e instalação

A abertura espera a captura desfocada antes de iniciar a animação. A captura
anterior é mantida entre aberturas, com cancelamento de respostas antigas e
limite de espera quando a superfície está sendo recriada. Os três acessos à
barra usam o mesmo fluxo. A revisão não inclui aprovação visual final no aparelho.

O progresso do primeiro boot mede bytes comprimidos consumidos. Não depende do
tamanho declarado de entradas TAR nem de uma estimativa de compressão 4:1, que
ficava sem avançar durante entradas grandes/esparsas. Cada etapa reserva o fim
para validação e escrita; falhas/CRC não são apresentadas como instalação concluída.

## RAM

Preparar RAM deixa de alocar/tocar até 256 MiB para pressionar o sistema. Executa
uma coleta/liberação de páginas ociosas do próprio app antes da abertura do Wine.
O limite antigo e seus testes de orçamento foram removidos junto com esse caminho.

RAM Aggressive agora instala uma biblioteca pequena nos processos Bionic do
Wine, preservando os demais preloads. Ela pede liberação imediata de páginas
liberadas e desativa a retenção do cache secundário quando o alocador suporta as
opções. O preload alcança também o bootstrap e os filhos; desligar a opção o remove.
Não percorre heaps ativos nem força paginação do jogo. Opções usadas:
[mallopt do Bionic](https://android.googlesource.com/platform/bionic/+/master/libc/include/malloc.h).

Teste isolado no aparelho, 4.096 alocações de 16 KiB, RSS em KiB:

| Política | Antes | Alocado | Após liberar |
| --- | ---: | ---: | ---: |
| Desativada | 2.924 | 68.520 | 4.048 |
| Ativada | 2.996 | 68.536 | 3.384 |

O sistema aceitou decay/cache/purge. Uma carga anterior de blocos de 1 MiB não
mostrou redução após liberar. São ensaios de alocador em subprocessos descartáveis,
não um A/B de boot completo da Proton. Não comprovam queda de cinco pontos na RAM
global: ela inclui outros apps, cache e memória que o Wine ainda está usando.

## Sensores e HUD

No SM-S926B, Android 37, sob o UID do app:

- GPU: `/sys/kernel/gpu/gpu_tmu`, leitura decimal em °C, confirmada entre 46 e 53 °C.
- CPU: TMU `curr_temp` é negado pelo firmware. O termistor AP em
  `/sys/devices/virtual/sec/sec-ap-thermistor/temperature` é legível em décimos
  de °C; o HUD identifica o valor como AP. Não substitui temperatura da GPU,
  bateria ou junção da CPU por uma leitura diferente sem identificação.
- Corrente: BatteryManager retornou -832/-1.069 em mA, embora a API documente µA.
  O perfil desse modelo corrige a unidade antes do cálculo. Valores já em µA
  e sentinelas não são multiplicados. Zero indisponível procura outra fonte.
  A conversão foi repetida no aparelho: -1.069 -> -1.069.000 µA.
- Swap: nova opção mostra usado/total a partir de SwapTotal e SwapFree do kernel,
  com indisponibilidade distinta de zero.

## Verificação reproduzível

- JVM: 528 casos, 526 aprovados, dois testes de symlink
  ignorados no Windows, sem falhas.
- PT/ES: mesmas strings/arrays de EN.
- Protocolo DisplayX: 23 cenários de IPC/fences/scanout aprovados.
- `tools/DisplayXNativeProbe.cpp` e `.java`: três quadros Vulkan convidados
  atravessaram a camada real, AHardwareBuffers, fences e socket até o compositor.
  Imagens lidas de uma ImageReader foram vermelho (255,0,0), verde (0,255,0) e
  azul (0,0,255). Só a criação da superfície X11 é substituída no teste. Não é
  ensaio de DXVK/Proton ou do driver Quasar em gameplay.
- `tools/HudSensorProbe.java` e `tools/GuestMemoryProbe.c`: fontes dos ensaios
  feitos em subprocessos, sem iniciar Wine, recriar prefixos ou apagar dados.

O APK mantém a chave estável dos testes locais. A chave da 0.9.5 original continua
indisponível; não se publica tag/release nem se promete atualização dessa assinatura.
