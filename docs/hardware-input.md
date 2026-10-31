# Mouse físico e teclado: investigação da issue #5

Relato: [issue #5](https://github.com/avavo/WinXclipse/issues/5), mouse USB e
teclado Bluetooth no S23 FE. Com mouse relativo ativo, o teclado deixa de responder
depois de alguns segundos; os dois botões do mouse executam a mesma ação. Sem
mouse relativo, o teclado responde, mas a câmera não acompanha o mouse.

## Falhas identificadas no código

- A detecção de controles aceitava qualquer bit comum de `SOURCE_GAMEPAD` e
  `SOURCE_JOYSTICK`. Os IDs de fontes incluem bits compartilhados de classe: um
  teclado com setas podia ser anunciado como controle e ter suas teclas consumidas
  pelo caminho de gamepad. Agora são exigidos os IDs completos, tanto do aparelho
  quanto do evento; um dispositivo composto ainda pode enviar teclas de teclado.
- `ACTION_DOWN` físico sempre inferia clique esquerdo, inclusive quando o evento
  informava `BUTTON_SECONDARY`. O estado agora distingue esquerdo, direito e meio,
  combinações, vários dispositivos e as duas formas de eventos usadas pelo Android.
  O clique direito enviado como `KEYCODE_BACK` tem propriedade separada dos eventos
  de movimento, para não ser solto ao mover um mouse que omite `buttonState`.
- Coordenadas de mouse capturado são deltas, mas recebiam a translação da tela.
  Agora usam apenas escala/rotação, incluindo o histórico dos eventos e um acumulador
  de frações. Fora da captura, o caminho relativo calcula diferenças entre posições
  sucessivas sem gerar um salto na primeira amostra. A roda usa unidades Win32 de 120.
- Havia recapturas atrasadas independentes e tentativas ilimitadas de foco. Agora há
  uma solicitação cancelável, com tentativas limitadas e confirmação pelo callback do
  Android. Pausa, perda de foco, sidebar, editor e teclado virtual impedem a captura;
  uma concessão atrasada é liberada se a tela já não estiver elegível.
- Mudanças de foco/sidebar liberam teclas e botões físicos pendentes no Wine.
  Teclas de fornecedores fora do mapa são ignoradas com segurança, e soltar uma
  letra não solta Shift enquanto o Android ainda o informa pressionado.

## Validação e limites

Compilação Java/Kotlin/recursos e 245 casos JUnit: zero falhas, um caso ignorado
por permissão de symlink no Windows. Os 16 novos casos verificam fontes de input,
eventos duplicados, cliques distintos, combinações, propriedade entre dispositivos,
tecla Back versus movimento, liberação de estado e transformação/acúmulo de deltas.
Traduções PT/ES conferidas: 1132 strings e 11 arrays. Nenhum APK foi gerado.

Não há aparelho Android conectado. A interrupção após alguns segundos não foi
reproduzida fisicamente; a issue permanece aberta. Para confirmar: repetir o RE7
com USB + Bluetooth por uma sessão prolongada, verificar câmera, movimentação e
cliques simultâneos, abrir/fechar a sidebar, alternar apps e usar o teclado virtual.
Repetir com teclado USB e um controle real para verificar a classificação.

Referências primárias:

- [Android InputDevice: IDs e classes de fontes](https://developer.android.com/reference/android/view/InputDevice).
- [Android: captura de ponteiro e deltas relativos](https://developer.android.com/develop/ui/views/touch-and-input/gestures/movement#pointer-capture).
- [Win32 MOUSEINPUT: botões e unidades da roda](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-mouseinput).
