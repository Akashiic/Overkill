<!-- Estudo do código do SlashBlade: Resharped (MIT, (c) 2024 M Mysterious Mountain Forging-shop Group), do Double Edged Reforged (GPL-3.0) e, como referência, do SlashBlade_2 (flammpfeil). Só leitura; os trechos citados são curtos e servem à análise. A arte desses projetos é "All Rights Reserved" e não está neste repositório. -->

# 04 - Lock-on, Camera, Concentration (Style) Rank, HUD, Blade Progression

Source studied: SlashBlade: Resharped (Forge 1.20.1), read-only.
All paths below are relative to `src/main/java/mods/flammpfeil/slashblade/` unless they start with `src/` or `assets/`/`data/`
(resources live in `src/main/resources/...` and datagen output in `src/generated/resources/...`).
Line numbers are those of the files in the clone.

Units: "tick" = 1/20 s game tick. "level" (rank level) = rank points / 300.

---------------------------------------------------------------------------------------------------

## 0. The input pipeline everything depends on

The mod does not use vanilla sneak/attack events for its abilities. Instead the client samples a bitset of
keys every client tick and sends it to the server **only when it changes and only while the main-hand item is a
SlashBlade**.

### 0.1 `InputCommand` bitset (`util/InputCommand.java:6-7`)

```java
FORWARD, BACK, LEFT, RIGHT, SNEAK, R_DOWN, L_DOWN, M_DOWN, R_CLICK, L_CLICK, ON_GROUND, ON_AIR, SPRINT, JUMP;
// bit = 1 << ordinal :  1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192
```
`EnumSetConverter.convertToInt` = sum of `1 << ordinal`. `ON_GROUND/ON_AIR` are never sent; they are added
server-side by `IInputState.getCommands(LivingEntity)` (`capability/inputstate/IInputState.java:16-27`) from
`onGround()`. `R_CLICK/L_CLICK` are injected transiently by `ItemSlashBlade.use()` / `onLeftClickEntity()`.

### 0.2 Client sampling (`event/handler/MoveInputHandler.java:40-117`, ClientTickEvent END)

* Returns early unless `player.getMainHandItem()` has the BLADESTATE capability (line 51).
* `FORWARD/BACK/LEFT/RIGHT` = `player.input.up/down/left/right`.
* `SNEAK` = `player.input.shiftKeyDown` (the sneak key state) (line 70-72).
* `JUMP` = `player.input.jumping`.
* `SPRINT` = custom key **"Special Move" (default V)** is down (line 80) - NOT the vanilla sprint key.
* `R_DOWN` = vanilla use key down, `L_DOWN` = vanilla attack key down.
* `M_DOWN` = custom key **"Summon Mirage Blade" (default middle mouse)** is down (line 91).
* If the set differs from the previous one: for every newly-pressed command store
  `lastPressTimes[c] = world.getGameTime()` locally, replace the local set, and send `MoveCommandMessage{int command}`.

### 0.3 Server handling (`network/MoveCommandMessage.java:33-71`)

* Ignored if main-hand empty or not a blade.
* `old = state.commands.clone()`, replace with received set, `lastPressTimes[c] = server gameTime` for every newly
  set bit, then fire `InputCommandEvent(player, state, old, current)` (`event/handler/InputCommandEvent.java`).
* Listeners of `InputCommandEvent`: `LockOnManager`, `TargetSelector.onInputChange` (taunt), `SummonedSwordArts`,
  `SlayerStyleArts` (tricks/air trick), `SuperSlashArts`, `EnemyStep`, `KickJump`.
* The per-entity `Scheduler` (a vanilla `TimerQueue<LivingEntity>`, `event/Scheduler.java`) is ticked from
  `ItemSlashBlade.inventoryTick` (`item/ItemSlashBlade.java:482-484`) with `level.getGameTime()`; callbacks
  scheduled for `pressTime + N` run on the first tick where gameTime >= that value. A key already queued under the same
  name is not re-queued (vanilla TimerQueue semantics).

**1.7.10 mapping:** SNEAK can be taken server-side from `PlayerToggleSneakEvent` (it is the same sneak-key state),
but V/middle-click/forward/back/left/right/jump must come from the client mod through a plugin channel (int bitset,
same bit layout is convenient). The mod sends only on change and only while holding a blade.

---------------------------------------------------------------------------------------------------

## 1. LOCK-ON

### 1.1 Trigger: yes, it is "hold Sneak" (`ability/LockOnManager.java:46-106`)

Server side, in the `InputCommandEvent` listener:

```java
if (!(stack.getItem() instanceof ItemSlashBlade)) return;            // main hand must be a blade
if (old.contains(SNEAK) == current.contains(SNEAK)) return;           // only on a SNEAK edge   (62)
if (old.contains(SNEAK) && !current.contains(SNEAK)) targetEntity = null;   // release -> unlock (66-68)
else { ...search... }                                                 // press -> search once
stack.getCapability(BLADESTATE).ifPresent(s -> s.setTargetEntityId(targetEntity)); // (104) -1 when null
```

* Lock is acquired **once, at the moment Sneak is pressed**; there is no re-targeting, no range check and no
  line-of-sight check while it is held. If the target dies the id simply stays stored (client code ignores dead
  targets, see below) until Sneak is released.
* There is no separate lock key, no toggle and no target cycling.
* The target id is stored **on the blade item** (`bladeState.TargetEntity`, int entity id, `-1` = none)
  (`capability/slashblade/ISlashBladeState.java:43,97,268-288`). It is synced to the client automatically because
  Forge re-sends an inventory slot whenever the item capability NBT changes (`getShareTag` puts the whole
  `bladeState` into the stack tag, `item/ItemSlashBlade.java:509-528`). On 1.7.10 you need an explicit
  "lock target = entityId / -1" packet to the client mod.
* Edge case: because input packets are only sent while holding a blade, switching away from the blade while sneaking
  leaves the old target stored on that blade.

### 1.2 Target selection

Step 1 - ray trace (`LockOnManager.java:72-89`, `util/RayTraceHelper.java:17-70`):

```java
RayTraceHelper.rayTrace(level, player, player.getEyePosition(1.0f), player.getLookAngle(), 40, 40, e -> true)
```
* Block clip from the eye along the look vector, 40 blocks (COLLIDER, no fluids). If a block is hit, the entity
  reach is shortened to the block distance.
* Entity candidates: all entities (except the player) in `player.getBoundingBox().expandTowards(look*reach).inflate(1.0)`;
  each candidate's AABB is **inflated by 0.5** and clipped against the segment; the nearest hit wins
  (`RayTraceHelper.java:52-61`). No cone/angle tolerance beyond that 0.5 inflation.
* If the hit is a `PartEntity` (multipart, e.g. dragon part) the parent is used. It must be a `LivingEntity` and pass
  `TargetSelector.lockon`.

Step 2 - fallback, nearest entity (`LockOnManager.java:91-97`):
```java
level.getNearbyEntities(LivingEntity.class, TargetSelector.lockon, player,
        player.getBoundingBox().inflate(12.0D, 6.0D, 12.0D))
     .min(comparingDouble(e -> e.distanceToSqr(player)))
```
* Box = player AABB grown by 12 horizontally and 6 vertically. **No facing requirement** (can lock something
  behind you). Priority = smallest squared distance (feet-to-feet).

`TargetSelector.lockon` (`util/TargetSelector.java:41-42`):
`new SlashBladeTargetingConditions().range(40.0D).selector(new AttackablePredicate())`

`SlashBladeTargetingConditions.test(attacker, target)` (`TargetSelector.java:291-313`):
1. If `target.getLastHurtByMob()` is the attacker or allied to it, or (target is a `Mob` and `mob.getTarget()` is
   the attacker/allied) -> add tag `"RevengeAttacker"` to the target.
2. Then vanilla `TargetingConditions(isCombat=true).test`:
   * target != attacker, target alive and not spectator,
   * selector (`AttackablePredicate`) must pass,
   * combat: `attacker.canAttack(target)`, `canAttackType`, and NOT `attacker.isAlliedTo(target)` (scoreboard team),
   * range: `distSqr(attacker, target.pos) <= max(40 * target.getVisibilityPercent(attacker), 2)^2`
     (visibility <1 for invisible/sneaking targets or targets wearing the matching mob head),
   * line of sight only checked when the attacker is a Mob (never for players).

`AttackablePredicate.test(target)` (`TargetSelector.java:61-97`), evaluated in this order:
1. config `pvp_enable` (default **false**) off and target is a Player -> false
2. target is an ArmorStand -> return `isMarker()`
3. target has tag `"RevengeAttacker"` -> remove tag, **true** (anything that attacked/targets you is lockable)
4. config `friendly_enable` (default **false**) off and target is not an `Enemy` (monster) -> false
5. target has a Player passenger -> false
6. target is glowing -> true
7. target is on a scoreboard team -> true
8. otherwise true unless the entity type is in tag `slashblade:blacklist/attackable`
   (`minecraft:villager`, optional `touhou_little_maid:maid`).

So by default: hostile mobs, plus any mob that hurt you or is targeting you (wolves, iron golems, zombified
piglins...), are lockable; passive animals and other players are not.

### 1.3 Camera / player rotation follow (client only) (`LockOnManager.java:108-175`)

Runs on `TickEvent.RenderTickEvent` phase START (once per rendered frame, before the world is rendered):

Conditions: local player non-null, main-hand item has BLADESTATE, `s.getTargetEntity(level)` non-null and
`isAlive()`, and the **local** input state contains SNEAK.

```java
float partialTicks = mc.getFrameTime();          // = timer partial tick 0..1 (NOT frame delta)
save yHeadRot, yBodyRot, xRot, yRot and their *O (previous) values
player.lookAt(EYES, target.position().add(0, target.getEyeHeight() / 2.0, 0));
       // aim point = target FEET + half of EYE HEIGHT (not bbHeight/2)
       // lookAt sets xRot = wrap(-atan2(dy, horiz)), yRot = wrap(atan2(dz,dx) - 90), yHeadRot = yRot
float step = 0.125f * partialTicks;
step *= Math.min(1.0f, Math.abs(Mth.wrapDegrees(oldYaw - player.yHeadRot) * 0.5));
player.setXRot(Mth.rotLerp(step, oldPitch, player.getXRot()));
player.setYRot(Mth.rotLerp(step, oldYaw, player.getYRot()));
player.setYHeadRot(Mth.rotLerp(step, oldYawHead, player.getYHeadRot()));
player.yBodyRot = oldYawOffset;                   // body yaw untouched
restore yBodyRotO, yHeadRotO, yRotO, xRotO        // so render interpolation is not disturbed
```
`Mth.rotLerp(d, a, b) = a + d * wrapDegrees(b - a)`.

Behaviour/quirks to reproduce exactly:
* Exponential approach: each frame the rotation moves `step` of the remaining angle; `step` is at most 0.125 and is
  multiplied by the **partial tick** (0..1, average ~0.5), so the effective speed is frame-rate dependent
  (more FPS = faster lock) and jittery. Average ~6 % of remaining angle per frame.
* Dead-zone softening: when the yaw error is below 2 degrees the step is scaled by `|yawError|/2`. The same factor
  scales the pitch lerp, so if yaw is already exactly aligned the pitch converges extremely slowly (quirk).
* Eye position is the current (non-interpolated) eye pos. Changes are made to the real `rotationYaw/Pitch`,
  so the server receives them via normal look packets; server-side attacks (which use `getLookAngle()`) therefore
  point at the target. The mouse still works on top (the lock just pulls).
* The movement code is not changed: while locked the player is sneaking and therefore walks at vanilla sneak speed
  and won't walk off edges. In third person the crouch *pose* is visually cancelled for blade holders
  (`event/client/SneakingMotionCanceller.java:30-55`: sets `model.crouching=false` and undoes the render offset,
  except for the local player in first person).

1.7.10 port: subscribe `TickEvent.RenderTickEvent` START, use `mc.timer.renderPartialTicks` as `partialTicks`,
operate on `rotationYaw`, `rotationPitch`, `rotationYawHead`; keep `renderYawOffset` and all `prev*` fields
unchanged; `MathHelper.wrapAngleTo180_float` for wrapDegrees.

### 1.4 Lock-on marker (`client/renderer/LockonCircleRender.java:43-122`)

Subscribed to `RenderLivingEvent<?,?>` (the abstract parent - in practice this receives both Pre and Post, i.e.
it probably draws twice per frame; additive blending makes that brighter - uncertain).
Drawn only when: local input has SNEAK, the rendered entity equals the blade's target, entity alive.

* Model `assets/slashblade/model/util/lockon.obj`, texture `assets/slashblade/model/util/lockon.png`
  (4x4 pure white, so color comes only from vertex color).
* Color `col = (blade effect color RGB) | 0xAA000000` (alpha 170/255). Effect color = `SummonedSwordColor`
  of the blade (default 0x3333FF).
* Transform (inside the entity render pose, origin at entity feet):
  ```java
  f = bbHeight * 0.5f;  translate(0, f, 0);
  offset = (cameraPos - (entityLerpPos + (0,f,0))) * 0.5;  translate(offset)   // marker sits HALFWAY to the camera
  mulPose(cameraOrientation());                                                // billboard
  scale(0.0025, -0.0025, 0.0025);
  ```
  Because it is halfway to the camera, its apparent size is twice its real size; it is NOT scaled by entity size
  and it is always visible in front of the target.
* Geometry (model units, x0.0025 -> blocks):
  * group `lockonBase`: 4 corner bracket shapes ("<" shapes) at radius 34.8-73.8 (0.087-0.18 blocks), z=0.
  * group `lockonHealth`: ring radius 80-100 (0.20-0.25 blocks), made of **4 arcs of 72 degrees separated by 18 degree gaps**
    (arc centers at 90, 0, -90, 180 degrees model space).
  * group `lockonHealthMask`: same ring footprint (r 78-102) but a helical ramp: z goes from +0.69 to -10.06 in 32
    faces; depth order of the arcs: arc at 54-126 deg shallowest, then 324-36, then 234-306, then 144-216 deepest.
* Draw order and states:
  1. `lockonBase` with `col`, render type "luminous": additive blend (`SRC_ALPHA, ONE`), no cull, no depth write,
     full-bright (light 0xF000F0).
  2. push; `translate(0, 0, health * 10.0f)` (health = hp/maxHp); `lockonHealthMask` with color `0x20000000`
     (black, alpha 0x20 -> visually invisible in additive mode) using the **depth-writing** luminous type; pop.
  3. `lockonHealth` with `col`, depth-writing luminous type (depth-tested).
  Effect: the invisible mask writes depth in front of part of the health ring; as health drops the mask moves and
  occludes more of the ring, so the visible ring length is proportional to remaining health (at 100 % nothing is
  hidden, at 0 % almost all of it is hidden; it disappears arc by arc in a continuous sweep). Simpler equivalent for
  1.7.10: draw the 4-arc ring only over `health * 360` degrees following the same order (deepest arc first to vanish).

### 1.5 How lock-on changes gameplay

There is no auto-dash in normal combos and no homing of normal attacks: combo/area attacks use the player's
look vector, which the client-side lock rotation points at the target. Systems that read the stored target:

| System | File | Effect of a lock target |
|---|---|---|
| Air Trick | `ability/SlayerStyleArts.java:144-159,262-332` | Sneak+Forward+V (new V press) with a target: if target == `lastHurtMob` and hit <100 ticks ago -> teleport sequence directly; else spawn an invisible-ish summoned sword at `targetCenter - look*2` flying along look (speed 1.0, dmg 0.01) that force-hits the target, and on hit teleports. Teleport: after 3 ticks (`sb.airtrick.counter`=3) player is moved to `target.pos + (0, bbHeight/2, 0) - look*2`, Y-velocity zeroed, 10 ticks "untouchable", combo reset to root, ender-man teleport sound. Without a target the same input does **Trick Up** (+0.8 Y). Multipart: first part used. |
| Judgement Cut SA | `slasharts/JudgementCut.java:40-44` | JC spawns at the target's eye position instead of raycast (5 blocks air / 7 entity). |
| Summoned sword (single) | `ability/SummonedSwordArts.java:118-128,153-174` | Aimed at the locked target's `y + eyeHeight*0.5`; else 12-block entity raycast; else 40-block block raycast. |
| Storm Swords | `SummonedSwordArts.java:277-319` | **Requires** a live lock target; swords orbit the target. |
| Heavy Rain Swords | `SummonedSwordArts.java:404-424` | Centered on target position (+7 Y), else 5 blocks ahead. |
| Blistering Swords | `entity/EntityBlisteringSwords.java:90-110` | Fired at lock target, else 12-block raycast. |
| Taunt | `util/TargetSelector.java:245-289` | While Sneak held, a fresh middle-click (M_DOWN edge) with a living target that is not already angry at you: `target.setLastHurtByMob(player)` and 5 angry-villager particles at the target's eye. This also makes the target lockable through the revenge rule. |
| Arrow reflect | `ability/ArrowReflector.java:28-68` | The target branch is dead code (`if (attacker instanceof LivingEntity) break;`), reflected projectiles go along the look vector (speed 3.5, inaccuracy 0.2, set crit). |

Sneak itself is also part of combo inputs (independent of having a target): ground Sneak+Forward+Right-click =
Rapid Slash, ground Sneak+Back+Right-click = Upper Slash, air Sneak+Back+Right-click = Aerial Cleave
(`registry/combo/ComboCommands.java:31-47`), and Sneak while standing still enables Guard (`ability/Guard.java`).

---------------------------------------------------------------------------------------------------

## 2. CONCENTRATION ("STYLE") RANK

Capability `slashblade:concentration` attached to **every LivingEntity** (`event/handler/CapabilityAttachHandler.java:20-28`).
Persisted NBT: `rawPoint` (long), `lastupdate` (long) only (`ConcentrationRankCapabilityProvider.java:30-43`);
`lastrankrise` is NOT saved.

### 2.1 Ranks and thresholds (`capability/concentrationrank/IConcentrationRank.java:20-49`, `ConcentrationRank.java:12`)

`UnitCapacity = 300` points per level; `MAX_LEVEL = 6.0` -> `maxCapacity = 6*300 - 1 = 1799`.

| Rank | `level` int | rank level range (points/300) | raw points | HUD row (v) |
|---|---|---|---|---|
| NONE | 0 | < 1.0 | 0-299 | hidden |
| D | 1 | [1.0, 2.0) | 300-599 | 0 |
| C | 2 | [2.0, 3.0) | 600-899 | 32 |
| B | 3 | [3.0, 4.0) | 900-1199 | 64 |
| A | 4 | [4.0, 5.0) | 1200-1499 | 96 |
| S | 5 | [5.0, 5.25) | 1500-1574 | 128 |
| SS | 6 | [5.25, 5.5) | 1575-1649 | 160 |
| SSS | 7 | >= 5.5 | 1650-1799 | 192 |

### 2.2 Decay (lazy, computed from time) (`IConcentrationRank.java:73-100`)

```java
long getRankPoint(long now) { return raw - reductionLimitter(now - lastUpdate); }
long reductionLimitter(long reduction) { return Math.min(reduction, raw % 300); }
float getRankLevel(now) { return getRankPoint(now) / 300f; }
```
* Decay = **1 point per tick** since the last update, but it can never take you below the integer floor of the
  current level (`raw % 300`). Consequences:
  * D..A decay down to exactly the bottom of their own rank and **stay there indefinitely**.
  * S, SS and SSS (all inside level 5) decay down to 1500 = S and stay at S.
  * Rank never returns to NONE by waiting; only damage removes it.
* Every `addRankPoint` bakes the decayed value into `raw` and sets `lastUpdate = now`, so the drain is effectively
  continuous 1 point/tick between the floor and the current value.

### 2.3 Gaining / losing points

`addRankPoint(user, delta)` (`IConcentrationRank.java:102-120`):
```java
oldRank = getRank(now);
raw = clamp(getRankPoint(now) + delta, 0, 1799);  lastUpdate = now;
if (oldRank.level < getRank(now).level) lastRankRise = now;
if (user is ServerPlayer) send RankSyncMessage{long rawPoint} to that player;
```
`addRankPoint(DamageSource src)` (`:122-135`): user = `src.getEntity()` (must be LivingEntity);
modifier = `getRankPointModifier(comboId)` if the user holds a blade, else `getRankPointModifier(src)`; both return
**0.1** (`ConcentrationRank.java:55-62`), so a hit always gives `0.1 * 300 = 30` points.

Sources (`event/handler/RankPointHandler.java:32-52`, `LivingHurtEvent` at priority LOWEST, not when cancelled):
* **Victim**: every non-cancelled hurt of any living entity: `victim.addRankPoint(victim, -300)` (one whole level).
  Any damage type counts (mob, fall, fire...). Damage negated earlier by Guard (LivingAttackEvent cancel) or the
  untouchable frames does not count.
* **Attacker**: if `source.getEntity()` is a LivingEntity whose main hand has a blade capability: **+30** per
  hurt event (so per target hit; area attacks hitting 3 mobs give +90). Indirect damage whose source entity is the
  player (summoned swords, drives, judgement cut...) counts too.
* **Just Guard** (`ability/Guard.java:183-185`): `addRankPoint(damageSources().thorns(victim))` -> source entity =
  the victim himself, holding a blade -> +30.

Arithmetic: D needs 10 hits, S needs 50 hits, SSS 55 hits, cap 60 hits; to climb you need on average more than one
hit every 30 ticks (1.5 s) to beat the drain.

Client sync (`network/RankSyncMessage.java:49-66`): client sets `raw = point`, `lastUpdate = clientGameTime`,
updates `lastRankRise` if the rank went up, then computes decay locally.

### 2.4 Gameplay effects of rank

| Effect | File:line | Formula |
|---|---|---|
| Melee damage bonus (all combo/area attacks through AttackHelper) | `util/AttackHelper.java:82-125` | `bonus = rank.level / 2.0` (D 0.5, C 1, B 1.5, A 2, S 2.5, SS 3, SSS 3.5). If rank >= S: `bonus = max(bonus, min(playerXpLevel, blade.refine) * refine_damage_multiplier(1.0))`. Total: `(attackAttr + sweepingBonus + bonus + enchantDamageBonus) * comboRatio * slashbladeDamageAttr * slashblade_damage_multiplier`, then x1.5 on vanilla-style crit. Sweeping bonus = `10 * sweepingRatio * 0.5`. |
| Drive projectile damage | `entity/EntityDrive.java:302-321` | same rank bonus formula added after `damage *= attackAttr`. |
| Proud Soul gain on kill | `event/handler/KillCounter.java:72-76` | `souls = floor(droppedXp * (1 + rank.level * 0.1))`, capped by `max_proud_soul_got` (100). |
| Guard | `ability/Guard.java:121-205` | rank >= S: normal (non-just) guard costs no durability and can block armor-bypassing projectiles. |
| Spiral / Storm / Blistering swords count | `ability/SummonedSwordArts.java:228-235,293-300,352-359` | 6 swords, 8 if rank >= S. |
| Heavy Rain count | `SummonedSwordArts.java:449` | `9 + min(rank-1, 0)` -> 8 at rank NONE, 9 otherwise (looks like a bug), times 2, plus 1 center sword. |
| Slash effect / JC / Drive visuals | `util/AttackManager.java:126,152`, `slasharts/*.java`, `client/renderer/entity/SlashEffectRenderer.java:80-138`, `entity/EntitySlashEffect.java:324` | entities store `rank` = rank level (float) at spawn. Slash effect: rank < C -> color forced to 0x555555; >= D color base layer; >= B extra white additive outer layer (0x404040); >= S dark inner layer (0x222222); > S (SS/SSS) spawns CRIT particles along the arc. |

Rank does NOT affect the Slash Art proud soul cost.

### 2.5 Rank HUD (`client/renderer/gui/RankRenderer.java:38-151`)

* Event: `RenderGuiOverlayEvent.Post` with no overlay filter (in Forge this fires after every overlay, so the HUD
  is redrawn many times per frame; the texture has ~4100 partially transparent pixels so the repeat makes edges more
  opaque - draw once and consider a slightly stronger alpha if matching looks).
* Not drawn if F1 hid the GUI (`Minecraft.renderNames()` false) or if any screen other than chat is open.
* Not drawn when rank == NONE.
* Visible only while `now < lastUpdate + 120` ticks (6 s after the last point change). Hard cut, no fade.
* "Text" variant for `now < lastRankRise + 20` ticks (1 s after ranking up) -> `textOffset = 128`, else 0.
* Texture `assets/slashblade/textures/gui/rank.png` 256x256, nearest filtering (`setFilter(false,false)`),
  depth test disabled, position-tex shader, no color tint. 1 texel = 1 GUI-scaled pixel (no scaling).
* Anchor: `x0 = guiScaledWidth * 2 / 3`, `y0 = guiScaledHeight / 5` (float translate, integer GUI pixels).

```java
rankOffset   = 32 * (rank.level - 1);          // D=0, C=32 ... SSS=192
textOffset   = showText ? 128 : 0;
progress     = getRankProgress(now);           // 0..1 inside the current rank
int gauge    = (int)(33 * progress);
int icon     = (int)(18 * progress);
int iconInv  = 17 - icon;
//            x   y              u              v                         w      h
quad(0,  0,                 textOffset+64, rankOffset,             64, 32);   // letter outline/frame
quad(0,  iconInv + 7,       textOffset,    rankOffset+iconInv+7,   64, icon); // filled letter, grows bottom-up
quad(0,  32,                0,             256-16,                 64, 16);   // gauge frame
quad(16, 32,                16,            256-32,                 gauge, 16);// gauge fill
```
`getRankProgress` (`IConcentrationRank.java:83-95`): `(level - lower) / (upper - lower)`; D..A width 1.0, S and SS
width 0.25, SSS uses `upper = floor(5.5 + 1) = 6` -> width 0.5.

Texture atlas content (measured from the PNG):
* 4 columns x 7 rows of 64x32 cells: col u=0 colored filled letter, u=64 outline (white with dark outline) letter,
  u=128 / u=192 the alternate ("text") versions shown right after a rank-up. Rows D,C,B,A,S,SS,SSS.
  The glyph pixels occupy y 7..24 of each cell (exactly the 18-px band the fill quad reveals), x roughly 25-41
  (S..A), 19-44 (SS), 13-50 (SSS).
* Gauge frame at v=240-243 (gray 1-px box x 15..48); gauge fill at v=225-226 (2-px red bar x 16..47,
  e.g. RGB 235,51,45 -> 235,138,136). So on screen the bar is a 32x2 red line inside a 34x4 gray box at y0+32.
* Icon fill quad is drawn with z -90 over frame z -95, but depth test is off, so draw order decides (frame, fill,
  gauge frame, gauge fill). Blending state is whatever the previous overlay left (normally alpha-blend on) -
  enable `GL_BLEND` with `SRC_ALPHA, ONE_MINUS_SRC_ALPHA` on 1.7.10.

1.7.10 port: `RenderGameOverlayEvent.Post` with `ElementType.ALL`, `ScaledResolution` width*2/3, height/5,
`Tessellator` quads with uv/256.

---------------------------------------------------------------------------------------------------

## 3. BLADE STATE & PROGRESSION (stored on the item)

### 3.1 NBT layout: `tag.bladeState` (`capability/slashblade/ISlashBladeState.java:38-146`)

| NBT key | type | meaning / default |
|---|---|---|
| `lastActionTime` | long | game time of the last combo state change |
| `TargetEntity` | int | lock-on target entity id, -1 none |
| `_onClick` | bool | internal |
| `fallDecreaseRate` | float | internal (air combos) |
| `AttackAmplifier` | float | unused by damage calc (written only) |
| `currentCombo` | string RL | current combo state id |
| `Damage` | int | durability damage (the stack's damage is redirected here: `getDamage/getMaxDamage` overrides, `ItemSlashBlade.java:532-540`) |
| `maxDamage` | int | max durability, default 40 |
| `proudSoul` | int | proud soul count (>=0) |
| `isBroken` | bool | broken |
| `isSealed` | bool | sealed |
| `baseAttackModifier` | float | base attack, default 4.0 |
| `killCount` | int | kills |
| `RepairCounter` | int | **refine** count |
| `SpecialAttackType` | string RL | slash art id, default `slashblade:judgement_cut` |
| `isDefaultBewitched` | bool | named blade that is bewitched without a custom name |
| `translationKey` | string | `item.<ns>.<name>` of a named blade ("" = nameless) |
| `StandbyRenderType` | byte | CarryType ordinal (NONE, DEFAULT, PSO2, NINJA, KATANA, RNINJA) |
| `SummonedSwordColor` | int | effect color (default 0x3333FF) |
| `SummonedSwordColorInverse` | bool | inverse blending of effects |
| `adjustXYZ` | list<double>[3] | model offset |
| `TextureName`, `ModelName` | string RL | optional, default `slashblade:model/blade.png` / `model/blade.obj` |
| `ComboRoot` | string RL | root combo, default `slashblade:standby` |
| `SpecialEffects` | list<string> | special effect ids (only registered ones kept) |

Vanilla stack tag also matters: enchantments, `display.Name` (custom name), `Unbreakable`.

### 3.2 Sword types (derived each time, `item/SwordType.java:17-64`)

* `BROKEN` = isBroken (removed if stack tag `Unbreakable`).
* `SEALED` = isSealed.
* `ENCHANTED` = stack has any enchantment (removed for Detune items: wood/bamboo/silverbamboo/white).
* `BEWITCHED` ("awakened") = `!sealed && enchanted && (hasCustomHoverName || isDefaultBewitched)`.
  -> **Renaming an enchanted blade in an anvil awakens it.**
* `FIERCEREDGE` = killCount >= 1000.
* `SOULEATER` = proudSoul >= 10000.
* `NOSCABBARD`+`EDGEFRAGMENT` if no blade capability.

Gates: charge/Slash Arts need ENCHANTED, not broken, not sealed (`ItemSlashBlade.java:388-392`,
`event/handler/SlashBladeEventHandler.java` onChargeBlade). Summoned swords need BEWITCHED + Power enchant.
Super Slash Art needs BEWITCHED + FIERCEREDGE + zero damage. Rarity: BEWITCHED=EPIC, ENCHANTED=RARE.
Tooltip (`ItemSlashBlade.java:612-721`): sword type line (Bewitched DARK_PURPLE, or GOLD if SOULEATER+FIERCEREDGE;
Enchanted DARK_AQUA; else "Sealed"(noname) DARK_GRAY; nothing if SEALED), `ProudSoul:%s` (dark purple if >10000),
`KillCount:%s` (dark purple if >1000), `SA :%s` (only BEWITCHED and not SEALED), `Refine :%s` colored by refine
(<10 GRAY, 10-49 YELLOW, 50-99 GREEN, 100-149 AQUA, 150-199 BLUE, >=200 LIGHT_PURPLE), special effects with their
required level (RED if player XP level >= required, else DARK_GRAY). The vanilla durability bar is hidden
(`isBarVisible=false`); instead the GUI icon renders a durability model (`client/renderer/SlashBladeTEISR.java:166-189`).

Sealed is never removed by any code path (only a recipe that outputs a different blade replaces it).

### 3.3 Attack and reach attributes (`item/ItemSlashBlade.java:103-151`)

```java
if (broken) amplifier = -0.5f - base;                          // -> modifier -1.5 => attack ~0
else { f = FIERCEREDGE ? 0.1f : 0.05f;
       amplifier = (1 - 1/(1 + f*refine)) * base; }            // diminishing, max +100% base
attackModifier = base + amplifier - 1;                          // replaces the sword's main-hand ATTACK_DAMAGE
reach (Forge ENTITY_REACH) += broken ? 1.25 : 2.5
```
Player base attack 1.0 -> displayed/used attack = `base + amplifier`. Attack speed modifier = 0 (speed 4.0).
`SlashBladeEvent.UpdateAttackEvent` can alter it (no built-in listener).

### 3.4 Kill count (`event/handler/KillCounter.java:35-56`)
`LivingDeathEvent` (LOWEST): if `source.getEntity()` is a LivingEntity holding a blade (main hand): `killCount += 1`
(any entity type, players included).

### 3.5 Proud soul (PS)

Gain:
* Kill XP (`KillCounter.java:58-90`): `LivingExperienceDropEvent` with an attacking player holding a blade:
  `ps += min(100, floor(droppedXp * (1 + 0.1*rank.level)))`. If SOULEATER: repair `max(1, gained/4)` durability.
* Refine on anvil: see 3.6.
* Crafting/smithing a blade sums PS of ingredient blades (3.9).

Spend:
* Slash Art (release of a charged right-click): `cost = 20` (`slasharts/SlashArts.java:52`); if PS < cost the blade
  takes 1 durability damage instead; creative free (`ItemSlashBlade.java:382-413`).
* Single summoned sword: `summon_sword_cost` = **2** PS; nothing happens if PS < 2.
* Summoned sword arts (Spiral/Storm/Blistering/Heavy Rain): `summon_blade_art_cost` = **20** PS.
* On breaking (see 3.7): PS converted into dropped tiny proud souls (100 PS each).

### 3.6 Refine / repair on the anvil (`event/handler/RefineHandler.java:38-189`)

Runs on `AnvilUpdateEvent` (priority LOW) only if no other handler produced output, left = blade, right = a valid
repair item: `isValidRepairItem` = stone tool materials (cobblestone/blackstone/cobbled deepslate tag) or any item in
`slashblade:proudsouls` (`ItemSlashBlade.java:584-603`).

`level = right.getEnchantmentValue()`:
| material | enchantmentValue |
|---|---|
| Tiny Proud Soul | 10 |
| Proud Soul | 50 |
| Blade Soul Ingot | 100 |
| Blade Soul Sphere | 150 |
| Blade Soul Crystal | 200 |
| Blade Soul Trapezohedron | config `trapezohedron_max_refine` (default Integer.MAX_VALUE) |
| cobblestone etc. | 0 |

Algorithm:
```java
refineLimit = max(10, level);
materialCost = 0; costResult = 0; refineResult = oldRefine;
while (materialCost < right.getCount()) {
    materialCost += 1;
    if (refineResult < refineLimit) refineResult += 1;           // RefineHandler.refineLimitCheck
    costResult += refine_level_cost;                              // default 1 level per item
    if (!creative && player.experienceLevel < costResult) break;  // NOTE: breaks AFTER adding -> cost > levels
}
if (oldRefine <= refineLimit) {
    // durability: +1 maxDamage per refine point, only until refine 200
    if (oldRefine + (refineResult) < 200) maxDamage += refineResult;      // (see note)
    else if (oldRefine < 200) maxDamage += min(oldRefine + refineResult, 200) - oldRefine;
    ps += (refineResult - oldRefine) * min(5000, level * 10);
    refine = refineResult;
}
damage = damage - max(damage, materialCost * max(1, level/2));   // always <= 0 -> FULL repair
output cost = costResult, materialCost = materialCost
```
Notes (literal code behaviour):
* The maxDamage code passes `e2.getRefineResult()` which is the NEW refine total, not the delta:
  `if (state.getRefine() + e2.getRefineResult() < 200) state.setMaxDamage(state.getMaxDamage() + e2.getRefineResult())`.
  So each anvil operation adds the whole new refine value to maxDamage (while old+new < 200). E.g. refine 0 -> 1
  adds 1, refine 1 -> 2 adds 2, 0 -> 5 in one go adds 5. (Probably a bug, but that is what it does.)
* Every material item is consumed and costs a level even when refine is already at the limit.
* If the player has fewer levels than items, the loop stops with `costResult = levels + 1`, so the anvil output
  cannot be taken (vanilla requires level >= cost) - only an exact stack size works. Creative ignores this.
* PS per refine point: tiny 100, proud soul 500, ingot 1000, sphere 1500, crystal 2000, trapezohedron 5000;
  cobblestone 0.
* Refine caps: up to 10 with tiny/cobble, 50 soul, 100 ingot, 150 sphere, 200 crystal, unlimited trapezohedron.
* Repair is always complete (any valid material). If the blade was broken and damage reaches 0 and it is not sealed,
  it is un-broken (`ItemSlashBlade.setDamage`, lines 213-228).
* Advancement `slashblade:tips/refine` when refine increased (`onAnvilRepairEvent`).

### 3.7 Durability, breaking, self-repair

Durability damage: 1 per `hurtEnemy` (each hit), 1 per block mined with hardness != 0, 1 per Slash Art without
enough PS, 1 per non-just Guard below rank S, `maxDamage/2` for a Super Slash Art.

Breaking (`ItemSlashBlade.damageItem`, lines 230-266): when `damage + amount >= maxDamage`, damage is set to
`maxDamage - 1` and the blade becomes **broken** (not destroyed) unless `BreakEvent` cancelled; only Detune
"destructable" items (wood, bamboo) are destroyed (`stack.shrink`). On the transition `getOnBroken` (lines 268-345):
* If enchanted: drop `n = ps >= 1000 ? 10 : max(1, ps/100)` (config `max_enchanted_proudsoul_drop` = 10) Tiny Proud
  Souls, each with one random enchantment (level 1) applicable to the blade (excluding config blacklist), `ps -= 100`
  each.
* Then drop `n = ps >= 1000 ? 10 : max(1, ps/100)` (config `max_proudsoul_drop` = 10) Tiny Proud Souls as one stack,
  `ps -= n*100`. This stack is spawned as a special `BladeItemEntity` rendered with the blade's model/texture,
  glowing, pushed up 0.4, pickup delay 40 ticks.
* `user.persistentData.BreakActionTimeout = now + 100`.
While broken: attack ~0 (see 3.3), reach +1.25, no Slash Arts/summons, renders the `item_damaged` model part.

Self-repair (`ItemSlashBlade.inventoryTick`, lines 456-481), **every tick while the blade is in the inventory but
not the selected slot**, if config `self_repair` (true) and (BEWITCHED or (Hunger effect and `hunger_can_repair`)),
damage > 0 and food > 0: `lvl = 1 + hungerAmplifier (0 if no hunger)`; if `self_repair_cost_exp` (true) the player
needs `experienceLevel >= 1*lvl` and loses `1*lvl` experience **points**; exhaustion `0.05*lvl`; repair `lvl`
durability.

### 3.8 Special effects (complete list)

Only **one** SE is registered (`registry/SpecialEffectsRegistry.java:18`):

`slashblade:wither_edge` - "Wither Edge" (`registry/specialeffects/WitherEdge.java`)
* `requestLevel = 20`, copiable = true, removable = true (line 16).
* If the holder (Player, blade in the selected slot) has XP level < 20: every tick the holder gets Wither II
  (amplifier 1) for 100 ticks (lines 20-37).
* If XP level >= 20: every blade hit (HitEvent) gives the target Wither II for 100 ticks (lines 40-53).
* Comes on Koseki by default. Can be moved with Blade Soul Crystals at a blade stand (3.10).

Generic SE rule: "effective" iff player XP level >= requestLevel (`SpecialEffect.isEffective`).

### 3.9 Slash Arts (list; mechanics are covered in other reports)

Registered (`registry/SlashArtsRegistry.java`): `none`, `judgement_cut` (ground/air variant; Just -> JC slash just;
Super -> JC end), `sakura_end`, `void_slash`, `circle_slash`, `drive_vertical`, `drive_horizontal`, `wave_edge`,
`piercing` (has a Just variant). All cost 20 PS. Default when missing/unknown = judgement_cut.
Charge timing (`slasharts/SlashArts.java:26-33`, `ISlashBladeState.doChargeAction`): `ChargeTicks = 9`,
just window `min(5, 3 + SoulSpeedLevel)`; elapsed < 9 = Fail, [9, 9+just) = Jackpot (Just), later = Success.
Full-charge particle (7 PORTAL) at exactly tick 9 of use. Super Slash Art: hold V for 20 ticks, not (sneak+move),
on ground, BEWITCHED+FIERCEREDGE, damage==0, costs maxDamage/2 (`ability/SuperSlashArts.java`).

### 3.10 Blade stand (`entity/BladeStandEntity.java`, `item/BladeStandItem.java`, `event/bladestand/BlandStandEventHandler.java`)

Items: `bladestand_1` (single), `bladestand_2` (dual), `bladestand_v` (vertical), `bladestand_s` (short) - placeable
only on top faces; `bladestand_1w`, `bladestand_2w` (wall racks) - placeable only on side faces. Entity extends
ItemFrame (holds 1 item, rotation 0-7 x45 degrees, drops its stand item when broken).

Interaction (main hand, server):
* Sneak + right-click with a blade on the stand -> cycles the entity `Pose` (display variant).
* Right-click holding a blade on an empty stand -> place it; holding a blade on an occupied stand -> swap; empty
  hand on occupied stand -> take it. Otherwise rotate (like an item frame).
* **Hitting (left-click) a stand holding a blade** fires `BladeStandAttackEvent`; built-in handlers:

| Handler | Held item | Effect |
|---|---|---|
| eventKoseki (45-62) | - (damage source = Wither boss explosion) | If a nameless blade (empty translation key) sits on a stand hit by a Wither explosion it is replaced by **Koseki**. |
| eventChangeSE (64-121) | Blade Soul Crystal with tag `SpecialEffectType` | Adds that SE to the blade if missing; consumes 1 crystal (not in creative). |
| eventChangeSA (123-172) | Blade Soul Sphere with tag `SpecialAttackType` | Sets the blade's slash art; consumes 1 sphere. |
| eventCopySE (174-254) | Blade Soul Crystal **without** SE tag | For the first registered SE on the blade that is copiable: consume 1 crystal, drop a crystal tagged with that SE, and remove the SE from the blade if removable (wither_edge is removable -> it is moved, not copied). |
| eventCopySA (256-316) | **Enchanted** Blade Soul Ingot | If blade SA != none and (copySAEnchantmentCheck) at least one of the ingot's enchantments is at max level on the blade: consume 1 ingot, drop a Blade Soul Sphere tagged `SpecialAttackType` = blade SA (blade keeps its SA). |
| eventProudSoulEnchantment (318-404, priority LOW) | Any enchanted proud soul item | For each enchantment on the soul that can apply to the blade: with probability tiny 0.25 / proud soul 0.5 / ingot 0.75 / others 1.0, set blade level to `min(maxLevel, current+1)`. Consumes 1 item (even if all rolls fail). |
Success effect: Wither-spawn sound (vol 0.5, pitch 0.8) + up to 32 PORTAL particles around the stand.

### 3.11 Summoned swords: cost, condition, key

Key: **"Summon Mirage Blade", default middle mouse button** (`client/SlashBladeKeyMappings.java:14`); when it is
on its default binding and you hold a blade, vanilla pick-block is cancelled (`event/handler/BlockPickCanceller.java`).
Server: `ability/SummonedSwordArts.java:63-151`.

Common conditions: blade not broken, not sealed, BEWITCHED, enchanted with **Power** (`minecraft:power`) level >= 1.
Damage of each sword = Power level.

* **Press** (M_DOWN edge): single "Summoned Sword" if PS >= 2 (costs 2). Spawn at eye + 1 block to the side
  (`getVectorForRotation(0, yaw+90)`, side alternates using the `sword_summoned` stat parity), random roll, speed 3.0,
  no spread, aimed at lock target / raycast (see 1.5). Sound chorus-fruit teleport (0.2, 1.45).
* Hold-commands checked once at `pressTime + 10` ticks (key still held and the press time unchanged), each 20 PS:
  * **Spiral Swords**: M held and NOT (sneak AND any move key). If spiral swords already orbit the player -> fire
    them; else summon 6 (8 at rank >= S), delays `360/count*i`, riding the player.
  * **Storm Swords**: M + Sneak + Back (not Forward), needs a live lock target; 6/8 swords riding the target.
  * **Blistering Swords**: M + Sneak + Forward and Back NOT pressed within the last 20 ticks; 6/8 swords riding the
    player, delays i; they fire (at lock target) when M is released.
  * **Heavy Rain Swords**: M + Sneak + Forward with Back pressed within the last 30 ticks (Back -> Forward
    command); 1 center + `(9 + min(rank-1,0)) * 2` swords at target pos (or 5 blocks ahead) + 7 Y.
  Note the single sword also fires on the press, so holding variants also spend 2 PS for the initial sword.

---------------------------------------------------------------------------------------------------

## 4. KEY BINDINGS

Registered in `client/ClientHandler.java:110-114`, defined in `client/SlashBladeKeyMappings.java`:

| Key mapping id | Lang (en_us) | Default | Category | Sent as | Used for |
|---|---|---|---|---|---|
| `key.slashblade.special_move` | "Special Move" | **V** (`GLFW_KEY_V`; 1.7.10 `Keyboard.KEY_V`) | `key.category.slashblade` ("Slash Blade") | `SPRINT` bit | Trick Dodge (ground, any move key + new V press, max 3 within 20 ticks), Trick Up (Sneak+Forward+V, no target), Air Trick (Sneak+Forward+V with lock target), Trick Down (air, Sneak+Back+V), Super Slash Art (hold 20 ticks), Guard while sprinting ("ninja run"). |
| `key.slashblade.summon_blade` | "Summon Mirage Blade" | **Middle mouse** (`GLFW_MOUSE_BUTTON_MIDDLE`; 1.7.10 keycode -98) | same | `M_DOWN` bit | Summoned swords and their hold variants; Sneak+M = taunt the lock target. |

Both use `KeyConflictContext.IN_GAME`, no modifier. Everything else uses vanilla keys: Sneak (lock-on, guard,
sneak combos), Use (right-click combos, hold to charge Slash Art), Attack (left-click combos), Jump (enemy step,
kick jump), movement keys.

---------------------------------------------------------------------------------------------------

## 5. BLADE DEFINITIONS (data-driven "named blades")

### 5.1 Format

Datapack registry `slashblade:named_blades` (`registry/slashblade/SlashBladeDefinition.java:28-41`), files at
`data/<ns>/slashblade/named_blades/<id>.json` (generated ones in
`src/generated/resources/data/slashblade/slashblade/named_blades/`).

```jsonc
{
  "item": "slashblade:slashblade",          // optional, item to use (default slashblade:slashblade)
  "name": "slashblade:yamato",              // required; translation key = "item.slashblade.yamato"
  "render": {                               // required (RenderDefinition.java:10-20)
    "texture": "slashblade:model/named/yamato.png",   // default slashblade:model/blade.png
    "model":   "slashblade:model/named/yamato.obj",   // default slashblade:model/blade.obj
    "summon_sword_color": 3158064,          // int ARGB, default 0xFF3333FF (-13421569)
    "color_inverse": false,                 // default false
    "carry_type": "default"                 // none|default|pso2|ninja|katana|rninja, codec default pso2
  },
  "properties": {                           // required (PropertiesDefinition.java:14-30)
    "root_combo": "slashblade:standby",     // default
    "slash_art": "slashblade:judgement_cut",// default
    "attack_base": 7.0,                     // default 4.0
    "max_damage": 40,                       // default 40
    "sword_type": ["bewitched"],            // only bewitched / broken / sealed have an effect
    "special_effects": ["slashblade:wither_edge"],
    "unbreakable": false                    // true -> stack tag Unbreakable
  },
  "enchantments": [ {"id": "minecraft:power", "lvl": 5} ],   // optional, lvl default 1
  "creativeGroup": "slashblade:slashblade"  // optional
}
```
Instantiation (`SlashBladeDefinition.getBlade`, lines 109-162): sets base attack, maxDamage, combo root, SA, SEs;
`bewitched` -> `isDefaultBewitched=true`; `broken` -> damage = max-1 and isBroken; `sealed` -> isSealed; model,
texture, color, inverse, carry type; translation key unless name is `slashblade:none`; writes `bladeState`;
applies enchantments; `Unbreakable` if requested. Note: the datagen Builder default carry type is DEFAULT while the
JSON codec default is PSO2 (datagen always writes it explicitly).

### 5.2 Builtin named blades (`data/builtin/SlashBladeBuiltInRegistry.java:55-318`)

Defaults when not set: attack 4.0, max_damage 40, slash art judgement_cut, color 0xFF3333FF, model `model/blade.obj`.
Paths are under `slashblade:` (assets/slashblade/...).

| id | display name (en_us) | texture | model | carry | attack | maxDmg | slash art | sword_type | enchantments | SE / color |
|---|---|---|---|---|---|---|---|---|---|---|
| yamato | Yamato | model/named/yamato.png | model/named/yamato.obj | default | 7.0 | 40 | judgement_cut | bewitched | soul_speed 2, power 5, feather_falling 4 | - |
| yamato_broken (name `yamato`) | Yamato | same | same | default | 7.0 | 40 | judgement_cut | broken, sealed | - | - |
| yuzukitukumo | Noble -Tukumo- Violet | model/named/a_tukumo.png | model/named/agito.obj | pso2 | 6.0 | 40 | drive_horizontal | bewitched | fire_aspect 1, sharpness 4, unbreaking 3 | - |
| muramasa | -Chizuru- Muramasa | model/named/muramasa/muramasa.png | model/named/muramasa/muramasa.obj | pso2 | 7.0 | 50 | drive_vertical | - | - | - |
| ruby | Sharpness -nameless- Ruby | model/ruby.png | model/blade.obj | default | 5.0 | 45 | judgement_cut | - | - | - |
| sange | Sange | model/named/sange/sange.png | model/named/sange/sange.obj | pso2 | 6.0 | 70 | void_slash | bewitched | - | - |
| fox_black | -Haze- SakuraFox | model/named/sange/black.png | model/named/sange/sange.obj | pso2 | 5.0 | 70 | piercing | bewitched | smite 4, knockback 2, fire_aspect 2 | - |
| fox_white | -Weiss- SakuraFox | model/named/sange/white.png | model/named/sange/sange.obj | pso2 | 5.0 | 70 | judgement_cut | bewitched | knockback 2, bane_of_arthropods 2, unbreaking 3, fire_aspect 2, looting 3 | - |
| rodai_wooden | -Wooden- Rodai | model/rodai_wooden.png | model/blade.obj | default | 2.0 | 60 | judgement_cut | - | - | - |
| rodai_stone | -Stone- Rodai | model/rodai_stone.png | blade.obj | default | 3.0 | 132 | judgement_cut | - | - | - |
| rodai_iron | Named -Steel- Rodai | model/rodai_iron.png | blade.obj | default | 4.0 | 250 | judgement_cut | - | - | - |
| rodai_golden | Named -Golden- Rodai | model/rodai_golden.png | blade.obj | default | 2.0 | 33 | judgement_cut | - | - | - |
| rodai_diamond | Named -Diamond- Rodai | model/rodai_diamond.png | blade.obj | default | 7.0 | 1561 | judgement_cut | - | - | - |
| rodai_netherite | Named -Netherite- Rodai | model/rodai_netherite.png | blade.obj | default | 8.0 | 2031 | judgement_cut | - | - | - |
| tagayasan | Ironwood -Tagayasan- | model/named/tagayasan.png | model/blade.obj | katana | 5.0 | 70 | drive_vertical | bewitched | smite 3, unbreaking 3 | - |
| agito | -Agito- | model/named/agito_false.png | model/named/agito.obj | pso2 | 5.0 | 60 | wave_edge | - | - | - |
| agito_rust | Rust -Agito- | model/named/agito_rust.png | model/named/agito.obj | pso2 | 3.0 | 60 | judgement_cut | sealed | - | - |
| orotiagito | -Orotiagito- | model/named/orotiagito.png | model/named/agito.obj | pso2 | 7.0 | 60 | wave_edge | bewitched | - | - |
| orotiagito_sealed | -Agito- | model/named/agito_true.png | model/named/agito.obj | pso2 | 5.0 | 60 | wave_edge | - | - | - |
| orotiagito_rust | Rust -Agito- | model/named/agito_rust_true.png | model/named/agito.obj | pso2 | 3.0 | 60 | judgement_cut | sealed | - | - |
| yasha | Yasha | model/named/yasha/yasha.png | model/named/yasha/yasha.obj | pso2 | 6.0 | 70 | sakura_end | - | - | - |
| yasha_true | Yasha -Kikouku- | model/named/yasha/yasha.png | model/named/yasha/yasha_true.obj | pso2 | 6.0 | 70 | circle_slash | bewitched | - | - |
| sabigatana | Sabigatana | model/named/muramasa/sabigatana.png | model/named/muramasa/muramasa.obj | pso2 | 3.0 | 40 | judgement_cut | - | - | - |
| sabigatana_broken (name `sabigatana`) | Sabigatana | same | same | pso2 | 3.0 | 40 | judgement_cut | broken, sealed | - | - |
| doutanuki | Steel -Doutanuki- | model/named/muramasa/doutanuki.png | model/named/muramasa/muramasa.obj | pso2 | 5.0 | 60 | circle_slash | bewitched | - | - |
| koseki | (missing in en_us; ja_jp "枯石の大刀", ru "Иссушающий -Косэки-") | model/named/dios/koseki.png | model/named/dios/dios.obj | ninja | 5.0 | 70 | drive_vertical | bewitched | power 2 | wither_edge, color 0x303030 |

Non-data "Detune" blade items (`registry/SlashBladeItems.java:126-141`, `item/ItemSlashBladeDetune.java`):
uses = maxDamage, base attack fixed by the item (`SimpleSlashBladeState.getBaseAttackModifier` returns the
constructor value; refine amplifier still applies on top), never Enchanted/Bewitched (so no Slash Arts / summons),
model `model/blade.obj`:

| item | name | maxDamage | attack | texture | destroyed when broken |
|---|---|---|---|---|---|
| slashblade_wood | Anonymity -Wood- | 60 | 2 | model/wood.png | yes |
| slashblade_bamboo | Anonymity -Bamboo Light- | 70 | 3 | model/bamboo.png | yes |
| slashblade_silverbamboo | Noted -Silver Bamboo Light- | 40 | 3 | model/silverbamboo.png | no |
| slashblade_white | Sharpness -White- | 70 | 4 | model/white.png | no |
| slashblade (normal ItemSlashBlade) | Anonymity -Nameless- | 40 (state default) | 4 | model/blade.png | no |

### 5.3 How named blades are obtained (progression requirements)

Recipe ingredient requirements use `RequestDefinition` (`recipe/RequestDefinition.java:24-129`): optional blade
`name`, minimum `proud_soul`, `kill`, `refine`, enchantments (min levels), sword types. Crafted results inherit:
PS and kills summed from ingredient blades, refine summed (config `do_crafting_sum_refine` true) or max, enchantments
merged (`recipe/SlashBladeShapedRecipe.java:63-127`). Smithing (rodai_netherite) adds PS and refine; kill count = max
when `do_crafting_sum_refine` is true, else sum (inverted naming in code).

Key recipes (`data/SlashBladeRecipeProvider.java`):
* slashblade_wood <- wooden sword + 2 logs; bamboo <- wood blade + 2 bamboo; silverbamboo <- bamboo blade + iron,
  string x2, paper, egg, black dye; white <- wood blade + 2 Blade Soul Ingots + gold.
* slashblade (Nameless) <- **broken** White blade + gold x2, string, blue dye, blaze rod, coal block.
* yamato_fix: broken+sealed Yamato surrounded by 8 Blade Soul Spheres -> Yamato.
* ruby <- broken Silver Bamboo + proud soul + 2 ingots + string + red dye.
* fox_black / fox_white <- Ruby with Smite >=1 / Looting >=1 + crystal, obsidian, feather, blaze powder, quartz
  block, wheat.
* muramasa <- any nameless blade with **PS >= 10000 and refine >= 20** + 8 spheres.
* tagayasan <- Wood blade with Unbreaking >=1, PS >= 1000, refine >= 10 + 4 spheres, 2 ender eyes, 2 pearls.
* agito <- agito_rust with kills >= 100 + 4 proud souls; orotiagito_sealed <- orotiagito_rust kills >= 100 + 4 souls;
  orotiagito <- orotiagito_sealed with kills >= 1000, PS >= 1000, refine >= 10 + 4 spheres + 4 souls.
* doutanuki <- sabigatana with kills >= 100, PS >= 1000, refine >= 10 + 2 spheres.
* sabigatana <- broken+sealed sabigatana + 2 ingots.
* yuzukitukumo <- nameless blade with Fire Aspect >=1 + diamond/lapis/gold/iron/redstone/emerald blocks + 2 spheres.
* rodai_wooden/stone/iron/golden <- broken Silver Bamboo with kills >= 100 + the vanilla sword + string + crystal;
  rodai_diamond/netherite same with Trapezohedron; rodai_netherite also via smithing (template + rodai_diamond +
  netherite ingot).

Mob drops (`entity_drop` registry, `event/drop/EntityDropEvent.java`; rate + 0.1 per looting level, capped 1):
Ender Dragon -> yamato_broken (100 %, dropped at fixed point 0,60,0); Wither -> sange (30 %, must be killed with a
blade); TF Naga -> agito_rust 30 %; TF Hydra -> orotiagito_rust 30 %; TF Minotaur -> yasha 5 % (blade kill); TF
Minoshroom -> yasha_true 20 % (blade kill). Zombies (not drowned/zombified piglin) spawning with an empty main hand:
`r < 0.05*specialMultiplier` -> sabigatana, else `r < 0.15*specialMultiplier` -> sabigatana_broken
(`event/handler/EntitySpawnEventHandler.java`). Koseki: Wither explosion hitting a blade stand holding a nameless blade.

---------------------------------------------------------------------------------------------------

## 6. Quick constants list (for the port)

```
LOCK: ray 40 blocks, entity AABB +0.5; fallback box +12/+6/+12 nearest; TargetingConditions range 40 (x visibility, min 2)
LOCK CAMERA: aim = feet + eyeHeight/2; step = 0.125*partialTick*min(1,|dYaw|*0.5); rotLerp yaw/pitch/headYaw
LOCK MARKER: halfway to camera, scale 0.0025, color effectColor|0xAA alpha, additive, fullbright
RANK: 300 pts/level, +30 per hit dealt (blade holder), -300 per hurt taken, +30 just guard, decay 1/tick to level floor
RANK CAP 1799; S>=1500, SS>=1575, SSS>=1650
HUD: x=W*2/3, y=H/5; visible 120 ticks after last change; text variant 20 ticks after rank-up
DMG BONUS: rank.level/2; at >=S max(that, min(xpLevel, refine))
PS GAIN: min(100, floor(xp*(1+0.1*rankLevel))); SA cost 20; summon 2; summon arts 20
REFINE ATTACK: base*(1-1/(1+f*refine)), f=0.05 (0.1 if kills>=1000)
FIERCEREDGE kills>=1000; SOULEATER ps>=10000; BEWITCHED = enchanted & (renamed | default)
REACH +2.5 (+1.25 broken)
KEYS: V = special move (SPRINT bit 4096), MMB = summon (M_DOWN bit 128)
```

## 7. Uncertainties

* `RenderLivingEvent<?,?>` (abstract parent) subscription: assumed to receive Pre and Post (marker drawn twice).
* `RenderGuiOverlayEvent.Post` without overlay filter: assumed to redraw the rank HUD once per overlay; GL blend
  state at that point is inherited, not set by the mod.
* Exact on-screen handedness (clockwise vs counter-clockwise) of the health-ring depletion was not derived; the
  mechanism (helical depth mask offset by `health*10`) is exact.
* Koseki has no `item.slashblade.koseki` key in en_us.json (only ja/zh/ru have one); in English it shows the raw key.
