# Estudo: SlashBlade + combate estilo DMC na 1.7.10 sobre o Künftig

Data do estudo: 2026-11-06.

**Base de evidência.** Este estudo se apoia em: (a) a página do repositório `flammpfeil/SlashBlade_2` (apenas a fachada: não foi lido o código-fonte); (b) páginas públicas de mods (CurseForge, Modrinth e similares); (c) o `README.md`, o `docs/API.md` e o `docs/rodmodels/README.md` do Künftig (não foi lido o código-fonte do Künftig). Onde algo é inferência, está marcado como **a verificar**.

> **Estado em 2026-11-06: implementado.** As lacunas da seção 4 viraram recursos da API 9 do Künftig (API 11 depois da mescla com a linha do AkashicFishing, ver docs/TESTES.md) e o plugin
> existe. Resumo do que virou cada uma:
>
> | # | Lacuna | Resultado |
> |---|---|---|
> | 1 | Canal de entrada | `KunftigInput` / `KunftigInputEvent` (canal `kunftig:input`), validado e com limite por jogador |
> | 2 | Previsão de golpe | `ao_atacar` no manifesto: o cliente começa o golpe no clique e o servidor confirma sem reiniciar |
> | 3 | API genérica para itens na mão | Mantida a `KunftigRods` (o manifesto já era genérico); o modelo da lâmina usa `estados.<s>.modelo` |
> | 4 | Projéteis | `Display#setMotion` (voo com uma mensagem), `moveTo` com deslize e `setRotation` com pitch e roll |
> | 5 | HUD de rank | `KunftigHud` (texto, barra, imagem na tela) |
> | 6 | Lock-on | `KunftigCamera` (câmera segue o alvo, marcador) |
> | 7 | Desempenho com muitos jogadores | **Ainda não medido** num servidor com vários jogadores |
> | 8 | Movimento | Investida, esquiva, golpes aéreos e queda por `setVelocity` no servidor; **sensação a validar online** |
> | 9 | Dano em mobs de mods | Dano como do jogador (passa pelos eventos), tamanho do alvo pela altura dos olhos; **a validar com mobs de mods** |
>
> Correções a este estudo, confirmadas no código do Künftig: o manifesto exige a chave `modelos`; o controlador de
> `transicao_ticks` é sempre `vara`, mesmo com `modelo`; o ciclo automático da vara não atrapalha a espada (só `sacar`
> dispara). O Bukkit 1.7.10 não tem `PrepareAnvilEvent`: o refino virou comando. Mecânicas: [`COMBATE.md`](COMBATE.md).

---

## 1. O que é o SlashBlade

- Mod de katanas de `flammpfeil`. Descrição das fontes públicas: inspirado em Demon Blade, DMC e PSO2; cada katana tem combos especiais e há um sistema de ranks (S e acima).
- Na prática é um mod de **combate de espada estilo hack'n'slash**. A referência é a série **Devil May Cry** (DMC), não o reboot DmC da Ninja Theory.
- **SlashBlade_2** (`flammpfeil/SlashBlade_2`) é o código do autor original para a 1.14.4 em diante. Na raiz do repositório não há README nem arquivo de licença visível.
- **SlashBlade: Resharped** é um fork da comunidade para o Minecraft moderno (aparece como MIT). Sua descrição afirma que o sistema de combate replica bastante o do Vergil em DMC5.
- Existem addons do Resharped que reescrevem o combate para ficar mais "DMC-like" (por exemplo o **True POWER**, GPL v3).
- **O SlashBlade original já existe na 1.7.10** (há builds r87/r88 e vários addons de lâminas para a 1.7.10).

Consequência: o projeto não é "criar um SlashBlade na 1.7.10". É levar o **design** do SlashBlade_2 e do combate estilo DMC para o stack do Gabriel, sem copiar código.

## 2. Arquitetura proposta

```
 plugin Bukkit (este projeto)  --compileOnly-->  kunftig-api
        |                                              ^
        | runtime                                      | embutida no mod
        v                                              |
 Thermos/Crucible 1.7.10  --  mods/kunftig-<versão>.jar  (+ UniMixins)
        ^
        | canais kunftig:* (pacotes)
        v
 cliente 1.7.10: Künftig + GeckoLib Unofficial 1.0.4 + resource pack
```

| Camada | Responsabilidade |
|---|---|
| **Plugin Bukkit** | lâminas por JSON, estado persistente (PDC), combos, dano, rank de estilo, slash arts, validação anti-cheat, envio de estados de animação |
| **Mod Künftig (cliente + servidor)** | renderização GeckoLib dos itens, canais de pacotes, previsão local de animação, (novo) canal de entrada |
| **Resource pack** | geometria e animações Bedrock, texturas, sons, manifesto `item_models_3d.json` e `displays.json` |

Observação: o que seria "mod + addon" no SlashBlade moderno vira, aqui, **um plugin único**. O combate estilo DMC é um **módulo configurável** dentro dele (rank points, janela de combo, dash, Judgement Cut).

## 3. O que o Künftig já cobre (segundo a documentação)

| Necessidade | O que existe |
|---|---|
| Katana 3D animada na mão (1ª e 3ª pessoa, chão, moldura) | Manifesto `assets/<ns>/kunftig/item_models_3d.json` + GeckoLib Unofficial 1.0.4. O campo `itens` define em quais itens o renderer desenha (o padrão é a vara de pesca); os peixes do AkashicFishing já provam que não é exclusivo de varas |
| Poses de braço | `braco_direito` / `braco_esquerdo` por estado; `duas_maos`; animação `animation.braco.<estado>` |
| Estados enviados pelo servidor | `KunftigRods.setState(player, nome)`: aceita qualquer nome que o pack declare em `estados` (`a-z`, `0-9`, `_`, até 32 caracteres); canal `kunftig:rod`; alcance de 96 blocos; quem chega depois recebe o estado já andando |
| Parâmetro contínuo | `KunftigRods.setTension(player, 0..1)` (hoje usado para a vara vergar; pode servir como "carga") |
| Prioridade do evento mais recente | O cliente toca estados sozinho e vale o evento mais recente, do cliente ou do servidor. Esse é o desenho certo para latência de combate |
| Estado da lâmina (kill count, proud soul, refine) | `KunftigItems` + `PersistentDataContainer` (formato `PublicBukkitValues`, mesmo layout do Bukkit 1.14+) |
| Visual que muda com o nível da lâmina | Manifesto: `variante` (ossos mostrados conforme um número guardado no item), `qualidade`, `mutacao`, `tamanho` (lido do PDC) |
| Brilho e aura | `luz` (ossos sem sombra, brilho máximo) e `translucido` por osso |
| Ícone 2D no inventário | `KunftigItems.setModel` + `assets/<ns>/kunftig/item_models.json` |
| Efeitos no mundo (Judgement Cut, espadas invocadas, Drive) | `KunftigDisplays.spawn/setState/setItems/teleport/remove` (modelo GeckoLib animado, até 8 itens nos ossos, canal `kunftig:display`) |
| Partículas | `KunftigParticles` com partículas modernas (`END_ROD`, `DAMAGE_INDICATOR`...) para clientes com Künftig + Et Futurum Requiem, com fallback vanilla |
| Troca de slot com Backhand | `KunftigPlayers.isHotbarSwitch(event)` (o Backhand gera `PlayerItemHeldEvent` falsos a cada clique direito) |
| Ping | `KunftigPlayers.getPing` |

## 4. Lacunas (o que precisa ser criado)

| # | Lacuna | Detalhe | Onde mora |
|---|---|---|---|
| 1 | **Canal de entrada cliente→servidor** | Todos os canais documentados (`kunftig:nametag`, `kunftig:holo`, `kunftig:rod`, `kunftig:display`) vão do servidor para o cliente. Cliques e troca de slot chegam pelo Bukkit; lock-on, guard, dash e special precisam de teclas próprias | Künftig (novo, ex.: `kunftig:input`) |
| 2 | **Automatismo de cliente específico de vara** | `sacar`, `lancar` e `parada` seguem o ciclo da pesca. Para katana, o clique esquerdo deve disparar o golpe localmente e o servidor confirma ou corrige | Künftig |
| 3 | **API de nomes para itens na mão** | Hoje é `KunftigRods`. Uma API genérica (ex.: `KunftigHeld`/`KunftigBlades`, "API 9") evita usar o nome de vara para espadas. O manifesto já é genérico | Künftig |
| 4 | **Projéteis em movimento** | `Display` tem `teleport`, mas a documentação não diz se há interpolação nem qual o custo de pacotes. **A verificar** no jogo (ex.: Drive voando) | Künftig / teste |
| 5 | **HUD de rank (D até SSS)** | Hologramas são texto no mundo, não overlay de tela. Um HUD de verdade é recurso novo de cliente | Künftig |
| 6 | **Lock-on, câmera e FOV, stun** | Extras de cliente e de dano; deixar para depois | Künftig + plugin |
| 7 | **Desempenho com muitos jogadores** | O README do Künftig lista como pendente a validação no cliente do TEOF (Angelica, Backhand, NecroTempus) e com vários jogadores. Combate muda de estado muito mais vezes por segundo que pesca | Teste |
| 8 | **Movimento (dash, Stinger, air hike)** | Via `setVelocity` no servidor, com possível jitter por previsão de cliente. **A verificar** | Plugin + teste |
| 9 | **Dano e stagger em mobs modded** | Sistema de stun (estilo True POWER) via `EntityDamageByEntityEvent`; o comportamento em mobs de mods pode variar. **A verificar** | Plugin + teste |

## 5. Custo real: arte e animação

O maior custo não é código. Cada golpe do combo precisa de uma animação feita no Blockbench (geometria Bedrock 1.12.0, animação 1.8.0), mais as poses dos braços para 1ª e 3ª pessoa. Um combo de seis a sete golpes, iaido (saque) e special é muito trabalho de animação. O GeckoLib Unofficial 1.0.4 tem limites já tratados no Künftig (por exemplo, não lê resource packs escolhidos depois da partida; o Künftig contorna isso lendo pelo `IResourceManager`; `catmullrom` é linear e degraus são dois keyframes, então o pack deve vir "amostrado").

## 6. Sensação de combate (latência)

- A animação do golpe deve **começar no cliente no instante do input** (previsão), e o servidor apenas valida e resolve dano e efeitos.
- Se o cliente esperar a resposta do servidor, o combo fica "pesado" com ping alto.
- O Künftig já tem a regra "vale o evento mais recente", que permite corrigir o cliente quando o servidor discordar.
- Janelas de timing curtas (Just Guard, Royal Guard) são sensíveis a ping: usar `KunftigPlayers.getPing` para compensar, como o exemplo do minigame de pesca no `API.md`.

## 7. Licenças e direitos

| Item | Situação | Recomendação |
|---|---|---|
| SlashBlade_2 (código e assets) | Sem arquivo de licença visível na raiz do repositório | Sem licença explícita, o padrão é "todos os direitos reservados". Não copiar código nem assets; criar modelos, animações, texturas e sons do zero |
| SlashBlade: Resharped | Aparece como MIT | Referência de ideias mais segura. É código para Forge moderno, quase nada se reaproveita diretamente |
| True POWER | GPL v3 | Usar só como referência de ideias. Copiar código obrigaria o projeto a ser compatível com GPL |
| Geckolib-Unofficial 1.0.4 | MIT | Pode ser dependência |
| Künftig | "Not chosen yet" | Decidir a licença do Künftig e deste projeto juntas, antes de crescer |

## 8. Roadmap em fases

### Fase 1: fatia vertical (sem canal novo)
- Uma katana 3D na mão com `sacar`, `parada` e 3 golpes encadeados.
- Manifesto `item_models_3d.json` com `itens` apontando para um item base do vanilla (**a decidir**: por exemplo, espada de ferro).
- Entrada só por cliques e eventos do Bukkit; dano no servidor.
- Objetivo: validar o desenho e medir a latência do modo 100% servidor, além do desempenho com poucos jogadores. A previsão local de golpe não existe no Künftig hoje e entra na fase 2, se a sensação exigir. Detalhes em [`FASE1-DESIGN.md`](FASE1-DESIGN.md).

### Fase 2: estado persistente e entrada
- Lâminas definidas por JSON no plugin (id, kill count, proud soul, refine, slash art, special effects), guardadas via PDC.
- Visual por nível (`variante`, `qualidade`).
- **Canal de entrada** no Künftig e janela de combo de verdade.

### Fase 3: slash arts e rank
- Drive e Judgement Cut com `KunftigDisplays` e `KunftigParticles` (fallback de partículas para clientes sem Künftig, como o AkashicFishing faz).
- Sistema de rank de estilo (rank points) no servidor.
- Medir custo de pacotes de `Display.teleport` para projéteis.

### Fase 4: extras
- Lock-on com câmera, HUD de rank, stun, dual wield (se entrar no escopo).

## 9. Decisões em aberto

1. Qual item base do vanilla a katana usa (a escolha é por onde o renderer se registra).
2. Dual wield entra no escopo desde o início? (O Backhand já existe no stack; a documentação diz que a mão secundária fica com o ícone 2D nas varas.)
3. Licença do Künftig e deste projeto.
4. Nome final do plugin e do namespace do resource pack.
5. Quantos golpes por combo e quais tipos de lâmina na primeira versão (define o volume de arte).

## 10. Riscos resumidos

- **Arte** (animações) é o gargalo principal.
- **Desempenho** do renderer GeckoLib com vários jogadores em combate ainda não foi validado.
- **Latência** em janelas curtas de timing.
- **Dependência** do GeckoLib Unofficial 1.0.4 (versão fixada no Künftig) e de UniMixins.
- **Licenças**: não reaproveitar código nem assets sem licença clara.
