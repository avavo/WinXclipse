# Armazenamento por URI no gerenciador — 0.9.6

## Fluxo

O gerenciador recusava a pasta escolhida quando não conseguia convertê-la em um
caminho diretamente legível. Agora esse caso abre um navegador do local autorizado,
usando `DocumentsContract` e `ContentResolver`. IDs de documentos permanecem opacos;
não são concatenados a caminhos do filesystem.

- A pasta aparece entre os locais salvos quando há permissão persistente de leitura.
  Nome em cache evita consultar dispositivos/provedores ao desenhar a tela inicial.
  Manter pressionado o local permite removê-lo da lista, preservando seus arquivos.
- Navegação por subpastas, seta/Voltar até a seleção de locais, restauração após
  rotação, filtro da pasta atual, ordenação e alternância de arquivos ocultos.
- Seleção múltipla, copiar/recortar/colar, criar pasta, renomear, excluir e compartilhar.
  Opções de alteração respeitam as capacidades informadas pelo provedor.
- Área de transferência comum aos dois navegadores permite copiar nos dois sentidos
  entre documentos e C:/Downloads/outros caminhos. Operações locais mantêm o motor
  `FileTransfer`, com seu caminho rápido de rename no mesmo volume.
- Uma operação antiga não remove itens de uma área de transferência que o usuário
  substituiu enquanto ela terminava. Falhas de movimentos mantêm os itens pendentes.

As consultas e operações do provedor ficam fora da thread da interface. Binding,
filtro e ordenação usam metadados já enumerados, sem uma consulta por linha desenhada.
Consultas antigas são canceladas/descartadas ao trocar de pasta ou destruir a view.
Textos novos estão em EN/PT/ES; contextos do diálogo conservam o idioma escolhido.

O acesso e sua persistência seguem o
[Storage Access Framework do Android](https://developer.android.com/training/data-storage/shared/documents-files):
o usuário escolhe uma árvore, e permissões/capacidades podem ser revogadas ou mudar.
O app não substitui falta de acesso por uma listagem vazia considerada bem-sucedida.

## Transferências

1. Inventariar a origem, recusando ciclos, nomes duplicados/inválidos e destino dentro
   dela. Limites de profundidade/quantidade impedem recursão sem fim ou inventário
   ilimitado. Listagens que informam carregamento/erro não servem como inventário completo.
2. Copiar para um item de staging exclusivo no destino. Verificar tamanho conhecido
   e SHA-256 por leitura do arquivo copiado; só então considerar seus bytes válidos.
3. Conferir novamente nomes, filhos, tipos, tamanhos e datas da origem. Substituição
   exige confirmação e mantém o anterior em backup até publicar o novo item por rename.
   Falha na publicação tenta restaurar o anterior; falha de rollback conserva o backup
   e informa seu nome para recuperação.
4. Sem rename no provedor, nomes novos ainda funcionam com uma segunda cópia. Ela
   reutiliza o inventário original, sem confundir um índice atrasado do staging com
   uma pasta vazia. Substituição sem suporte para conservar o anterior é recusada,
   orientando a usar outro nome/pasta.
5. Movimentação só solicita exclusão da origem após publicação e nova conferência.
   Alteração da origem, cancelamento, falta de capacidade ou recusa de exclusão
   mantém a origem e comunica o resultado. Cópias completas permanecem disponíveis.

O progresso distingue conferência, cópia e verificação. Cancelamento solicita
`CancellationSignal` nas consultas/abertura e fecha streams em uma thread auxiliar
para interromper leituras bloqueadas, sem fazer esse fechamento na thread da UI.

## Evidência e limites

32 casos novos: 21 de transferência, três da área de transferência e oito com um
`DocumentsProvider` simulado no Android em JVM. Cobrem bytes/nome Unicode, diretórios,
tamanho desconhecido, leitura interrompida, checksum incorreto, colisões, origem
alterada, cancelamento bloqueante/pós-publicação, falhas de rename/rollback,
ausência de rename, índice atrasado, ciclos e pastas com muitos arquivos.

Os testes Android exercitam os métodos do provedor e descritores, IDs que mudam
no rename, fechamento de todos os cursores, flags somente leitura, perda de acesso,
listagem incompleta, seletor real do Fragment, persistência do local, navegação,
rotação e colagem por URI pelo File Manager local. O provedor reprova consultas na
thread principal no teste de navegação. As permissões e o dispositivo são simulados.

Suíte completa: 396 casos, 395 aprovados, zero falhas/erros e um ignorado pela
permissão de symlink do Windows. Java/Kotlin/recursos e PT/ES passaram, com 1196
strings e 11 listas por idioma. Sem APK/AAB/release.

Não é uma montagem/FUSE de URI para o Wine: para executar programas desses locais,
o usuário deve copiá-los para C: ou armazenamento mapeável. Arquivos virtuais que
exigem conversão e links simbólicos não são convertidos/dereferenciados por este
motor. Provedores com listagens ainda carregando precisam concluir/ser atualizados.

Renames, exclusões e persistência dependem do contrato do provedor; não há transação
durável contra morte do processo, nem bloqueio de outros aplicativos escrevendo
simultaneamente nos documentos. A conferência de metadados detecta mudanças usuais,
sem prometer comparação atômica com a exclusão remota. Limpeza recusada pode deixar
staging/backup recuperável. Velocidade, permissões reais, remoção de USB/SD e lifecycle
do processo ainda precisam de validação física. Nenhum aparelho ADB está conectado.
