# Efeitos e HUD do Overkill (arte própria)

`gerar.py` gera os modelos e as texturas dos efeitos e do HUD, todos nossos. O motor que os desenha é o do Künftig
(o do SlashBlade portado, MIT); daqui só saem os arquivos, seguindo as convenções funcionais dele:

| Efeito (`efeitos` do blades.json) | Arquivo | Convenção do motor | O nosso |
|---|---|---|---|
| `corte` | `model/efeito/corte.obj/png` | anel r 54..100 no plano XZ, seção em lente com alfa 0 nas bordas (y = 0), U = ângulo, V = raio; o motor varre a textura na vertical durante a vida | 72 segmentos (o do SB tem 32); fios de energia afiados, núcleo claro e linhas de velocidade |
| `julgamento` | `model/efeito/julgamento.obj/png` | esfera r 100 (`base`) e o crescente de vento no plano XY (`wind`) | esfera 32x18; vento com fios e borda clara |
| `onda` | `model/efeito/onda.obj/png` | crescente no plano XZ abaulado para +Z (`base`) | a borda da frente mais forte, rastros para trás |
| `espada` | `model/efeito/espada.obj/png` | ao longo de +Z, ponta em z = +300, largura em X (`ss`) | uma katana espectral curva, com fios claros |
| `mira` | `model/efeito/mira.obj/png` | `lockonBase`, `lockonHealth`, `lockonHealthMask` (a rampa de z +0,69 a -10,06 que esconde o anel conforme a vida) | quatro colchetes em V e um losango, anel de vida em doze gomos; a textura dá o brilho suave nas faixas |
| `rank` | `textures/gui/rank.png` | layout do rank.png do SlashBlade: letras D..SSS em células 64x32 (cheia, contorno, e as duas da variante de subida), a barra embaixo | 512x512 (o dobro), letras em metal com cor por rank; a variante de subida em vermelho com brilho |
| `contador` | `textures/gui/contador.png` | dígitos 0..9 em 32x48 a partir de x 0, a palavra HITS em (320, 4) 112x40, em 512x64 | o contador de golpes estilo DmC |

```bash
.venv/bin/python arte/efeitos/gerar.py
```

Depois de mudar a arte, rode também `arte/katanas/gerar.py` se os nomes dos efeitos mudarem (ele escreve o
`blades.json`).
