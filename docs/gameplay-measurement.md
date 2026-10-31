# Medição de gameplay

Em Configurações do HUD, habilitar o botão de medição e escolher as métricas.
Um toque começa, outro termina e expande o resultado. O botão é arrastável;
segurar o resultado abre o relatório completo e a ação Salvar TXT. O salvamento
automático é opcional, em Download/WinXclipse, com arquivo UTF-8 e nome único.

O relógio é System.nanoTime para frames, amostras e duração. Segundo plano e
pausa manual da sessão fecham o segmento atual. Ao retomar, os contadores de
frametime e os endpoints da bateria recomeçam, sem somar o tempo/consumo da pausa.
Fechar a sessão conclui uma medição em curso e aplica o salvamento escolhido.

## Frames

- Fonte: atualizações de conteúdo de janelas X11 de aplicação. Não é inferido por
  multiplicador de FG, frequência de tela ou número de chamadas de redraw de cursor.
- Saída: desenho concluído pelo compositor quando há conteúdo novo ou um quadro
  gerado. Os quadros gerados têm contagem separada. Não são timestamps físicos de scanout.
- Média: quantidade de quadros observados / tempo ativo medido, incluindo stalls.
- Mínimo/máximo: inverso dos intervalos maior/menor entre atualizações reais.
- 1% low: inverso da média do 1% mais lento dos intervalos, arredondando a
  quantidade para cima. Histograma limitado, com resolução de 0,1 ms até 1 s,
  1 ms até 60 s e um bucket para stalls maiores; cada bucket guarda a soma real.
  Seleção parcial dentro de um bucket usa a média desse bucket. Não guarda uma
  lista ilimitada de todos os frames de uma sessão longa.

Sem intervalos suficientes, low/min/max ficam N/A. Há testes para FPS constantes,
stalls longos, quadros gerados separados, amostras repetidas e pausas.

## Bateria

Potência é descarga líquida da bateria do aparelho, não consumo exclusivo do app.
A energia integra W pelo tempo (Wh); W e Wh não são intercambiáveis. A média usa
somente a duração com amostras válidas, informada separadamente no relatório.
Intervalos entre sensores maiores que 5 s não são integrados. No encerramento,
a última amostra pode cobrir no máximo 3 s por retenção do último valor.

Consumo em porcentagem/mAh usa endpoints por segmento, sem acumular oscilações
do fuel gauge. Exige duas leituras e endpoints próximos do início/fim; ausência
ou carregamento observado tornam o consumo indisponível. Misturar uma carga
medida com outra estimada também não produz uma subtração apresentada como real.
Estimativas por capacidade/porcentagem são marcadas com ≈.

O relatório guarda aparelho, runtime, driver, wrapper, resolução, tradutores,
configuração gráfica e FG. Metadados são capturados ao começar. No Android 10+
a publicação usa MediaStore Downloads com IS_PENDING; nos anteriores, arquivo
temporário seguido de renomeação, com erro explícito se faltar acesso.

Compilação e testes de cálculo não substituem a validação do botão, escrita no
MediaStore e sensores em aparelho real. Não há aparelho ADB conectado neste lote.
