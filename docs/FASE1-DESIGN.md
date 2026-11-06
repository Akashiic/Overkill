# Fase 1: design da fatia vertical (katana 3D, 3 golpes, sem canal novo)

Data: 2026-11-06. Continuação de [`ESTUDO.md`](ESTUDO.md), seção 8.

**Estado deste documento: rascunho de design.** Não há código. Tudo o que é valor numérico abaixo é **valor de partida, a calibrar no jogo**. Tudo o que depende de comportamento do Künftig que a documentação não descreve está marcado **a verificar**. A fonte de verdade para a sintaxe exata do manifesto é o pack do AkashicFishing (`tools/rod3d` e `tools/peixe3d`) e o código do Künftig, que ainda não foram lidos para este documento.

> **Histórico.** Este foi o design da primeira fatia. A implementação foi além: previsão de golpe no cliente, canal de
> entrada, slash arts, rank e lock-on já existem (veja o [`README`](../README.md) e [`COMBATE.md`](COMBATE.md)).
> Correções ao rascunho abaixo: o manifesto precisa da chave `"modelos"` em volta dos modelos; `transicao_ticks` usa o
> controlador `"vara"` (com `"modelo"` a mistura é ignorada); o item base escolhido foi a espada de ouro, com todas as
> espadas listadas em `itens`; `kills` é INTEGER; o golpe vanilla é cancelado para itens-lâmina.

---

## 1. Objetivo e escopo

**Objetivo:** provar o desenho completo (item 3D na mão, estados animados vindos do servidor, combo encadeado, dano no servidor) com o menor conjunto possível de peças, e **medir a latência do modo 100% servidor**.

**Dentro do escopo**
- Uma katana ("básica") com modelo GeckoLib na mão, em 1ª e 3ª pessoa, no chão e na moldura.
- Estados: `sacar`, `parada`, `golpe_1`, `golpe_2`, `golpe_3`.
- Combo de 3 golpes com janela de encadeamento, resolvido no servidor.
- Estado persistente mínimo da lâmina via PDC (id e kill count).
- Fallback: cliente sem Künftig/GeckoLib vê a espada vanilla e o dano funciona igual.

**Fora do escopo (fases seguintes):** canal de entrada, previsão local de golpe, slash arts, rank de estilo, lock-on, HUD, dual wield, projéteis.

**Mudança em relação ao `ESTUDO.md`:** a previsão local de golpe não existe no Künftig hoje (lacuna 2). Nesta fase o cliente **não** prevê nada: a animação só toca quando o servidor manda o estado. Isso é intencional, para medir o pior caso. Se a sensação for ruim, a previsão local vira a primeira tarefa da fase 2.

## 2. Nomes provisórios

| Item | Valor provisório | Observação |
|---|---|---|
| Namespace | `overkill` | Decisão em aberto (ver `ESTUDO.md`, seção 9) |
| Modelo | `overkill:katana/basica` | Chave sem sufixos, como no manifesto |
| Item base do vanilla | a decidir | Define em quais itens o renderer se registra (`itens`). Escolher um item que o servidor não use para outra coisa |
| PDC | `overkill:blade_id`, `overkill:kills` | `NamespacedKey(plugin, ...)` |

## 3. Peças e responsabilidades

```
Clique esquerdo do jogador (cliente)
   -> evento Bukkit no servidor (a confirmar: PlayerInteractEvent / PlayerAnimationEvent)
   -> plugin: máquina de combo decide o golpe e a janela
   -> KunftigRods.setState(player, "golpe_N")        (canal kunftig:rod)
   -> clientes com Künftig+GeckoLib tocam a animação
   -> tick do golpe ativo: plugin calcula o setor de acerto e aplica dano
```

| Camada | Faz | Não faz |
|---|---|---|
| Plugin | combo, janela, cooldown, acerto, dano, PDC, estados | desenho |
| Künftig | transporte de estado, renderer GeckoLib, manifesto | decidir golpe |
| Resource pack | geometria, animações, texturas, manifesto | lógica |

## 4. Manifesto do resource pack (rascunho)

Arquivo: `assets/overkill/kunftig/item_models_3d.json`. Campos conforme `docs/rodmodels/README.md` do Künftig. **A verificar:** se, para um modelo que não é vara, `estados.<s>.modelo` substitui por completo o `vara` obrigatório, e o nome exato de cada campo, contra um pack real de peixe do AkashicFishing.

```json
{
  "_formato": 1,
  "overkill:katana/basica": {
    "itens": ["minecraft:iron_sword"],
    "geo": "overkill:geo/katana/basica.geo.json",
    "animacoes": "overkill:animations/katana/basica.animation.json",
    "textura": "overkill:textures/katana/basica.png",
    "braco": "overkill:animations/katana/basica_braco.animation.json",
    "estados": {
      "sacar":   { "modelo": "animation.katana.sacar",   "braco": "animation.braco.sacar" },
      "parada":  { "modelo": "animation.katana.parada",  "braco": "animation.braco.parada", "laco": true },
      "golpe_1": { "modelo": "animation.katana.golpe_1", "braco": "animation.braco.golpe_1" },
      "golpe_2": { "modelo": "animation.katana.golpe_2", "braco": "animation.braco.golpe_2" },
      "golpe_3": { "modelo": "animation.katana.golpe_3", "braco": "animation.braco.golpe_3" }
    },
    "luz": ["lamina_brilho"],
    "translucido": ["lamina_brilho"],
    "transicao_ticks": { "modelo": 2 },
    "pega": [0, 0, 0],
    "display": {
      "primeira_pessoa": { "rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": 1.0 },
      "terceira_pessoa": { "rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": 1.0 },
      "chao":            { "rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": 1.0 },
      "moldura":         { "rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": 1.0 }
    }
  }
}
```

Regras do Künftig que valem para os estados (documentadas): nome em `a-z`, `0-9`, `_`, até 32 caracteres; estado que não é laço toca uma vez e volta a `parada`; o mesmo estado de novo recomeça a animação; vale o evento mais recente.

Ajuste dos valores de `display`: `/kunftigclient rods display <contexto> ...` imprime o JSON pronto no chat e no log. **A verificar** se o comando atua em itens que não são varas. Validação do pack: `./gradlew :test -ProdPack=<pasta do pack>`.

### Arte mínima da fase 1
- 1 geometria Bedrock (katana com bainha opcional; osso `lamina_brilho` para `luz`/`translucido`).
- 5 animações do item e 5 animações de braço (`sacar`, `parada`, `golpe_1`, `golpe_2`, `golpe_3`).
- 1 textura. Sons de golpe (opcional, via resource pack).

## 5. Definição da lâmina (plugin)

Arquivo de configuração do plugin (formato proposto; os valores são de partida).

```json
{
  "id": "overkill:katana/basica",
  "item_base": "IRON_SWORD",
  "modelo": "katana/basica",
  "dano_base": 6.0,
  "combo": {
    "janela_ticks": 14,
    "passos": [
      { "estado": "golpe_1", "inicio_ticks": 2, "ativo_ticks": 2, "recuperacao_ticks": 6, "mult_dano": 1.0, "alcance": 3.0, "arco_graus": 100 },
      { "estado": "golpe_2", "inicio_ticks": 2, "ativo_ticks": 2, "recuperacao_ticks": 6, "mult_dano": 1.1, "alcance": 3.0, "arco_graus": 110 },
      { "estado": "golpe_3", "inicio_ticks": 3, "ativo_ticks": 2, "recuperacao_ticks": 10, "mult_dano": 1.6, "alcance": 3.4, "arco_graus": 140 }
    ]
  }
}
```

### Máquina de combo (por jogador, no servidor)

| Estado interno | Evento | Próximo |
|---|---|---|
| `OCIOSO` | clique | `PASSO_1`: envia `golpe_1`, agenda o tick ativo |
| `PASSO_N` (em recuperação) | clique dentro de `janela_ticks` | `PASSO_N+1`, ou `PASSO_3` termina e volta a `OCIOSO` |
| `PASSO_N` | `janela_ticks` expira sem clique | `OCIOSO` (próximo clique recomeça no golpe 1) |
| qualquer | troca real de slot (`isHotbarSwitch`) ou desconexão | `OCIOSO`, limpar agendamentos |
| `PASSO_N` (em início/ativo) | clique | ignorado (anti-spam; cooldown por golpe) |

Regras: um único golpe em andamento por jogador; tudo na thread principal; timers por tick do servidor (20 ticks/s).

### Acerto e dano (no tick ativo)
- Entidades vivas num setor: distância até `alcance` e ângulo dentro de ±`arco_graus/2` da direção do olhar, excluindo o próprio jogador.
- Dano = `dano_base * mult_dano`, aplicado como dano causado pelo jogador (para os eventos de dano de outros plugins e mods ainda funcionarem).
- **Risco de dano duplo:** o clique pode também causar o golpe vanilla. Decidir se o evento vanilla é cancelado para itens-lâmina. **A verificar** no jogo.
- **A confirmar contra o Bukkit 1.7.10 do Crucible** (compilar com o jar gerado por `scripts/build-legacy-bukkit-api.sh` do Künftig e o gate `checkLegacyBukkitApi`): nomes e assinaturas de `PlayerInteractEvent`/`Action`, `PlayerAnimationEvent`, `EntityDamageByEntityEvent`, `LivingEntity#damage` e `Entity#getNearbyEntities`. Uma busca por javadoc só devolveu versões a partir da 1.8.8, então nada foi assumido como verificado para a 1.7.10.

### Estado persistente (PDC)
- `overkill:blade_id` (STRING) e `overkill:kills` (LONG ou INTEGER, a decidir).
- Pilha "do servidor" só guarda dados: usar `KunftigItems.asDataCapable(item)`; usar `KunftigItems.setItemMeta` para não perder NBT com `ItemMeta` vazio (exceção herdada do Cauldron, documentada no `API.md`, seção 4).
- Item com modelo 3D: `KunftigItems.setModel(item, new NamespacedKey(plugin, "katana/basica"))` (API nível 4).

### Verificação no `onEnable`
- Mod presente (`Class.forName("com.akashic.kunftig.api.KunftigApi")`).
- `KunftigApi.apiVersion() >= 7` e `KunftigApi.isRodsAvailable()` para estados; `KunftigApi.isItemDataAvailable()` para PDC.
- `KunftigRods.drawsRodModels(player)` por jogador para saber se ele vê o 3D.
- **A verificar:** como o ciclo automático de `sacar`/`lancar`/`parada` do cliente (feito para a vara) se comporta com um item que não é vara. Se `lancar` disparar no clique direito ou esquerdo e atrapalhar o combo, isso vira uma tarefa de ajuste no Künftig (lacunas 2 e 3 do `ESTUDO.md`).

## 6. Critérios de aceite

1. A katana aparece em 3D na mão (1ª e 3ª pessoa), no chão e na moldura, em quem tem Künftig + GeckoLib + pack. Quem não tem vê a espada vanilla e o dano funciona.
2. Três cliques dentro da janela produzem `golpe_1`, `golpe_2`, `golpe_3` em sequência; fora da janela recomeça no `golpe_1`.
3. O dano é aplicado só pelo plugin (sem dano duplo), com alcance e arco corretos; mobs vanilla e pelo menos um mob de mod testados.
4. Outro jogador a até 96 blocos vê a animação; quem chega no meio vê o estado no ponto certo.
5. Troca de slot e cliques do Backhand não corrompem a máquina de combo.
6. Sem erros no log do cliente (`Kunftig 3D rods: ...`) nem no servidor.
7. Medição de latência (clique até o início visível da animação) com ping simulado baixo, médio e alto. Registrar os números neste repositório.

## 7. Plano de teste

- Harness do Künftig: `/kunftigclient rods ...` (estado e display, sem servidor) para ajustar poses; harness visual de desenvolvimento (`dev/RodRenderHarness`) para capturas. **A verificar** o que cada comando cobre para itens que não são varas.
- Teste a dois jogadores, um com Künftig e outro sem.
- Ping variado com `KunftigPlayers.getPing(player)` para correlacionar com a sensação.
- Desempenho: dois a quatro jogadores golpeando ao mesmo tempo, observando TPS e quadros.

## 8. Rascunho para a fase 2: canal de entrada (`kunftig:input`)

Só um esboço, para orientar a decisão. O formato definitivo deve seguir o estilo de `docs/nametags/PROTOCOL.md` do Künftig.

- Sentido: cliente → servidor. Mensagem crua num canal próprio, como `kunftig:rod` e `kunftig:holo`.
- Conteúdo por mensagem: id da ação (golpe, guard, dash, special, lock-on), pressionar ou soltar, número de sequência, tick do cliente, yaw e pitch.
- O servidor é a autoridade: valida frequência (limite por jogador), descarta ações impossíveis para o estado atual e responde com o estado oficial (o cliente corrige se discordar).
- Teclas configuráveis no cliente; ativação só se o jogador tem uma lâmina na mão.
- Previsão local: o cliente toca o estado do golpe no instante da tecla; "vale o evento mais recente" já cobre a correção vinda do servidor.
- **A verificar:** custo de pacotes, rate limit adequado e segurança contra clientes modificados.

## 9. Perguntas em aberto para decidir antes de codar

1. Item base do vanilla da katana.
2. Namespace e nome final do plugin.
3. `kills` como INTEGER ou LONG.
4. Quem produz a arte da fase 1 (geometria e 5 animações) e em que ferramenta.
5. Cancelar o golpe vanilla do clique esquerdo para itens-lâmina, ou deixá-lo e compensar.
