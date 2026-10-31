# Arquivos de instalação e downloads simultâneos

O catálogo, os URLs manuais e a recuperação de dependências compartilham a fila de
rede e o mesmo cache persistente. Antes, o arquivo completo era entregue diretamente
a cada instalador. Se dois fluxos pedissem o mesmo URL, finalizar um deles podia
apagar o ZIP/WCP que o outro ainda estava lendo.

`ContentDownloadQueue.acquire` agora entrega um `Artifact` com arquivo próprio e
fechamento explícito. A aquisição acontece antes de liberar a vaga da fila de rede:
a limpeza do cache aguarda a aquisição em andamento. O app tenta criar um hardlink
no mesmo diretório privado, preservando o conteúdo sem copiar novamente vários GiB.
Se o sistema de arquivos não aceitar hardlinks, usa uma cópia em blocos cancelável.
O instalador apenas lê esse arquivo; fechar o artefato remove seu próprio caminho.

Catálogo, URL manual de wrapper e todas as dependências automáticas usam essa API.
Cada instalação pode limpar o cache compartilhado sem invalidar os artefatos já
entregues às demais. Uma versão diferente publicada no mesmo caminho também não
altera o arquivo de uma instalação iniciada anteriormente.

Pausar no fim preserva o cache completo para retomada. Cancelar antes de receber
a vez não apaga o trabalho de outro consumidor ativo do mesmo pacote. Falhas ou
cancelamento na preparação não entregam um arquivo parcial ao instalador.
Artefatos abandonados após encerrar o processo são removidos na próxima aquisição
nesse diretório; arquivos ativos, parciais e arquivos de outras origens são mantidos.

Validação: 259 casos JUnit, zero falhas e um ignorado por permissão de symlink
no Windows; compilação Java/Kotlin/recursos aprovada. Sete casos novos cobrem
consumidores independentes, limpeza durante conclusão, substituição do cache,
órfãos, pausa, cancelamento e dois consumidores do mesmo pacote na fila.
A integração de instalação/pausa ainda exige validação no Android. Nenhum APK foi
gerado; não foi medida uma melhoria de velocidade de instalação em hardware.
