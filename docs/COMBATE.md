# Combate: referência

O combate do Overkill é o do **SlashBlade: Resharped** (MIT), portado estado por estado para o plugin
(`src/main/java/com/akashic/overkill/combo`). Os números abaixo são os do SlashBlade; o estudo completo, com as
linhas do código original, está em [`fidelidade/estudo/01-combo.md`](fidelidade/estudo/01-combo.md) e
[`fidelidade/estudo/04-lockon-rank-progression.md`](fidelidade/estudo/04-lockon-rank-progression.md).

## Entradas

| Entrada | De onde vem no 1.7.10 |
|---|---|
| Clique direito (`R_CLICK`) | o pacote de usar item (`PlayerInteractEvent`) com a katana na mão |
| Clique esquerdo num alvo (`L_CLICK`) | o ataque do vanilla, que é cancelado: o golpe vem só do combo |
| Botão direito segurado (`R_DOWN`) | a espada "defendendo" (`isBlocking`): é o que carrega a slash art |
| Sneak | o servidor sabe (e é o lock-on) |
| Frente, trás, lados, pulo, botão do meio, tecla especial | as teclas seguradas que o cliente Künftig manda (`KunftigInput.KEYS`) |
| Chão / ar | o servidor (`isOnGround`) |

Cliques no meio da janela de um golpe são **descartados**, sem fila (como no SlashBlade).

## Tempo

* Movimentos a 30 quadros por segundo. Um estado dura `(int)(quadros x 1000/30 / velocidade) + espera` ms e termina
  no primeiro tick em que `ticks x 50` passa disso; aí vai para o estado seguinte (um por tick).
* A janela de um golpe é `(int)(quadros x 2/3)` ticks: antes disso o clique é ignorado.
* As ações da linha do tempo de um estado contam a partir do primeiro tick dele (chave 0).

## Estados principais

| Estado | Quadros | Dura (ms / tick em que acaba) | Janela | Clique leva a | Golpes |
|---|---|---|---|---|---|
| `combo_a1` | 1-10 | 300 / 7 | 3 | A2 | corte -10 (0,44) no clique |
| `combo_a2` | 100-115 | 500 / 11 | 3 | A3 | corte 170 (0,44) no clique |
| `combo_a2_end` | 115-132 | 566 / 12 | — | **C** | |
| `combo_c` | 400-459 | 1966 / 40 | 10 | — | cortes -30 e -35 (0,88) |
| `combo_a3` | 200-218 | 600 / 13 | 6 | A4 (A4ex com Força/Fome) | cortes -61 e 138 (0,44) |
| `combo_a3_end` | 218-230 | 400 / 9 | — | **B1** | |
| `combo_a4` | 500-576 | 2533 / 51 | 14 | — | cortes 45 e 50 (0,44) |
| `combo_a4_ex` | 800-839 | 1300 / 27 | 14 | A5ex | cortes 70 e 255 (1,0) |
| `combo_a5ex` | 900-1013 | 3766 / 76 | 22 | — | três cortes críticos (1,0) |
| `combo_b1` | 700-720 | 666 / 14 | 8 | B2 | 2 cortes + 8 da rajada (0,244) |
| `combo_b2`..`b6` | 710-720 | 333 / 7 | 4 | o próximo | 7 cortes da rajada cada |
| `combo_b7` | 710-764 | 1800 / 37 | 22 | — | 8 da rajada + 2 finais |
| `aerial_rave_a1`..`a3` | 1100.. | 733 / 15 .. | 3, 3, 6 | o próximo | 0,28; o A3 empurra (smash) |
| `aerial_rave_b3` / `b4` | 1400 / 1500 | 1233 / 25 | 6 | B4 | lança (toss) / derruba (meteor) |
| `upperslash` | 1600-1659 | 1966 / 40 | 7 | — | corte -80 que lança (0,5), stun 15 |
| `aerial_cleave` | 1800.. | — | — | — | cai 3 blocos/tick acertando; corte ao pousar (meteor) |
| `rapid_slash` | 2000-2019 | 633 / 13 | — | (com Força: de novo) | investida 0,8/tick, 5 cortes |
| `rising_star` | 2100-2137 | 1233 / 25 | 12 | — | sobe 0,6, dois cortes que lançam (0,6) |

Comandos de raiz (do repouso, ou quando vencem a continuação pela prioridade, menor vence):

| Teclas | Estado | Prioridade |
|---|---|---|
| chão + Sneak + frente + clique direito | `rapid_slash` | 70 |
| chão + Sneak + trás + clique direito | `upperslash` | 90 |
| chão + clique | `combo_a1` | 100 |
| ar + Sneak + trás + clique direito | `aerial_cleave` | 70 |
| ar + clique | `aerial_rave_a1` | 80 |

Estados aéreos viram o seu estado final ao pousar. No ar, os golpes dão uma flutuação que enfraquece a cada tick até
pousar.

## Slash arts

Segurar o botão direito: menos de 9 ticks não faz nada; de 9 a 11 é o **just** (a versão crítica); mais é o
**sucesso**. Soltar em até 3 ticks depois de um estado de embainhar começar também é just (a "carga rápida"). Uma
arte só começa se for mais forte que o estado atual, e nunca dentro da janela de um golpe.

| Arte | Katana | O que faz |
|---|---|---|
| Corte do Julgamento (`judgement_cut`) | Kusanagi | recua, e um Judgement Cut no alvo travado (ou onde você olha): 10 ticks, acerta a cada 2 em volta (0,16); o just é crítico, lança e soma três cortes |
| Onda Vertical (`drive_vertical`) | Serpente | um corte e uma onda que voa (velocidade 2, 10 ticks, 1,5 x ataque) |
| Corte Circular (`circle_slash`) | Sakurajima | quatro cortes críticos em volta (0,325) |
| Fio da Tempestade (`wave_edge`) | Raijin | um corte e cinco ondas verticais em velocidades de 0,2 a 1,0 |
| Onda Horizontal, Corte do Vazio, Fim da Sakura | (configuráveis) | `drive_horizontal`, `void_slash`, `sakura_end` |

Cada Slash Art custa **20 almas** da katana; sem elas, sai assim mesmo e a katana leva 1 de desgaste. Uma katana
quebrada não tem Slash Art (veja [PROGRESSAO.md](PROGRESSAO.md)).

## Acertos e dano

* Um **corte** vive 10 ticks e, nos ticks 2, 4, 6, 8 e 10, acerta tudo em volta do jogador (como ele está naquele
  tick): esfera de alcance **4,5** (quebrada 3,25) a partir dos pés, cortada por uma caixa empurrada meio alcance
  para a frente. Cada alvo uma vez por corte. A invulnerabilidade do vanilla é ignorada.
* **Alvos**: mobs hostis e qualquer um que bateu em você ou mira em você; nunca aldeões, os seus bichos, ou uma
  montaria com jogador; jogadores só com `pvp: true`.
* **Dano** = `(ataque da lâmina + bônus do rank + Afiação/Julgamento/Ruína) x multiplicador do golpe`, x1,1 no corte
  crítico, x1,5 no crítico de pulo do vanilla. O ataque da lâmina cresce com o nível, a estrela e a fortificação
  ([PROGRESSAO.md](PROGRESSAO.md)). Bônus do rank: rank/2 (D 0,5 ... SSS 3,5); do S para cima, pelo menos
  min(nível de XP do jogador, fortificação), a fortificação no lugar dos refinos do SlashBlade.
* Cada acerto gasta 1 de durabilidade da katana.
* **Knockback**: `cancelar` (fica no lugar), `lançar` (sobe 0,75), `meteoro` (desce 5,5), `empurrar` (x1,5).
* **Stun** nos mobs: 10 ticks (15 no corte para cima e no corte descendente, 36 e 40 em artes): parados, caindo
  devagar; depois de um meteoro, batem no chão.

## Rank de concentração

300 pontos por nível, até 1799: D 300, C 600, B 900, A 1200, S 1500, SS 1575, SSS 1650. +30 por acerto, -300 por dano
levado; escorre 1 ponto por tick até o piso do nível em que está. O HUD aparece por 6 segundos a cada mudança. O
contador de golpes ("27 HITS") conta acertos com menos de 3 segundos entre eles.

## Lock-on

Apertar Sneak com a katana: trava no alvo que você olha (até 40 blocos) ou no mais perto (12 de lado, 6 de altura).
Enquanto segura, o cliente puxa a câmera e mostra a mira com a vida do alvo. Soltar destrava. O Judgement Cut cai no
alvo travado.
