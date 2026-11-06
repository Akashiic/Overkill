# Fidelidade ao SlashBlade: estudo e plano

Data: 2026-11-06. Objetivo: o sistema do **SlashBlade: Resharped** (e do addon **Double Edged Reforged**) no
Minecraft 1.7.10, como plugin + resource pack + Künftig, **fiel** em mecânica, tempo, animação e efeitos.

Este documento resume o estudo do código-fonte e decide a arquitetura. Os relatórios completos, com
`arquivo:linha` e constantes exatas, estão em [`estudo/`](estudo):

| Relatório | Assunto |
|---|---|
| [`01-combo.md`](estudo/01-combo.md) | Máquina de combo, ~90 estados (quadros, duração, janelas, prioridades, cada corte), entrada, acerto, dano, knockback, stun, movimento, "just" |
| [`02-render-animation.md`](estudo/02-render-animation.md) | Modelos OBJ das lâminas, esqueleto `bladeholder` e `hardpointA/B`, VMD (30 fps, lerp/nlerp), animação do jogador no modelo vanilla, primeira pessoa, estados de GL, receita de port para o 1.7.10 |
| [`03-vfx.md`](estudo/03-vfx.md) | Arco de corte (transformações, 4 passes por rank, alfa), Judgement Cut, Drive/Wave Edge, 6 tipos de espadas invocadas, 9 slash arts, todos os `doSlash` dos combos, cores |
| [`04-lockon-rank-progression.md`](estudo/04-lockon-rank-progression.md) | Lock-on segurando Shift, câmera, marcador, rank de concentração (D…SSS), HUD, estado da lâmina (abates, proud soul, refino na bigorna, quebra), efeitos especiais, teclas, as 26 lâminas de fábrica |
| [`05-der-and-diffs.md`](estudo/05-der-and-diffs.md) | O que o DER acrescenta (dupla empunhadura, 56 estados duplos, três lâminas, render da segunda lâmina) e as diferenças SB2 × Resharped |

Fontes estudadas (só leitura; nada foi executado): SlashBlade_Resharped `6e2a0a0` (1.9.65), DoubleEdgedReforged
`6204257` (2.3.1), SlashBlade_2 `3fd99e2`. Os pontos críticos de cada relatório foram conferidos no código por mim
(lock-on no `LockOnManager`, combos no `ItemSlashBlade`/`ComboCommands`, interpolação no `MotionData`, arco no
`SlashEffectRenderer`).

## 1. O que o estudo mudou (a implementação atual está errada nisto)

| Assunto | Implementado antes | Como é no SlashBlade |
|---|---|---|
| Botão do combo | clique esquerdo | **clique direito** (`Item.use`); o esquerdo só conta quando acerta uma entidade |
| Golpes | 11 golpes inventados | ~90 estados com quadros e janelas exatos: combo A/B/C, aerial rave, upperslash, aerial cleave, rapid slash, rising star, sheath, slash arts |
| Escolha do golpe | postura (chão/ar/agachado/correndo) | tabela de comandos + **prioridade** (menor vence); Shift+Frente+clique direito = rapid slash, Shift+Trás+clique direito = upperslash; no ar, Shift+Trás = aerial cleave |
| Tempo | quadros em ticks inventados | duração `(int)(|fim−início|·1000/30/velocidade)` ms; janela de travamento `(int)(quadros·2/3)` ticks, cliques antes dela descartados |
| Acerto | arco por golpe | cada corte cria um efeito de 10 ticks que acerta **nos ticks 2, 4, 6, 8 e 10** numa esfera de raio 4,5 em volta do jogador, dentro de uma caixa empurrada para a frente |
| Lock-on | tecla Z | **segurar Shift**: o alvo é escolhido no aperto (raio de 40 blocos no olhar; senão o mais perto em ±12/±6) e solto ao soltar |
| Rank | D…SSS com pontos inventados e nomes próprios | 300 por nível, máx. 1799; S em 1500, SS em 1575, SSS em 1650; +30 por acerto, −300 ao apanhar; decai 1/tick até o piso do rank; HUD `textures/gui/rank.png` |
| Lâmina 3D | cubos GeckoLib na mão | **OBJ** com partes (`blade`, `sheath`, `blade_damaged`, `effect`, `*_luminous`), movida por um esqueleto invisível (`hardpointA` = lâmina, `hardpointB` = bainha), não pelo braço |
| Animação | keyframes de cubos | **VMD** (MikuMikuDance) a 30 fps: `combostate/motion.vmd` move a lâmina, `pa/player_motion.vmd` move o corpo do jogador |
| Arco de corte | partículas e cubos | `util/slash.obj` (anel de 270°), giros `Y(−yaw−90)·Z(pitch)·X(roll)·Y(offset−135·p)`, alfa `1−(1−t)^4`, 4 passes aditivos conforme o rank, cinza abaixo de C |
| Espadas invocadas | 3 espadas teleguiadas | 6 tipos (básica, espiral, tempestade, bolhas/blistering, chuva pesada, air trick) com formações e regras próprias |
| Teclas | R, F, Alt, Z | **V** (golpe especial) e **botão do meio** (espadas invocadas), mais Shift e as setas de movimento combinadas |

Tudo isso será refeito a partir do estudo. Os recursos genéricos do Künftig (canal de entrada, HUD, câmera,
exibições, previsão) continuam úteis, mas precisam de ajustes para o modelo do SlashBlade.

## 2. Licenças: o que pode e o que não pode

| Projeto | Código | Arte (OBJ, PNG, VMD, PMD) |
|---|---|---|
| SlashBlade_2 | todos os direitos reservados | todos os direitos reservados |
| SlashBlade: Resharped | **MIT** | **todos os direitos reservados** ("Art Resources: All Rights Reserved") |
| Double Edged Reforged | **GPL-3.0** | o VMD duplo é derivado do VMD do SlashBlade (proveniência incerta) |
| NyMMD (leitor de VMD/PMD dentro do SlashBlade) | MIT (nyatla) | — |

Consequências:

* **Lógica:** portar o comportamento do Resharped é permitido (MIT, com o aviso de copyright). Os números (quadros,
  tempos, fórmulas) são o que torna o port fiel.
* **Arte:** os modelos das lâminas, as texturas dos efeitos, o `rank.png` e, principalmente, as **animações VMD**
  (o que faz "parecer SlashBlade") não podem ir para este repositório nem ser distribuídos aos jogadores sem
  permissão dos autores.
* O motor no Künftig vai ler **exatamente os formatos do SlashBlade** (OBJ com as mesmas partes, VMD, PNG). Assim o
  mesmo pack serve com a arte original (se houver permissão) ou com arte própria no mesmo formato.

**Decisão necessária do dono do projeto:** qual arte o pack vai usar. Veja a seção 6.

## 3. Arquitetura para o 1.7.10

```
Servidor (Overkill)                                   Cliente (Künftig + pack)
  máquina de combo portada do Resharped                 OBJ das lâminas (leitor próprio, regras do SB)
  ├─ estado atual por jogador (id, início em ticks)     VMD: lâmina (bladeholder) e corpo (player_motion)
  ├─ cortes (doSlash): acerto nos ticks 2..10           arco de corte, Judgement Cut, Drive, espadas
  ├─ dano, knockback, stun, flutuação no ar             marcador do lock-on, HUD do rank
  ├─ slash arts, espadas invocadas, lock-on (Shift)     câmera seguindo o alvo
  └─ rank, abates, proud soul, refino, quebra
          │  kunftig:blade  (estado do combo: id, quadros, início; cor, rank) ──────────────▶
          │  kunftig:effect (efeitos: tipo, posição, rotação, roll, tamanho, cor, rank) ────▶
          ◀─ kunftig:input  (teclas seguradas, como o MoveCommandMessage do SB: 14 bits)
```

### 3.1 Künftig (cliente), recursos novos

1. **Leitor de OBJ do SlashBlade**: triângulos `v/vt/vn`, normais por vértice, V invertido, UV puxado 0,0005 para o
   centro da face, cor por vértice, partes por nome; operador de UV e "alfa 0 onde y = 0" para os efeitos.
2. **VMD**: leitor e amostragem (lerp na posição, nlerp — lerp normalizado, que o NyMMD chama de "Slerp" — no quaternion, sem curvas Bézier), hierarquia
   do `bladeholder` (9 ossos na origem, sem IK).
3. **Lâmina do jogador**: matriz do `hardpointA`/`hardpointB` com `translate(0,1.5,0)·scale(1/8)·rotZ(180)·espelho X`
   e o OBJ a 1/16; lâmina de reserva (mão secundária ou slot 0) por tipo de porte; primeira pessoa com a
   transformação fixa do SB; estados de GL por passe (normal: alfa, luz de entidade, sem faces de trás; luminosas:
   aditivo, fullbright, sem profundidade); brilho de carga no grupo `effect`.
4. **Corpo do jogador por VMD**: ossos `head/torso/left arm/right arm/left leg/right leg` para as partes do
   `ModelBiped` (Euler ZYX, sinais (−x,+y,−z)), osso `body` no modelo inteiro (pivô 0,7 bloco), posição ×(1,−1,1)·2 px;
   braços substituem, pernas somam (substituem nos aéreos); swing vanilla cancelado. É o que o PlayerAnimator faz no
   SB.
5. **Efeitos**: renderers portados do Resharped (arco de corte com os 4 passes, Judgement Cut com esferas e
   crescentes, Drive, espadas invocadas, marcador do lock-on com o anel de vida, HUD do rank com as coordenadas de
   textura exatas).
6. **Entrada**: o cliente manda o conjunto de teclas seguradas a cada mudança (frente/trás/esquerda/direita, Shift,
   pulo, botões do mouse, V, botão do meio), como o `MoveCommandMessage`.

### 3.2 Overkill (servidor)

Port direto do `ComboStateRegistry`, `ComboCommands`, `AttackManager`, `KnockBacks`, `StunManager`,
`LockOnManager`, `ConcentrationRank`, slash arts, `SummonedSwordArts` e do estado da lâmina, com as adaptações do
1.7.10 abaixo. As 26 lâminas de fábrica entram como dados (`blades.yml`).

| SlashBlade (1.20.1) | 1.7.10 com Bukkit |
|---|---|
| `Item.use` (clique direito) | `PlayerInteractEvent` RIGHT_CLICK_AIR/BLOCK com a lâmina |
| Clique esquerdo numa entidade | `EntityDamageByEntityEvent` (golpe vanilla cancelado) |
| Segurar o clique direito (`onUseTick`, `releaseUsing`) | a guarda da espada (`isBlocking`) por tick |
| Entidades de efeito | estado no servidor + mensagem `kunftig:effect` aos clientes perto |
| Stun (congela a IA) | sem `setAI` no 1.7.10: velocidade zerada por tick e lentidão; a avaliar no jogo |
| Bigorna (refino) | sem `PrepareAnvilEvent` no 1.7.10: `InventoryClickEvent` na bigorna |
| Mão secundária (DER) | Backhand (já no modpack) |
| Suporte de lâmina | sem armor stand: exibição do Künftig + interação |

## 4. Como a fidelidade será verificada

* **Testes de tabela**: cada estado de combo comparado com a tabela do relatório 01 (duração, janela, próximo
  estado, ticks dos cortes, parâmetros).
* **Testes de matemática de render**: matrizes do `hardpointA` em quadros conhecidos (o relatório 02 traz poses de
  amostra calculadas do `motion.vmd`), transformações do arco, alfas.
* **Capturas no 1.7.10** pelo harness do Künftig, golpe a golpe, comparadas lado a lado com capturas do Resharped
  no 1.20.1 nos mesmos quadros (se for possível rodar o Resharped aqui como referência).

## 5. Plano em fases

| Fase | Entrega | Verificação |
|---|---|---|
| F1 | Künftig: OBJ do SB, VMD, lâmina e bainha no `bladeholder`, primeira pessoa, corpo do jogador por VMD, canal `kunftig:blade` | testes das matrizes e capturas de cada estado |
| F2 | Künftig: efeitos (arco, Judgement Cut, Drive, espadas invocadas), marcador do lock-on, HUD do rank, câmera do lock-on do SB, entrada no formato do SB | testes de render e capturas |
| F3 | Overkill: máquina de combo portada (todos os estados), acerto, dano, knockback, stun, flutuação | testes contra as tabelas do estudo |
| F4 | Overkill: slash arts, espadas invocadas (6 tipos), lock-on por Shift, rank exato, progressão (abates, proud soul, refino na bigorna, quebra, efeitos especiais), 26 lâminas | testes |
| F5 | DER: dupla empunhadura com Backhand, 56 estados duplos, três lâminas | testes e capturas |
| F6 | Validação num servidor Crucible com jogadores: sensação, ping, desempenho, mobs de mods | relatório |

Andamento: **F1 feita** (Künftig: motor de lâminas, conferido contra as poses do SB e contra capturas do Resharped).
**F2 feita no Künftig**: arco de corte (4 passadas por rank), Judgement Cut (esferas que escurecem com subtração
reversa, cascas e ventos, com o bug do alfa do vento mantido), drive, espadas invocadas (voo tick a tick como o SB,
cravadas no alvo, órbitas por mensagem), marcador do lock-on com o anel de vida por máscara de profundidade, câmera do
lock-on segurando o Sneak e HUD do rank com o decaimento do SB (`KunftigEffects`, canal `kunftig:effect`; ver
`docs/API.md` §18 do Künftig). A entrada no formato do SB (bits de teclas) entra com a F3.

## 6. Decisão pendente: a arte

1. **Pedir permissão** aos autores (arte do SB2: flammpfeil; Resharped: o grupo do Resharped) para usar os modelos,
   texturas e VMDs no pack do servidor. É o único caminho para ficar **idêntico**, principalmente nas animações.
2. **Arte própria no mesmo formato** (OBJ + PNG + VMD feitos por artistas). Fiel na mecânica e na tecnologia,
   mas o "jeito" das animações depende do animador.
3. **As duas**: o motor e o pack de teste com arte própria provisória, trocável pela original assim que houver
   permissão.

Até a decisão, o desenvolvimento usa os arquivos originais **só localmente**, para conferir a fidelidade, sem
versioná-los nem distribuí-los.
