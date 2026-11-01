# Progresso do primeiro início

O boot1 executa a preparação do ImageFS/Proton e, depois, a instalação dos WCPs
embutidos. A segunda etapa não encaminhava o callback de progresso do
ContentsManager para o diálogo. A cópia inicial de cada WCP também não publicava
progresso.

O ImageFS agora usa o PreloaderDialog. Antes do primeiro contador de bytes, a
janela fica indeterminada, sem mostrar 0%. A extração mantém os percentuais por
bytes comprimidos; a confirmação da instalação reserva a conclusão em 100%.

Os WCPs dividem o progresso total por pacote: cópia inicial, extração/validação e
confirmação. O callback de instalação atualiza a janela, e o pacote só termina
quando finishInstallContent informa sucesso. A etapa final usa a mensagem de
finalização já traduzida.

O PreloaderDialog guarda atualizações recebidas antes da criação da janela.
Ao mostrá-la, aplica o valor pendente sem reiniciar em zero. Texto e indicador
são atualizados juntos; ao fechar, o estado é limpo para a próxima operação.

Verificação: regressões da janela com atualização vinda de uma thread antes de
show, preparação indeterminada, atualização do anel/texto e reabertura. O teste
de extração Zstandard verifica callbacks intermediários, contagem de bytes e
conteúdo extraído. Os testes do coordenador ImageFS verificam reparo, recibos e
preservação dos dados.

Resultado: 41 testes aprovados, sem falhas ou skips, e APK compilado.

No S24+, uma leitura dos assets do APK instalado confirmou tamanhos disponíveis
válidos para imagefs.tzst, Proton, FEX e Box64. O ensaio nativo separado de
extração foi encerrado pelo sistema e não é tratado como validação do boot1
completo. Nenhum dado do app foi apagado nem uma reinstalação foi forçada.
