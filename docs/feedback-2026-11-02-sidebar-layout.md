# Ajustes dos cinco prints — 2026-11-02

1. Os imports de presets e as engrenagens na página Configurações usam apenas o
   ícone e o ripple transparente. Os contornos menores continuam nos campos de
   container/shortcut e nos downloads contextuais.
2. Sair, Pausar e Ajuda conservam o mesmo peso, altura de 48 dp, padding e escala
   centerInside. A regra que dimensiona ajuda dos switches estava também
   substituindo os LayoutParams do botão Ajuda do rodapé, removendo seu peso.
   Agora atua nas linhas de opções com switch ou editor de valor; o rodapé e os
   rótulos conservam suas dimensões.
3. Painéis de HUD tem 12 dp de intervalo até o seletor de aparência; este tem
   outros 8 dp até Mostrar HUD. O espaçamento continua após o estilo final.
4. O limitador usa ContentDialog no host da barra lateral, como as outras páginas.
   Os presets e o campo personalizado ficam na mesma página. As alterações são
   aplicadas enquanto o usuário edita; X/voltar confirma a edição válida mais
   recente. O botão do tile continua ligando/desligando o limite, e escolher um
   valor quando desligado conserva esse estado. Valores personalizados aceitam
   15–240 FPS; entrada inválida mantém o último valor salvo.
5. Ajuda do Wrapper fica no rótulo, com tamanho de legenda. Download e engrenagem
   têm os mesmos 48 dp e a engrenagem compartilha a coluna dos outros campos.
   A ajuda do Wrapper DX também fica centralizada no rótulo, inclusive no atalho.

Os testes medem a interface depois de PopupStyle/ControlStyle serem aplicados
repetidamente, para cobrir a ordem real que causou a regressão. Conferem alvos
iguais, glifos dentro do padding, intervalos do HUD, colunas/centros em PT-BR com
fonte normal/aumentada e o editor FPS incorporado, incluindo valor personalizado,
validação e confirmação por X/voltar. O teste dos switches avançados conserva a
coluna da ajuda do seletor de limite de VRAM.

Este lote não modifica o transporte DisplayX e não executa o RE9.

Validação: 573 testes JVM, 571 aprovados, dois skips de symlink no Windows,
sem falhas/erros. APK compilado e assinado para testes locais.
