# Resource pack

O pack do Overkill fica em `resourcepack/` e sai como zip no CI (`overkill-resourcepack`). Tudo nele é gerado pelos
scripts de [`arte/`](../arte/) e conferido pelo `./gradlew checkPack`.

```
resourcepack/assets/overkill/
  kunftig/blades.json         as katanas, os efeitos e os movimentos, para o motor de lâminas do Künftig
  kunftig/item_models.json    o ícone de cada katana no inventário
  model/katana/<nome>.obj/png as cinco katanas (atlas 2048x1024 cada)
  model/efeito/*.obj/png      corte, julgamento, onda, espada, mira
  textures/gui/rank.png       o HUD do rank (512x512, no layout do SlashBlade)
  textures/gui/contador.png   o contador de golpes
  textures/items/katana/*.png os ícones (64x64)
  movimento/                  os VMD: na alfa vêm de um pack à parte (não versionados)
```

## Gerar

```bash
python3 -m venv .venv && .venv/bin/pip install numpy pillow bpy
.venv/bin/python arte/katanas/gerar.py     # katanas e blades.json
.venv/bin/python arte/efeitos/gerar.py     # efeitos e HUD
.venv/bin/python arte/katanas/icone.py     # ícones (Blender, sem tela)
```

Prévias no Blender: `arte/katanas/previa.py` (veja [`arte/katanas/README.md`](../arte/katanas/README.md)).

## Conferir

```bash
./gradlew checkPack
```

Falha quando falta um arquivo que o `blades.json` cita, um modelo não tem os grupos que o motor desenha (`blade`,
`blade_damaged`, `sheath`, `effect`, os ícones `item_*`; nos efeitos, `base`, `wind`, `ss`, os da mira), ou uma
katana não tem ícone. A falta dos VMD só avisa: na alfa eles vêm do pack separado.

## A alfa

Os movimentos da lâmina e do corpo (`movimento/motion.vmd` e `player_motion.vmd`) são, na alfa, os do SlashBlade:
arte com todos os direitos reservados, por isso ficam fora deste repositório (a pasta está no `.gitignore`) e vão
num pack separado, só para a alfa. Na beta entram as animações próprias, no mesmo formato.
