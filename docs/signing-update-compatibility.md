# Compatibilidade de atualização 0.9.5 → 0.9.6

O APK fornecido em Downloads foi inspecionado com apksigner/aapt em 01/11/2026:

| Campo | 0.9.5 fornecida | Build local de teste 0.9.6 |
| --- | --- | --- |
| applicationId | com.win.xclipse | com.win.xclipse |
| versionCode | 26 | 27 |
| Certificado SHA-256 | e93877205c6b7ef5c9171bcb2c075d4916307b5d6a2276f3599b55d2e830164c | 5e95ff1915c8414e9ba59e1ecd1a01b72fe632c3d3c8e6e9d0c38027a83ffe9d |

A versão e o identificador permitem atualizar, mas os certificados são diferentes.
Para atualizar diretamente a 0.9.5 preservando os dados, a compilação precisa
ser assinada com a chave privada correspondente à coluna 0.9.5 (Android Debug).
A chave não foi encontrada nos diretórios locais pesquisados. O certificado
público dentro do APK não contém essa chave privada. Não se deve apresentar
uma build assinada pela chave local como compatível com a 0.9.5 original.

O build aceita o caminho por KEYSTORE_STORE_FILE ou keystore.storeFile em
local.properties. Alias e senhas usam KEYSTORE_KEY_ALIAS, KEYSTORE_STORE_PASSWORD
e KEYSTORE_KEY_PASSWORD, ou as propriedades locais equivalentes já existentes.
Nenhuma chave ou senha deve ser incluída no Git.

No código, a atualização da base conserva home, opt e contents (pacotes de
Wine/Proton, emuladores, DXVK/VKD3D e drivers), com teste de regressão. O arquivo
imagefs.tzst foi listado: ele não contém dados de prefixos de usuário ou contents.
Isso cobre a preservação no fluxo de manutenção testado; a atualização Android
real permanece pendente da assinatura original e de validação no aparelho.

## Busca adicional de recuperação — 01/11/2026

Após o usuário informar que não sabe onde a versão antiga foi compilada, a
busca foi ampliada ao disco C:, incluindo nomes sem extensão. Nenhum material
de assinatura compatível foi identificado. A instalação local de teste já usa
a chave estável nova, que permite atualizar as builds de teste entre si.

Também foram consultadas as oito execuções históricas do GitHub Actions e suas
árvores de código, inclusive commits ausentes dos refs locais. Todas contêm
somente testkey_untrusted.jks, cujo certificado é B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1,
diferente do certificado E9387720… da 0.9.5 fornecida. Os workflows arquivam
somente APKs; não há secrets de Actions nem caches disponíveis nesse repositório.
Esses caminhos de recuperação não forneceram a chave privada original.
