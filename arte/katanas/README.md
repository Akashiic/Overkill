# Katanas do Overkill (arte própria)

As katanas do servidor são geradas por código: geometria, UV e textura saem dos scripts desta pasta, sempre iguais.
Nenhum arquivo do SlashBlade é usado. Do SlashBlade vêm só as **medidas de encaixe**, para as animações dos combos
caírem no lugar certo: a unidade (1/128 de bloco), a ponta em `x = -291`, o pomo em `x = +41,7`, o fio em `+Y` e os
nomes dos grupos que o motor de lâminas do Künftig lê.

| Katana | Ideia | Brilho | Cor dos efeitos |
|---|---|---|---|
| `aco` | a inicial: aço polido, hamon notare, cabo azul-escuro, laca preta | não | `0x3333FF` |
| `serpente` | aço esverdeado com escamas no ji, hamon midare, guarda com serpente enrolada, bainha de pele de cobra | não | `0x33CC77` |
| `sakurajima` | o vulcão: aço escuro com veios de magma, fio incandescente, pétalas de sakura virando brasa na bainha | lâmina, bainha, guarda | `0xFF6A2A` |
| `raijin` | o deus do trovão: aço azulado com relâmpagos, guarda mokko com mitsudomoe, bainha índigo com raio dourado | lâmina, bainha | `0x55AAFF` |
| `kusanagi` | Kusanagi no Tsurugi: reta e de dois fios, filete de ouro, guarda com o espelho, bainha branca com nuvens de ouro | lâmina, bainha, guarda | `0xFFD966` |

## Gerar

```bash
python3 -m venv .venv && .venv/bin/pip install numpy pillow
.venv/bin/python arte/katanas/gerar.py            # todas
.venv/bin/python arte/katanas/gerar.py raijin     # uma
```

Saída: `resourcepack/assets/overkill/model/katana/<nome>.obj` e `.png` (atlas 2048 x 1024) e
`resourcepack/assets/overkill/kunftig/blades.json` (o modelo de cada uma é `overkill:katana/<nome>`).

Prévias no Blender (o módulo `bpy` do pip, sem tela; Cycles na CPU):

```bash
.venv/bin/pip install bpy
.venv/bin/python arte/katanas/previa.py resourcepack/assets/overkill/model/katana/aco.obj \
    resourcepack/assets/overkill/model/katana/aco.png /tmp/aco.png
```

## Arquivos

* `geometria.py`: a malha (grades com normais suaves, tampas, faixas, cópia transformada) e a escrita do OBJ.
* `katana.py`: as partes (lâmina shinogi-zukuri ou tsurugi com kissaki, habaki, seppa, tsuba com aro e lóbulos,
  tsuka com o relevo do ito, fuchi, kashira, menuki, saya com koiguchi, kojiri, kurikata e sageo), a versão quebrada,
  a casca do brilho de carga, as camadas que brilham e os ícones (de uma versão leve da mesma katana). O mapa do atlas
  (`ATLAS`) fica aqui.
* `textura.py`: o pintor do atlas, região por região, nas mesmas coordenadas da geometria: hamon com nie, ashi e
  nioi, hada itame, shinogi-ji polido, yokote, seda do cabo sobre a pele de arraia, ferro martelado, cobre com
  neko-gaki, laca, cordão trançado.
* `katanas.py`: as cinco katanas (medidas, paleta e os padrões próprios de cada uma).

## Grupos

| Grupo | O que é | Quando o motor desenha |
|---|---|---|
| `blade` | a espada inteira (lâmina, habaki, seppa, tsuba, tsuka) | na mão |
| `blade_damaged`, `blade_fragment` | a espada quebrada em `x = -110` e o pedaço | quebrada |
| `sheath` | a bainha com o cordão | na cintura / nos golpes |
| `effect` | a casca em volta da lâmina | brilho de carga (com a textura do creeper) |
| `item_blade`, `item_bladens`, `item_damaged` | ícones na diagonal (versão leve) | inventário, chão, moldura |
| `*_luminous` | as mesmas superfícies, um fio acima, com a região que brilha do atlas | passe aditivo, sem luz |

O passe aditivo soma a cor da textura: o que não deve brilhar é pintado de preto nas regiões `lamina_luz`,
`saya_luz` e `tsuba_luz`.

## Animações

Na alfa, os combos usam os movimentos (VMD) do SlashBlade, que **não ficam no repositório**: o pack da alfa coloca
`assets/overkill/movimento/motion.vmd` e `player_motion.vmd` (a pasta está no `.gitignore`). Na beta entram as
animações próprias, no mesmo formato.
