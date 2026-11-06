# Combate: referência

Todos os números estão em **ticks do servidor** (20 por segundo) e saem de
[`core/Arsenal.java`](../src/main/java/com/akashic/overkill/core/Arsenal.java), a mesma fonte que o gerador do
resource pack usa. Se mudar um número lá, rode `./gradlew generatePack` (o CI falha se esquecer).

## 1. Como um golpe acontece

```
clique esquerdo
  cliente (Künftig): EntityClientPlayerMP#swingItem -> o modelo começa o golpe previsto (ao_atacar) na hora
                     -> o mesmo swingItem manda ao servidor o balanço do braço
  servidor (Overkill): PlayerAnimationEvent -> ComboMachine.attack(tick, postura)
                       -> KunftigRods.setState(jogador, golpe)  (o cliente que previu não recomeça a animação)
                       -> nos ticks de acerto: corta o que está no arco, cada alvo uma vez por golpe
```

* **Postura** no instante do clique: no chão ou no ar, agachado, correndo.
* Um golpe tem **preparo**, **acerto** (ticks em que corta) e **recuperação**. Um clique dentro da **janela**
  encadeia o próximo golpe; fora dela, durante o golpe, é ignorado (sem spam); depois do golpe, recomeça pela entrada.
* O golpe vanilla com a lâmina é cancelado: quem corta são os ticks de acerto, todos os alvos no arco.
* O dano passa pelo `EntityDamageByEntityEvent` como dano do jogador, então plugins de proteção e mods veem o golpe.
  O tempo de invulnerabilidade do alvo é zerado antes de cada corte (combos rápidos acertam todos os golpes).
* Cada corte que acerta gasta 1 de durabilidade da lâmina (a da espada vanilla fica sempre cheia).

## 2. Golpes da katana

Dano = dano da lâmina × multiplicador. Janela em ticks desde o início do golpe (inclusive).

| Golpe | Entrada / postura | Preparo / acerto / recuperação | Janela | Próximo | Dano | Alcance / arco | Efeito |
|---|---|---|---|---|---|---|---|
| `golpe_1` | parado no chão | 2 / 2 / 10 | 4 a 13 | `golpe_2` | 1,0 | 3,5 / 110° | |
| `golpe_2` | | 2 / 2 / 10 | 4 a 13 | `golpe_3` | 1,1 | 3,5 / 120° | |
| `golpe_3` | | 3 / 2 / 11 | 5 a 15 | `golpe_4` | 1,25 | 3,8 / 100° | |
| `golpe_4` | | 4 / 3 / 14 | | | 1,8 | 4,0 / 160° | empurrão forte |
| `estocada` | correndo | 3 / 4 / 10 | 7 a 16 | `golpe_3` | 1,4 | 4,5 / 50° | investida para a frente |
| `lancamento` | agachado no chão | 3 / 2 / 12 | 5 a 16 | `aereo_1` (no ar) ou `golpe_2` | 1,0 | 3,5 / 100° | lança o alvo para cima |
| `aereo_1` | no ar | 2 / 2 / 9 | 4 a 12 | `aereo_2` | 0,9 | 3,5 / 120° | mantém o alvo e você no ar |
| `aereo_2` | | 2 / 2 / 9 | 4 a 12 | `aereo_3` | 1,0 | 3,5 / 120° | mantém o alvo e você no ar |
| `aereo_3` | | 3 / 2 / 12 | | | 1,3 | 3,8 / 140° | crava o alvo no chão |
| `queda` | agachado no ar | 2 / 6 / 10 | | | 1,6 | 3,0 / 360° | mergulho |
| `iaido` | atacar enquanto saca (10 ticks) | 1 / 2 / 10 | 3 a 12 | `golpe_2` | 2,0 | 4,0 / 140° | saque rápido |

A previsão do cliente segue a cadeia simples (chão ou ar, sem agachar nem correr). Quando o servidor escolhe outro
golpe pela postura (por exemplo `aereo_1` em vez de `golpe_1` porque você estava no ar), o estado dele vence e o
cliente corrige em um ou dois quadros.

O tamanho dos alvos vem da altura dos olhos (o Bukkit 1.7.10 não informa o tamanho de uma entidade); funciona para
mobs vanilla e de mods.

## 3. Slash Arts

Segure o **botão direito** com a lâmina (a guarda da espada) até a barra encher e solte. Uma lâmina quebrada não
usa Slash Art. O alvo de uma arte à distância é o alvo travado; sem trava, o que você olha; sem nada, o ponto onde o
olhar bate num bloco.

| Arte | Carga | Custo (proud soul) | O que faz |
|---|---|---|---|
| `corte_dimensional` (Corte Dimensional) | 14 | 12 | 5 pulsos de cortes num raio de 3,2 blocos, até 14 blocos de distância, 0,8 × dano por pulso |
| `onda` (Onda Cortante) | 12 | 8 | Projétil a 1,2 bloco/tick por 18 blocos, raio 1,6, 1,6 × dano em cada alvo do caminho |
| `corte_circular` (Corte Circular) | 10 | 6 | Dois giros em volta, raio 5, 1,4 × dano |

Os efeitos 3D são exibições do Künftig (`overkill:corte_dimensional`, `overkill:onda`, `overkill:corte_circular`). A
onda usa `Display#setMotion`: uma mensagem para o voo inteiro. Todo mundo também vê as partículas.

## 4. Teclas do Künftig

Ficam ligadas enquanto o jogador segura uma lâmina (`KunftigInput.bind`). Sem o Künftig 9 no cliente, não há teclas;
o combo e as Slash Arts funcionam do mesmo jeito.

| Tecla (padrão) | Ação | Detalhes |
|---|---|---|
| Especial (R) | Espadas invocadas | 3 espadas, 1,3 bloco/tick, 0,45 × dano cada, recarga 14 ticks, custo 1 proud soul. Teleguiadas no alvo travado ou no que você olha; sem alvo, vão reto |
| Habilidade (F) | Truque aéreo | Teleporte para o lado do alvo travado (até 20 blocos), virado para ele. Recarga 40 ticks, custo 2 |
| Esquiva (Alt esquerdo) | Esquiva | 1 bloco/tick na direção das teclas de movimento (para trás sem nenhuma), 6 ticks invulnerável, recarga 16 ticks. Um ataque esquivado vale 30 pontos de estilo |
| Travar alvo (Z) | Trava de alvo | O alvo mais perto do centro da visão (cone de 35°, 20 blocos, com linha de visão). A câmera do Künftig segue; aperte de novo para soltar |

## 5. Rank de estilo

| Rank | Pontos | Título |
|---|---|---|
| D | 1 | Desleixado |
| C | 100 | Cortante |
| B | 250 | Brutal |
| A | 450 | Avassalador |
| S | 700 | Selvagem |
| SS | 1000 | Supremo |
| SSS | 1400 (máximo 1800) | Soberano |

* Cada alvo acertado vale os pontos do golpe, **divididos por 2 a cada uso recente do mesmo golpe** (os últimos 6).
  Variar rende mais.
* Apanhar tira 80 + 20 × dano pontos. Sem ganhar nada por 50 ticks, o rank escorre (mais rápido quanto mais alto).
* No HUD do Künftig: a letra, o título, a barra até o próximo rank e o número de golpes seguidos.

## 6. Lâminas e progressão

| Fórmula | Valor |
|---|---|
| Dano da lâmina | `dano_base + min(refino, refino_maximo) × dano_por_refino + min(abates, 1000) / 200` |
| Lâmina quebrada | 1/4 do `dano_base`, sem Slash Art |
| Proud soul por abate | `max(1, vida máxima da vítima / 4 × (1 + 0,25 × rank))`; sem rank, rank 0 |
| Refino | custa `10 + 5 × refino` proud soul |
| Reparo | custa 1 proud soul por 10 de desgaste |

Os dados ficam no item (`PublicBukkitValues`, formato do Bukkit 1.14+): `overkill:blade`, `overkill:kills`,
`overkill:proud_soul`, `overkill:refine`, `overkill:wear`, e o modelo em `kunftig:model`.
