# Verificação local do FSR

Extrai os shaders literais de EASU/RCAS e o vertex shader dos fontes Java ativos,
com RCAS em 0.8 stops. Não usa uma segunda cópia dos shaders. O exportador falha
se encontrar concatenações que não conhece; rever a extração ao mudar o formato
dos fontes. O JSON gerado não é versionado.

Na raiz do repositório:

```sh
python tools/fsr-qa/export_shaders.py
python -m http.server 8767 --bind 127.0.0.1 --directory tools/fsr-qa
```

Abrir `http://127.0.0.1:8767/`. A página compila/linka os shaders num contexto
WebGL, desenha e lê os pixels. Confere imagem constante com letterbox, limites
de amostragem e RCAS contra cálculo CPU baseado na referência FSR1. Um controle
sem o novo clamp precisa reproduzir a falha nas bordas; o teste não aceita passar
com uma cena incapaz de distinguir o comportamento anterior.

Resultado esperado: oito verificações aprovadas. O teste detecta erros GLSL,
uniforms e resultados numéricos nesse contexto. Não mede desempenho, Vulkan,
frame generation ou comportamento do driver Android.

Referência: [AMD FSR1](https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/master/ffx-fsr/ffx_fsr1.h).
