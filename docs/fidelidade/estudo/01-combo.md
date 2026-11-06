<!-- Estudo do código do SlashBlade: Resharped (MIT, (c) 2024 M Mysterious Mountain Forging-shop Group), do Double Edged Reforged (GPL-3.0) e, como referência, do SlashBlade_2 (flammpfeil). Só leitura; os trechos citados são curtos e servem à análise. A arte desses projetos é "All Rights Reserved" e não está neste repositório. -->

# SlashBlade: Resharped (Forge 1.20.1, v1.9.65): combo system, input and melee attacks

Source: `ref/resharped` @ commit `6e2a0a0` ("update SlashBlade 1.9.65").
All paths are relative to `src/main/java/mods/flammpfeil/slashblade/` unless noted. "L" means line.
Study method: reading only. Nothing from the repository was built or run. The only arithmetic I ran was a small
Python script that reproduces the Java `double`/`float` conversions for the tick and ms tables.

---

## 0. TL;DR: the model in 12 lines

1. Every blade (ItemStack capability `ISlashBladeState`) stores **`comboSeq`** (a ResourceLocation of a `ComboState`) and **`lastActionTime`** (world game-time in ticks when that state was entered).
2. A `ComboState` is a node in a graph. It has a **motion frame range** (VMD frames at **30 fps**), a **speed**, an optional extra **timeout ms**, a **priority** (lower number = stronger), `next` (where a click goes), `nextOfTimeout` (where it goes when time runs out), and callbacks (`clickAction` on enter, `tickAction` every tick, `holdAction` every tick while right-click is held, `releaseAction` on right-click release, `hitEffect` per successful hit).
3. **State lifetime** = `(int)(|end-start| * 1000/30 / speed) + timeout` milliseconds. It is checked in ms against `elapsedTicks*50`. When it is exceeded, the state switches to `nextOfTimeout` and `lastActionTime` is reset.
4. **Clicks** come from the server-side calls `Item.use()` (right click → adds `R_CLICK`) and `onLeftClickEntity` (left click **on an entity only** → adds `L_CLICK`). Each calls `progressCombo`.
5. `progressCombo`: `next = current.getNext(user)`. If `next == current` the click is **ignored** (lock window or "uninterruptible" state). Otherwise it is compared with the *root* (standby) command picked from the currently held keys. The **lower priority number wins**, and the tie goes to the combo continuation.
6. Lock windows use `TimeoutNext(frames)`: clicks before `(int)(frames*2/3)` ticks of the state are **dropped (no buffer)**.
7. Keys held (W/A/S/D, sneak, jump, RMB/LMB/MMB down, the special-move key "V" = `SPRINT`) are sent client→server as a 32-bit enum bitmask every time they change (`MoveCommandMessage`). `ON_GROUND`/`ON_AIR` is added on the server from `onGround()`.
8. Attacks: `AttackManager.doSlash(...)` does **not** hit directly. It spawns an `EntitySlashEffect` (lifetime 10 ticks). On ticks 2,4,6,8,10 of its life the effect runs an **area attack centred on the player** (the player's *current* position and look at that tick). The area is a sphere of radius `reach = ENTITY_REACH−1` (4.5 blocks with a blade) measured feet-to-feet, inside an AABB pushed forward by reach/2. Each target is hit **at most once per slash effect**.
9. Damage per hit = `(ATTACK_DAMAGE + sweepingBonus + rankBonus + smite/bane bonus) × comboRatio × (slashEffectCrit ? 1.1 : 1) × slashblade_damage_attr(1.0) × config(1.0) × (vanilla falling crit ? 1.5 : 1)`. **Invulnerability frames are bypassed** (`invulnerableTime = 0` before each hit).
10. Knockback is replaced through a per-target NBT "factor" that the next `LivingKnockBackEvent` consumes. `cancel`, `toss` (vy ≥ 0.75), `meteor` (vy ≤ −5.5) and `smash` (horizontal ×1.5) are the types. All except `smash` zero the horizontal knockback.
11. Each successful hit with a `hitEffect` stuns PathfinderMobs: 10 ticks by default (15, 36 or 40 for some moves). While stunned the mob's AI is frozen, its fall is slowed (vy×0.25), or it is slammed down (vy−2/tick) if its fallDistance > 5.
12. Animation playback is **real-time 1:1**: `frame = startFrame + (elapsedTicks + partial) * 50ms / 33.33ms`, clamped to the state's span. `speed` changes only the state duration (logic), **not the playback rate**.

---

## 1. `ComboState`: definition and field semantics

File: `registry/combo/ComboState.java`

| Field (L) | Type | Builder default (L273-289) | Semantics |
|---|---|---|---|
| `motionLoc` (32) | ResourceLocation | `DefaultResources.ExMotionLocation` = `slashblade:combostate/motion.vmd` | VMD file of the **blade motion** (bone `hardpointA`). Piercing states use `combostate/piercing.vmd` (`DefaultResources.testLocation`). |
| `start` (35), `end` (37) | int, **frames** | (none) | Frame range in the VMD, **30 fps** (`TimeValueHelper`: "20ticks = 1sec, 30frames = 1sec"). |
| `speed` (39) | float | 1.0 | Divides the duration only (`getTimeoutMS`). It is **not** used by the renderer (see §7). |
| `loop` (40) | boolean | false | Renderer only: `time %= span` (LayerMainBlade L268-270). Used by `none`, `standby`, `aerial_cleave_loop`. |
| `timeout` (43) | int **ms** | 0 | Extra ms added to the motion span ("Next input acceptance period *ms"). Only `standby` (1000) and `aerial_cleave_loop` (1000) use it. |
| `next` (45) | `Function<LivingEntity,RL>` | `→ "none"` | Destination on a click (see `progressCombo`). If it returns the **current state id**, the click is ignored. |
| `nextOfTimeout` (46) | `Function<LivingEntity,RL>` | (no default; always set) | Destination when the duration runs out. It is also used on landing for `aerial()` states (FallHandler). |
| `holdAction` (48) | Consumer | no-op | Called from `Item.onUseTick` every tick **while the use (right) button is held** (both sides). It receives the **raw** `comboSeq` (not timeout-resolved). |
| `tickAction` (50) | Consumer | `ArrowReflector::doTicks` | Called every tick from `inventoryTick` (both client and server) when the blade is the selected main-hand item. `addTickAction` chains with `andThen`, so the default arrow reflector is always first. |
| `hitEffect` (52) | BiConsumer(target, attacker) | no-op | Called from `ItemSlashBlade.hurtEnemy` after a **successful** hit, using the attacker's state *at hit time* (resolved). In practice it is always `StunManager.setStun`. |
| `clickAction` (54) | Consumer | no-op | Called by `updateComboSeq` right after the state becomes current, whatever the cause (click, timeout transition, guard, ability…). Many states put their first slash here. |
| `releaseAction` (56) | BiFunction(user, ticksIntoState) → ArtsType | `→ Fail` | Called from `doChargeAction` (right-click release) when the charge was not a "Jackpot". `releaseActionQuickCharge` (L161-170) gives the "just sheath" bonus. |
| `isAerial` (58) | boolean | false | On `LivingFallEvent`/`PlayerFlyableFallEvent` (landing), the state is replaced with `nextOfTimeout` (FallHandler L50-62). |
| `priority` (60) | int | **1000** | Lower = stronger. It is compared in `progressCombo` (combo continuation vs root command) and in `doChargeAction`/`SuperSlashArts` (an SA may start only if `current.priority > sa.priority`). |

Key methods:

```java
// ComboState.java L82-85
public int getTimeoutMS() {
    return (int) (TimeValueHelper.getMSecFromFrames(Math.abs(getEndFrame() - getStartFrame())) / getSpeed()) + timeout;
}
// TimeValueHelper: FramesToMSec = 1000.0/30.0 (L13), FramesToTicks = 20.0/30.0 (L37), TicksToMSec = 50 (L7)
```

`TimeoutNext` (L172-198) is the lock window:

```java
static public TimeoutNext buildFromFrame(int timeoutFrame, Function<...> next) {
    return new TimeoutNext((int) TimeValueHelper.getTicksFromFrames(timeoutFrame), next);   // truncation!
}
public ResourceLocation apply(LivingEntity e) {
    long elapsed = ComboState.getElapsed(e);              // raw ticks since lastActionTime (+1 on client)
    if (timeout <= elapsed) return next.apply(e);
    else return state.getComboSeq();                      // == current → progressCombo ignores the click
}
```

Frame→tick truncation table (`(int)(f*20/30)` in Java doubles): 3→2, 4→2, 5→3, 6→4, 7→4, 9→6, 10→6, 11→7, 13→8, 15→10, 18→12, 21→14, 22→14, 33→22.

`TimeLineTickAction` (L200-247) maps **ticks** (not frames) to actions: `put(int ticks, action)`. When ticked it looks up `adjustElapsed = elapsed - offset`. `offset` is an **instance field** set at the first call ever (see §7.3 for the quirk this causes). Where the source writes `(int) TimeValueHelper.getTicksFromFrames(n)` as a key, the value is truncated as in the table above.

`getElapsed` (L249-252) → `ISlashBladeState.getElapsedTime(user)` (ISlashBladeState L152-160):

```java
long ticks = Math.max(0, level.getGameTime() - getLastActionTime());
if (level.isClientSide()) ticks = Math.max(0, ticks + 1);     // client sees one tick more
```

`releaseActionQuickCharge` (L161-170):

```java
int level = EnchantmentHelper.getEnchantmentLevel(Enchantments.SOUL_SPEED, user);
if (elapsed <= 3 + level) return ArtsType.Jackpot;   // "just" timing (+advancement)
else return ArtsType.Fail;
```

---

## 2. State machine engine (ISlashBladeState)

File: `capability/slashblade/ISlashBladeState.java`

### 2.1 Timeout resolution: `resolvCurrentComboStateTicks` (L432-458)

```java
current = registry.containsKey(getComboSeq()) ? getComboSeq() : NONE;
currentCS = registry.get(current);
int time = (int) getMSecFromTicks(getElapsedTime(user));          // ticks*50
while (!current.equals(NONE) && currentCS.getTimeoutMS() < time) { // strictly greater
    time -= currentCS.getTimeoutMS();
    current = currentCS.getNextOfTimeout(user);  // (NextOfTimeOutComboEvent may override/cancel→NONE)
    this.updateComboSeq(user, current);          // sets comboSeq, lastActionTime=now, runs clickAction
}
return (ticks=(int)(time/50), current);
```

* This is called every tick from `ItemSlashBlade.inventoryTick` (L488) **before** `tickAction`. It is also called from `progressCombo`, `hurtEnemy`, `doChargeAction`, etc.
* Quirk: `currentCS` is never reassigned inside the loop. Because `updateComboSeq` resets `lastActionTime` to *now*, the leftover `time` is thrown away on the next call. **Effective behaviour: at most one transition per tick, and the new state starts with elapsed 0 on the tick of the transition.** If the elapsed time is long enough for several timeouts (for example the blade was not held), the loop keeps applying the *first* state's `nextOfTimeout` until `time` drops below that first state's timeout. Timeouts then catch up one per later tick.
* A state therefore ends on the first tick where `elapsedTicks*50 > timeoutMS`, that is at elapsed tick `floor(timeoutMS/50)+1` (column "expires" in the table, §3).

### 2.2 Entering a state: `updateComboSeq` (L411-423)

Posts `BladeMotionEvent` (cancellable; the server-side listener broadcasts it to players within 20 blocks for player animation) → `setComboSeq` → `setLastActionTime(level.getGameTime())` → `clickAction(entity)`.

### 2.3 Click: `progressCombo(user, isVirtual)` (L305-337)

```java
currentloc = resolvCurrentComboState(user);        // applies pending timeouts first
current = registry.get(currentloc); if (current == null) return NONE;
next = current.getNext(user);                       // NextComboEvent may override / cancel(→NONE, no change)
if (!next.equals(NONE) && next.equals(currentloc)) return NONE;    // click ignored
rootNext = registry.get(getComboRoot()).getNext(user);             // root = "standby" unless blade overrides
resolved = (nextCS.priority <= rootNextCS.priority) ? next : rootNext;
if (!isVirtual) updateComboSeq(user, resolved);
return resolved;
```

Consequences:
* **No input buffering.** A click during a lock window (or in a state whose `next` is itself) is discarded.
* A state whose `next` returns its own id cannot be interrupted by clicks at all, not even by root commands. Examples: `aerial_cleave*`, `combo_b1_end`, `combo_b_end`, `judgement_cut*`, `void_slash`, `sakura`… (see table).
* If `next` is `"none"` (priority 1000), the root command always wins, unless it is also `"none"`. That is how a fresh A1 starts after the end states.
* The root command can **override** a combo continuation when it has a strictly lower number. Example: during combo A (100), `Sneak+Forward+RMB` (rapid slash, 70) or `Sneak+Back+RMB` (upperslash, 90) take over.
* Clicking with `next == "none"` while root also gives `"none"` (for example in the air with no matching entry; this cannot happen because `{ON_AIR}` always matches) → `updateComboSeq(none)`.

### 2.4 Root ("standby") command table: `ComboCommands` (`registry/combo/ComboCommands.java` L15-48)

`standby.next = ComboCommands::initStandByCommand`. It takes the current command set `getCommands(user)` (held keys + transient click + ON_GROUND/ON_AIR) and selects, among map entries whose key set is **fully contained** in it, the one whose target state has the **minimum priority number** (fallback `none`):

| Required inputs (all must be present) | Target state | priority |
|---|---|---|
| ON_GROUND, SNEAK, FORWARD, R_CLICK | `rapid_slash` | 70 |
| ON_GROUND, BACK, SNEAK, R_CLICK | `upperslash` | 90 |
| ON_GROUND, L_CLICK | `combo_a1` | 100 |
| ON_GROUND, R_CLICK | `combo_a1` | 100 |
| ON_AIR, SNEAK, BACK, R_CLICK | `aerial_cleave` | 70 |
| ON_AIR (any click) | `aerial_rave_a1` | 80 |

(Iteration is over a HashMap and `Stream.min` keeps the first minimum, so ties are irrelevant here because tied entries lead to the same state.) Holding Forward and Back together with Sneak+RMB picks rapid slash (70 < 90). Note that `L_CLICK` is only ever produced by hitting an entity (§4), so **in the air only right click (or LMB on an entity) starts aerial combos**.

`blade.getComboRoot()` defaults to `standby` (SlashBladeState L320-325). Named blades may define another root, which is how addon movesets plug in.

`combo_b1_alt` (`BACK+R_DOWN`) is declared (L12) but unused in the base mod.

### 2.5 Tick driver: `ItemSlashBlade.inventoryTick` (`item/ItemSlashBlade.java` L447-502)

For every slashblade stack in the inventory of a LivingEntity, on **both sides**:
1. `INPUT_STATE.getScheduler().onTick(living)` (timer queue used by abilities).
2. `loc = state.resolvCurrentComboState(living)` (timeouts). It returns NONE if the main hand is not a SlashBlade.
3. If the stack is the selected main-hand item: `cs.tickAction(living)`. If the id is unknown, `comboSeq = comboRoot`.

Server timing detail: click packets are processed **between** server ticks, so `updateComboSeq` at click time stores `lastActionTime = G`. The next tick increments game time to G+1 before players tick (`ServerPlayer.doTick` runs in the connection phase after the level tick). The first `tickAction` of a **click-started** state therefore sees `elapsed = 1` on a dedicated server. A **timeout-started** state (transition inside `inventoryTick`) gets its first `tickAction` with `elapsed = 0` in the same tick. The same holds for states set from `onUseTick` (`rising_star`, `upperslash_jump`): `updatingUsingItem` runs before `inventory.tick()` in `LivingEntity.tick/aiStep`.

### 2.6 Right-click hold / release

* `ItemSlashBlade.use` (L171-193): adds `R_CLICK` → `progressCombo` → removes `R_CLICK`. It swings the arm if the result is not `none`, and **always `startUsingItem`** (use duration 72000, L166). Holding RMB is therefore "using the item": vanilla calls `use()` only once per press.
* `onUseTick` (L416-444) runs every tick while held (both sides): `currentComboState(rawComboSeq).holdAction(player)`. It then posts `ChargeActionEvent` (cancelled when the blade is broken, sealed or not ENCHANTED, SlashBladeEventHandler) and, server side, spawns PORTAL particles when `ticksUsing == fullChargeTicks (9)`.
* `releaseUsing` (L382-413), server only, needs an ENCHANTED blade that is not broken or sealed: `sa = state.doChargeAction(entity, elapsed = 72000 - timeLeft)`. If an SA started, it costs `slashArts.getProudSoulCost()` (20) proud souls, or 1 durability if there are not enough (free in creative), then swings.

`doChargeAction` (ISlashBladeState L343-409):

```java
if (elapsed <= 2) return NONE;                         // a short tap does nothing
if (broken || sealed) return NONE;
(ticksIntoState, current) = resolvCurrentComboStateTicks(user);
// "Uninterrupted": inside a TimeoutNext lock window (getNext returns the very same RL instance) → NONE
if (current != NONE && current.getNext(user) == currentloc) return NONE;   // reference equality!
full = 9 (SlashArts.ChargeTicks); span = min(5, 3 + SoulSpeedLvl); just = full + span;
type = elapsed <  full ? Fail : elapsed < just ? Jackpot : Success;
if (type != Jackpot) { r = current.releaseAction(user, ticksIntoState); if (r != Fail) type = r; }  // quick charge
csloc = slashArts.doArts(type, user);   // Jackpot→comboStateJust, Success→comboState, Super→comboStateSuper, Fail→none
// PerformSlashArtEvent may change/cancel
if (csloc != NONE && !current.equals(csloc) && current.priority > cs.priority) updateComboSeq(user, csloc);
```

`SlashArts` constants (`slasharts/SlashArts.java` L26-33): `ChargeTicks = 9`, `ChargeJustTicks = 3`, `ChargeJustTicksMax = 5`, `getJustReceptionSpan = min(5, 3 + SoulSpeed level)`. SA states have priority 50 (45 for `judgement_cut_slash_just`), so they can start from every state with a higher number (combo 100, upperslash 90, aerial 80, rapid/cleave/rising-star-end 70). They can never start from another SA state (50 > 50 is false).

---

## 3. Complete table of registered combo states (base mod)

File `registry/ComboStateRegistry.java`. All use `combostate/motion.vmd` except the piercing states (`combostate/piercing.vmd`). Durations come from `getTimeoutMS`; "expires" is the first elapsed tick (server) at which the state is replaced; "lock" is the TimeoutNext tick threshold (clicks earlier than that are dropped). In the Attack column `tN:` is a TimeLineTickAction key (ticks since the state's first tick, §7.3), `click:` is clickAction (runs when entering), `hold:` is holdAction. If not listed, `hitEffect = StunManager::setStun` (10 ticks) for attacking states and none for end states.

`doSlash` signature used below: **`doSlash(roll°, offset, mute, critical, ratio, knockback)`**. Defaults are offset=`Vec3.ZERO` and knockback=`cancel`. The 5-arg overload is `doSlash(e, roll, mute, critical, ratio)`. "rush" = `genRushOffset()` = `((rand−0.5)·2, (rand−0.5)·2, 0)`. "up" = `(rand−0.5, 0.8, 0)`. `r` = `random.nextFloat()`.

### 3.1 Standby

| id (L) | frames | spd | prio | timeoutMS / expires | next (click) | nextOfTimeout | notes |
|---|---|---|---|---|---|---|---|
| `none` (37) | 0-1 loop | 1 | 1000 | 33 (never times out: the loop skips NONE) | none | none | tick: `resetRot` |
| `standby` (42) | 0-1 loop | 1 | 1000 | 1033 / 21 | `ComboCommands.initStandByCommand` | none | root state |

### 3.2 Ground combo A / C (start: ground + RMB or LMB on an entity)

| id (L) | frames | prio | timeoutMS / expires | lock | next | nextOfTimeout | attacks / actions |
|---|---|---|---|---|---|---|---|
| `combo_a1` (48) | 1-10 (9) | 100 | 300 / 7 | 3 | `combo_a2` | `combo_a1_end` | **click:** `doSlash(-10, mute=T, crit=F, 0.44)`; tick resetRot |
| `combo_a1_end` (55) | 10-21 (11) | 100 | 366 / 8 | none | `combo_a2` | `combo_a1_end2` | releaseAction **quickCharge**; no hitEffect |
| `combo_a1_end2` (61) | 21-41 (20) | 100 | 666 / 14 | none | none | none | (player anim comment: "guard" pose; non-just guard enters it, §6.3) |
| `combo_a2` (66) | 100-115 (15) | 100 | 500 / 11 | 3 | `combo_a3` | `combo_a2_end` | **click:** `doSlash(170, T, F, 0.44)` |
| `combo_a2_end` (71) | 115-132 (17) | 100 | 566 / 12 | none | **`combo_c`** | `combo_a2_end2` | quickCharge |
| `combo_a2_end2` (77) | 132-151 (19) | 100 | 633 / 13 | none | none | none | |
| `combo_c` (82) | 400-459 (59) | 100 | 1966 / 40 | 10 | none | `combo_c_end` | t2 `doSlash(-30, F, F, 0.88)`; t3 `doSlash(-35, T, F, 0.88)`; click: advancement |
| `combo_c_end` (92) | 459-488 (29) | 100 | 966 / 20 | none | none | none | t0 quick-sheath sound (CHAIN_HIT); quickCharge |
| `combo_a3` (100) | 200-218 (18) | 100 | 600 / 13 | 6 | `isPowered ? combo_a4_ex : combo_a4` | `combo_a3_end` | t2 `doSlash(-61, F, F, 0.44)`; t6 `doSlash(138, F, F, 0.44)` |
| `combo_a3_end` (113) | 218-230 (12) | 100 | 400 / 9 | none | **`combo_b1`** | `combo_a3_end2` | |
| `combo_a3_end2` (118) | 230-281 (51) | 100 | 1700 / 35 | none | none | `combo_a3_end3` | |
| `combo_a3_end3` (123) | 281-306 (25) | 100 | 833 / 17 | none | none | none | t0 sheath sound; quickCharge |
| `combo_a4` (131) | 500-576 (76) | 100 | 2533 / 51 | 14 | none | `combo_a4_end` | t8 `doSlash(45, F, F, 0.44)`; t9 `doSlash(50, T, F, 0.44)`; body spin `setRot(+72°)` t8-t12, reset t13 |
| `combo_a4_end` (148) | 576-608 (32) | 100 | 1066 / 22 | none | none | none | t0 sheath sound; quickCharge |
| `combo_a4_ex` (156) | 800-839 (39) | 100 | 1300 / 27 | 14 | `combo_a5ex` | `combo_a4_ex_end` | t7 `doSlash(70, F, F, 1.0)`; t14 `doSlash(255, F, F, 1.0)` |
| `combo_a4_ex_end` (166) | 839-877 (38) | 100 | 1266 / 26 | none | none | `combo_a4_ex_end2` | |
| `combo_a4_ex_end2` (170) | 877-894 (17) | 100 | 566 / 12 | none | none | none | t0 sheath sound; quickCharge |
| `combo_a5ex` (178) | 900-1013 (113) | 100 | 3766 / 76 | 22 | none | `combo_a5ex_end` | t15 `doSlash(35, F, T, 1.0)`; t17 `doSlash(40, T, T, 1.0)`; t19 `doSlash(30, T, T, 1.0)`; spin t13-17, reset t18 |
| `combo_a5ex_end` (196) | 1013-1061 (48) | 100 | 1600 / 33 | none | none | none | t0 sheath sound; quickCharge |

`isPowered(e)` (AttackManager L38-49) = has Strength **or Hunger** effect (overridable by `PowerBladeEvent`).

### 3.3 Ground combo B ("rush", entered by clicking during `combo_a3_end`)

The motion range 710-720 is replayed for b2..b6. Alternating rush slashes: on even keys `roll = -90+180r, mute=F`, on odd keys `roll = 90+180r, mute=T`. All use offset = rush, crit=F, ratio 0.244.

| id (L) | frames | prio | timeoutMS / expires | lock | next | nextOfTimeout | attacks |
|---|---|---|---|---|---|---|---|
| `combo_b1` (204) | 700-720 (20) | 100 | 666 / 14 | 8 | `combo_b2` | `combo_b1_end` | t6: `doSlash(-30, F, F, .244)` **and** `doSlash(145, T, F, .244)`; t7..t14: 8 alternating rush slashes (t7,9,11,13: −90+180r unmuted; t8,10,12,14: 90+180r muted), .244; click: advancement |
| `combo_b1_end` (248) | 720-743 (23) | 100 | 766 / 16 | **self (uninterruptible)** | `combo_b1_end` | `combo_b1_end2` | t9 `doSlash(0, up, F, **T**, **1.0**)`; t10 `doSlash(5, up, T, F, **1.0**)`; spin t9-13, reset t14 |
| `combo_b1_end2` (266) | 743-764 (21) | 100 | 700 / 15 | none | none | `combo_b1_end3` | |
| `combo_b1_end3` (270) | 764-787 (23) | 100 | 766 / 16 | none | none | none | t0 sheath sound (**no** quickCharge here) |
| `combo_b2` (276) | 710-720 (10) | 100 | 333 / 7 | 4 | `combo_b3` | `combo_b_end` | t0..t6: 7 alternating rush slashes .244 (t0 unmuted −90+180r, t1 muted 90+180r, …) |
| `combo_b3` (298) | 710-720 | 100 | 333 / 7 | 4 | `combo_b4` | `combo_b_end` | same as b2 (t2 uses ratio **0.24** instead of .244, apparently a typo) |
| `combo_b4` (320) | 710-720 | 100 | 333 / 7 | 4 | `combo_b5` | `combo_b_end` | same as b2 |
| `combo_b5` (341) | 710-720 | 100 | 333 / 7 | 4 | `combo_b6` | `combo_b_end` | same |
| `combo_b6` (363) | 710-720 | 100 | 333 / 7 | 4 | `combo_b7` | `combo_b_end` | same |
| `combo_b7` (385) | 710-764 (54) | 100 | 1800 / 37 | 22 | none | `combo_b7_end` | t0..t7: 8 alternating rush slashes .244; t12 `doSlash(0, up, F, T, .244)`; t13 `doSlash(5, up, T, F, .244)`; spin t12-16, reset t17; click: advancement |
| `combo_b7_end` (421) | 764-787 (23) | 100 | 766 / 16 | none | none | none | t0 sheath sound; quickCharge |
| `combo_b_end` (427) | 720-743 (23) | 100 | 766 / 16 | **self** | `combo_b_end` | `combo_b_end2` | t9 `doSlash(0, up, F, T, .244)`; t10 `doSlash(5, up, T, F, .244)`; spin t9-13 |
| `combo_b_end2` (445) | 743-764 (21) | 100 | 700 / 15 | none | none | `combo_b_end3` | |
| `combo_b_end3` (449) | 764-787 (23) | 100 | 766 / 16 | none | none | none | t0 sheath sound; quickCharge |

### 3.4 Aerial rave (start: in air + any click)

All "attack" aerial states call `FallHandler::fallDecrease` every tick (hover, §5.2). States marked aerial switch to `nextOfTimeout` on landing.

| id (L) | frames | prio | aerial | timeoutMS / expires | lock | next | nextOfTimeout | attacks / actions |
|---|---|---|---|---|---|---|---|---|
| `aerial_rave_a1` (456) | 1100-1122 (22) | 80 | yes | 733 / 15 | 3 | `aerial_rave_a2` | `aerial_rave_a1_end` | t2 (=frames 3) `doSlash(-20, F, F, 0.28)`; fallDecrease; resetRot |
| `aerial_rave_a1_end` (466) | 1122-1132 (10) | 80 | no | 333 / 7 | none | none | none | fallDecrease; t0 sheath sound; quickCharge |
| `aerial_rave_a2` (474) | 1200-1210 (10) | 80 | yes | 333 / 7 | 3 | `aerial_rave_a3` | `aerial_rave_a2_end` | t2 `doSlash(150, F, F, 0.28)`; fallDecrease |
| `aerial_rave_a2_end` (483) | 1210-1231 (21) | 80 | yes | 700 / 15 | none | **`aerial_rave_b3`** | `aerial_rave_a2_end2` | fallDecrease |
| `aerial_rave_a2_end2` (488) | 1231-1241 (10) | 80 | no | 333 / 7 | none | none | none | fallDecrease; t0 sheath sound; quickCharge |
| `aerial_rave_a3` (496) | 1300-1328 (28) | 80 | yes | 933 / 19 | 6 | none | `aerial_rave_a3_end` | t2 (=frames 4) `doSlash(0, ZERO, F, F, .28, **smash**)`; t3 `doSlash(-3, ZERO, T, T, .28, smash)`; click: advancement |
| `aerial_rave_a3_end` (511) | 1328-1338 (10) | 80 | no | 333 / 7 | none | none | none | fallDecrease; sheath sound; quickCharge |
| `aerial_rave_b3` (519) | 1400-1437 (37) | 80 | yes | 1233 / 25 | 6 | `aerial_rave_b4` | `aerial_rave_b3_end` | **click: `vy = 0.6`** (keeps x/z); t3 (=frames 5) `doSlash(237, ZERO, F, F, .34, **toss**)`; t6 (=frames 10) same again; body rot −90°/tick t0-3, −120°/tick t4-6, reset t7 |
| `aerial_rave_b3_end` (544) | 1437-1443 (6) | 80 | no | 200 / 5 | none | none | none | fallDecrease; sheath; quickCharge |
| `aerial_rave_b4` (552) | 1500-1537 (37) | 80 | yes | 1233 / 25 | 6 | none | `aerial_rave_b4_end` | t6 (=frames 10) `doSlash(45, ZERO, F, F, .34, **meteor**)`; t7 `doSlash(50, ZERO, T, T, .34, meteor)`; rot +90° t5-8 |
| `aerial_rave_b4_end` (572) | 1537-1547 (10) | 80 | no | 333 / 7 | none | none | none | fallDecrease; sheath; quickCharge |

### 3.5 Upperslash ("Rising/upper slash"), upperslash jump

| id (L) | frames | prio | timeoutMS / expires | lock | next | nextOfTimeout | attacks / actions |
|---|---|---|---|---|---|---|---|
| `upperslash` (583) | 1600-1659 (59) | 90 | 1966 / 40 | 7 | none | `upperslash_end` | t4 (=frames 7) `doSlash(-80, ZERO, F, F, 0.50, **toss**)`; **hitEffect stun 15**; **hold:** when `ticksUsingItem == 6` (=frames 9) and the held commands contain `BACK` and `R_DOWN` → `updateComboSeq(upperslash_jump)` |
| `upperslash_end` (614) | 1659-1693 (34) | 90 | 1133 / 23 | none | none | none | sheath; quickCharge |
| `upperslash_jump` (621) | 1700-1713 (13) | 90 | 433 / 9 | 4 | none | `upperslash_jump_end` | aerial; **click: `vy = 0.6`, onGround=false**; rot +90° t0-3; fallDecrease. (No attack of its own.) |
| `upperslash_jump_end` (639) | 1713-1717 (4) | 90 | 133 / 3 | none | none | none | sheath; fallDecrease; quickCharge |

### 3.6 Aerial cleave (in air + Sneak + Back + RMB)

| id (L) | frames | prio | timeoutMS / expires | next | nextOfTimeout | actions |
|---|---|---|---|---|---|---|
| `aerial_cleave` (647) | 1800-1812 (12) | 70, aerial | 400 / 9 | **self** | `aerial_cleave_loop` | **click:** `vy = 0.1` (keeps x/z). **Every tick:** `fallDistance=1`; elapsed==2 → PLAYER_ATTACK_STRONG sound; elapsed>2 → `vy -= 3.0`; elapsed even → `areaAttack(meteor, 0.44, forceHit=T, resetHit=F, mute=T)`; if onGround → `doSlash(55, ZERO, T, T, 0.44, meteor)` + switch to `aerial_cleave_landing` + landing particles; **elapsed==1 → switch to `aerial_cleave_loop`** (so on a server it lasts about one tick). rot +90° t0-3. **No hitEffect.** |
| `aerial_cleave_loop` (696) | 1812-1817 (5) loop, timeout +1000 | 70 | 1166 / 24 | self | none | Every tick: `fallDistance=1`, **`vy -= 3.0`**; elapsed even → `areaAttack(meteor, .44, T, F, T)`; onGround → `doSlash(55, ZERO, T, T, .44, meteor)` + `aerial_cleave_landing`. hitEffect stun **15**. |
| `aerial_cleave_landing` (720) | 1816-1859 (43) | 70 | 1433 / 29 (lock 4) | none | `aerial_cleave_end` | **click:** `doSlash(60, ZERO, F, F, .44, meteor)`; resetRot; **no hitEffect** |
| `aerial_cleave_end` (728) | 1859-1886 (27) | 70 | 900 / 19 | none | none | sheath; quickCharge |

### 3.7 Rapid slash (疾走居合, "dash iai": ground + Sneak + Forward + RMB) and Rising Star

| id (L) | frames | prio | timeoutMS / expires | next | nextOfTimeout | actions |
|---|---|---|---|---|---|---|
| `rapid_slash` (736) | 2000-2019 (19) | 70 | 633 / 13 | `isPowered ? rapid_slash_quick : rapid_slash(self→ignored)` | `rapid_slash_end` | **tick:** elapsed==0 → ARMOR_EQUIP_IRON sound; `elapsed<=3 && onGround` → `moveRelative(inWater?0.35:0.8, forward)` (dash); `2<=elapsed<6` → `doSlash(roll=-45+90r (+180 if elapsed even), rush, F, crit=isPowered, 0.44)`; elapsed==7 → `doSlash(-30, rush, F, T, 0.44)`; elapsed 7..10 `setRot(+90°)`, >10 `setRot(0)`. **hold** (ticksUsing ≥ 2, every tick): temporary `ENTITY_REACH −3` (reach becomes 1.5), `areaAttack(beforeHit=X, 0.44, forceHit=T, resetHit=F, mute=T)`. X: if `R_DOWN` is held and the state is still `rapid_slash` → nested `areaAttack(toss, 0.44, T, **resetHit=T**, T)`; if it found anything → `updateComboSeq(rising_star)`. click: advancement. |
| `rapid_slash_quick` (817) | 2000-2001 (1) | 70 | 33 / 1 | self | `rapid_slash` | t0 sheath sound; quickCharge. (Powered: re-clicking re-arms a new rapid slash 1 tick later.) |
| `rapid_slash_end` (825) | 2019-2054 (35) | 70 | 1166 / 24 | none | `rapid_slash_end2` | |
| `rapid_slash_end2` (830) | 2054-2073 (19) | 70 | 633 / 13 | none | none | sheath; quickCharge |
| `rising_star` (837) | 2100-2137 (37) | 80, aerial | 1233 / 25 (lock 12) | none | `rising_star_end` | **click:** `setDeltaMovement(0, 0.6, 0)`, onGround=false; `doSlash(-57, ZERO, F, F, 0.6, toss)`. t6 (=frames 9) `doSlash(-57, ZERO, F, F, 0.6, toss)`. elapsed<3: `if vy<=0 vy=0.6`, x=z=0. rot +72° t0-9, reset t10. fallDecrease. |
| `rising_star_end` (882) | 2137-2147 (10) | 70 | 333 / 7 | none | none | fallDecrease; sheath; quickCharge |

### 3.8 Slash-art states (reached only through `doChargeAction` / `SuperSlashArts`)

SA registry (`registry/SlashArtsRegistry.java`): `judgement_cut` (ground → `judgement_cut`, air → `judgement_cut_slash_air`, just → `judgement_cut_slash_just`, super → `judgement_cut_end`); `sakura_end` (ground/air left); `void_slash`; `circle_slash`; `drive_vertical`; `drive_horizontal`; `wave_edge` (→ `wave_edge_vertical`); `piercing` (just → `piercing_just`). The default "super" for every SA is `judgement_cut_end`.

| id (L) | frames | spd | prio | timeoutMS / exp | next | nextOfTimeout | actions |
|---|---|---|---|---|---|---|---|
| `judgement_cut` (890) | 1900-1923 (23) | 1 | 50 | 766 / 16 | self | `judgement_cut_slash` | elapsed==0: TRIDENT_THROW sound; elapsed<=3: backstep `moveRelative(-0.3, fwd)` + sneak-style edge clamp + direct `move(SELF, vec)`; **every tick horizontal velocity = 0**; fallDecrease |
| `judgement_cut_slash` (959) | 1923-1928 | **0.4** | 50 | 416 / 9 | self | `judgement_cut_sheath` | t0 `JudgementCut.doJudgementCut` (spawns EntityJudgementCut at the lock-on target's eyes, else ray-trace (5 blocks for blocks / 7 for entities), else 5 blocks ahead); fallDecrease |
| `judgement_cut_sheath` (967) | 1928-1963 (35) | 1 | 50 | 1166 / 24 | none | none | sheath; fallDecrease; quickCharge |
| `judgement_cut_slash_air` (975) | 1923-1928 | **0.5** | 50 | 333 / 7 | self | `judgement_cut_sheath_air` | t0 doJudgementCut; **fallResist** |
| `judgement_cut_sheath_air` (989) | 1928-1963 | 1 | 50 | 1166 / 24 | none | none | sheath; quickCharge |
| `judgement_cut_slash_just` (998) | 1923-1928 | 1 | **45** | 166 / 4 | self | `judgement_cut_slash_just2` | **t1** `doJudgementCutJust` (critical JC); fallResist |
| `judgement_cut_slash_just2` (1011) | 1923-1928 | **0.75** | 50 | 222 / 5 | self | `judgement_cut_slash_just_sheath` | fallResist |
| `judgement_cut_slash_just_sheath` (1019) | 1928-1963 | 1 | 50 | 1166 / 24 | none | none | sheath; quickCharge |
| `judgement_cut_end` (1207) | 1923-1928 | 1 | 50 | 166 / 4 | self | `judgement_cut_sheath` | t0 `doJudgementCutSuper` (JC on every target within the reach+32 sphere, and Slowness X for 40 ticks) |
| `void_slash` (1029) | 2200-2277 (77) | 1 | 50 | 2566 / 52 | self | `void_slash_sheath` | **every tick `setDeltaMovement(0,0,0)`**; t16 `doVoidSlashAttack` (an effect with 0 dmg per tick that marks hits; on despawn after 36 ticks it hits each marked target for `(ATK*(1+0.1*Power)+rankBonus)*scale*mult*5.1` indirect magic + push 0.5 forward); rot −36° t16-20, 18° t57-66; hitEffect stun **40** |
| `void_slash_sheath` (1056) | 2278-2299 | 1 | 50 | 700 / 15 | none | none | sheath; quickCharge |
| `sakura_end_left` (1065) | 1816-1859 | **6** | 50 | 238 / 5 | `sakura_end_right` | `sakura_end_right` | click `SakuraEnd.doSlash(22.5, ZERO, F, F, 0.5)` (identical to `doSlash`) |
| `sakura_end_right` (1073) | 204-218 | **1.1** | 50 | 424 / 9 | none | `sakura_end_finish` | click `doSlash(157.5, ZERO, F, T, 0.76)`; stun **36** |
| `sakura_end_finish` (1081) | 218-281 | 1 | 50 aerial | 2100 / 43 | none | `sakura_end_finish2` | |
| `sakura_end_finish2` (1085) | 281-314 | 1 | 80 | 1100 / 23 | none | none | sheath; quickCharge |
| `sakura_end_left_air` (1092) | 1300-1328 | **3.2** | 50 aerial | 291 / 6 | `…right_air` | `…right_air` | click doSlash(22.5, F, F, 0.5); fallDecrease |
| `sakura_end_right_air` (1100) | 1200-1210 | 1 | 50 aerial | 333 / 7 | none | `…finish_air` | click doSlash(157.5, F, T, 0.76); fallDecrease |
| `sakura_end_finish_air` (1108) | 1210-1231 | 1 | 50 aerial | 700 / 15 | none | `…finish2_air` | fallDecrease |
| `sakura_end_finish2_air` (1113) | 1231-1241 | 1 | 50 | 333 / 7 | none | none | sheath; quickCharge; fallDecrease (twice) |
| `circle_slash` (1123) | 725-743 (18) | 1 | 50 | 600 / 13 | self | `circle_slash_end` | t4..t7 `CircleSlash.doCircleSlashAttack(yRot = 180, 90, 0, -90)`: a slash effect with roll 0, crit=T, ratio **0.325**, cancel, visual yaw `playerYaw − 22.5 + yRot`; spin t4-8 |
| `circle_slash_end` (1140) | 743-764 | 1 | 100 | 700 / 15 | none | `circle_slash_end2` | |
| `circle_slash_end2` (1144) | 764-787 | 1 | 100 | 766 / 16 | none | none | sheath (no quickCharge) |
| `drive_horizontal` (1150) | 400-459 | 1 | 50 | 1966 / 40 (lock 10) | none | `drive_horizontal_end` | t2 `doSlash(-30, ZERO, F, F, 0.21)`; t3 `Drive.doSlash(roll 0, lifetime 10, ZERO, crit F, damage 1.5, speed 2)` |
| `drive_horizontal_end` (1160) | 459-488 | 1 | 50 | 966 / 20 | none | none | sheath; quickCharge |
| `drive_vertical` (1168) | 1600-1659 | 1 | 50 | 1966 / 40 (lock 10) | none | `drive_vertical_end` | t2 `doSlash(-80, ZERO, F, F, 0.21)`; t3 `Drive.doSlash(-90, 10, ZERO, F, 1.5, 2)` |
| `drive_vertical_end` (1181) | 1659-1693 | 1 | 50 | 1133 / 23 | none | none | sheath; quickCharge |
| `wave_edge_vertical` (1193) | 1600-1659 | 1 | 50 | 1966 / 40 (lock 10) | none | `drive_vertical_end` | t2 `doSlash(-80, ZERO, F, F, 0.21)`; t3 `WaveEdge.doSlash(roll 90, lifetime 20, ZERO, F, dmg 0.4, minSpeed 0.2, maxSpeed 1.0, count 4)` (the loop `i<=count` gives **5** drives) |
| `piercing` (1221) | 1-33 (piercing.vmd) | 1 | 50 | 1066 / 22 | self | `piercing_2` | resetRot (wind-up) |
| `piercing_2` (1228) | 33-55 | 1 | 50 | 733 / 15 (lock 6) | none | `piercing_end` | elapsed<3: `moveRelative(0.8 / 0.35 in water, fwd)` + `areaAttack(toss, **1.1**, forceHit T, resetHit F, mute T)`; elapsed==1 TRIDENT_THROW |
| `piercing_just` (1247) | 34-55 | 1 | 50 | 700 / 15 (lock 6) | none | `piercing_end` | same as piercing_2 |
| `piercing_end` (1266) | 55-65 | 1 | 50 | 333 / 7 | none | `piercing_end2` | |
| `piercing_end2` (1273) | 65-90 | 1 | 50 | 833 / 17 | none | none | sheath; quickCharge |

### 3.9 Flow chart (ground)

```
standby/none --RMB|LMB(entity)--> A1 --click(>=3t)--> A2 --click(>=3t)--> A3 --click(>=6t)--> A4 (or A4ex if powered --click(>=14t)--> A5ex)
                                   |timeout(7t)        |timeout(11t)        |timeout(13t)
                                   v                   v                    v
                                 A1_end --click--> A2 A2_end --click--> C   A3_end --click--> B1 --click(>=8t)--> B2 ->...-> B7 (each >=4t, B7 lock 22t)
                                   v                   v                    v                    |timeout            |timeout of B2..B6
                                 A1_end2 -> none     A2_end2 -> none      A3_end2 -> A3_end3   B1_end(finisher x2 @1.0) -> ...  B_end(finisher @.244)
Sneak+Fwd+RMB: rapid_slash (dash 0.8/t, 5 slashes) [hold RMB and touch an enemy -> rising_star]
Sneak+Back+RMB: upperslash (toss) [keep Back+RMB held: at use-tick 6 -> upperslash_jump vy 0.6]
air: any click -> aerial_rave_a1 -> a2 -> a3 (smash) ;  a2 -> (wait) a2_end -> click -> b3 (vy .6, toss x2) -> b4 (meteor)
air: Sneak+Back+RMB -> aerial_cleave (vy -3/tick, meteor area hits every 2 ticks, landing slashes)
```

---

## 4. Input

### 4.1 `InputCommand` enum (`util/InputCommand.java` L7)

`FORWARD, BACK, LEFT, RIGHT, SNEAK, R_DOWN, L_DOWN, M_DOWN, R_CLICK, L_CLICK, ON_GROUND, ON_AIR, SPRINT, JUMP` ("32bit limit"). They are serialized as `EnumSetConverter.convertToInt` (bit i = ordinal i). `InputCommand.move = {FORWARD, BACK, LEFT, RIGHT}`.

### 4.2 Client sampling: `event/handler/MoveInputHandler.java` L108-186

It runs on `ClientTickEvent` phase END, only if the main hand holds a SlashBlade:
* `FORWARD/BACK/LEFT/RIGHT` ← `player.input.up/down/left/right` (movement keys, not velocity)
* `SNEAK` ← `input.shiftKeyDown`; `JUMP` ← `input.jumping`
* `SPRINT` ← `KEY_SPECIAL_MOVE` (default key **V**, `client/SlashBladeKeyMappings` L10). Note: this is **not** the vanilla sprint key.
* `R_DOWN` ← `options.keyUse.isDown()`; `L_DOWN` ← `keyAttack.isDown()`; `M_DOWN` ← `KEY_SUMMON_BLADE` (default **middle mouse**).
* If the set differs from the last one, the client stores the new set and the press times (`lastPressTimes[c] = client gameTime` for newly pressed keys) and sends a **`MoveCommandMessage{int command}`** to the server.

### 4.3 Server: `network/MoveCommandMessage.handle` (L33-71)

Only if the main hand is a SlashBlade: replace `IInputState.commands` with the received set, record `lastPressTimes[c] = server gameTime` for newly set bits, and post `InputCommandEvent(old, current)`. Abilities listen to it (SlayerStyleArts dodges, SuperSlashArts, LockOn, SummonedSwords, KickJump, EnemyStep, TargetSelector "revenge mark" = Sneak + MMB). No combo state changes directly on key change; only clicks advance combos.

`IInputState.getCommands(owner)` (L25-36) returns a clone with `ON_GROUND` if `owner.onGround()` else `ON_AIR`. The capability is attached to every LivingEntity (CapabilityAttachHandler).

### 4.4 Clicks

* **Right click** → vanilla `ServerboundUseItemPacket` → `ItemSlashBlade.use` (L171-193): transient `R_CLICK` → `progressCombo` → remove → `startUsingItem` (always). It also runs client-side (prediction). Holding RMB never re-calls `use` (the item is in use), so **one press = one combo step**. Holding feeds `holdAction` and charge (§2.6).
* **Left click on an entity** → `Player.attack` → Forge `onLeftClickEntity` (L196-209): if `!state.onClick()`, transient `L_CLICK` → `progressCombo`. It **returns true, which cancels the vanilla attack entirely**. The left-clicked entity is not damaged directly: damage comes only from the slash effects / area attacks of the combo state. `onClick` is set during `doMeleeAttack` to stop recursion.
* **Left click in the air or on a block does nothing** for combos (no handler or mixin exists).
* `onEntitySwing` (L735) cancels the vanilla swing animation unless `lastActionTime == gameTime` (the swing happens only on the tick a combo step started).

### 4.5 Timing windows (summary)

* Continuation accepted from `elapsed >= lockTicks` (TimeoutNext) until the state times out. After that the `_end` state's `next` may still continue the chain (A1_end → A2, A2_end → C, A3_end → B1, aerial a2_end → b3). Later end states (`_end2`, `_end3`) have `next = none`, so a click there starts a fresh root command.
* Clicks in the lock window are **dropped**. There is no queue or buffer.
* Sneak / back / forward conditions are evaluated **at the moment of the click** from the last received key state.
* "Hold" checks: upperslash jump requires `BACK` + `R_DOWN` held exactly at use-tick 6. Rising star requires `R_DOWN` held while the rapid-slash hold area attack finds a target (from use-tick 2 until rapid slash ends, which is 13 ticks).

---

## 5. Melee hit detection, damage, knockback, stun

### 5.1 `AttackManager.doSlash` (`util/AttackManager.java` L86-132), server only

```java
DoSlashEvent ev = new DoSlashEvent(blade, state, player, roll, critical, comboRatio, knockback);  // cancellable
Vec3 pos = player.position().add(0, eyeHeight*0.75, 0).add(look.scale(0.3));
pos = pos.add(UP.scale(centerOffset.y))                    // getVectorForRotation(-90, yaw) == (0,1,0)
         .add(RIGHT.scale(centerOffset.z))                 // getVectorForRotation(0, yaw+90) == player's right
         .add(look.scale(centerOffset.z));                 // NOTE: offset.x is never used; z is used twice
EntitySlashEffect jc = new EntitySlashEffect(...);
jc.setPos(pos); jc.setOwner(player); jc.setRotationRoll(ev.roll); jc.setYRot(player.yRot); jc.setXRot(0);
jc.setColor(blade.colorCode); jc.setMute(mute); jc.setIsCritical(ev.critical);
jc.setDamage(ev.damage /* = comboRatio */); jc.setKnockBack(ev.knockback); jc.setRank(rankLevel);
level.addFreshEntity(jc);
```

So `offset` is **visual only**. The hit area does not depend on it (next section). `rush` offsets only shift the arc up/down by `(rand−0.5)*2`.

### 5.2 `EntitySlashEffect` (`entity/EntitySlashEffect.java`)

* `lifetime = 10` (L59), no gravity, never moves (`shoot` → zero velocity unless it is a wave).
* `tick()` (L281-361):
  * `tickCount == 2`: sound. Not muted → TRIDENT_THROW (vol 0.8, pitch 0.625+0.1r). Muted → PLAYER_ATTACK_SWEEP (0.5, 0.4/(0.8+0.4r)). If critical → also WITHER_HURT (0.2, 0.4+0.25r).
  * Particles / landing dust (cosmetic).
  * **Hit:** `if (tickCount % 2 == 0)`, i.e. on ticks **2, 4, 6, 8, 10**:
    ```java
    float ratio = (float) damage * (isCritical ? 1.1f : 1.0f);
    hits = AttackManager.areaAttack(shooter, this.action.action /*knockback setter*/, ratio,
                                    forceHit=true, resetHit=false, mute=true, exclude=alreadyHits);
    if (!cycleHit) alreadyHits.addAll(hits);   // each target is hit once per slash effect
    ```
  * Despawn when `lifetime < tickCount` (removed at tick 11).
* **The area attack is centred on the shooter (player)**, using the player's position and look at that tick, **not** on the effect. A single `doSlash` can therefore catch targets during ~10 ticks after it starts (5 checks), each at most once. Overlapping effects (rush slashes, one per tick) each hit independently, which is how B gives about 10 hits.

### 5.3 `AttackManager.areaAttack(LivingEntity, beforeHit, ratio, forceHit, resetHit, mute, exclude)` (L228-254)

Server: `founds = TargetSelector.getTargettableEntitiesWithinAABB(level, player)` minus `exclude`. For each: `beforeHit.accept(living)` (sets the knockback factor), then `doMeleeAttack(player, e, forceHit, resetHit, ratio)`. Unless `mute`, it plays PLAYER_ATTACK_SWEEP (vol 0.5). It returns the found list, which **includes entities whose damage failed**.

`doMeleeAttack` (L329-342): `doManagedAttack` → `state.setOnClick(true); AttackHelper.attack(attacker, t, ratio); setOnClick(false)`, then `ArrowReflector.doReflect(target)` (a projectile target is re-shot along the player's look at speed 3.5, inaccuracy 0.2, crit arrow) and `TNTExtinguisher.doExtinguishing`.

`doManagedAttack` (L305-315): **`if (forceHit) target.invulnerableTime = 0;`** attack; `if (resetHit) target.invulnerableTime = 0;`. Every slash-effect hit uses forceHit=true, so vanilla i-frames never block SlashBlade hits. resetHit=false leaves `invulnerableTime=20` after the hit, which blocks *other* damage sources unless they are larger.

### 5.4 Target selection: `util/TargetSelector.java`

```java
static double getResolvedReach(LivingEntity user) {        // L236-243
    double reach = 4.0;
    AttributeInstance a = user.getAttribute(ForgeMod.ENTITY_REACH.get());
    if (a != null) reach = a.getValue() - 1;
    return reach;
}
static AABB getResolvedAxisAligned(AABB bb, Vec3 dir, double reach) {   // L222-234
    if (dir == Vec3.ZERO) bb = bb.inflate(reach*2);
    else bb = bb.move(dir.scale(reach*0.5)).inflate(reach);
    return bb.inflate(1.0);     // padding
}
```

* `ENTITY_REACH` (Forge, base 3.0) + blade modifier `ReachModifier.BladeReach() = +2.5` (broken: `+1.25`) (`item/ReachModifier.java`, ItemSlashBlade L142-145) gives 5.5, so **reach = 4.5** (broken: 3.25). During rapid-slash hold there is a −3 modifier, so reach = 1.5.
* AABB = player bounding box (0.6×1.8×0.6) moved by `look*reach/2` (3D, so it follows the pitch), inflated by `reach+1` on every side. With reach 4.5 that is 11.6 × 12.8 × 11.6 centred about 2.25 blocks ahead.
* Candidates (L137-174), concatenated (duplicates possible):
  1. Projectiles in the AABB with `distSq(player) < reach²` whose owner is not the player (they get reflected).
  2. `PrimedTnt` in the AABB with `distSq < reach²` (they get extinguished).
  3. Multipart entities (dragon) in `aabb.inflate(5)` → their parts, if the parent passes `AttackablePredicate` and the part is within reach.
  4. Every `LivingEntity` in the AABB that passes `SlashBladeTargetingConditions.range(reach).ignoreInvisibilityTesting().selector(AttackablePredicate)`.
* Vanilla `TargetingConditions.test` (combat mode): `attacker != target`, `target.canBeSeenAsEnemy()`, selector, `attacker.canAttack(target)`, `canAttackType`, `!isAlliedTo`, and **range: `distanceToSqr(attacker, target.position) <= max(reach,2)²`**. That is a feet-to-feet sphere. No line-of-sight check is done for players (LOS applies only to Mob attackers).
* **Net hit volume:** sphere of radius 4.5 around the player's feet, cut by the forward-shifted AABB. Behind the player the box ends `reach/2 + 1.3 = 3.55` blocks from the centre along each axis, so targets behind are hit only within about 3.55 blocks on the axis-aligned rear side. The slash `roll` angle does **not** affect hits.
* `SlashBladeTargetingConditions.test` (L291-313): if `target.getLastHurtByMob()` is the attacker (or allied), or the target is a Mob whose `getTarget()` is the attacker (or allied), it tags the target `"RevengeAttacker"`.
* `AttackablePredicate` (L61-97), in order:
  * Player and `pvp_enable=false` (default) → false.
  * ArmorStand → `isMarker()`.
  * Has the `RevengeAttacker` tag → remove the tag, true.
  * `friendly_enable=false` (default) and not `Enemy` → false.
  * Has a player passenger → false.
  * Glowing → true.
  * Has a team → true.
  * Otherwise `!type in ATTACKABLE_BLACKLIST` tag.

  **By default only hostile mobs, and mobs that hit or target you (or that you marked with Sneak+MMB), are hit.**

### 5.5 Damage: `util/AttackHelper.attack` (L36-77) and `calculateTotalDamage` (L82-98)

```java
if (player && !ForgeHooks.onPlayerAttackTarget(player, target)) return;   // AttackEntityEvent
if (!target.isAttackable() || target.skipAttackInteraction(attacker)) return;
boolean isCritical = fallDistance>0 && !onGround && !onClimbable && !inWater && !Blindness
                     && !passenger && target instanceof LivingEntity && !isSprinting;   // vanilla jump-crit
double dmg = attr(ATTACK_DAMAGE)
           + 10 * (EnchantmentHelper.getSweepingDamageRatio(attacker) * 0.5)   // Sweeping: I 2.5, II 3.33, III 3.75
           + getRankBonus(attacker)
           + EnchantmentHelper.getDamageBonus(mainHand, target.getMobType());  // Sharpness/Smite/Bane
dmg *= comboRatio * attr(slashblade:slashblade_damage, default 1.0, range 0..512) * config.slashblade_damage_multiplier(1.0);
if (player) { CriticalHitEvent e = ForgeHooks.getCriticalHit(player, target, isCritical, isCritical?1.5F:1.0F);
              if (e != null) dmg *= e.getDamageModifier(); }
if (dmg <= 0) return;
knockback = attr(ATTACK_KNOCKBACK) + Knockback ench + (sprinting ? 1 : 0);
fireAspect pre-ignite 1s;  target.hurt(playerAttack(player), dmg)
on success: if (knockback>0) living.knockback(kb*0.5, sin(yaw), -cos(yaw)), attacker vel x/z *= 0.6, stop sprint;
            restore ServerPlayer target motion (PvP); PLAYER_ATTACK_CRIT sound; crit particles if crit;
            setLastHurtMob; post-hurt/post-damage enchant effects; item.hurtEnemy (→ hitEffect/stun + 1 durability);
            fire aspect lvl*4 s; DAMAGE_INDICATOR particles; exhaustion 0.1
on failure: PLAYER_ATTACK_NODAMAGE sound; undo pre-ignite
```

* `comboRatio` passed down from the slash effect = `doSlash ratio × (critical flag ? 1.1 : 1.0)`.
* **`ATTACK_DAMAGE`** comes from `ItemSlashBlade.getAttributeModifiers` (L103-151). It replaces the sword modifier (vanilla `BASE_ATTACK_DAMAGE_UUID`) with `base + amplifier − 1`, so the attribute total = `1 (player base) + base + amp − 1` = **`base + amp`** (+ Strength etc.):
  * `base` = `baseAttackModifier` (default 4.0, per-blade definition).
  * `amp` = `(1 − 1/(1 + f·refine)) · base` with `f = 0.1` for FIERCEREDGE blades, else 0.05. This tends to +100% of base.
  * Broken: `amp = −0.5 − base`, so the total is −0.5 → clamped to 0 by the attribute.
  * Can be changed by `UpdateAttackEvent`.
* **Rank bonus** `getRankBonus` (L110-125): `rank.level / 2.0`. Levels: NONE 0, D 1, C 2, B 3, A 4, S 5, SS 6, SSS 7, so the bonus is +0…+3.5. If rank ≥ S: `max(level/2, min(playerXpLevel, refine) * refine_damage_multiplier(1.0))`. Rank points: unit capacity 300, every hit with a blade adds 0.1 unit (30 points), every hurt taken removes 1 unit, decay 1 point per tick (within the current rank), max 6 units. (Details belong to the rank study.)
* Vanilla-equivalent jump crit ×1.5 still applies (sprinting disables it). The slash-effect "critical" flag is a separate ×1.1.

Example: a plain blade (base 4, refine 0, rank NONE, no enchantments). A1 = 4·0.44 = **1.76**. A4ex slash = 4.0. B rush slash = 0.976. B1_end finisher 1st slash = 4·1.0·1.1 = 4.4.

Other attack routes:
* Area attack with an `IShootable` owner (L267-303; used by drives / JC / summoned swords, not by melee slash effects): `(owner.damage [+0.1·Power if not a SlashEffect]) × ATTACK_DAMAGE + rankBonus, × comboRatio × scale × mult`, indirect magic damage. Target search is `owner.bbox.inflate(reach)`.
* Indirect slash effects (`indirect` flag) use radius 4.0 around the effect. This is not used by base melee.

### 5.6 Knockback types: `util/KnockBacks.java` L9-12 + `event/handler/KnockBackHandler.java`

The knockback "action" is the `beforeHit` consumer. It writes NBT `knockback_factor = (horizontal, vertical, addFallDistance)` onto the target. `onLivingKnockBack` (L35-81) consumes it on the target's next `LivingKnockBackEvent` (normally the one fired by `LivingEntity.hurt` → `knockback(0.4, dx, dz)`):

```java
factor = nbt; nbt.remove;
if (target.fallDistance < 0) target.fallDistance = 0;
target.fallDistance += factor.z;
if (rand < KNOCKBACK_RESISTANCE) return;          // resisted: vanilla knockback proceeds normally
target.hasImpulse = true; motion = target.deltaMovement;
if (factor.x == 0) { event.setCanceled(true); motion = (0, motion.y, 0); }   // horizontal KB cancelled AND horizontal motion zeroed
else event.setStrength(strength * factor.x);
if (factor.y > 0) { onGround=false; setDeltaMovement(motion.x, max(motion.y, factor.y), motion.z); }
else if (factor.y < 0) setDeltaMovement(motion.x, min(motion.y, factor.y), motion.z);
```

| KnockBacks | factor (h, v, fall) | effect |
|---|---|---|
| `cancel` (default) | (0, 0, 0) | no horizontal knockback, horizontal velocity zeroed, vertical untouched (target stays in place) |
| `toss` | (0, +0.75, −0.75) | vy = max(vy, **0.75**), horizontal zeroed, fallDistance −0.75 (after clamping to ≥0, so it can go negative) |
| `meteor` | (0, −5.5, +5.5) | vy = min(vy, **−5.5**), horizontal zeroed, fallDistance +5.5 (above 5, so a stunned mob gets slammed, §5.7) |
| `smash` | (1.5, 0, 0) | vanilla knockback strength ×**1.5** (0.4 → 0.6), vertical untouched |

Caveat: the factor is written even when the hit fails (for example `isAttackable` false), and it stays until the next knockback event on that entity. If `ATTACK_KNOCKBACK > 0` or the player sprints, `AttackHelper.applyKnockback` calls `knockback()` a second time *after* the factor was consumed, so that one is vanilla.

1.7.10 note: in 1.7.10, `EntityLivingBase.knockBack` adds +0.4 vertical (clamped to 0.4) regardless of onGround. In 1.20 the vertical part is only applied when on ground (`min(0.4, vy/2+strength)`). There is no knockback event in 1.7.10, so emulate it: after `attackEntityFrom`, overwrite the motion according to the factor.

### 5.7 Hit-stop / stun: `ability/StunManager.java` (+ `capability/mobeffect`, `entity/ai/StunGoal.java`)

* Only `PathfinderMob`s (creatures with goals), not players.
* `setStun(target)` = 10 ticks (`DEFAULT_STUN_TICKS` L19). `setManagedStun(now, d)`: `timeout = now + min(d, stunLimit=200)`, applied only if it is later than the current timeout (it extends, never shortens).
* `StunGoal` is added at priority **−1** with flags MOVE, JUMP, LOOK, TARGET. It runs while stunned, so all AI movement, targeting and looking is frozen. `stop()` clears the stun.
* While stunned (`onEntityLivingUpdate` L31-49), every tick: if `fallDistance > 5` → `vy -= 2.0` (meteor slam); else if `vy < 0` → `vy *= 0.25` (air juggle / slow fall).
* Durations: 10 by default; `upperslash` and `aerial_cleave_loop` 15; `sakura_end_right` 36; `void_slash` 40. States without hitEffect do not stun: `aerial_cleave`, `aerial_cleave_landing`, all end states, `judgement_cut_slash_just2`, piercing_end. Note: the hitEffect used is the attacker's **state at the moment the effect hits** (resolved), and slash effects live 10 ticks. Late hits of a slash effect can therefore land during an `_end` state and not stun.
* There is no attacker hit-stop (no freeze of the player) in this version.

### 5.8 Arrow reflection while swinging: `ability/ArrowReflector.doTicks` (default tickAction)

While `ticksUsingItem > 0` (RMB held) and `ticks < (end−start)*2/3/speed` of the current state, every projectile within the reach sphere (same AABB) that is not owned by the player is re-shot along the player's look (speed 3.5, inaccuracy 0.2, crit).

---

## 6. Sheathing ("noutou"), quick draw / iai, "just" bonuses

### 6.1 Sheath states

The last state of every chain (`*_end`, `*_end2`, `*_end3`, `*_sheath`, `rapid_slash_quick`) plays **`SoundEvents.CHAIN_HIT` vol 1.0 pitch 1.0** at t0 (`AttackManager.playQuickSheathSoundAction` L344-350). That is the sheath "click". Most of them also have `releaseAction = releaseActionQuickCharge`. (Exceptions: no quickCharge on `combo_b1_end3` and `circle_slash_end2`. Also quickCharge **without** the sound on `combo_a1_end` and `combo_a2_end`.)

### 6.2 Quick-charge "just sheath" Slash Art

Hold RMB (charge) and release (`releaseUsing`). If the hold lasted more than 2 ticks and was not a natural Jackpot (holding 9…9+span−1 ticks), `current.releaseAction(user, ticksIntoCurrentState)` is tried. With `releaseActionQuickCharge` it returns **Jackpot when `ticksIntoState <= 3 + SoulSpeedLevel`**. The "just" variant of the blade's SA then starts (for Judgement Cut that is `judgement_cut_slash_just`, a critical JC, priority 45). In practice: keep RMB held through the last combo click and release right as the blade starts to sheath (the first 3 ticks, +1 per Soul Speed level).

The normal charge windows (§2.6) are: hold < 9 ticks → Fail; 9 ≤ hold < 9+min(5, 3+SoulSpeed) → **Jackpot ("just charge")**; longer → Success (normal SA). Release during an attack's TimeoutNext lock window → nothing ("Uninterrupted").

### 6.3 Just guard (`ability/Guard.java`)

The blade needs **Thorns**, the player must be on the ground, and hold Sneak with no movement key (or SPRINT key while vanilla-sprinting, "ninja run"). The attack must come from the front half (`attackVec·view < 0`). **Just** = `now − lastPressTime(SNEAK) < 5 + SoulSpeed` (L113-116).
* **Just guard:** any melee or projectile damage is cancelled, the combo is set to **`combo_a1`** (L164). Its clickAction fires a counter `doSlash(-10, mute, 0.44)`. The player gets 10 ticks Untouchable (L179) and rank points.
* **Normal guard:** projectiles only (and not armor-bypassing unless rank ≥ S), not during an attack's lock window. The combo is set to `combo_a1_end2` (guard pose) and it costs 1 durability unless rank ≥ S.
* A melee attacker is knocked back 0.5.

### 6.4 Quick draw / iai

There is no separate "iai" button in Resharped. Rapid slash (`rapid_slash`, the code comment calls it 疾走居合 "dash iai") is the quick-draw move: a dash plus 5 slashes, chainable when powered via `rapid_slash_quick`. Combo A1 itself is the draw slash from the sheathed (standby) state.

### 6.5 Other combo resets

* SlayerStyleArts sprint-dodge resets the combo to root (`updateComboSeq(root)`).
* EnemyStep and KickJump set `none`.
* `SuperSlashArts` (hold the special-move key V for 20 ticks on the ground, with a BEWITCHED + FIERCEREDGE blade at full durability, costing half the max durability) starts `slashArts.doArts(Super)` = `judgement_cut_end`, if `current.priority > 50`.

---

## 7. Time base: ticks vs frames, animation playback

### 7.1 Constants

* Motion frames: **30 fps** (VMD). `MmdMotionPlayer.updateMotion(ms)`: `frame = ms / (100/3)`, clamped to the VMD's max frame (`jp/nyatla/nymmd/MmdMotionPlayer.java` L174-183).
* Game: 20 ticks/s, so 1 tick = 50 ms = **1.5 frames**.
* `TimeoutNext` and timeline keys are in **ticks** (some are converted from frames with truncation).
* State durations are in **ms**, derived from frames.

### 7.2 Renderer (blade model): `client/renderer/layers/LayerMainBlade.java` L220-279

```java
double time = 50 * (max(0, gameTime - lastActionTime) + partialTicks);      // NO +1 here
while (combo != NONE && combo.getTimeoutMS() < time) { time -= combo.getTimeoutMS(); combo = combo.nextOfTimeout; }  // client-side look-ahead
if (combo == NONE) combo = root (standby);
span = min(maxMotionMs, |end-start| * 33.33);
if (combo.loop) time %= span;
time = min(span, time);                  // holds the last frame if the state lasts longer than its motion
mmp.updateMotion(startMs + time);        // frame = startFrame + time/33.33
```

**`speed` is ignored by the renderer.** With speed < 1 (JC slash 0.4/0.5/0.75) the state lasts longer and the last frame is held. With speed > 1 (sakura left 6.0, right 1.1, left_air 3.2) the state ends before its motion finishes, so the animation is cut and the next state's motion starts. The player body animation (`compat/playerAnim/PlayerAnimationOverrider`, file `model/pa/player_motion.vmd`, optional PlayerAnimator compat) also plays 1:1 in ticks (`VmdAnimation.tick: currentTick++`, `time = 50*(tick+partial)`). It is restarted on each `BladeMotionEvent` for mapped states, with ranges covering a whole chain (for example A1 → 1-41, A2 → 100-151, A3 → 200-306, B1 → 700-787, B2..B7 → 710-787, rapid → 2000-2073; full list in `PlayerAnimationOverrider.initAnimations`).

The body yaw spin (`UserPoseOverrider.setRot(e, deg, offset)`) stores `sb_yrot` in entity persistent NBT. It accumulates by `deg` per tick when `offset=true`: 72°×5 ticks = one full 360° spin. It is applied as an extra Y rotation of the whole player model at render time and is cosmetic only.

### 7.3 Timeline execution detail and quirks (for exact reproduction)

* `TimeLineTickAction.offset` (ComboState L201, L228-243) is per **ComboState instance**, shared by all players on that side and never reset.
  * First-ever call: `offset = elapsed`.
  * Then `adjust = elapsed − offset`.
  * If negative, the buggy code does `elapsed -= offset; offset = elapsed;` (offset becomes negative), adjust = 0. On the next call `offset < 0` re-captures the offset. A key can then fire twice.
* On a dedicated server, click-started states first tick at elapsed 1, so their timelines capture offset 1. Key `k` then fires at elapsed `k+1` (the k-th tick after the first processed tick). Timeout-started states (`*_end` sheath sounds) capture offset 0.
* In practice the intended semantics are: **key k = the (k+1)-th server tick of the state, counting the first tick on which the state is processed as k=0.** I recommend implementing that (store a per-player "ticks since state start" counter that is 0 on the first processed tick). Do not copy the shared-offset bug.
* Hand-written tick lambdas (rapid_slash, rising_star, aerial_cleave, judgement_cut, piercing) use raw `elapsed` (`gameTime − lastActionTime`, +1 on client) **without** an offset. On a server, rapid slash therefore dashes at elapsed 1..3 (elapsed 0 is never seen for click-started states) and slashes at elapsed 2,3,4,5 and 7. On the client the `+1` shifts things one tick earlier relative to client time. I am **not fully sure** how many dash impulses (3 or 4) the local client applies; see the uncertainties section.
* `clickAction` runs immediately when the state is entered (in packet processing for clicks). A1's and A2's slash effects are spawned on the click itself, and their first hit check happens 2 effect ticks later.

### 7.4 Movement during combos (all via `setDeltaMovement` / `moveRelative` in tick or click actions; they run on both sides, and for the local player the **client** side is what actually moves it)

| Move | Code | Effect |
|---|---|---|
| Aerial hover (all aerial attack/end states, JC ground/sheath, void slash, sakura air) | `FallHandler.fallDecrease` (L105-138) | If airborne and gravity enabled: `fallDistance = 1` (no fall damage build-up); `ratio = fallDecreaseRate` (then `+0.05`, capped at 1.0); `g = ENTITY_GRAVITY(0.08) × 0.85` (+0.02 per Feather Falling level, max 0.93); **if vy < 0: `vy = (vy + g) × ratio`**. `fallDecreaseRate` is reset to 0 only on landing (FallHandler.resetState), so the first aerial tick fully stops the fall and every later tick of air combat gives less hover (about 20 ticks of "air budget"). |
| Strong hover | `FallHandler.fallResist` (L140-154) | if vy < 0: `vy += g + 0.002` (JC air / just states) |
| Aerial rave B3 launch | click | `vy = 0.6` |
| Upperslash jump | click | `vy = 0.6`, `onGround = false` |
| Rising star | click + elapsed<3 | `vel = (0, 0.6, 0)`; first 3 ticks re-assert vy = 0.6 if falling, x = z = 0 |
| Aerial cleave dive | click / tick | click `vy = 0.1`; then `vy -= 3.0` **per tick** (from elapsed 3 in `aerial_cleave`, every tick in the loop); `fallDistance = 1` |
| Rapid slash dash | tick | `elapsed <= 3 && onGround`: `moveRelative(0.8, (0,0,1))`: adds 0.8 × forward(yaw) to velocity each tick (0.35 in water) |
| Piercing dash | tick | `elapsed < 3`: `moveRelative(0.8/0.35, forward)` |
| Judgement Cut backstep | tick | `elapsed <= 3`: `moveRelative(-0.3, forward)` + edge clamp (like sneaking: shrinks x/z in 0.05 steps while no ground below) + direct `move(SELF, vec)`; **every tick** horizontal velocity set to 0 (rooted) |
| Void slash | tick | velocity = 0 every tick |
| Attacker slow-down | AttackHelper.applyKnockback | when knockback > 0: attacker x/z × 0.6, stop sprinting |

1.7.10 equivalents:
* `moveRelative(speed, (0,0,1))` = `moveFlying(0, 1, speed)` (both normalize then add `speed·(−sin yaw, cos yaw)`).
* Gravity is 0.08 and air drag 0.98 in both versions.
* For a Bukkit-driven implementation, player movement must be pushed with velocity packets (`setVelocity`), because the client is authoritative for its own motion. In the original, these tick actions also run client-side through the item tick.

---

## 8. Uncertainties and quirks to double-check

1. **TimeLineTickAction offset** (§7.3): the shared, never-reset `offset` makes the exact firing tick depend on the very first use and on the side (in single-player the integrated server and the client even share the same object across threads). My reading of the 1.20.1 server tick order (packets processed between ticks; level game-time increment before `ServerPlayer.doTick`) gives offset 1 for click-started states on a dedicated server. That conclusion comes from memory of vanilla, not from this repository.
2. **Client vs server elapsed**: `getElapsedTime` adds +1 on the client, while `LayerMainBlade` does not. How the client's own predicted `progressCombo` (called in `use`/`onLeftClickEntity` client-side) is reconciled with the server's state depends on the ItemStack/capability sync, which I did not trace fully. `MotionBroadcastMessage` only drives the player-animation layer of nearby clients.
3. **Exact number of dash impulses** for rapid slash/piercing on the local client (3 vs 4) depends on the client tick order (when `use` runs vs `inventoryTick`).
4. `resolvCurrentComboStateTicks` reuses the first state's `nextOfTimeout` inside the loop (a bug). This matters only when more than one timeout elapses before the next resolve (blade not ticked).
5. `doSlash`'s `centerOffset.x` is ignored and `.z` is applied twice (right + forward). This is visual only.
6. `TargetingConditions` range semantics (feet-to-feet sphere, `max(range, 2)`, no LOS for players) are vanilla 1.20.1 behaviour recalled from memory, not from this repository. The shared static `TargetSelector.areaAttack` is mutated by `.range(reach)` on each call (thread-unsafe, but the result is still correct).
7. The 0.24 vs 0.244 ratio in `combo_b3` t2 and the 1.0 vs 0.244 ratio in `combo_b1_end` vs `combo_b_end` are in the source as-is. The first looks like a typo; the second looks intentional (B1 finisher).
8. Rapid-slash hold: the outer `areaAttack` deals a 0.44 hit to every target in 1.5 reach **every tick** while RMB is held (forceHit). The nested call (toss) deals another 0.44 when RMB is held. Behaviour is as written, but it is strong. Check whether you want to reproduce it exactly.
9. Left-click in the air does nothing in Resharped (unlike the original 1.7.10 SlashBlade). Combos are effectively right-click driven, and left-click works only on entities.
