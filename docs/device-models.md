# Modelos Exynos

`ExynosDevices` é a tabela compartilhada pela comunidade, barra superior e HUD.
Famílias com variantes Snapdragon exigem um código regional reconhecido. Nomes
comerciais ou os três dígitos do modelo não bastam para classificá-las como Exynos.

- S26 SM-S942B/N, S26+ SM-S947B/N e S26 FE SM-S741B/N seguem a seleção explícita
  solicitada pelo mantenedor. Outros sufixos e o prefixo sem sufixo não fazem match.
  `/DS` e códigos de cor/armazenamento não mudam a variante regional inicial.
- S24 FE usa Exynos **2400e**, S25 FE usa **2400**, S26 FE usa **2500**.
  O HUD consulta o modelo antes do ID genérico do silício, para manter o `e` do 2400e.
  [Samsung S25 FE](https://images.samsung.com/is/content/samsung/assets/global/ir/docs/2025_3Q_Interim_Report.pdf)
  e [Samsung S26 FE](https://www.samsung.com/uk/smartphones/galaxy-s/galaxy-s26-fe-graphite-256gb-sm-s741bzkgeub/).
- S22 B permanece Exynos; S22 N não é incluído, porque a variante coreana usa
  Snapdragon. [Samsung Service S22](https://www.samsungsvc.co.kr/solution/639578).
- S24/S24+ N usam Exynos 2400; B permanece incluído. U/U1/W/0/E e Ultra não são
  classificados como Exynos por essa família. [Samsung Service S24](https://www.samsungsvc.co.kr/solution/1848050).
- Z Flip8 tem dois processadores conforme a região. A tabela aceita B e N;
  U/U1/W e modelos sem região continuam excluídos. A
  [comparação Samsung Alemanha](https://www.samsung.com/de/support/mobile-devices/vergleich-galaxy-z-fold8-ultra-vs-galaxy-z-fold8-vs-galaxy-z-flip8/)
  identifica SM-F776B/Exynos 2600. Para N, a identificação é uma inferência conjunta
  do [modelo coreano SM-F776N](https://www.samsung.com/sec/support/model/SM-F776NZWEKOO/)
  e do [lançamento coreano com Exynos 2600](https://www.samsung.com/sec/business/insights/news/news_260728_04/).
  A [Samsung Austrália](https://www.samsung.com/au/mobile/buying-guide/galaxy-z-flip8-features-specs/)
  confirma a existência de variantes Snapdragon; não se generaliza pela família.

## Auditoria das famílias anteriores — 01/11/2026

- S23 FE B/N = Exynos 2200/Xclipse 920. Conferidos o
  [produto europeu B](https://www.samsung.com/es/smartphones/galaxy-s/galaxy-s23-fe-graphite-256gb-sm-s711bzageub/)
  e o [suporte do modelo coreano N](https://www.samsung.com/sec/support/model/SM-S711NZOWKOO/).
  Variantes U/W não são incluídas. S22 B e S24 B/N mantêm a seleção regional acima.
- Z Flip7 = Exynos 2500/Xclipse 950 e Flip7 FE = 2400/940, conforme a
  [comparação oficial](https://www.samsung.com/uk/support/mobile-devices/galaxy-z-flip7-vs-galaxy-z-flip7-fe-comparison/).
  A [ficha americana do Flip7](https://image-us.samsung.com/us/smartphones/galaxy-z-flip7/pdf/B7_B2B_Spec_Sheet_R3Lc.pdf)
  também identifica o Exynos; a regra não é herdada pelo Flip8.
- A55/A37 = Exynos 1480/Xclipse 530; A56 e Tab S10 FE/FE+ = 1580/540;
  A57 = 1680/550. Fontes: [dispositivos Exynos](https://semiconductor.samsung.com/processor/showcase/smartphone/),
  [A37/A57](https://news.samsung.com/sg/samsung-unveils-galaxy-a57-5g-and-galaxy-a37-5g-packing-pro-level-features-at-awesome-price),
  [comparação A56/A57](https://www.samsung.com/fr/guide-achat-mobile/comparatifs/galaxy-a56-vs-a57/)
  e [Tab S10 FE](https://www.samsung.com/us/support/answer/ANS10005059/).
  GPUs conferidas nas fichas [1480](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1480/),
  [1580](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1580/)
  e [1680](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1680/).

Auditoria limitada às famílias presentes na tabela, sem adicionar aparelhos por
semelhança de nome. Testes incluem sufixos aceitos/recusados, códigos comerciais
completos, `/DS`, 2400e e formatos independentes de modelo/SoC/GPU.

Modelos não reconhecidos conservam a identificação real fornecida pelo Android.
A tabela não deve inventar um SoC Exynos para um modelo desconhecido.
