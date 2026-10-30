# Interface de console — reservada para a 1.0.0

A interface nova está preservada neste diretório, com o código e os recursos
do commit `57a049e`. Ela não foi descartada e não precisa ser recuperada do
histórico para continuar o desenvolvimento.

## Build 0.9.6

`consoleUi` não é um flavor, build type nem source set registrado no Gradle.
Portanto, `java/`, `res/` e `AndroidManifest.xml` daqui não participam da
compilação, do merge de recursos ou do manifest da 0.9.6. A build continua
usando a interface clássica de `src/main`, sem entrada no menu para a nova UI.

## Conteúdo preservado

- `java/com/winlator/cmod/home/`: as 11 telas/componentes Kotlin originais.
- `res/`: fontes, drawables, licenças, cores, textos e os layouts de menu e
  sidebar da interface nova. Os nomes originais dos recursos foram mantidos.
- `AndroidManifest.xml`: declaração da `HomeActivity` para o futuro merge.
- `integration.patch`: mudanças originais de integração em `MainActivity`,
  `XServerDisplayActivity`, artwork/SteamGridDB e configuração Kotlin/Compose.
  É um patch de referência de `018bbac` para `57a049e`, ainda não aplicado.

## Retomar na 1.0.0

Ao concluir a interface, registrar este source set (código, recursos e
manifest), reativar Kotlin/Compose e revisar a integração de navegação usando
`integration.patch`. Preservar as correções de RAM Fix feitas depois do commit
de referência. O patch não deve ser aplicado à build 0.9.6.
