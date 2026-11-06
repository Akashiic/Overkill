# Resource pack

O pack fica em [`resourcepack/`](../resourcepack) e é gerado por
[`src/tools/.../PackGenerator.java`](../src/tools/java/com/akashic/overkill/pack/PackGenerator.java) a partir do
[`Arsenal`](../src/main/java/com/akashic/overkill/core/Arsenal.java), o mesmo código que o servidor joga.

```bash
./gradlew generatePack                        # manifestos sempre; arte só onde não existe
./gradlew generatePack --args="--force-art"   # reescreve a arte também (apaga o trabalho de um artista!)
./gradlew checkPack                           # o CI roda: manifestos iguais ao que o código gera, arquivos citados existem
```

## O que é código e o que é arte

| Arquivo | Tipo | Quem mexe |
|---|---|---|
| `assets/overkill/kunftig/item_models_3d.json` | manifesto | **gerado**: estados de cada golpe e Slash Art, `ao_atacar` com as janelas do servidor |
| `assets/overkill/kunftig/item_models.json` | manifesto | gerado: ícone 2D de cada lâmina |
| `assets/overkill/kunftig/displays.json` | manifesto | gerado: efeitos (Corte Dimensional, Onda, Corte Circular, espada invocada) |
| `pack.mcmeta` | manifesto | gerado |
| `geo/`, `animations/`, `textures/` | arte | gerada uma vez como ponto de partida; depois é do artista |

Os manifestos têm que acompanhar os golpes: um golpe novo, uma janela diferente ou uma lâmina nova mudam o
`ao_atacar` e os estados. Por isso o CI compara o pack commitado com o que o código gera.

## Trocar a arte (Blockbench)

A arte gerada é provisória: uma katana de cubos e coreografias simples para cada golpe. Para substituir:

1. Abra `geo/blade/katana.geo.json` no Blockbench (modelo **Bedrock**, geometria 1.12.0). Mantenha o osso raiz
   `katana` com a pega na origem e o osso `brilho` (é o que o manifesto lista em `luz` e `translucido`). Outros ossos
   são livres.
2. Animações: `animations/blade/katana.animation.json` (o item, osso `katana`) e `braco.animation.json` (o braço em
   terceira pessoa, osso `braco_direito`). Formato 1.8.0. **Um nome por estado** (`animation.katana.<estado>` e
   `animation.braco.<estado>`), com a **duração do golpe**: `(preparo + acerto + recuperação) / 20` segundos (tabela
   em [`COMBATE.md`](COMBATE.md)). O corte deve cair entre o fim do preparo e o fim do acerto, que é quando o servidor
   aplica o dano.
3. O GeckoLib Unofficial 1.0.4 trata `catmullrom` como linear: exporte "amostrado" (vários keyframes).
4. Texturas: `textures/blade/<lâmina>.png` (64×64 na katana gerada) e `textures/items/katana/<lâmina>.png` (ícone
   16×16 do inventário).
5. Depois de trocar, **não** rode `generatePack --force-art`. Rode `./gradlew checkPack`.

Para ajustar a posição na mão, use no jogo `/kunftigclient rods display <contexto> <rx> <ry> <rz> <tx> <ty> <tz> <escala>`
e copie o JSON impresso para o `display` em `PackGenerator.bladeModel`, depois `./gradlew generatePack`.

## Validação

* `./gradlew checkPack` (Overkill): manifestos atualizados e todo arquivo citado presente.
* No Künftig, com a pasta do pack: `./gradlew :test -ProdPack=<caminho>/resourcepack` roda o `RodPackCheckTest` e o
  `DisplayPackCheckTest`, que leem o pack com o mesmo parser do cliente e conferem cada arquivo, osso e animação. O
  pack gerado passa nos dois.
* Visual: o harness de desenvolvimento do Künftig tem o modo `blades` (arquivo `run/client/kunftig-rod-harness` com
  `blades`, o pack em `run/client/resourcepacks/Overkill` e selecionado em `options.txt`; `./gradlew runClient`).
  Ele tira capturas da katana em cada estado, das exibições, do HUD e da trava de câmera, e escreve um relatório com
  as conferências da previsão de golpe.
