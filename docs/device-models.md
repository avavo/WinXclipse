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
- Z Flip8 tem dois processadores conforme a região. A tabela mantém somente a
  variante B confirmada no mercado europeu; outras aguardam verificação específica.
  [Samsung Ireland](https://www.samsung.com/ie/smartphones/galaxy-z-flip8/) e
  [Samsung Australia, variantes regionais](https://www.samsung.com/au/mobile/buying-guide/galaxy-z-flip8-features-specs/).

Modelos não reconhecidos conservam a identificação real fornecida pelo Android.
A tabela não deve inventar um SoC Exynos para um modelo desconhecido.
