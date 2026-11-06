<!-- Estudo do código do SlashBlade: Resharped (MIT, (c) 2024 M Mysterious Mountain Forging-shop Group), do Double Edged Reforged (GPL-3.0) e, como referência, do SlashBlade_2 (flammpfeil). Só leitura; os trechos citados são curtos e servem à análise. A arte desses projetos é "All Rights Reserved" e não está neste repositório. -->

# 05 — Double Edged Reforged (DER) and SlashBlade_2 vs Resharped differences

Read-only study. Nothing was built or run. Every number below comes from reading the source, or from parsing the shipped VMD files as data with small scripts kept outside the repos.

Path abbreviations used in references:

| Prefix | Path |
|---|---|
| `DER/` | `ref/der/src/main/java/com/cloudy/doubleedgedreforged/` |
| `DERres/` | `ref/der/src/main/resources/` |
| `RS/` | `ref/resharped/src/main/java/mods/flammpfeil/slashblade/` |
| `SB2/` | `ref/sb2/src/main/java/mods/flammpfeil/slashblade/` |

Version facts:
- DER 2.3.1 targets Forge 47.4.20 and requires SlashBlade Resharped 1.9.65 or later (`ref/der/gradle.properties`).
- The Resharped checkout is 1.9.65, MIT licensed.
- The sb2 checkout is "Slash Blade" 0.1.2, licensed "NyMmd-MIT:nyatla, ObjModelImporter:forge, All other Rights reserved." (`ref/sb2/gradle.properties`).

---

## 0. Time base you need for every table

- **Frames are 30 fps and ticks are 20 tps.** `TimeValueHelper.getTicksFromFrames(f) = f * (20.0/30.0)` (`RS/util/TimeValueHelper.java`).
- **DER always writes timeline keys as `(int) getTicksFromFrames(F)`.** Truncation maps two frames onto one tick:
  `0→0 1→0 2→1 3→2 4→2 5→3 6→4 7→4 8→5 9→6 10→6 11→7 12→8 13→8 14→9 15→10 16→10 17→11 18→12 19→12 20→13 21→14 22→14 23→15 24→16 25→16 26→17 27→18 28→18 29→19 30→20 31→20 32→21 33→22`.
- **`TimeLineTickAction` is a `HashMap<Integer, action>` filled with `put`** (`RS/registry/combo/ComboState.java:207-243`). When two frames truncate to the same tick, the later `put` replaces the earlier one. DER has several such collisions, so some attacks written in its source never fire. The tables below show only the actions that survive (marked "overwritten" where it matters).
- **Resharped's own combos mostly use raw tick keys** (`.put(8, …)`). DER converted them to frame keys, so timings shifted. For example, vanilla `combo_a4` hits at ticks 8 and 9, while `dual_combo_a4` hits at tick 8 only.
- **State duration:** `getTimeoutMS() = (int)(|end-start| * 33.333 / speed) + timeout` (`RS/registry/combo/ComboState.java:82-85`). DER never calls `.speed()`, so speed is 1.0 everywhere. `timeout` is 0 except in `dual_aerial_cleave_loop`, where it is 1000.
- **Elapsed ticks:** `elapsed = gameTime - lastActionTime`, plus 1 on the client (`RS/capability/slashblade/ISlashBladeState.java:152-156`).
- **Timeline quirk (port caveat):** each `TimeLineTickAction` instance keeps an `offset` field. It is set on the first call ever and is shared across all entities (`ComboState.java:228-243`). Treat a key as "ticks since the state started".
- **`TimeoutNext.buildFromFrame(F, x)` input window** (`ComboState.java:172-198`):
  - Before tick `int(F*2/3)`, `next` returns the current state. `progressCombo` then returns NONE, because next equals current (`ISlashBladeState.java:305-337`). The click is **dropped, not buffered**.
  - From that tick on, the click advances to `x`.
  - A plain `next(e -> X)` accepts input at any time.
- **Priority resolution:** `progressCombo` takes `next` only if `next.priority <= rootNext.priority`. Otherwise it takes the standby-command result (`ISlashBladeState.java:321-329`).
- **What a click is:** a left click on an entity (`onLeftClickEntity`) or a right-click `use` (`RS/item/ItemSlashBlade.java:170-209`). Neither of DER's combo `next` functions checks which button was used.

`AttackManager.doSlash` overloads (`RS/util/AttackManager.java:55-131`):
- `doSlash(e, roll, mute, critical, ratio)` uses offset `Vec3.ZERO` and `KnockBacks.cancel`.
- `doSlash(e, roll, Vec3 offset, mute, critical, ratio[, KB])` takes an explicit offset and knockback.
- Color comes from `getMainHandItem()`'s state, so a slash made in off-hand context gets the off-hand blade's color.

The spawn position is `pos = eye*0.75 + look*0.3 + up(pitch -90)*offset.y + right(yaw+90)*offset.z + look*offset.z` (`AttackManager.java:103-107`). `offset.x` is ignored and `offset.z` is used twice. Two consequences:
- `genRushOffset = (rnd-0.5, rnd-0.5, 0)*2` (`AttackManager.java:360-362`) only jitters the slash vertically, by ±1 block.
- `Vec3(rnd-0.5, 0.8, 0)` only raises the slash by 0.8.

---

## 1. What DER adds or changes

### 1.1 Entry point and registrations

- `DER/DoubleEdgedReforged.java:20-25` does three things in the mod constructor:
  - registers the `ComboState` DeferredRegister (`DualComboStateRegistry.COMBO_STATE`);
  - registers `DualComboRedirectHandler` on the Forge bus. This happens before Resharped registers its `BladeMotionEventBroadcaster` (common setup) and `PlayerAnimationOverrider` (client setup), so DER's redirect runs first;
  - registers the COMMON config.
- `DER/HeadBladeAttack.java:23` is an `@EventBusSubscriber`.
- Mixin config: `DERres/double_edged_reforged.mixins.json`.
  - Common: AttackManager, EntitySlashEffect, LivingEntity, ItemSlashBlade, SlashBladeUpdateEvent.
  - Client: CustomHeadLayer, FirstPersonStandbyOffhand, LayerMainBladeDualHardpoint, PlayerAnimationOverrider, YsmDualBladeLayer, YsmPlayerRenderer.

**Config** (`DER/Config.java:19-44`), COMMON, section "双刀流设置 / Dual Wield Settings":

| key | default | range | used by |
|---|---|---|---|
| `enableThreeSwordStyle` | true | bool | head blade equip/tick/attack |
| `enableNineSwordStyle` | true | bool | **unused** (no code references it) |
| `offhandDamageMultiplier` | 0.75 | 0–1 | `AttackManagerMixin` |
| `headDamageChance` | 0.5 | 0–1 | `HeadBladeAttack` |
| `headDamageMultiplier` | 0.5 | 0–1 | `AttackManagerMixin` |
| `nineSwordRampageMultiplier` | 0.35 | 0–1 | **unused** |
| `disableLegendRelicLibBuiltInDoubleAttack` | true | bool | LegendRelicLib compatibility |
| `useLegendRelicLibDoubleAttackTag` | true | bool | LegendRelicLib compatibility |

### 1.2 Dual-wield detection (`DER/DualWieldSlashBladeActions.java:12-28`)

- `isSlashBlade(stack)`: the stack is non-empty, its item is an `ItemSlashBlade`, and it has the `BLADESTATE` capability.
- `isDualWielding(e)`:
  - the raw MAINHAND slot holds a SlashBlade;
  - the raw OFFHAND slot holds a SlashBlade;
  - `canUseAsOffhandDualBlade(offhand)` is true. That check only matters when LegendRelicLib is loaded and the tag gate is on, in which case the off-hand item must carry `legendreliclib:can_double_attack` (`DER/LegendRelicLibCompat.java:35-46`).
- Detection runs for any `LivingEntity`, not only players. It reads raw slots through `getItemBySlot`, so the main-hand override described below never fools it.
- Nothing about the off-hand blade's own state (broken, sealed and so on) is checked.

### 1.3 Per-blade context: how an off-hand or head blade "becomes" the main hand

`DER/DualWieldContext.java` keeps a ThreadLocal `BladeContext(entity, stack, state, slot)`.

**`withBlade(entity, stack, state, slot, action)`** (lines 83-131) runs `action` while the context is active, then restores the previous context.
- If `entity` is a Player and `slot != MAINHAND`, it also calls `swapMainhandAttributes` (lines 133-196). The main-hand item's MAINHAND attribute modifiers are removed and the extra blade's MAINHAND modifiers are added as transient modifiers. Afterwards it swaps back.
- The swap runs under a global `synchronized` lock and is re-entrant through a depth counter.

**`LivingEntityMixin`** (`DER/mixin/LivingEntityMixin.java:16-39`):
- `getMainHandItem()` at HEAD returns the context stack when a non-mainhand context matches the entity.
- `setItemInHand(MAIN_HAND, s)` is redirected to `setItemSlot(contextSlot, s)`, so breakage writes go to the correct slot.

**Consequences.** Everything in SlashBlade that reads `getMainHandItem()` inside the context uses the extra blade:
- `DoSlashEvent` (and therefore SE hooks);
- slash color;
- `isPowered`;
- refine-based rank bonus;
- enchantment damage bonus (`AttackHelper.getEnchantmentBonus` uses `getMainHandItem()`);
- `hurtEnemy`: durability, `HitEvent`, and the hit-effect of the *extra blade's own* combo state. Because the off-hand state normally sits idle, **off-hand hits most likely do not apply the combo's `StunManager::setStun`**. This is inferred from `RS/item/ItemSlashBlade.java:347-366` and `RS/util/AttackHelper.java:236-245` and was not tested.

**Slash entities keep their context.**
- `AttackManagerMixin` RETURN calls `tagSlashEffect`, which stores the slot name in the effect's persistent NBT `DoubleEdgedReforgedBladeSlot` (`DualWieldContext.java:61-81`).
- `EntitySlashEffectMixin` (`DER/mixin/EntitySlashEffectMixin.java:21-53`) redirects the `AttackManager.areaAttack` call inside `EntitySlashEffect.tick`. If the effect is tagged, the redirect re-enters `withBlade(slot)` before the area attack. Damage dealt on the effect's later ticks (every 2nd tick) therefore still uses the off-hand or head blade's attributes and enchantments.

**Continuous SEs on extra blades.**
- `ItemSlashBladeMixin.inventoryTick` HEAD/RETURN (`DER/mixin/ItemSlashBladeMixin.java:46-110`) pushes and pops a context when the ticking stack is:
  - the off-hand blade while dual wielding, or
  - the head blade while three-sword mode is enabled and the main hand holds a SlashBlade.
- `isInMainhand` is forced to `false` for that context (lines 69-80), so the extra blade never runs combo tick actions.
- `SlashBladeUpdateEventMixin` (`DER/mixin/SlashBladeUpdateEventMixin.java:23-44`) makes `UpdateEvent.isSelected()` return:
  - `isDualWielding` for the off-hand blade;
  - `enableThreeSwordStyle && main is SB && head is SB` for the head blade.

  This exists because vanilla `Inventory.tick` passes `selected == 0` for offhand slot 0.

### 1.4 Combo redirect (`DER/DualComboRedirectHandler.java:17-63`)

DER listens to `BladeMotionEvent`, which is posted by `ISlashBladeState.updateComboSeq` (`RS/capability/slashblade/ISlashBladeState.java:411-423`) before `setComboSeq`. When all of the following hold, it replaces the event's combo id, and that id becomes the stored state:
- the entity is dual wielding;
- the active context is null or MAINHAND;
- the id is in the map below.

The same handler runs on the client for `MotionBroadcastMessage` replays.

| vanilla id (`slashblade:`) | → DER id (`double_edged_reforged:`) |
|---|---|
| combo_a1 / a2 / a3 / a4 | dual_combo_a1 / a2 / a3 / a4 |
| combo_a4_ex | dual_combo_a4_ex |
| combo_a5ex (`COMBO_A5`) | dual_combo_a5 |
| combo_b1 … combo_b7 | dual_combo_b1 … b7 |
| combo_c | dual_combo_c |
| aerial_rave_a1 / a2 | dual_combo_sky_1 / dual_combo_sky_2 |
| aerial_rave_a3 / b3 / b4 | dual_combo_sky_a3 / sky_b3 / sky_b4 |
| upperslash / upperslash_jump | dual_upperslash / dual_upperslash_jump |
| aerial_cleave / _loop / _landing | dual_aerial_cleave / _loop / _landing |
| rapid_slash / rapid_slash_quick | dual_rapid_slash / dual_rapid_slash_quick |
| rising_star | dual_rising_star |

**How entry works.**
- The real entry points are Resharped's standby command map (`RS/registry/combo/ComboCommands.java:31-48`):
  - ground + L_CLICK or R_CLICK → `combo_a1`;
  - ground + SNEAK + FORWARD + R_CLICK → `rapid_slash`;
  - ground + BACK + SNEAK + R_CLICK → `upperslash`;
  - air + SNEAK + BACK + R_CLICK → `aerial_cleave`;
  - air → `aerial_rave_a1`.
- Each of these is redirected. After entry, DER states chain to other DER states directly.
- `*_end` states are not in the map. They are reached only through DER's own `nextOfTimeout`.
- Slash-art states (judgement cut, drive, circle slash, sakura end, wave edge, void slash, piercing) are **not redirected**. They play exactly as in Resharped.

### 1.5 Complete dual combo state table

Source: `DER/DualComboStateRegistry.java`. Every state uses `motionLoc = double_edged_reforged:combostate/dual_player_motion.vmd` (`DER/ExtraResources.java:6`) and speed 1.0. Every attacking state has `addHitEffect(StunManager::setStun)` unless noted.

Notation:
- `t` = tick key after truncation, `F` = frame written in the source.
- Slash = `(roll, ratio[, flags][, offset][, KB])`. Flags: `m` = mute, `c` = critical. Default offset is ZERO, default KB is cancel.
- `RUSH` = `genRushOffset`, `rnd` = `random.nextFloat()`.
- Off-hand slashes are executed through `withBlade(OFFHAND)`, so their real ratio is ratio × 0.75 by default.
- "rot72" = `UserPoseOverrider.setRot(e, 72, true)`. It only has a visible effect when PlayerAnimator is absent.

#### Ground A line

| state (line) | frames | ms | next (input window) | timeout → | main-hand slashes | off-hand slashes | other |
|---|---|---|---|---|---|---|---|
| dual_combo_a1 (45) | 1–10 | 300 | from t4 (F6) → dual_combo_a2 | a1_end | t3: (45, 1.0) | — | resetRot each tick. No click slash: vanilla slashes on click with (-10, 0.44, m) |
| dual_combo_a1_end (61) | 10–21 | 366 | any → dual_combo_a2 | a1_end2 | — | — | quick-charge release removed |
| dual_combo_a1_end2 (71) | 21–41 | 666 | none | none | — | — | no sheath sound |
| dual_combo_a2 (81) | 100–115 | 500 | from t3 (F5) → dual_combo_a3 | a2_end | **none** | t2: (170, 1.0) | |
| dual_combo_a2_end (102) | 115–132 | 566 | any → dual_combo_c | a2_end2 | — | — | |
| dual_combo_a2_end2 (112) | 132–151 | 633 | none | none | — | — | |
| dual_combo_a3 (122) | 200–218 | 600 | from t6 (F9) → `isPowered` ? dual_combo_a4_ex : dual_combo_a4 | a3_end | t2: (-70, 0.66) | t5: (105, 0.66) | `isPowered` = Strength or Hunger effect, or `PowerBladeEvent` |
| dual_combo_a3_end (144) | 218–230 | 400 | any → dual_combo_b1 | a3_end2 | — | — | |
| dual_combo_a3_end2 (154) | 230–243 | 433 | none | a3_end3 | — | — | vanilla 230–281 |
| dual_combo_a3_end3 (164) | 243–255 | 400 | none | none | — | — | vanilla 281–306 plus sheath sound |
| dual_combo_a4 (174) | 500–521 | 700 | t9 (F14) → none | a4_end | t8: (50, 0.44, c). F12 (45) is overwritten | t8: (0, 0.44, c). F12 (-10) is overwritten | rot72 at t6, t7, t8; resetRot at t9. Click grants advancement `arts/combo_a`. Vanilla 500–576 |
| dual_combo_a4_end (215) | 521–535 | 466 | none | none | — | — | |
| dual_combo_a4_ex (225) | 800–839 | 1300 | from t14 (F22) → dual_combo_a5 | a4_ex_end | t7: (70, 1.0); t8: (75, 1.0) | t7: (45, 1.0, off(0,-0.25,0)); t8: (50, 1.0, off(0,-0.25,0)); t14: (250, 1.0) | rot72 at t5, t6, t7; reset at t8 |
| dual_combo_a4_ex_end (274) | 839–844 | 166 | none | a4_ex_end2 | — | — | vanilla 839–877 |
| dual_combo_a4_ex_end2 (284) | 844–860 | 533 | none | none | — | — | vanilla 877–894 |
| dual_combo_a5 (293) | 900–1013 | 3766 | t22 (F33) → none | a5_end | t15: (35, 1.0, c); t16: (45, 1.0, m c). F24 (40) is overwritten. t17: (50, 1.0, m c) | t15/t16/t17: (35/45/50, 1.0, off(0,-0.125,0)). F24 (40) is overwritten | Click grants `arts/combo_a_ex`. Vanilla rot72 (t13–17) was dropped |
| dual_combo_a5_end (343) | 1013–1061 | 1600 | none | none | — | — | |
| dual_combo_c (1005) | 400–420 | 666 | t10 (F15) → none | c_end | t2: (190, 1.0) | t8: (45, 1.0) | Click grants `arts/combo_c`. Vanilla 400–459 with (-30) and (-35, c) at 0.88 |
| dual_combo_c_end (1028) | 420–435 | 500 | none | none | — | — | vanilla 459–488 |

#### Ground B line (rush)

| state (line) | frames | ms | next | timeout → | main-hand slashes | off-hand slashes | other |
|---|---|---|---|---|---|---|---|
| dual_combo_b1 (353) | 700–725 | 833 | from t14 (F21) → b2 | b1_end | t0: (20, .244) and (25, .244, c). At t3, t4, t6, t7, t8, t10, t11, t12: (35+20·rnd, .244, RUSH), with c at t4, t7, t10, t12 | At t4, t5, t6, t8, t9, t10, t12: (125+20·rnd, .244, RUSH), with c at t5, t8, t10 | Click grants `arts/combo_b`. Vanilla: 700–720, window F13 (t8), slash pair at t6, rush at t7–14 with (±90+180·rnd) |
| dual_combo_b1_end (432) | 725–745 | 666 | `TimeoutNext(F12, self)`, so input is always ignored | **b_end2** | t2: (0, 1.0, c, off(·,0.8,0)); t4: (5, 1.0, m, off(·,0.8,0)) | t3: (0, 1.0, off(·,0.8,0)); t4: (5, 1.0, m, off(·,0.8,0)) | rot72 at t4, t5, t6; reset at t7. No dual b1_end2 or b1_end3 exists |
| dual_combo_b2 … b6 (474 / 548 / 622 / 696 / 770) | 705–725 | 666 | from t10 (F16) → next b | b_end | At t0, t2, t3, t4, t6, t7, t8, t10: (35+20·rnd, .244, RUSH), with c at t2, t4, t7, t10 | At t1, t2, t4, t5, t6, t8, t9: (125+20·rnd, .244, RUSH), with c at t2, t5, t8 | Vanilla: 710–720 (333 ms), window F6 (t4), 7 slashes at t0–6 |
| dual_combo_b7 (844) | 705–764 | 1966 | t22 (F33) → none | **b_end3** | Same 8 rush slashes as b2, plus t16: (0, .244, c, off(·,0.8,0)) and t17: (5, .244, m, off(·,0.8,0)) | Same 7 rush slashes, plus t16: (0, .244, off(·,0.8,0)) and t18: (5, .244, m, off(·,0.8,0)) | rot72 at t18–t21; reset at t22. Click grants `arts/combo_b_max` |
| dual_combo_b_end (944) | 725–745 | 666 | self (ignored) | b_end2 | t2: (0, .244, c, off(·,0.8,0)); t4: (5, .244, m, off(·,0.8,0)) | t3: (0, .244, off(·,0.8,0)); t4: (5, .244, m, off(·,0.8,0)) | rot72 at t4–t6; reset at t7 |
| dual_combo_b_end2 (986) | 745–764 | 633 | none | b_end3 | — | — | |
| dual_combo_b_end3 (996) | 764–770 | 200 | none | none | — | — | vanilla 764–787 plus sheath sound |

#### Aerial line (all priority 80; every state calls `FallHandler::fallDecrease` each tick)

| state (line) | frames | ms | flags | next | timeout → | main-hand | off-hand | other |
|---|---|---|---|---|---|---|---|---|
| dual_combo_sky_1 (1038) | 1100–1122 | 733 | aerial | from t3 (F5) → sky_2 | sky_1_end | t2: (-20, .28) | — | resetRot |
| dual_combo_sky_1_end (1056) | 1122–1132 | 333 | | none | none | — | — | |
| dual_combo_sky_2 (1067) | 1200–1210 | 333 | aerial | from t4 (F6) → sky_a3 | sky_2_end | t3: (-60, .28, toss) | t3: (225, .28, toss) | vanilla window F5 with a slash (150) at t2 |
| dual_combo_sky_2_end (1091) | 1210–1231 | 700 | aerial | any → sky_b3 | sky_2_end2 | — | — | |
| dual_combo_sky_2_end2 (1103) | 1231–1240 | 300 | | none | none | — | — | |
| dual_combo_sky_a3 (1114) | 1300–1328 | 933 | aerial | t5 (F8) → none | sky_a3_end | t2: (0, .28, smash); t3: (-3, .28, m c, smash) | t2: (90, .28) | resetRot. Click grants `arts/aerial_a` |
| dual_combo_sky_a3_end (1141) | 1328–1340 | 400 | | none | none | — | — | **keeps `releaseActionQuickCharge`**; it is the only DER state that does |
| dual_combo_sky_b3 (1153) | 1400–1437 | 1233 | aerial | from t6 (F9) → sky_b4 | sky_b3_end | t3: (237, .34, toss); t6: (237, .34, toss) | t4: (90, .34); t6: (90, .34) | Click sets motion.y = 0.6. Rotation: t0 -90, t1 -90, t2 -120, t3 -120, reset at t4 (collisions removed the vanilla t4–6 entries) |
| dual_combo_sky_b3_end (1200) | 1437–1443 | 200 | | none | none | — | — | |
| dual_combo_sky_b4 (1211) | 1500–1537 | 1233 | aerial | t6 (F9) → none | sky_b4_end | t6: (45, .34, meteor); t7: (50, .34, m c, meteor) | t6: (40, .34); t7: (45, .34); t8: (50, **1.0**, m c) | rot90 at t3, t4, t5; reset at t6. Click grants `arts/aerial_b` |
| dual_combo_sky_b4_end (1259) | 1537–1547 | 333 | | none | none | — | — | |

#### Command arts

| state (line) | frames | ms | prio / flags | next | timeout → | attacks and actions |
|---|---|---|---|---|---|---|
| dual_upperslash (1272) | 1600–1659 | 1966 | 90 | t7 (F11) → none | upperslash_end | Hold action: when `getTicksUsingItem() == 6` (F9) and the input contains {BACK, R_DOWN}, switch to dual_upperslash_jump and grant its advancement. Main t6 (F9): (-80, .50, toss); vanilla uses F7 = t4. Hit stun 15. No off-hand slash |
| dual_upperslash_end (1305) | 1659–1693 | 1133 | 90 | none | none | — |
| dual_upperslash_jump (1315) | 1700–1713 | 433 | 90, aerial | t4 (F7) → none | jump_end | Click: motion.y = 0.6, onGround = false, hasImpulse = true. rot90 at t0–t3, reset at t4. fallDecrease. No attack |
| dual_upperslash_jump_end (1342) | 1713–1717 | 133 | 90 | none | none | fallDecrease |
| dual_aerial_cleave (1353) | 1800–1812 | 400 | 70, aerial | self | aerial_cleave_loop | Identical to vanilla. Click: motion.y = 0.1. Each tick: fallDistance = 1. At elapsed 2, sound PLAYER_ATTACK_STRONG (0.75, 1.0). After elapsed 2, motion.y -= 3.0 per tick. On even ticks, areaAttack(meteor, .44, force, noReset, mute). On ground: doSlash(55, .44, m c, meteor), switch to landing, 20 landing particles. At elapsed 1, switch to loop. rot90 at t0–t3. Main hand only |
| dual_aerial_cleave_loop (1405) | 1812–1817 | 1166 (+1000) | 70, loop | self | none | Same per-tick fall and attack logic. Hit stun 15 |
| dual_aerial_cleave_landing (1434) | 1817–1859 | 1400 | 70 | t4 (F6) → none | cleave_end | Click: main (60, .44, meteor). Off-hand t1 (F2): (90, .44). resetRot. Vanilla range starts at 1816 |
| dual_aerial_cleave_end (1456) | 1859–1886 | 900 | 70 | none | none | resetRot. No sheath sound |
| dual_rapid_slash (1467) | 2000–2019 | 633 | 70 | `isPowered` ? rapid_slash_quick : self (ignored) | rapid_slash_end | Hold action (ticksUsing ≥ 2): add a transient reach −3 modifier, then main `areaAttack(.44, force, mute)`. Inside that, if R_DOWN is held and the state is still dual_rapid_slash, `areaAttack(toss, .44, force, reset, mute)`; any hit switches to dual_rising_star. Then the **off-hand** runs `areaAttack(.44, force, mute)`. The reach modifier is removed in `finally`. Tick action: at elapsed 0, sound ARMOR_EQUIP_IRON (1, 1). While elapsed ≤ 3 and on ground: `moveRelative(0.8, or 0.35 in water, forward)`. For elapsed 2–5: roll = −45 + 90·rnd (+180 on even ticks), c = `isPowered`, main slash (roll, .44, RUSH) and off-hand slash (same roll, .44, its own RUSH). At elapsed 7: main and off-hand (-30, .44, c, RUSH). Elapsed 7–10: setRot 90. After 10: setRot(0, false) |
| dual_rapid_slash_quick (1552) | 2000–2001 | 33 | 70 | self | dual_rapid_slash | Vanilla also plays a sheath sound and keeps quick charge; DER drops both |
| dual_rapid_slash_end (1562) | 2019–2030 | 366 | 70 | none | none | Vanilla: end 2019–2054, then end2 2054–2073 with sheath sound |
| dual_rising_star (1572) | 2100–2137 | 1233 | 80, aerial | t12 (F18) → none | rising_star_end | Click: motion (0, 0.6, 0), airborne, main (-57, .6, toss). Timeline t6: **off-hand (0, .6)**. The main F9 (-57) slash is overwritten by the off-hand F10 entry, so it never fires. rot72 at t0–t9, reset at t10. For elapsed < 3, keep vertical speed at least 0.6. fallDecrease |
| dual_rising_star_end (1629) | 2137–2147 | 333 | 70 | none | none | fallDecrease |
| dual_sa_before (1640) / dual_sa_after (1654) | 2300–2302 | 66 | 1000 | none | none | **Registered but never referenced.** `sa_before` plays a quick sheath sound at t1. The VMD contains frames 2300–2312 for them |

#### Graph summary

Ground:
- Main path: A1 →(≥t4) A2 →(≥t3) A3 →(≥t6) A4, or A4_EX when powered. A4_EX →(≥t14) A5.
- Branches from the end states: A1_end click → A2. A2_end click → C. A3_end click → B1.
- Rush: B1 →(≥t14) B2 → B3 → B4 → B5 → B6 → B7, each step at ≥t10.
- Rush timeouts:
  - B1 → b1_end → b_end2 → b_end3;
  - B2–B6 → b_end → b_end2 → b_end3;
  - B7 → b_end3.

Air:
- sky_1 →(≥t3) sky_2 →(≥t4) sky_a3.
- sky_2_end click → sky_b3 →(≥t6) sky_b4.

Global differences from Resharped:
- Every "sheath" tail is shortened or retimed to return to a dual stance.
- `playQuickSheathSoundAction` is removed everywhere.
- `releaseActionQuickCharge` survives only on `dual_combo_sky_a3_end`.

The design notes in `ref/der/docs/dual_combo_split_plan.md` are an earlier plan. They list vanilla rolls and frame blocks, **not** the shipped values. Use the code.

### 1.6 Off-hand damage path (exact)

1. A DER state calls `withBlade(OFFHAND)`, which swaps attack attributes to the off-hand blade's MAINHAND modifiers (players only) and overrides `getMainHandItem()`.
2. `AttackManagerMixin.scaleSyntheticBladeDamage` (`DER/mixin/AttackManagerMixin.java:19-39`) is a `@ModifyVariable` at HEAD of the 8-argument `doSlash`. It sets `comboRatio *= 0.75` for the off-hand (or `*= 0.5` for the head).
3. `DoSlashEvent(damage = scaled ratio)` fires, the effect gets `setDamage(event.getDamage())`, and the effect is tagged with the slot.
4. On each hit tick of the effect, `EntitySlashEffect` computes `ratio = damage × (critical ? 1.1 : 1.0)` and calls `areaAttack` inside the restored context. Resharped's `AttackHelper.calculateTotalDamage` (`RS/util/AttackHelper.java:82-98`) then computes:

   ```
   (ATTACK_DAMAGE + sweepBonus + rankBonus + enchantBonus(offhand blade)) × ratio × slashBladeDamageAttr × SLASHBLADE_DAMAGE_MULTIPLIER
   ```

   Vanilla crit applies on top (×1.5 when falling).
5. Direct off-hand `areaAttack` calls (rapid slash hold) take the same route: inside the context, ratio 0.44 has no mixin scaling. **Note:** the 0.75 multiplier hooks only `doSlash`, not `areaAttack`, so the off-hand rapid-slash area hits use the full 0.44 ratio, with the off-hand blade's attributes.

### 1.7 Three-blade (head slot) mode

- **Equip:** `ItemSlashBladeMixin.canEquip` (`DER/mixin/ItemSlashBladeMixin.java:32-40`) is added as a plain method, which overrides Forge `IForgeItem.canEquip`. It returns true only for the HEAD slot, only for Players, only when `enableThreeSwordStyle` is on, and (with LegendRelicLib) only when the item carries the tag.
- **Attack:** `HeadBladeAttack.onDoSlash` (`DER/HeadBladeAttack.java:82-111`):
  - Trigger: server side, config on, for **any** `DoSlashEvent` whose context is not HEAD. That includes main-hand slashes, off-hand slashes and slash arts that use `doSlash`. Dual wielding is not required; main hand plus head is enough.
  - With probability 0.5, it calls `AttackManager.doSlash` in HEAD context with:
    - roll 0.0;
    - offset ZERO;
    - mute false;
    - critical = `event.isCritical()`;
    - ratio = `event.getDamage()`, which already includes the off-hand factor if an off-hand slash triggered it. The mixin then multiplies by 0.5;
    - KB = `event.getKnockback()`;
    - color = the head blade's color.
  - The head slash is spawned **before** the triggering slash, because it is created inside the event.
- **Tick and SE:** the head blade gets a context push in `inventoryTick` and `isSelected = true` (§1.3).
- **Render:** `DualBladeRenderHelper.renderMouthBlade` (`DER/DualBladeRenderHelper.java:330-367`; package `client`, although the file sits in the root folder) runs at HEAD of `LayerMainBlade.render` for every entity:
  - It draws only the `blade` (or `blade_damaged`) part plus `_luminous`, with no sheath.
  - Transform: `head.translateAndRotate` → `translate(0, -0.1, -0.215)` → `rotX(-90)` → `rotY(180)` → `scale(1/128)`.
  - It is skipped for the local player in first person.
- `CustomHeadLayerMixin` (`DER/mixin/CustomHeadLayerMixin.java:18-41`) cancels vanilla head-item rendering when the head slot holds a SlashBlade.
- The "nine-sword" options exist in config only.

### 1.8 Rendering of the off-hand blade and the sheath

**Background (Resharped).** `RS/client/renderer/layers/LayerMainBlade.java:200-364`:
- It samples the combo's `motionLoc` VMD on `bladeholder.pmd` and places the blade at bone `hardpointA` and the sheath at `hardpointB`.
- Common transform: `translate(0, 1.5, 0)`, `scale(1.5/12)`, `rotZ(180)`, model scale `(1/128)·(12/1.5)`.
- Before that, `renderOffhandItem` (lines 91-115) draws an off-hand SlashBlade **sheathed at its CarryType standby position**. If the off-hand is empty, it draws hotbar slot 0's blade instead.

DER changes this with `LayerMainBladeDualHardpointMixin` (`DER/mixin/LayerMainBladeDualHardpointMixin.java:26-76`).

**A. Standby or non-dual states while dual wielding** (`renderOffhandItem` HEAD, cancelled). `renderStandbyOffhandBlade` (`DualBladeRenderHelper.java:280-324`) draws the off-hand blade **unsheathed in the off arm, with no sheath**:
1. `humanoidModel.translateToHand(offArm)`
2. `rotX(-90)`, `rotY(180)`
3. `translate(side/16, 0.125, -0.625)`, where side is -1 for the left arm and +1 for the right
4. `rotZ(90)`, `rotX(-90)`, `rotY(180)`
5. `translate(0.25, -0.15, 0)`
6. `scale(1/128)`
7. Render `blade` (or `blade_damaged`) and `_luminous`

The main blade still renders through vanilla hardpoints, for example sheathed at the waist in standby.

**B. Dual motion states** (`render` HEAD). The branch runs when `isUsingDualMotion` holds: the combo the renderer resolves, using the same timeout walk as LayerMainBlade with root fallback (`DualBladeRenderHelper.java:51-98`), has the DER VMD as its `motionLoc`.

`renderDualBladesInHands` (lines 109-170) works as follows:
1. Sample the DER VMD at `start + clamp(elapsed, 0, span)` (modulo span when looping) on SlashBlade's `model/pa/alex.pmd` with an independent `MmdMotionPlayerGL2` (`DER/client/DualVmdPoseSampler.java:36-74`). Read bones `body`, `right arm`, `left arm`.
2. Convert each bone (lines 95-119; same convention as Resharped's `VmdAnimation.get3DTransform`):
   - position × (body ? 1/8 : 2), with Y negated for arms;
   - Euler ZYX from the quaternion;
   - rotation X negated for arms, Y negated for body, Z always negated.
3. Without PlayerAnimator, or for non-players, apply the body pose to the stack: `translate(px, py+0.7, pz)`, `rotZ`, `rotY`, `rotX`, `translate(0, -0.7, 0)`.
4. For each hand, temporarily set the `HumanoidModel` arm: `setPos(±5 + px, 2 + py, pz)` (-5 for the right arm) and `setRotation(rx, ry, rz)`. Then `translateToHand` plus the same chain as in A. The off-hand also gets `translate(0.25, -0.15, 0)` along the blade.
5. If the main blade is charged, draw `renderChargeEffect` (texture `creeper_armor.png`) on it.
6. `renderDualStandbySheaths` (lines 375-446) draws **only the main-hand sheath**, at its CarryType standby position:
   - base `translate(0, 1.5, 0)`;
   - PSO2: `(1, -1.125, 0.2)`, `rotZYX(-0.122173, 0, 0)`;
   - KATANA: `(0.25, -0.875, -0.55)`, `rotZYX(π, π/2, 0.261799)`;
   - DEFAULT: same offset, `rotZYX(0, π/2, 0.261799)`;
   - NINJA: `(-0.5, -2, 0.2)`, `rotZYX(-2.094395, 0, π)`;
   - RNINJA: `(0.5, -2, 0.2)`, `rotZYX(-1.047198, 0, 0)`;
   - PSO2, NINJA and RNINJA are hidden for the local player in first person;
   - scale `1.5/12` then `(1/128)·(12/1.5)`.

   The off-hand sheath is never drawn.
7. The vanilla render is cancelled.

**What this means visually.** In dual combos both blades are rigidly fixed to the hands and follow only the arm bones of `dual_player_motion.vmd`. The bladeholder or hardpoint blade animation of `motion.vmd` is not used. The main sheath stays at the hip.

`PlayerAnimationOverriderMixin` (`DER/mixin/PlayerAnimationOverriderMixin.java:24-49`) handles the player body:
- For dual wielders whose combo uses the DER VMD, it builds a new `VmdAnimation(DER vmd, combo.start, combo.end, combo.loop)` and puts it on PlayerAnimator layer 0. The vanilla handler is cancelled.
- Each phase plays only its own frame range. Vanilla plays a whole block, for example 1–41 for `combo_a1`.
- `setBlendLegs(false)` is **not** set, even for aerial states. Vanilla disables leg blending in the air.
- Without PlayerAnimator, the body is not animated but the blades still move.

### 1.9 First-person behavior

- In Resharped, first person goes through `BladeFirstPersonRender` (`RS/client/renderer/model/BladeFirstPersonRender.java:44-83`). It calls `LayerMainBlade.render` with an identity pose, `translate(0, 0, -0.5)`, `rotZ(180)`, `scale(1.2, 1, 1)`, `rotX(-pitch)`, using the player renderer's `PlayerModel` as parent. DER's mixins therefore apply in first person too.
- **Standby while dual wielding:** the off-hand blade is hidden. `FirstPersonStandbyOffhandMixin` (priority 2000) cancels `renderOffhandItem` when `FirstPersonBladeVisibility.shouldHideStandbyOffhandBlade` holds (`DER/client/FirstPersonBladeVisibility.java:12-21`): first-person camera, local player, dual wielding, and not in dual motion. The main blade shows as vanilla.
- **During dual combos:** both blades are drawn on the arm poses sampled from the VMD, inside the first-person frame. The main sheath is drawn unless its carry type is PSO2, NINJA or RNINJA.
- The mouth blade is never drawn in first person.
- The YSM path also hides the first-person standby off-hand blade.

### 1.10 Mixin inventory

| mixin | target, method | kind | purpose |
|---|---|---|---|
| AttackManagerMixin | `AttackManager.doSlash(8-arg)` | `@ModifyVariable` HEAD on `comboRatio`; `@Inject` RETURN | scale off-hand and head ratio; tag the slash entity with its slot |
| EntitySlashEffectMixin | `EntitySlashEffect.tick` → `AttackManager.areaAttack(...)` | `@Redirect` (require 0) | run delayed hits in the tagged blade's context |
| LivingEntityMixin | `LivingEntity.getMainHandItem`, `setItemInHand` | `@Inject` HEAD, cancellable | context blade acts as the main hand; writes go to the source slot |
| ItemSlashBladeMixin | `ItemSlashBlade` (adds `canEquip`); `inventoryTick` HEAD/RETURN; `isInMainhand` HEAD | added method plus injects | head-slot equip; SE context for off-hand and head; block combo ticks |
| SlashBladeUpdateEventMixin | `SlashBladeEvent.UpdateEvent.isSelected` | `@Inject` HEAD | off-hand and head blades count as selected |
| PlayerAnimationOverriderMixin (client) | `PlayerAnimationOverrider.onBladeAnimationStart` | `@Inject` HEAD, cancel | play DER VMD ranges on PlayerAnimator |
| LayerMainBladeDualHardpointMixin (client) | `LayerMainBlade.renderOffhandItem`, `render` | `@Inject` HEAD, cancel | unsheathed off-hand blade; arm-bound dual rendering; mouth blade |
| FirstPersonStandbyOffhandMixin (client, prio 2000) | `LayerMainBlade.renderOffhandItem` | `@Inject` HEAD, cancel | hide the off-hand blade at first-person standby |
| CustomHeadLayerMixin (client) | `CustomHeadLayer.render` | `@Inject` HEAD, cancel | no vanilla head-item render for a SlashBlade |
| YsmDualBladeLayerMixin, YsmPlayerRendererMixin (client, `@Pseudo`) | obfuscated Yes Steve Model classes | inject (require 0) | YSM model compatibility: VMD fallback onto GEO bones, or GEO animations named `double_edged_reforged:dual_*` (README). Not relevant to 1.7.10 |

LegendRelicLib compatibility (`DER/LegendRelicLibCompat.java`) works by reflection. It can turn off that mod's own off-hand extra slash and can gate dual wielding on its tag.

---

## 2. Changes DER makes to VFX, lock-on, rank, slash arts and input

- **VFX:** no new effect, entity, renderer, particle or sound.
  - Off-hand and head slashes are ordinary `EntitySlashEffect`s. They take the extra blade's color (through the `getMainHandItem` override), the player's current rank level (so the same rank-tier visuals), the same lifetime and the same `TRIDENT_THROW` sound, unless muted.
  - Visual change: two slash arcs per beat, with mirrored rolls such as main 35+20·rnd against off-hand 125+20·rnd in the B rush, and main -60 against off-hand 225 in sky_2.
  - Removed: all quick-sheath (CHAIN_HIT) sounds in DER chains.
- **Lock-on:** no change. `LockOnManager` and the target are untouched.
- **Rank:** no change to `ConcentrationRank`. Off-hand and head hits are normal player attacks, so they presumably feed rank points through Resharped's normal handler; this was not traced in detail.
- **Slash arts:** no change.
  - SA combo states are not redirected and keep Resharped's single-blade logic and animation.
  - During them the off-hand blade is shown unsheathed in the off arm (path A of §1.8).
  - SA charge release still works from standby. Quick charge (release inside an `*_end` window, `RS/registry/combo/ComboState.java:161-170`) is **disabled** in DER chains, except on `dual_combo_sky_a3_end`.
  - `dual_sa_before` and `dual_sa_after` are dead code.
- **Input handling:** no new keys or commands. The standby command map is Resharped's.
  - Changed input windows: A1 F5→F6, sky_2 F5→F6, sky_a3 F9→F8, B1 F13→F21, B2–B6 F6→F16, A4 F21→F14 (it leads to none anyway).
  - First hit is delayed: vanilla A1 and A2 slash on the click (t0). DER's A1 slashes at t3, and DER's A2 has only an off-hand slash, at t2.
  - The B rush runs at half speed: each loop takes 20 frames (666 ms) instead of 10 (333 ms).

---

## 3. Assets DER ships, and license notes

| file | size | notes |
|---|---|---|
| `DERres/assets/double_edged_reforged/combostate/dual_player_motion.vmd` | 166,352 B | The only gameplay asset |
| `ref/der/docs/images/double_blade.png` | 34,321 B | README banner. Actually a JPEG, 480×300 |
| `DERres/pack.mcmeta`, `META-INF/mods.toml`, `double_edged_reforged.mixins.json` | — | metadata |

DER ships no models, no textures and no lang files (config comments are hard-coded in two languages). Blade models and textures come from Resharped or other blade definitions at runtime. Head and off-hand rendering reuse each blade's own OBJ parts: `blade`, `blade_damaged`, `*_luminous`, `sheath`, `effect`.

**VMD analysis** (parsed as data):
- Header "Vocaloid Motion Data 0002", model name `alex_arm`, 1498 bone keyframes, no morph or camera keyframes.
- Bones: `全ての親, body, torso, 首, head, eyes, 肩, left arm, ひじ, 手首, right arm, 下半身, left leg, right leg, ひざ, 足首`. This is the same bone set as SlashBlade's `model/pa/player_motion.vmd`: model `Alex`, 1336 keys, byte-identical (md5 `8338120b…`) in sb2 and Resharped.
- Frame clusters `[0–41, 100–151, 200–302, 400–608, 700–1132, 1200–1240, 1299–1351, 1400–1443, 1500–1547, 1600–1717, 1800–2147, 2200–2312]` match the original, plus 2300–2312, which is new.
- Per block, 30–60 % of keyframes are byte-identical to the original and the rest are edited (mostly arms and body, plus legs in some blocks).

  **Conclusion: DER's VMD is a modified derivative of SlashBlade's `player_motion.vmd`.**
- It holds player bones only, with no `hardpointA/B` or blade bones, which is why DER binds blades to the arms.

**Licenses:**
- DER declares **GPL-3.0-or-later** in three places: `LICENSE` (full GPLv3 text), the README "许可证" section and `gradle.properties` `mod_license`. There is no NOTICE, credits or per-asset license file, and the VMD's provenance is not stated anywhere.
- Upstream Resharped declares **MIT** ("Copyright (c) 2024 M Mysterious Mountain Forging-shop Group").
- The original SlashBlade 2 sources declare **"All other Rights reserved"**, with author Furia; only NyMmd (MIT, nyatla) and the OBJ importer are excepted.
- So the VMD's chain is: Furia's ARR asset → re-released under Resharped's MIT claim → modified under DER's GPL claim. Whether DER can license the derived VMD under GPL is unclear.

**For the 1.7.10 port:**
- Copying DER code (or translating it closely) puts the copy under GPL-3.0-or-later.
- Re-implementing from the numbers and behavior documented here is a different situation. This is not legal advice.
- Redistributing `dual_player_motion.vmd`, or SlashBlade's own VMD and PMD, carries the upstream "All rights reserved" uncertainty. Get explicit permission, or author new motion data.

---

## 4. SlashBlade_2 (sb2) vs Resharped: what matters for the feel

Where the combos live:
- **sb2:** the active combos are the `ex_*` states in `SB2/capability/slashblade/combo/Extra.java`. The default root is `Extra.STANDBY_EX` (`SB2/capability/slashblade/SlashBladeState.java:420-425`).
- **sb2 legacy:** `SB2/capability/slashblade/ComboState.java` holds the old SB1-style states (`old_motion.vmd`, speeds 1.5 or 1.75, areaAttack-based). They are not the default.
- **Resharped:** the same `ex_*` set, renamed without the prefix, in `RS/registry/ComboStateRegistry.java`.

| system | sb2 vs Resharped | matters for feel? |
|---|---|---|
| Motion assets | `player_motion.vmd`, `alex.pmd` and `combostate/motion.vmd` are byte-identical. sb2 also ships per-move split VMDs and `old_motion.vmd` | Same animation |
| PlayerAnimator mapping | Identical frame blocks and leg-blend flags, except combo_a3: 200–314 in sb2, 200–306 in Resharped | Same |
| Combo timing | Start/end frames, priorities, `next`/timeout graph, `TimeoutNext` windows and timeline tick keys are identical for all A, B, C, aerial, upperslash, aerial cleave, rapid slash and rising star states. Differences: `a3_end3` end 314 → 306; `rising_star_end` priority 80 → 70; void_slash priority 45 → 50; `judgement_cut_slash` speed 0.4 → 1.0; sb2's air and just JC variants (speeds 0.5 / 0.75) were removed; `standby_inair` removed | Combo rhythm is the same |
| Damage ratios per slash | sb2 mostly 1.0 (rush 0.1, b1 pair 0.25, aerial cleave 0.1, rapid/rising toss 0.01). Resharped retuned them: A1/A2/A3/A4 0.44, C 0.88, A4_EX/A5 1.0, all B 0.244 (b1_end 1.0), aerial A 0.28, aerial B 0.34, upperslash 0.5, rising star 0.6, cleave 0.44 | Balance only. DER uses Resharped's values as its base but rewrote many |
| Damage model | sb2 (`SB2/util/AttackManager.java:98-122, 184-215`): ratio becomes an ATTACK_DAMAGE `MULTIPLY_BASE` modifier `(1 + sweep·0.5)·ratio`, plus a rank `ADDITION` modifier, then vanilla `Player.attack`. Resharped (`RS/util/AttackHelper.java:36-125`): custom formula `(atk + sweep·5 + rank + enchant) × ratio × SB damage attribute × config` | Numbers differ; the hit sequence does not |
| Slash effect entity | `EntitySlashEffect.tick` logic is the same (lifetime 10, hits on even ticks, crit ×1.1, rank particles at S+). Resharped adds an overridable `getSlashSound` (still TRIDENT_THROW) and keeps `alreadyHits` | Same |
| Slash renderer | `SlashEffectRenderer`: formatting-only differences | Same |
| Blade layer | Hardpoint rendering, scales and PlayerAnimator body sync are the same. Resharped adds off-hand and hotbar-slot-0 standby rendering with CarryType offsets, and a render-layer blacklist tag. `BladeRenderState` adds `RenderOverrideEvent` and color hooks | Off-hand display is Resharped-only, and DER replaces it |
| First person | `BladeFirstPersonRender`: same transforms (null-safety only) | Same |
| Lock-on | Same 40-block ray and 12×6×12 fallback box. Resharped uses `TargetSelector.lockon` instead of `lockon_focus` for the ray target and no longer excludes IShootable | Minor |
| Rank | `IConcentrationRank` thresholds identical: D 1, C 2, B 3, A 4, S 5, SS 5.25, SSS 5.5 | Same |
| Slash arts | sb2: judgement cut (plus void slash states). Resharped adds sakura_end, circle_slash, drive_vertical/horizontal (`EntityDrive` and `DriveRenderer`), wave_edge, piercing, and an SA registry. JC entity damage 1.0 → 0.1 with areaAttack ratio 0.16 | Content difference |
| Sounds | Quick sheath is CHAIN_HIT 1.0/1.0 in both: sb2 plays it at the entity, Resharped server-side at the level | Same |

**Which one is the reference:**
- For DER fidelity, the reference is **Resharped 1.9.65**. DER hooks Resharped classes such as `LayerMainBlade.renderOffhandItem`, `PlayerAnimationOverrider`, `AttackHelper`, `BladeMotionEvent` and the registries. It inherits Resharped's ratios, damage model and SA set.
- For animation and combo timing, sb2 and Resharped are interchangeable.

---

## 5. Port-relevant summary (1.7.10: Bukkit server plus client mod)

- **Server side** (state machine and damage):
  - Re-create the 54 live DER states from §1.5 (56 are registered; `dual_sa_before` and `dual_sa_after` are unused) with the effective post-collision tick tables. Or decide deliberately to "fix" the collisions; that would diverge from DER's real behavior.
  - Off-hand slash: same slash entity logic; ratio × 0.75; attack and enchant values from the off-hand item; off-hand durability.
  - Head slash: 50 % chance on every slash event; roll 0; ratio × 0.5; head blade's color.
  - Redirect vanilla entries to dual entries only while both hands hold blades.
- **Client side:**
  - Play the DER VMD body animation per phase range, without leg-blend disabling.
  - Bind both blades to the arm bones using the transform chain in §1.8: hand offset `(±1/16, 0.125, -0.625)`, extra off-hand offset `(0.25, -0.15, 0)`, scale 1/128.
  - Main sheath at its CarryType standby pose. No off-hand sheath.
  - Off-hand blade unsheathed in hand at standby, hidden in first-person standby.
  - Mouth blade at `head + (0, -0.1, -0.215)`.

## 6. Uncertainties

- **Stun on off-hand hits:** inferred from `hurtEnemy` reading the off-hand blade's own (idle) combo state. Not verified in game.
- **Mixin ordering:** `FirstPersonStandbyOffhandMixin` (priority 2000) and `LayerMainBladeDualHardpointMixin` both cancel `renderOffhandItem` at HEAD. Which runs first was not verified. The intended result (hidden in first-person standby, drawn otherwise) does not depend on the order.
- **Timeline offset quirk:** Resharped's `TimeLineTickAction` offset handling can shift timeline keys by about one tick. Exact behavior depends on when the first call happens; it was not simulated.
- **Rank-point accrual for off-hand and head hits:** assumed to be normal; not traced through `RankPointHandler`.
- **Server and client agreement on the redirect:** both sides run the redirect. A remote client relies on the synced off-hand item to compute `isDualWielding`.
- **VMD licensing:** a legal judgment, not resolved here.
