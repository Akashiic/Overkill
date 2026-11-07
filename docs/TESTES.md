# Testes no servidor

## 2026-11-07: servidor do TEOF (Crucible) e servidor enxuto

Os arquivos do servidor de produção (Crucible `1.7.10-staging-67b53034`, Java 21, 215 mods, 63 plugins), com o
Künftig da branch (API 9), o Overkill e o AkashicFishing 1.4.0 compilados na hora. Cópia isolada: DiscordSRV, LiteBans,
PixCraft e BungeeGuard fora, LuckPerms em H2 em vez do MySQL.

### 1. Servidor completo

| O quê | Resultado |
|---|---|
| Boot com os 215 mods e os plugins | **ok**, `Done (89s)` com 10 GB de heap e um mundo novo |
| Künftig | todas as APIs habilitadas (pesca, itens, partículas, entrada, tela, lâminas e efeitos) |
| Overkill | habilitado, 5 lâminas; recebe o canal `kunftig:input` |
| AkashicFishing | habilitado; vê o Künftig API v9 e o NecroTempus |
| `/overkill`, `/overkill recarregar` pelo console | ok |
| Desligar | os dois plugins desabilitam sem erro |

O servidor do pacote não tinha o Künftig nem o AkashicFishing: os dois precisam entrar em `mods/` e `plugins/`.

Ao rodar o servidor sem terminal (em segundo plano), o leitor de console do Crucible lê "linha vazia" sem parar e
enche a memória de comandos (117 milhões de `ServerCommand`, 10 GB em minutos). Não é dos mods: com um console de
verdade (ou um pipe na entrada padrão) não acontece. Fica o aviso para quem roda o servidor por script.

### 2. Servidor enxuto + cliente de testes

O mesmo Crucible com só UniMixins, lwjgl3ify, GeckoLib e Künftig, o Overkill com os drops a 100%, e o cliente de
desenvolvimento do Künftig rodando o roteiro `overkill` (`OverkillServerHarness`): entra, pega a Kusanagi, trava num
zumbi, faz o combo, a Slash Art, mata, e usa a forja, registrando chat, descrição do item e vida dos alvos.

![Lock-on, Judgement Cut, combate em terceira pessoa e a forja](capturas/teste-no-servidor.jpg)

| O quê | Resultado |
|---|---|
| Estrela sorteada ao empunhar | ok ("Kusanagi no Tsurugi: ★ Estrela Prata - Obtida!") e "Forjada por" |
| Lock-on (Sneak) | ok: mira dourada com o anel de vida no zumbi, câmera puxada |
| Combo (cliques direitos) | ok: 20 → 17,5 → 15,1 → 12,6 de vida; arco do corte; contador "1 HITS" ... "8 HITS" |
| Carga e Slash Art | ok: a katana sai da bainha, Judgement Cut no alvo travado (10,1 → 5,7) |
| Abates | ok: nível 1 → 3, almas, pedras e ofudas no tier do zumbi (comum, 25%) |
| Desgaste e reparo | ok: 1463/1500 depois da luta; o reparo custou 4 almas |
| Forja | ok: Shift+clique da katana, da pedra e do ofuda; APRIMORAR; o cano conta até o sorteio; "+1" na saída; a pedra gasta, o ofuda guardado (não houve regressão); o reparo |

### Achados e correções

* **`KunftigInput.bind` recusava `teclas`** (a API validava só as ações): a exceção no tick parava o combate de todos.
  Corrigido no Künftig (com teste) e o Overkill ficou resiliente: o erro de um jogador é logado uma vez e o resto segue,
  e se um Künftig antigo recusar as teclas, liga só a tecla especial.
* **O nome do item piscava sobre a hotbar durante a luta**: cada gravação dos dados na katana faz o cliente mostrar o
  nome de novo. Agora a gravação espera uma pausa na luta (3 segundos sem acertar), sobe de nível na hora, e no máximo
  a cada 10 segundos.

### A verificar

* Com o cliente completo do modpack (o cliente de testes só tem o Künftig): Backhand, Angelica, NecroTempus.
* A Slash Art das outras katanas (ondas, corte circular, wave edge) e o combo B e aéreo, que o roteiro ainda não faz.
* Vários jogadores ao mesmo tempo e o PvP.
