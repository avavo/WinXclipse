# Extração de pacotes com preenchimento zerado — 0.9.6

## Achado no pacote original

O `bin/wine-preloader` do asset `proton-9.0-arm64ec.txz` tem tamanho lógico de
2.099.266.128 bytes. Sua inspeção completa encontrou quase todo esse conteúdo
preenchido por zeros. O extrator anterior enviava todos esses bytes para escrita.
Não é seguro remover essa região do arquivo: offsets e tamanhos pertencem ao ELF.

## Alteração

Para entradas regulares de tar com pelo menos 16 MiB, `SparseFileWriter` mantém o
conteúdo lógico e avança a posição sobre blocos de 64 KiB inteiramente zerados.
Blocos com dados continuam escritos por completo. O último byte é materializado
quando necessário, porque mover a posição sozinho não aumenta o tamanho do arquivo.
Arquivos menores continuam no caminho anterior, com buffer.

No Linux, escrever após avançar além do fim permite que o intervalo seja lido como
zeros, conforme [lseek(2)](https://man7.org/linux/man-pages/man2/lseek.2.html).
A API utilizada é [FileChannel](https://developer.android.com/reference/java/nio/channels/FileChannel).
A quantidade de espaço alocada depende do filesystem. A mudança não pressupõe
que todo destino reduza sua alocação física, nem mede o trabalho interno do kernel.

Entradas GNU sparse usam `getRealSize()`, o tamanho lógico reconstruído pelo
leitor tar, preservando extensões que ficam depois de grandes lacunas.

A cópia trunca o destino antes de começar, evitando que dados anteriores ocupem
intervalos pulados. Leituras parciais são completadas; EOF antecipado e falhas de
I/O invalidam a extração. Escritas parciais continuam no offset correto e ausência
de progresso é um erro. O tamanho declarado é respeitado sem consumir a próxima
entrada. O source continua pertencendo ao chamador.

Permissões continuam sendo aplicadas pelo extrator. O layout, bytes, tamanho
lógico e mapeamentos ELF não são modificados. RAM Fix e código de Wine/FEX/Box
permanecem como estavam. Descompactação e leitura dos zeros ainda têm custo;
esta alteração reduz o envio de dados para escrita, sem pular a validação do tar.

A revisão também reproduziu a aceitação de um XZ sem seus 12 bytes finais quando
havia bastante padding após o fim do tar. O extrator agora drena a compressão até
EOF antes de anunciar sucesso, verificando também o rodapé/CRC. O controle negativo
com tar de blocos de 128 KiB falha sem esse reparo. Um tar válido com esse mesmo
padding continua aceito; o primeiro caso pequeno já era recusado pelo fluxo antigo.

## Validação

O teste abre o asset original, extrai o preloader com o novo caminho e compara o
SHA-256 completo entre a entrada e o arquivo resultante. Continua lendo o restante
do arquivo tar/XZ até o fim. Nenhum executável é iniciado.

| Medida do preloader original | Resultado |
| --- | ---: |
| Tamanho lógico preservado | 2.099.266.128 bytes |
| Bytes submetidos para escrita pelo novo método | 82.512 bytes |
| SHA-256 da entrada e da saída | Idênticos |

Os 16 casos novos também cobrem zeros no começo/meio/fim, arquivo vazio, destino
com dados antigos, conteúdo binário, bloco final curto, leitura fragmentada,
tamanho acima de `int`, EOF, desconexão, escritas curtas/sem progresso e tamanho
inválido. Cinco testes do extrator Android em JVM conferem entrada grande seguida
por manifesto, GNU sparse com extensão final, tar truncado e rodapé XZ ausente/válido.

Suíte completa: 364 casos, 363 aprovados, zero falhas/erros e um ignorado pela
permissão de symlink no Windows. Java/Kotlin/recursos compilaram. Não houve geração
de APK/AAB/release. O teste foi feito no computador: tempo, alocação real e boot em
ext4/F2FS de um Android ainda precisam de medição. Os números acima não são uma
estimativa de tempo poupado ou de FPS.
