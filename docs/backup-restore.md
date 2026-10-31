# Origem dos arquivos de restauração — 0.9.6

A restauração distingue o arquivo escolhido pelo usuário de uma cópia criada no
cache pelo app. `RestoreArchive` mantém essa posse durante a leitura do tar.

- Uma URI `file:` legível usa o arquivo diretamente, sem copiá-lo novamente nem
  incluí-lo na limpeza, mesmo se o arquivo selecionado estiver dentro do cache.
- Uma URI de documento é aberta pelo ContentResolver e copiada para um arquivo
  temporário único. A cópia só fica disponível após fechar os streams com sucesso.
- Falha ao criar o temporário, ler ou fechar o provedor fecha o stream e remove
  a cópia parcial. Antes, o resolvedor compartilhado podia deixar um `restore_*.tmp`
  após falha de leitura, ou o stream aberto se a criação do temporário falhasse.
- O temporário também é limpo se a extração falhar; fechamento repetido é permitido.
  Nenhum arquivo de origem é removido. Falha de limpeza do cache não transforma
  uma restauração concluída em outra tentativa de extrair os dados.

O resolvedor SAF anterior só retornava caminhos diretos para diretórios; os
backups em URI de documento já usavam cópia temporária. A exclusão de um backup
original por esse fluxo não foi reproduzida. A correção trata a posse explicitamente
e os vazamentos de arquivo/stream nas falhas, sem declarar perda de backup confirmada.

## Validação

Sete testes de arquivos/streams cobrem origem preservada em sucesso e erro,
cópia maior que o buffer, desconexão do provedor, falha ao criar arquivo no cache,
falha no fechamento, limpeza após erro do extrator, fechamento repetido, leituras
de tamanho zero e origem inválida. Compilação Java/Kotlin/recursos e suíte completa
executadas sem gerar APK/AAB.

Os testes usam streams e diretórios locais. Restauração real pelo seletor Android,
permissões de provedor, backups antigos e preservação integral dos dados em aparelho
continuam na Parte 12. A extração do backup completo mantém sua semântica anterior;
esta mudança não a transforma em uma transação recuperável após perda de energia.
