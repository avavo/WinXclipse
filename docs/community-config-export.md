# Exportação de configurações da comunidade

Na interface clássica, o menu do container abre um rascunho com nome do jogo,
autor/apelido opcional, FPS esperado opcional, notas e compatibilidade. Discord
não é obrigatório para salvar ou compartilhar. O nome do aparelho continua sendo
detectado pelo app.

A capa automática disponível nos atalhos do container aparece como prévia. O
usuário pode buscar pelo nome do jogo, escolher entre imagens de vários resultados
do seletor já usado pelo app, importar uma imagem do Android ou exportar sem capa.
A busca usa a chave SteamGridDB configurada no app, quando houver uma personalizada.
Falha de busca não substitui a imagem já escolhida. A busca e a preparação da capa
ocorrem fora da thread da interface. O rascunho salva os campos e a capa preparada
durante recriação do diálogo.

Imagens de entrada têm limite de 16 MiB, dimensões de até 16384 por eixo e 64 milhões
de pixels. A decodificação reduz a maior dimensão para até 1280 e aplica orientação
EXIF antes de gerar PNG. Rascunhos ficam no cache privado; arquivos com mais de sete
dias são removidos ao preparar novas capas. Caminhos privados e URIs não vão para
o manifesto.

O ZIP usa o mesmo manifesto de dependências exatas da importação. Inclui
`config.txt` e, quando selecionada, `cover.png`, sem binários do jogo ou saves. É
montado em arquivo temporário no diretório final, fechado/sincronizado e só então
renomeado. Exportações anteriores são preservadas; chamadas simultâneas recebem
nomes diferentes. Erros não deixam um ZIP parcial anunciado como exportado.

Destino: `Download/WinXclipse/CommunityConfigs`. Compartilhamento usa o seletor
Android e uma URI FileProvider com permissão temporária de leitura. O ZIP continua
disponível mesmo sem rede ou app de compartilhamento.

As configurações do catálogo GitHub mantido pelo proprietário recebem um selo de
verificação, inclusive no cache offline desse catálogo. O estado vem da origem do
catálogo, não de um campo que o autor possa inserir no ZIP. O selo aparece na ficha
e nos detalhes. Isso prepara a distinção entre o catálogo existente e futuros
envios públicos sem verificação.

Após salvar, o usuário pode compartilhar o ZIP ou escolher **Publicar na comunidade**.
A publicação usa exatamente o ZIP revisado e preserva o arquivo local se a rede
falhar. Novos envios começam sem verificação e recebem uma identidade estável;
repetir o envio com a mesma credencial/ZIP recupera a mesma publicação. O serviço
e o painel de moderação estão descritos em [community-service.md](community-service.md).

Validação: compilação Java/Kotlin/recursos e 252 casos JUnit, sem falhas e com um
ignorado por permissão de symlink no Windows. Sete novos casos cobrem conteúdo do
ZIP, UTF-8, ausência de imagem, exportação concorrente, arquivos anteriores,
nomes/limites e falhas antes da publicação. PT/ES: 1163 strings e 11 arrays.
O seletor Android, a busca online e o compartilhamento ainda precisam de validação
visual no aparelho. Nenhum APK ou release foi gerado.
