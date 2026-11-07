# Materiais da forja

`forja.py` gera os ícones dos materiais da `/forja` (veja [`docs/PROGRESSAO.md`](../../docs/PROGRESSAO.md)), todos
nossos:

| Ícone | Como é feito |
|---|---|
| Pedras de afiar (`pedra_<tier>`) | Blender: um toishi de duas camadas (bloco chanfrado, deslocado por ruído), grão e manchas na textura procedural, a face de cima lisa e molhada; dos épicos para cima, veios (bordas de um voronoi) que emitem luz na cor do tier, e pontos de mineral nos raros |
| Tamahagane | Blender: três torrões (icosferas deslocadas por voronoi, decimadas e com sombreamento chapado, as facetas do aço) metálicos, com a cor pela fresnel indo do aço ao azul, ao violeta e ao dourado nas bordas |
| Ofudas (`ofuda_<pct>`) | Pillow: papel com fibras, moldura dupla vermelha, pinceladas de ponta afinada e o selo do percentual na cor do tier (o de 100% brilha), arqueado e na diagonal |

Tudo é feito em 512 px, recortado, centralizado e reduzido para 64 x 64 (como os ícones das katanas). Os PNG vão para
`resourcepack/assets/overkill/textures/items/forja/` e os modelos em `kunftig/item_models.json`.

```bash
python forja.py              # todos (bpy, numpy, pillow)
python forja.py tamahagane   # só o tamahagane
```
