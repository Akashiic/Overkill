<!-- Estudo do código do SlashBlade: Resharped (MIT, (c) 2024 M Mysterious Mountain Forging-shop Group), do Double Edged Reforged (GPL-3.0) e, como referência, do SlashBlade_2 (flammpfeil). Só leitura; os trechos citados são curtos e servem à análise. A arte desses projetos é "All Rights Reserved" e não está neste repositório. -->

# 03 — SlashBlade: Resharped — visual effects and the entities behind them

Source studied (read only): `ref/resharped`, Forge 1.20.1.
Java paths below are relative to `src/main/java/mods/flammpfeil/slashblade/`.
Asset paths are relative to `src/main/resources/assets/slashblade/`.
`file:line` references point into those trees.

Conventions used in this document:

* **Ticks.** The server calls `tick()` with `tickCount` = 1 on the first tick after spawn (vanilla increments `tickCount` before calling `tick()`). A check like `lifetime < tickCount` therefore removes the entity during tick `lifetime + 1`.
* **Rotations.** In 1.20, `PoseStack.mulPose(Axis.XP/YP/ZP.rotationDegrees(a))` post-multiplies, exactly like `GL11.glRotatef(a, 1/0/0, 0/1/0, 0/0/1)`. `Axis.YN.rotationDegrees(a)` is `glRotatef(-a, 0,1,0)`. `scale` maps to `glScalef`. Every transform sequence below can be copied 1:1 into immediate-mode GL on 1.7.10.
* **Angle notation.** "φ" means `atan2(x, z)` in degrees. `glRotatef(θ, 0,1,0)` turns φ into φ+θ.
* **Yaw.** MC yaw: the look vector is `(-sin yaw, ., cos yaw)`. When facing +Z, +X is on the player's **left** and −X is on the player's **right**.

---

## 0. The common rendering machinery (`client/renderer/util/BladeRenderState.java`, `client/renderer/model/obj/*`)

### 0.1 OBJ loader quirks (needed for exact UVs and alpha)

* `WavefrontObject.parseTextureCoordinate` **flips V**: `v_loaded = 1 - v_file` (`client/renderer/model/obj/WavefrontObject.java:236`). The Forge 1.7.10 `WavefrontObject` does the same thing.
* Faces are emitted as GL `TRIANGLES` (`RenderType.create(..., VertexFormat.Mode.TRIANGLES, ...)`). All the effect OBJs are already triangulated.
* `Face.addFaceForRender` (`client/renderer/model/obj/Face.java:53-121`):
  * **UV operator** (static global; `setUvOperator(uScale, vScale, uOff, vOff)`): `u' = u*uScale + uOff`, `v' = v*vScale + vOff`. It is applied to the loaded (flipped) V.
  * Each vertex UV is nudged 0.0005 toward the face's average UV (anti-bleed). This is negligible.
  * **Alpha override** (static global): `Face.alphaOverrideYZZ = (v, a) -> v.y() == 0 ? 0 : a` (`Face.java:20`). The test uses the **raw model-space** vertex Y, before any transform. Every vertex whose model Y is exactly 0 gets alpha 0. All other vertices get the pass alpha. Gouraud interpolation then gives a soft fade.
  * The normal is transformed but has no effect, because the emissive shader ignores lighting.
* `BladeRenderState.renderOverrided` (`BladeRenderState.java:90-123`; the file starts at line 1 at the `package` line) takes the vertex colour from the static `col` (a `java.awt.Color`). It tessellates only the named OBJ group, then resets the alpha override, the UV operator and `col`. `setCol(int rgba)` means `new Color(rgba, hasAlpha=true)`, so the high byte is the alpha. `setCol(rgb, false)` forces alpha 255.

### 0.2 Render types (blend modes) used by effects

All of them use shader `RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE`. Its vertex shader passes `vertexColor = Color` and applies no lightmap or diffuse light, so every effect is **fullbright**. The packed light value passed in is irrelevant. From memory of vanilla 1.20 shaders (not part of this repo): the fragment shader computes `tex * vertexColor * ColorModulator`, **discards when alpha < 0.1**, and applies linear fog. In 1.7.10: lighting off, lightmap coords 240/240, `glAlphaFunc(GL_GREATER, 0.1f)`.

| name (method) | blend | depth write | cull | texture filter | used by |
|---|---|---|---|---|---|
| `getSlashBladeBlendLuminous` (`renderOverridedLuminous`) `BladeRenderState.java:214-230` | `LIGHTNING_ADDITIVE`: `blendFuncSeparate(SRC_ALPHA, ONE, ONE, ZERO)`, i.e. RGB additive: dst += src.rgb*src.a | **no** (COLOR_WRITE) | **off** (NO_CULL) | blur=true (GL_LINEAR), mipmap flag | slash "white outside" and "color add" passes, Drive, summoned swords, lock-on base, blade `_luminous` parts |
| `getSlashBladeBlendColorWrite` (`renderOverridedColorWrite`) `:186-201` | same additive | no | **on** (default CULL) | blur=false (GL_NEAREST) | slash "black inside" and "color alpha base" passes, JC wind |
| `getSlashBladeBlendReverseLuminous` (`renderOverridedReverseLuminous`) `:281-296` | `blendFuncSeparate(SRC_ALPHA, ONE, ONE, ONE)` + `glBlendEquation(GL_FUNC_REVERSE_SUBTRACT)`, i.e. dst = dst − src.rgb*src.a (**darkening**) | no | **on** (default) | blur=true | JC dark spheres / shells |
| `getSlashBladeBlendLuminousDepthWrite` `:252-267` | additive | **yes** | on (default) | blur=true | lock-on health mask / health ring |
| `getChargeEffect` `:232-250` | additive, shader `ENERGY_SWIRL`, texture offset (x,y) | no | off | nearest | blade charge glow |
| `getSlashBladeBlend` `:133-148` | normal translucent (SRC_ALPHA, ONE_MINUS_SRC_ALPHA), item-entity shader with lighting | yes | on | nearest | blade body only (not effects) |

The depth test is the default `LEQUAL` everywhere. In practice the mipmap flag is a no-op: a SimpleTexture has `MAX_LEVEL 0`. Texture wrap is the default `GL_REPEAT`. The slash UV offsets never leave [0, 1].

1.7.10 equivalent of the additive state:

```java
GL11.glEnable(GL11.GL_BLEND);
GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
GL11.glDepthMask(false);
GL11.glDisable(GL11.GL_LIGHTING);
OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
GL11.glEnable(GL11.GL_ALPHA_TEST);
GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
// Luminous: GL11.glDisable(GL11.GL_CULL_FACE);   ColorWrite / Reverse: glEnable(GL_CULL_FACE)
// Reverse: GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT) ... then restore GL_FUNC_ADD
// blur=true: glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN/MAG_FILTER, GL_LINEAR)
```

Draw with `tessellator.startDrawing(GL11.GL_TRIANGLES)` and set a per-vertex `setColorRGBA` so the alpha override works.

---

## 1. EntitySlashEffect — the slash arc (`entity/EntitySlashEffect.java`, `client/renderer/entity/SlashEffectRenderer.java`)

### 1.1 Entity type

* Registered in `SlashBlade.java:228-233`: `sized(3.0F, 3.0F)`, tracking range 4 chunks, update interval 20. `setNoGravity(true)`.
* `shoot()` zeroes motion (`isWave()` is always false), and `lerpMotion` zeroes it as well. **The slash never moves.**
* Synced data (`EntitySlashEffect.java:108-118`), with defaults:
  * `COLOR` = **0x3333FF**
  * `FLAGS` (bits: Critical, NoClip, Mute, Indirect)
  * `RANK` (float concentration-rank level) = 0
  * `ROTATION_OFFSET` = 0
  * `ROTATION_ROLL` = 0
  * `BASESIZE` = 1
* Server-only fields:
  * `lifetime` = **10**. It is not synced, so the client always uses 10 in the renderer.
  * `damage` = 1.0
  * `action` = `KnockBacks.cancel`
  * `cycleHit` = false
  * `alreadyHits`

> **Porting note.** Because `lifetime` is not synced, the client renders the void-slash (lifetime 36 on the server) with **lifetime 10**. The arc therefore finishes its sweep and fades out in 10 ticks, while the server keeps the invisible entity alive for 37 ticks. Reproduce this, or send the lifetime if you prefer the "intended" look.

* No setter exists for `ROTATION_OFFSET` anywhere: it is **always 0**. `BASESIZE` is 1, except for the critical Judgement-Cut sub-slashes (0.5).

### 1.2 Server behaviour per tick (`EntitySlashEffect.tick`, `:280-361`)

**1. Sound at `tickCount == 2`** (`:284-295`):
* not mute: `TRIDENT_THROW`, volume 0.80, pitch `0.625 + 0.1*rand`
* mute: `PLAYER_ATTACK_SWEEP`, volume 0.5, pitch `0.4 / (rand*0.4 + 0.8)`
* if critical, additionally `WITHER_HURT`, volume 0.2, pitch `0.4 + 0.25*rand`
* Subclasses override `getSlashSound()`: void slash uses `BLAZE_HURT`, circle slash uses `SoundEvents.EMPTY`.

**2. Particles when `tickCount % 2 == 0 || tickCount < 5`** (ticks 1, 2, 3, 4, 6, 8, 10; `:297-333`). The code runs on both sides: `addParticle` only shows on the client, and the block particles come from the server.

```java
progress = tickCount / (float) lifetime;
normal = (1,0,0); dir = (0,0,1);
// applied in this order to each vector:
Ry(60 + rotationOffset - 200*progress) -> Rz(roll) -> Rx(xRot) -> Ry(-yRot)
```

* **Block particles.** If the shooter is not in water or rain, a block ray runs from `pos + normal*1.5` to `pos + normal*3`. If it hits a block, `FallHandler.spawnLandingParticle(this, hit, normal, 3)` (`event/handler/FallHandler.java:84-103`) sends `(int)(150*min(0.2+3/15, 2.5))` = **60** `BLOCK` particles of the block at `hit + normal*0.5`, with spread 0 and speed 0.15.
* **Crit particles.** Only if the rank is **greater than S** (SS or SSS):
  * one `CRIT` at `p = pos + normal*baseSize*2.5` with velocity `dir + normal`
  * a second `CRIT` at `p + dir*(rand + 0.5)` with the same velocity

**3. Hit detection, every even tick** (2, 4, 6, 8, 10) while the owner is non-null (`:335-357`). `forceHit = true`.

* **Direct** (the normal case: not Indirect and the owner is a LivingEntity). The call is
  `AttackManager.areaAttack(shooter, action.action, ratio = damage*(crit ? 1.1 : 1.0), forceHit, resetHit=false, mute=true, exclude=alreadyHits)` (`util/AttackManager.java:228-254`).
  * **The hit volume is computed from the player's current position and look, not from the arc.** `TargetSelector.getTargettableEntitiesWithinAABB(world, player)` (`util/TargetSelector.java:126-174`):
    * `reach = ENTITY_REACH − 1`. The blade adds +2.5 reach (`item/ReachModifier.java:9`, `item/ItemSlashBlade.java:142`). With the Forge default of 3.0 the reach is **4.5** (1.25 extra when the blade is broken).
    * The box is the player's AABB moved by `look * reach * 0.5`, then inflated by `reach + 1` (`getResolvedAxisAligned`, `:222-234`).
    * Candidates are LivingEntities inside the box that pass `TargetingConditions.range(reach)`, i.e. a distance check from the attacker of at most 4.5 blocks. The filter also requires `AttackablePredicate` (`:61-97`): PvP config, armor stands only if marker, `Enemy` unless friendly-fire is enabled, the attackable-blacklist tag, and revenge targets.
    * The list also includes projectiles not owned by the player inside the box and within reach. They get reflected by `ArrowReflector` in `doMeleeAttack`. Primed TNT gets extinguished. Multipart parts are included.
  * Each target first gets `beforeHit` = the knockback setter, then `doMeleeAttack`, which calls `AttackHelper.attack(attacker, target, comboRatio)` (`util/AttackHelper.java`). This is a full melee hit:
    * damage = `(ATTACK_DAMAGE + sweepingBonus + rankBonus + killerEnchantBonus) * comboRatio * slashbladeDamageAttr * config(1.0)`, ×1.5 on a vanilla jump crit
    * vanilla knockback, then `KnockBackHandler` rewrites it
    * sound `PLAYER_ATTACK_CRIT` (1.0, 1.0)
    * `DAMAGE_INDICATOR` particles: `(int)(dealt*0.5)` of them when more than 2 damage was dealt
    * `invulnerableTime = 0` before the hit (forceHit)
  * Hit entities are added to `alreadyHits`, so **each target is hit at most once per slash** (`cycleHit` is never true).
* **Indirect** (only the JC sub-slashes): `areaAttack(this, action, reach=4.0, ...)` (`AttackManager.java:267-303`). The box is the slash entity's own 3×3×3 box inflated by 4 (the bottom-centre position is the entity position). There is no distance check (`range(0)`).
  * damage = `(slash.damage) * ATTACK_DAMAGE + rankBonus`, × 1.0 × damage attribute × config
  * damage source `indirectMagic(slash, owner)`

**4. Despawn.** On the server, when `lifetime < tickCount` the entity is removed (`:367-373`). With lifetime 10 that happens during tick 11.

**Knockback types** (`util/KnockBacks.java`, `event/handler/KnockBackHandler.java:18-80`). Each sets a factor that the next `LivingKnockBackEvent` consumes:
* `cancel` → horizontal knockback cancelled
* `toss` → vertical motion `max(vy, 0.75)`, fall distance −0.75
* `meteor` → `vy = min(vy, -5.5)`, fall distance +5.5
* `smash` → horizontal strength ×1.5
* Knockback resistance can resist the motion part.

### 1.3 Model: `model/util/slash.obj`, group **`base`**, texture `model/util/slash.png`

Geometry (worked out by parsing the OBJ):

* 200 vertices, 288 triangles: a **270° flat ring arc** with a lens-shaped cross-section, two-sided. The top faces have up-facing normals and CCW winding seen from above; the bottom faces are the mirror image.
* Four radial rings, 25 angular stations each, 11.25° apart:

| ring | radius | model Y (half-thickness) | v_file | v_loaded (1−v) | vertex alpha |
|---|---|---|---|---|---|
| inner edge | 54.42 | 0 | 0.1875 | 0.8125 | **0** (Y==0) |
| mid-inner | 69.53 | ±3.317 (tapers to ±0.099 at the tail end and ±0.001 at the head end) | 0.15625 | 0.84375 | pass alpha |
| mid-outer | 84.89 | ±4.750 (tapers to ±0.142 at the tail end and ±0.001 at the head end) | 0.09375 | 0.90625 | pass alpha |
| outer edge | 100.0 | 0 | 0.0 | 1.0 | **0** |

* **Angular extent.** φ runs from **90° (the +X axis, u = 0)** increasing through 180° (−Z) and 270° (−X) to **360° = 0° (the +Z axis, u = 1)**. The quadrant between +Z and +X is empty.
* The thickness tapers near both ends. Near the head (φ = 90°) the Y values are ±0.0005 to ±0.0008, which is **not** exactly 0, so the alpha stays on there.
* The **U mapping is non-linear**: dense texture at the head (u=0) end, stretched at the tail. Sample u values at 11.25° steps from φ = 90°:

  0, .0735, .1439, .2111, .2752, .3362, .3941, .4489, .5005 (φ = 180°), .5490, .5944, .6366, .6758, .7118, .7447, .7745, .8011 (φ = 270°), .8262, .8513, .8764, .9014, .9265, .9516, .9767, 1.0 (φ = 360°)

* **Texture `slash.png`**: 512×512 RGBA. RGB is pure white wherever alpha > 0, so the texture acts as an alpha mask. It shows a horizontal wispy streak cloud.
  * Alpha is non-zero for image rows v ≈ 0.13–0.81. The strongest rows are v ≈ 0.19–0.40: row-average alpha 44–108 out of 255, max 219.
  * Alpha is non-zero for columns u ≈ 0.12–0.94. Brightest at u ≈ 0.44–0.56, fading to almost nothing below u ≈ 0.15 and above u ≈ 0.85.
  * The visible streak therefore spans φ ≈ 113°…297° of the arc. Its brightest part is around φ ≈ 170–200°.

### 1.4 Renderer: exact transform and passes (`SlashEffectRenderer.java:41-139`)

```java
// origin = interpolated entity position (dispatcher)
rotY(-lerp(pt, yRotO, yRot) - 90)
rotZ( lerp(pt, xRotO, xRot))           // xRot is always 0 for slashes
rotX( rotationRoll )
L = 10 (client lifetime)
progress  = min(L, tickCount + pt) / L                 // 0..1
t         = progress  (clamped)
baseAlpha = 1 - t^4      // code: a = clamp(L - tickCount - pt, 0, L)/L; a = -(a-1)^4 + 1
rotY( rotationOffset(=0) - 135 * progress )            // the sweep
scale(1, 0.25, 1)
scale(1.2, 1.2, 1.2)
yscale = 0.03
alphaByte = (int)(255*baseAlpha) & 0xFF
color = entity.color & 0xFFFFFF
if (rankCode.level < C.level) color = 0x555555         // rank NONE or D -> grey
```

Then up to four passes, each wrapped in push/pop. Each pass does `scale(s, 0.03, s)`, sets `Face.setAlphaOverride(alphaOverrideYZZ)` and `Face.setUvOperator(1, 1, 0, vOff)`, sets the colour, and draws group `base`.

| # | condition (rank) | s (× baseSize) | vOff | colour (ARGB) | render type |
|---|---|---|---|---|---|
| 1 "black alpha inside" | S, SS, SSS (level ≥ 5) | `lerp(p, 0.035, 0.030)` (shrinks) | `-0.8 + 0.3p` | `0x222222`, alpha alphaByte | **ColorWrite** (additive, culled, nearest). Despite the name this is *additive* dark grey |
| 2 "color alpha base" | D and up (≥ 1) | `lerp(p, 0.030, 0.035)` | `-0.35 - 0.15p` | color, alpha alphaByte | **ColorWrite** |
| 3 "white add outside" | B and up (≥ 3) | `lerp(p, 0.030, 0.0375)` (grows faster) | `-0.5 - 0.2p` | `0x404040`, alpha alphaByte | **Luminous** (additive, no cull, linear) |
| 4 "color add base" | always | `lerp(p, 0.030, 0.035)` | `-0.35 - 0.15p` | color, alpha alphaByte | **Luminous** |

(`Mth.lerp(p, a, b) = a + p*(b - a)`.)

Effective V band sampled after the flip and offset. Rows go top-to-bottom of the PNG:
* pass 2/4: outer edge `0.65 − 0.15p`, inner edge `0.4625 − 0.15p`. The band slides from rows 0.46–0.65 to rows 0.31–0.50.
* pass 1: outer `0.2 + 0.3p`, inner `0.0125 + 0.3p`
* pass 3: outer `0.5 − 0.2p`, inner `0.3125 − 0.2p`

**World size** (baseSize 1):
* core radius = 100 × 1.2 × (0.03 → 0.035) = **3.6 → 4.2 blocks**, inner edge 1.96 → 2.29 blocks
* white outer pass grows 3.6 → 4.5 blocks; black inner pass shrinks 4.2 → 3.6
* maximum half-thickness 4.75 × 0.25 × 1.2 × 0.03 ≈ **0.043 blocks**, so the arc is essentially a flat disc sector

**Fragment contribution** (additive): `dst += colour.rgb * texAlpha * vertexAlpha(radial) * baseAlpha`, after the alpha < 0.1 discard on the product `texA * vertA`.

### 1.5 How roll and rotation offset orient the arc

The local frame after `rotY(-yaw-90)` is **X = forward, Y = up, Z = right** (the player's right). A model point at model angle φ_m and radius R ends up at

```
ψ      = φ_m + rotationOffset(0) − 135·progress
right' = right·cos(roll) − up·sin(roll)        // rotX(roll) turns the "right" axis
P      = R·( sin ψ · forward + cos ψ · right' )
```

* The head (u=0, φ_m = 90°) starts exactly at **forward** and rotates by −135° over the life.
* The bright centre (φ_m ≈ 180°) moves from `−right'` (left, for roll 0) to `(forward + right')/√2`.
* **Roll 0** gives a horizontal slash sweeping **left → right** in front of the player.
* **Roll 180** gives the mirror: right → left.
* **Roll −80/−90** gives a rising (upward) slash. Positive roll (e.g. 45) tilts it so the slash goes from upper-left down to lower-right.
* The base orientation is always horizontal yaw-only: `setXRot(0)` and `yRot = player.getYRot()`. Pitch never tilts a slash.

### 1.6 Server `doSlash` spawn parameters (`util/AttackManager.java:55-132`)

`doSlash(player, roll, [colorCode], centerOffset, mute, critical, comboRatio, knockback)` does nothing on the client and nothing without a blade state.

```java
pos = player.position() + (0, eyeHeight*0.75, 0) + look*0.3
pos += up * centerOffset.y                      // getVectorForRotation(-90, yaw) == (0,1,0)
     + right * centerOffset.z                   // getVectorForRotation(0, yaw+90) == the player's RIGHT
     + look * centerOffset.z                    // (z is applied twice; centerOffset.x is IGNORED)
slash.setPos(pos)
slash.owner = player
slash.roll = roll
slash.yRot = player.getYRot()
slash.xRot = 0
slash.color = colorCode       // blade state getColorCode() (0xFFRRGGBB), or 0xFFFFFF if no state
slash.mute = mute
slash.critical = critical
slash.damage = comboRatio
slash.knockback = knockback
slash.rank = rankCap.getRankLevel(gameTime)   // float, 0 if the capability is missing
// lifetime stays 10, baseSize stays 1, rotationOffset stays 0
```

`AttackManager.genRushOffset(e)` returns `((r−0.5)*2, (r−0.5)*2, 0)`. Only the y component matters, so the slash is shifted **up or down by a uniform value in [−1, 1]**.

### 1.7 Variants built on EntitySlashEffect

| variant | where | differences |
|---|---|---|
| **Void Slash** | `AttackManager.doVoidSlashAttack` / `newVoidSlashEffect` (`:134-221`) | same position formula with offset 0; **roll 180**; **yRot = playerYaw − 22.5**; colour from the blade; not mute, not critical; `damage = 0`; `getSlashSound() = BLAZE_HURT`; **lifetime 36** (server only). Every even tick it calls `areaAttack(player, cancel, ratio 0)`: no damage, but targets are tagged into `alreadyHits`. When `36 < tickCount` (tick 37): `GLASS_BREAK` (1.0, `0.625 + 0.1r`), 16 `ENCHANTED_HIT` particles (spread 0.7, speed 0.02). Each alive tagged entity gets `addDeltaMovement(−sin(ownerYaw)*0.5, 0.05, cos(ownerYaw)*0.5)` and `indirectMagic` damage `((ATK*(1+0.1*Power) + rankBonus) * damageAttr * config) * 5.1` with forceHit/resetHit. Then the entity is removed. |
| **Circle Slash** | `slasharts/CircleSlash.java:19-72` | same position; roll 0; **yRot = playerYaw − 22.5 + yRot(param)**; critical **true**; damage **0.325**; knockback cancel; `getSlashSound() = EMPTY` (only the critical `WITHER_HURT` is heard). Spawned 4 times (§4.3). |
| **Sakura End** | `slasharts/SakuraEnd.java:31-80` | identical to `AttackManager.doSlash` |
| **JC critical sub-slash** | `EntityJudgementCut.tick:224-247` | at the JC position; **yaw = 120·tickCount + serverSeed**, pitch 0; **roll 30**; **baseSize 0.5** (radius 1.8 → 2.1 blocks); damage 0.1; **Indirect**; not mute; critical; knockback cancel; colour and rank from the JC. One per tick on ticks 1, 2, 3. |

---

## 2. EntityJudgementCut — "dimension slash" sphere (`entity/EntityJudgementCut.java`, `client/renderer/entity/JudgementCutRenderer.java`, `slasharts/JudgementCut.java`)

### 2.1 Spawning (`slasharts/JudgementCut.java:32-81`)

**Position**, in order of preference:
1. the blade's **lock-on target**: its eye position
2. otherwise `RayTraceHelper.rayTrace(eye, look, airReach 5, entityReach 7)`:
   * entity hit → `target.position + (0, eyeHeight/2, 0)`
   * block hit → the hit location
3. otherwise `eye + look*5`

**Fields:**
* owner = user
* colour = blade `getColorCode()`
* rank = rank level
* yaw and pitch are never set (stay 0)
* **lifetime = 10** (the default; never changed)
* damage 1.0

Spawn sound: `ENDERMAN_TELEPORT` at the JC, volume 0.5, pitch `0.8 / (rand*0.4 + 0.8)`.

**Variants:**
* `doJudgementCutJust` = the same, then `setIsCritical(true)`.
* `doJudgementCutSuper(owner)` (`:87-112`): every targetable living entity in `ownerAABB.inflate(48)` within reach `resolvedReach + 32` (= 36.5) gets **Slowness 40 ticks amp 10** and its own JC at its feet (`entity.getX/Y/Z`). After the loop: `ENDERMAN_TELEPORT` (1.0, 1.0) at the owner.

Entity box: `sized(2.5F, 2.5F)` (`SlashBlade.java:203-208`).

### 2.2 Server tick (`EntityJudgementCut.tick:208-252`)

* **Sound:** `tickCount < 8` and even (ticks 2, 4, 6): `WITHER_HURT`, volume 0.2, pitch `0.5 + 0.25*rand`.
* **Hits:** every even tick (2, 4, 6, 8, 10) while the owner is non-null:
  * `areaAttack(this, crit ? toss : cancel, reach 4.0, forceHit, resetHit=false, comboRatio 0.16, exclude=null)`
  * **Multi-hit**: there is no exclude list, so 5 hits.
  * Volume: the JC's 2.5³ box inflated by 4 in every direction, no distance check.
  * Damage per hit: `(1.0 + 0.1*PowerLevelOfBlade) * ATTACK_DAMAGE + rankBonus`, then × 0.16 × damageAttr × config. Source `indirectMagic`.
* **If critical, on ticks 1, 2, 3:** spawns the slash sub-effects from §1.7. They are drawn as normal slash arcs around the JC and rotate 120° apart.
* **End:** when `lifetime < tickCount` (tick 11), `burst()`:
  * 16 `CRIT` particles (spread 0.5, speed 0.25)
  * potion "burst": the default effect list is `POISON` duration 1 amp 1, applied to living entities within distance 3 (`factor = 1 − d/4`). Practically no effect.
  * removed

### 2.3 Model `model/util/slashdim.obj`, texture `model/util/slashdim.png` (32×32 RGBA)

* **Group `base`**: a UV-sphere of radius 100 centred on the origin. 432 triangles, outward CCW winding. All UVs fall inside the texture's top-right 8×8 block (u 0.768–0.978, v_loaded 0.023–0.209), which is pure opaque white. The sphere is therefore drawn with a flat vertex colour.
* **Group `wind`**: a crescent ("blade of wind") in the **X–Y plane**, bulging toward +Y.
  * Inner edge apex (0, 80), outer edge apex (0, 130). Horns at x = ±97, y ≈ 32–40. Thickness z = ±7.4 at the centre.
  * This is the same shape as `drive.obj` with axes swapped.
  * UV: u from 0.07 (horns) to 0.675 (centre); v_loaded from 0.696 (inner edge) to 0.396 (outer edge). In the texture this region is white RGB with alpha rising from about 10 at the horns to about 190 at the centre, so the horns fade out.

### 2.4 Renderer (`JudgementCutRenderer.java:41-133`)

```java
rotY( lerp(yRotO, yRot) - 90 )      // NOTE: +yRot here (yaw is always 0 for JC, so this is rotY(-90))
rotZ( lerp(xRotO, xRot) )           // 0
L = lifetime (10, client default)
baseAlpha = 1 - t^4,  t = clamp((tickCount + pt)/L, 0, 1)
seed = entity.getSeed()             // random.nextInt(360) in the CONSTRUCTOR: client and server seeds differ
rotY(seed)
scale(0.01)                         // sphere radius = 1 block
col = entity.color (RGB)
hsb = RGBtoHSB(col)
baseColor = HSBtoRGB(0.5 + hsb.h, hsb.s, 0.2) & 0xFFFFFF   // complementary hue, brightness 0.2
```

For the default 0x3333FF, `baseColor` = (51, 51, 10).

**Pass A: 5 nested "darkening" spheres.** The scale is cumulative, inside one push. Radii 0.95, 0.9025, 0.857, 0.815, 0.774 blocks.

```java
for l in 0..4:
  scale(0.95, 0.95, 0.95)
  setCol(baseColor | ((0xFF & (int)(0x66*baseAlpha)) << 24))
  draw "base" with ReverseLuminous        // dst -= baseColor * a ; back-face culled
```

**Pass B: 3 expanding pulse shells**, each in its own push/pop:

```java
cycle = 15
for l in 0..2:
  wave = (tickCount + (15/3)*l + pt) % 15       // 0..15, staggered by 5 ticks
  scale(1 + 0.03*wave)                           // radius 1.0 -> 1.45 blocks
  setCol(baseColor | ((int)(0x88 * ((15 - wave)/15) * baseAlpha) << 24))
  draw "base" with ReverseLuminous
```

**Pass C: 5 spinning wind crescents**, each in its own push/pop:

```java
for l in 0..4:
  rotX(72*l)
  rotY(30)
  offsetTicks = tickCount + pt + seed + 7*l
  progress    = (offsetTicks % 28) / 28                  // motionLen = 7*(5-1)
  scale(0.4 + progress)                                  // crescent radius ~0.32..1.82 blocks
  rotZ(18 * offsetTicks)                                 // 360/20 deg per tick, spins in its own plane
  a = 0xFF & (int)( Math.min(0, 255 * sin(2π*progress) * baseAlpha) )
  setCol(new Color(col.r, col.g, col.b, a))              // the ORIGINAL colour, not the complement
  draw "wind" with ColorWrite (additive, culled), light MAX_LIGHT
```

> **The wind alpha bug must be reproduced.** `Math.min(0, …)` is always ≤ 0.
> * While `sin > 0` (progress < 0.5) the alpha is **0**, i.e. invisible.
> * While `sin < 0`, `v = (int)(255·sin·baseAlpha)` lies in [−255, −1], and `alpha = v & 0xFF = 256 + v`.
>   * Near progress 0.5⁺ and 1.0⁻ the alpha is ≈ 255 (bright).
>   * Around progress 0.75 it is ≈ 1 (almost invisible) when baseAlpha = 1.
>   * When `|255·sin·baseAlpha| < 1` the int truncates to 0 and the alpha is 0.
>
> Winds l = 0 and l = 4 have the same phase (offset 28 ≡ 0 mod 28) but different planes.

Overall look: a dark, colour-tinted bubble of about 1 block radius, with pulsing dark shells and flickering coloured crescents spinning through it. The whole thing lives 10 ticks.

---

## 3. EntityDrive — the flying crescent (`entity/EntityDrive.java`, `client/renderer/entity/DriveRenderer.java`, `slasharts/Drive.java`, `slasharts/WaveEdge.java`)

### 3.1 Spawning

`Drive.doSlash(player, roll, [yRot=0], lifetime, colorCode, centerOffset, critical, damage, knockback, speed)` (`slasharts/Drive.java:33-74`):

```java
pos = player.position + (0, eyeHeight*0.75, 0) + look*0.3 (+ centerOffset as in doSlash)
drive.setPos(pos)
drive.damage = damage
drive.speed  = speed
drive.shoot(look.yRot(yRot), speed, inaccuracy 0)
   // velocity = look * speed
   // yRot = atan2(vx, vz) deg ; xRot = atan2(vy, horiz) deg   (arrow convention)
owner = player
rotationRoll = roll
color = blade color, or 0xFF3333FF if no state
critical
knockback
lifetime (synced float)
rank
```

`WaveEdge.doSlash` (`slasharts/WaveEdge.java:30-66`) spawns **count+1** drives. For count = 4 that is **5** drives, from the same position and along the same look direction. Each gets `speed = minSpeed + rand*(maxSpeed − minSpeed)`, so a fan of crescents at different speeds along one line.

Entity: `sized(3,3)`. Synced: `COLOR` (default 0x3333FF), `RANK`, `LIFETIME` (float, default 10), `ROTATION_OFFSET`, `ROTATION_ROLL`, `BASESIZE`, `SPEED` (default 0.5). Default damage field 7.0 (always overwritten).

### 3.2 Server behaviour

The tick is the summoned-sword flight code (`EntityAbstractSummonedSword.tick:285-466`, see §5.2). The drive never sticks:

* **Motion.** The position advances by its velocity every tick; velocity ×0.99 per tick; no gravity. Its rotation is smoothed toward the velocity direction (`lerp 0.2`).
* **Hit test.** A segment from the position to position + motion, against entity boxes inflated by 0.3 (`ProjectileUtil.getEntityHitResult`). Candidates come from `bbox.expandTowards(motion).inflate(1)`.
  * Filter: not spectator, alive, pickable, not the shooter, not already hit.
  * Targets that fail `TargetSelector.test` are put into alreadyHits.
  * `pierce` is 0, so at most **one entity is hit per tick**. Because `EntityDrive.onHitEntity` never records hits, **the same target can be hit on consecutive ticks**.
* **Damage** (`EntityDrive.onHitEntity:276-351`):
  * `damage * ATTACK_DAMAGE + rankBonus` (rank.level/2, or `min(xpLevel, refine)*1.0` at S and above), then × damageAttr × config
  * critical: `+rand(ceil(d)/2 + 2)`
  * `invulnerableTime = 0` first; source `indirectMagic`
  * on success: stun, enchant post-effects, potion effects, `TRIDENT_HIT` sound (1.0, `1.2/(r*0.2+0.9)`)
* **Block hit** → removed (`onHitBlock:354`) unless noClip. If its position is inside a block collision box it is also despawned via `tryDespawn`.
* **Lifetime** → removed when `lifetime < tickCount`. Also, generically, after 100 ticks in the air.
* If critical: 4 `CRIT` particles per tick along the motion (the base code). Never used by the built-in arts.

### 3.3 Model `model/util/drive.obj` group **`base`**, texture `model/util/ss.png` (32×32, solid white)

* Crescent in the **X–Z plane**, bulging toward **+Z**:
  * inner apex z = 80, outer apex z = 130 (on x = 0)
  * horns at x = ±97 (z ≈ 32–40), thickness y = ±7.4 at the centre, tapering to the horns
  * 52 vertices, 96 triangles, roughly outward winding
* The UVs are the same as the slashdim `wind`, but the texture is white, so the crescent is uniformly coloured.

### 3.4 Renderer (`DriveRenderer.java:37-63`)

```java
L = lifetime (synced float)
r = clamp((L - tickCount)/L, 0, 1)        // no partialTicks: steps once per tick
baseAlpha = max(0, 0.75 - (r - 1)^4)      // = 0.75 - t^4 ; max 0.75
rotY( rotLerp(pt, yRotO, yRot) - 90 )     // arrow convention (+yRot)
rotZ( rotLerp(pt, xRotO, xRot) )
rotX( rotationRoll )
scale(0.015)
rotY(90)                                  // crescent bulge (+Z) -> +X = flight direction
setCol((color & 0xFFFFFF) | ((0xFF & (int)(255*baseAlpha)) << 24))
draw "base", ss.png, Luminous (additive, no cull, fullbright)
```

* World size: the crescent spans ±1.46 blocks across, 1.2 (inner) to 1.95 (outer) blocks ahead of the origin, ±0.11 blocks thick. The convex side faces the flight direction.
* Roll 0: the crescent is horizontal (lies in the flight-direction / right plane). Roll ±90: vertical.

---

## 4. Slash Arts (`registry/SlashArtsRegistry.java`, `slasharts/SlashArts.java`)

### 4.1 Charge mechanics

* Hold use (right-click) with an `ENCHANTED` sword type that is not broken or sealed (`ItemSlashBlade.releaseUsing:381-411`, `ISlashBladeState.doChargeAction:340-411`).
* `ChargeTicks` = **9**. `ChargeJustTicks` = 3. `ChargeJustTicksMax` = 5. The "just" span is `min(5, 3 + SoulSpeedLevel)`.
* **Visual cue:** at exactly `ticksUsingItem == 9` the server sends 7 `PORTAL` particles at `eye + look`, spread 0.7, speed 0.02 (`ItemSlashBlade.onUseTick:429-437`).
* `isCharged` is true when `ticksUsingItem > 9`. While charged, the blade model group `effect` gets the charge glow. Details:
  * `LayerMainBlade.java:352-357` calls `BladeRenderState.renderChargeEffect` with `textures/entity/creeper/creeper_armor.png`.
  * Shader `ENERGY_SWIRL` with texture offset `(f*0.1 % 1, f*0.01 % 1)`, where `f = tickCount + pt`.
  * Additive, no cull, colour white.
  * 1.7.10: creeper-power-armor style, i.e. a texture-matrix translation plus an additive pass.
* **On release:**
  * elapsed ≤ 2: nothing
  * elapsed < 9: **Fail**, except that if the current combo state's `releaseAction` is `releaseActionQuickCharge` and the state was entered ≤ 3 + SoulSpeed ticks ago, the result is **Jackpot** ("quick charge")
  * 9 ≤ elapsed < 9 + span: **Jackpot** (just)
  * elapsed ≥ 9 + span: **Success**
* Success → `comboState`. Jackpot → `comboStateJust` (which defaults to `comboState`).
* The art only starts if its combo state's priority is lower than the current state's.
* Cost: 20 proud souls (`costSoul`), otherwise 1 durability.
* **Super** (`ability/SuperSlashArts.java`): hold Sprint.
  * At +5 ticks, if still held: 32 `REVERSE_PORTAL` particles around the player, velocity (dx, dy+0.2, dz).
  * At +20 ticks, if still held: `releaseSSA`. Requires BEWITCHED + FIERCEREDGE, an undamaged blade, and being on the ground. Costs half the max durability. Runs `doArts(Super)`, which is **`judgement_cut_end` for every art**.
* A `ComboState`'s **`clickAction` runs every time the state is entered**, including automatic timeout transitions (`ISlashBladeState.updateComboSeq:412-422`).
* `TimeLineTickAction` keys are **ticks since entering the state**, measured by game time (`ComboState.java:200-252`, `ISlashBladeState.getElapsedTime:152-160`).
* State duration = `msFromFrames(end − start) / speed`, with 30 frames per second.

### 4.2 Registered arts

| id | default blades | Success → states | Jackpot (just) | what it spawns |
|---|---|---|---|---|
| `slashblade:none` | — | `none` | — | nothing |
| `judgement_cut` (default for any blade without `slash_art`) | yamato, fox_white, etc. | ground: `judgement_cut` (frames 1900–1923, ≈15 ticks): tick 0 `TRIDENT_THROW` (0.8, `0.625+0.1r`); ticks 0–3 player steps back 0.3. Then `judgement_cut_slash` (1923–1928, speed 0.4 ≈ 8 ticks): **tick 0 `doJudgementCut`**. Then `judgement_cut_sheath` (1928–1963): tick 0 `CHAIN_HIT` sheath sound. Air: `judgement_cut_slash_air` (speed 0.5), tick 0 JC | `judgement_cut_slash_just`: **tick 1 `doJudgementCutJust`** (critical JC with 3 sub-slashes), then `_just2` (speed 0.75), then `_just_sheath` | EntityJudgementCut (§2) |
| `sakura_end` | yasha | ground: `sakura_end_left` (frames 1816–1859, speed 6 ≈ 4.8 ticks): on entry `SakuraEnd.doSlash(roll 22.5, mute false, crit false, dmg 0.5)`. Then `sakura_end_right` (204–218, speed 1.1): on entry `doSlash(roll 157.5, crit **true**, dmg 0.76)`, stun 36. Then finish (218–281), finish2 (281–314, sheath sound). Air: `sakura_end_left_air` (1300–1328, speed 3.2) and `_right_air` (1200–1210) with the same two slashes | same as success | 2 EntitySlashEffect |
| `void_slash` | sange | `void_slash` (2200–2277 ≈ 51 ticks; player velocity zeroed every tick): **tick 16 `doVoidSlashAttack`** (§1.7); stun 40. Then `void_slash_sheath` | same | 1 void slash |
| `circle_slash` | doutanuki, yasha_true | `circle_slash` (725–743): ticks **4, 5, 6, 7** `CircleSlash.doCircleSlashAttack` with yRot **180, 90, 0, −90**. That is four slashes yawed `playerYaw − 22.5 + {180, 90, 0, −90}`, roll 0, critical, dmg 0.325: a full 360° ring built over 4 ticks | same | 4 EntitySlashEffect |
| `drive_horizontal` | yuzukitukumo | `drive_horizontal` (400–459): tick 2 `doSlash(roll −30, dmg 0.21)`; tick 3 `Drive.doSlash(roll 0, lifetime 10, crit false, dmg 1.5, speed 2.0)` | same | 1 slash + 1 horizontal drive |
| `drive_vertical` | koseki, tagayasan, muramasa | `drive_vertical` (1600–1659): tick 2 `doSlash(roll −80, 0.21)`; tick 3 `Drive.doSlash(roll −90, lifetime 10, dmg 1.5, speed 2.0)` | same | 1 slash + 1 vertical drive |
| `wave_edge` | agito, orotiagito (+ sealed) | `wave_edge_vertical` (1600–1659): tick 2 `doSlash(roll −80, 0.21)`; tick 3 `WaveEdge.doSlash(roll 90, lifetime 20, crit false, dmg 0.4, minSpeed 0.2, maxSpeed 1.0, count 4)` | same | 1 slash + **5** vertical drives at random speeds 0.2–1.0 |
| `piercing` | fox_black | `piercing` (frames 1–33, motion `testLocation`), then `piercing_2` (33–55): ticks 0–2 dash (`moveRelative` 0.8, 0.35 in water) + `areaAttack(toss, ratio 1.1)`; tick 1 `TRIDENT_THROW` (1.0, 1.0) | `piercing_just` (34–55, same dash) | **no effect entity** |
| Super (any art) | — | `judgement_cut_end` (1923–1928): tick 0 `doJudgementCutSuper` (a JC on every target within ≈36 blocks + Slowness), then `judgement_cut_sheath` | — | N × EntityJudgementCut |

### 4.3 Normal combo slash arcs: every `doSlash` call (`registry/ComboStateRegistry.java`)

Argument order for the short overloads: `doSlash(e, roll, mute, critical, ratio)` and `doSlash(e, roll, offset, mute, critical, ratio[, knockback])`. The knockback is `cancel` unless stated.

"click" means on entry (tick 0). "R" means a fresh `rand.nextFloat()`. "rush" means `genRushOffset`, a random vertical shift in [−1, 1].

Entry points (`registry/combo/ComboCommands.java:31-48`):
* ground L/R click → `combo_a1`
* sneak + forward + R → `rapid_slash`
* sneak + back + R → `upperslash`
* in the air: `aerial_rave_a1`; sneak + back + R → `aerial_cleave`

| state (line) | frames | slash events |
|---|---|---|
| combo_a1 (48) | 1–10 | click: roll **−10**, mute, ratio 0.44 |
| combo_a2 (66) | 100–115 | click: roll **170**, mute, 0.44 |
| combo_c (82) | 400–459 | t2: **−30**, 0.88; t3: **−35**, mute, 0.88 |
| combo_a3 (100) | 200–218 | t2: **−61**, 0.44; t6: **138**, 0.44 |
| combo_a4 (131) | 500–576 | t8: **45**, 0.44; t9: **50**, mute, 0.44 |
| combo_a4_ex (156; when "powered": Strength or Hunger effect) | 800–839 | t7: **70**, ratio 1.0; t14: **255**, 1.0 |
| combo_a5ex (178) | 900–1013 | t15: **35**, crit, 1.0; t17: **40**, mute, crit; t19: **30**, mute, crit |
| combo_b1 (204) | 700–720 | t6: **−30** (0.244) **and** **145** (mute) in the same tick; t7…t14: 8 rush slashes alternating, even keys `−90+180R` (not mute), odd keys `90+180R` (mute), ratio 0.244, rush offset |
| combo_b1_end (248) | 720–743 | t9: roll **0**, offset y **+0.8** (x = R−0.5 is ignored), crit, ratio 1.0; t10: roll **5**, offset y +0.8, mute, 1.0 |
| combo_b2 … combo_b6 (276, 298, 320, 341, 363) | 710–720 each | t0…t6: 7 rush slashes alternating `−90+180R` / `90+180R` (odd ticks mute), 0.244 (b3 t2 uses 0.24) |
| combo_b7 (385) | 710–764 | t0…t7: 8 rush slashes as above; t12: roll 0, y +0.8, crit, 0.244; t13: roll 5, y +0.8, mute, 0.244 |
| combo_b_end (427) | 720–743 | t9: roll 0, y +0.8, crit, 0.244; t10: roll 5, y +0.8, mute, 0.244 |
| aerial_rave_a1 (456) | 1100–1122 | t2: **−20**, 0.28 |
| aerial_rave_a2 (474) | 1200–1210 | t2: **150**, 0.28 |
| aerial_rave_a3 (496) | 1300–1328 | t2: **0**, 0.28, smash; t3: **−3**, mute, crit, smash |
| aerial_rave_b3 (519) | 1400–1437 | t3 and t6: **237**, 0.34, toss (click sets vy = 0.6) |
| aerial_rave_b4 (552) | 1500–1537 | t6: **45**, 0.34, meteor; t7: **50**, mute, crit, meteor |
| upperslash (583) | 1600–1659 | t4: **−80**, 0.50, toss (stun 15) |
| aerial_cleave / _loop (647 / 696) | 1800–1817 | the player falls (vy −3/tick); every 2 ticks areaAttack(meteor, 0.44) without an effect entity. **On landing**: `doSlash(55, mute, crit, 0.44, meteor)` + 230 `BLOCK` landing particles, then enter `aerial_cleave_landing` |
| aerial_cleave_landing (720) | 1816–1859 | click: **60**, 0.44, meteor. Two slashes on landing in total |
| rapid_slash (736) | 2000–2019 | elapsed 2, 3, 4, 5: roll `−45+90R`, **+180 on even elapsed** (2, 4), rush offset, crit = isPowered, 0.44; elapsed 7: **−30**, rush, crit, 0.44 |
| rising_star (837) | 2100–2137 | click: **−57**, 0.6, toss; t6: **−57**, 0.6, toss |
| drive_* / wave_edge (1150 / 1168 / 1193) | | see §4.2 |

The frame→tick timeline keys use `(int) getTicksFromFrames(f)` = `(int)(f*2/3)`: 3→2, 4→2, 5→3, 7→4, 9→6, 10→6.

---

## 5. Summoned swords (`entity/EntityAbstractSummonedSword.java` and subclasses, `client/renderer/entity/SummonedSwordRenderer.java`, `ability/SummonedSwordArts.java`)

### 5.1 Common renderer (all five types use `SummonedSwordRenderer`, `SlashBlade.java:255-259`)

* Model: `getModelLoc()` = synced string `MODEL` + ".obj"; default `slashblade:model/util/ss.obj`. Group **`ss`**. Texture: the same name + ".png", default `ss.png` (solid white 32×32).
* `ss.obj` group `ss`: a sword along **+Z**.
  * blade tip at z = **+300**
  * blade base and guard at z ≈ −41 to −57; guard width x = ±42.96 at z ≈ −42
  * grip and pommel down to z = **−198.3**
  * thickness y = ±8.68 (diamond cross-section)
  * 24 triangles
  * Group `sb` (another blade shape) is unused.

Transform (`SummonedSwordRenderer.java:231-265`):

```java
if (hitEntity != null) {                         // stuck in a target
  rotY( -(rotLerp(pt, hit.yRotO, hit.yRot) - 90) )     // Axis.YN
  rotY( -offsetYaw )                                   // offsetYaw = random 0..360 (synced)
} else
  rotY( rotLerp(pt, yRotO, yRot) - 90 )
rotZ( rotLerp(pt, xRotO, xRot) )
rotX( roll )                                     // synced ROLL
scale(0.0075)
rotY(90)                                         // sword +Z -> +X (= flight direction; arrow convention)
if (hitEntity != null) translate(0, 0, -100)     // model units, after rotY(90): shifts 0.75 block back along the sword axis
setCol(entity.color, hasAlpha=false)             // opaque alpha 255, colour 0xRRGGBB
draw "ss", Luminous (additive, no cull, no depth write, fullbright)
```

* World size: tip **2.25 blocks** ahead of the origin, pommel **1.49** blocks behind, guard ±0.32 blocks, thickness ±0.065.
* There is **no fade or alpha animation**; constant brightness.
* The sword axis follows `yRot` / `xRot`, using the arrow convention `yRot = atan2(vx, vz)`. Roll spins the blade flat around its axis.

### 5.2 Common server flight logic (`EntityAbstractSummonedSword.tick:285-466`)

* Box `sized(0.5, 0.5)`, no gravity.
* `shoot(dir, velocity, inaccuracy)`: `v = normalize(dir) + gaussian*0.0075*inaccuracy`, scaled by velocity. It also sets yRot/xRot from v.
* **Stuck state** (`hitEntity` set): each tick the position is set to `(target.x, target.y + eyeHeight*0.5, target.z)` and `delay--`. When delay < 0 (it starts at **100** after `setHitEntity`), or the target dies → `burst()`.
* **In the air:**
  * ray against blocks; then an entity segment test (as for the drive) with `pierce` iterations
  * the shooter is excluded for the first 5 air ticks
  * after the move: if critical, 4 `CRIT` particles; velocity ×0.99; `BUBBLE` particles in water; yaw/pitch lerp 0.2 toward the velocity
* **Entity hit** (`onHitEntity:513-614`):
  * damage `ceil(damage)`, critical adds `rand(i/2 + 2)`, × damageAttr × config
  * `indirectMagic`; `invulnerableTime = 0`; stun
  * if `pierce == 0`: **sticks** (`setHitEntity`, random offsetYaw, delay 100) and then bursts after 5 s
  * if `pierce > 0`: the hit id is recorded; once `alreadyHits.size() >= pierce + 1` → burst
  * sound `TRIDENT_HIT` (1.0, `1.2/(r*0.2+0.9)`)
  * potion effects applied
* **Block hit:**
  * the base class lodges the sword: inGround, `TRIDENT_HIT_GROUND` (1.0, `2.2/(r*0.2+0.9)`), crit and pierce cleared
  * on the ground: `burst()` after `getOnGroundLifeTime()` (100 ticks; heavy rain 20), or when the block is broken
  * the Spiral, Storm and Blistering subclasses `burst()` immediately on a block hit
* **Air timeout:** removed (no burst) when `tickCount > 100`, it was never in the ground, and it is not riding.
* **`burst()`:** `GLASS_BREAK` (1.0, `1.2/(r*0.2+0.9)`), 16 `CRIT` (spread 0.5, speed 0.25), potion splash within 3 blocks, then removed.

### 5.3 Variants (spawned by `ability/SummonedSwordArts.java`)

All of them require a BEWITCHED blade with Power ≥ 1; their damage is the **Power level**.
* Basic swords cost `summon_sword_cost` = 2 souls.
* The arts cost `summon_blade_art_cost` = 20 souls.

| variant | input | count / layout | behaviour |
|---|---|---|---|
| **Basic** (`EntityAbstractSummonedSword`, `SummonedSwordArts.java:92-150`) | press M (summon key) | 1 per press | Position: `eye + right*(±1)`; the side alternates on the statistic counter. Target: lock-on, else a 12-block entity ray, else a 40-block block ray. `shoot(target − pos, 3.0, 0)`. **Roll = random 0–360.** Pierce 0, so it sticks. Sound `CHORUS_FRUIT_TELEPORT` (0.2, 1.45) to the player. |
| **Spiral** (`EntitySpiralSwords`) | hold M for 10 ticks (not sneak + move) | **6**, or **8** at rank S and up; delay = `360/count*i` | Rides the player. Every rideTick: `deg = ((gameTime % 30)*12 + delay) % 360`; position = `vehicle.pos + (0, eyeHeight/2, 0) + 2*(−sin deg, 0, cos deg)`; `setRot(−deg, 0)`. The sword points **radially outward** and the ring turns once per 30 ticks. While orbiting, the server runs `hitCheck`: `getRayTrace(position, viewVector)`. **Bug:** the second argument is used as the END POINT, i.e. a point near world origin. Each hit applies cancel knockback and damage, and resets alreadyHits, so it can re-hit every tick. Burst after 200 ticks. **Second hold-M** → `doFire`: stop riding, `tickCount = 0`, `shoot(horizontal(pos − vehicle.pos), 3.0, 1.0)`, i.e. fired outward. Pierce 5. |
| **Storm** (`EntityStormSwords`) | hold M + sneak + back (no forward); needs a lock-on target | 6 / 8; delay = `360/count*i` | Rides the **target**. `deg = (tickCount*360/(5 + tickCount) + delay) % 360`, so it decelerates and makes almost one turn. Position = `target.pos + (0, eyeH/2, 0) + 4*(−sin deg, 0, cos deg)`; `setRot(−deg − 180, 0)`: pointing **inward**. Orbit hitCheck as for spiral. At `tickCount ≥ 20` → fire: `shoot(horizontal(target − pos), 3.0, 1.0)`. Pierce 1. On hit: **toss** + stun. |
| **Blistering** (`EntityBlisteringSwords`) | hold M + sneak + forward, with Back last pressed more than 20 ticks ago | 6 / 8; delay = i | Rides the player. `level = i/2`, `side = (i even ? +1 : −1)`. `offset = ((1 − 0.1*level)*side, 0.25*level, −0.1*level)`, then `.xRot(rad(−pitch))`, then `.yRot(rad(−yaw))`; position = `vehicle.pos + (0, eyeH*0.8, 0) + offset`. Note: x+ (the "isRight" swords) lands on the player's **left** in MC coordinates. `setRot(−yaw, −pitch)`: pointing where the player looks. When M is released: `fireTime = tickCount + i`. At fireTime: target = lock-on, else a 12-block entity ray, else a 40-block block ray; `shoot(target − pos, 3.0, 1.0)`; `ENDER_DRAGON_FLAP` (1.0, 1.0) to the player. Pierce 5. No orbit hit check. On hit: cancel + stun. |
| **Heavy Rain** (`EntityHeavyRainSwords`) | hold M + sneak + forward, with Back pressed within the last 30 ticks | 1 + **9×2** = 19. If rank level is 0, `count = 9 + min(rank−1, 0)` = 8, giving 17 | Base = lock-on target position, or `player pos + forward(yaw)*5`, then +7 Y. One sword exactly at the base (delay 0). Then for i = 0..8, two swords each at `base + (U(−2.5, 2.5), 0, U(−2.5, 2.5))` with delay i. `xRot = −90`: pointing **down**. They hang motionless (`setRot(yaw, −90)`). On the first rideTick, `fireTime = tickCount + 10 + delay`. At fireTime: `shoot((0,−1,0), 4.0, 2.0)`. Pierce 5. Persistent `CustomPotionEffects`: **Slowness amp 10, 20 ticks** on hit. Cancel + stun. On-ground lifetime 20. |
| **Air-trick sword** (`ability/SlayerStyleArts.java:277-318`) | slayer-style air trick | 1 | Spawned at `target mid-height − look*2`; `shoot(look, 1.0, 0)`; damage 0.01; forced hit on the first tick, then the player teleports. Same visuals. |

**Colour of every sword:** the blade `getColorCode()`, default 0x3333FF. Spiral, Storm, Blistering and Heavy Rain swords have roll 0, so their blade flats are horizontal. Only basic swords get a random roll.

---

## 6. Colours

| source | value |
|---|---|
| Entity default `COLOR` (slash, JC, drive, summoned sword) | `0x3333FF` |
| Blade state default (`SlashBladeState.getEffectColor`, `capability/slashblade/SlashBladeState.java:260`) | `new Color(0x3333FF)`, so `getColorCode()` returns `0xFF3333FF` (alpha byte set) |
| Blade definition default (`RenderDefinition` codec field **`summon_sword_color`**, `registry/slashblade/RenderDefinition.java:15,67`) | `0xFF3333FF` |
| Built-in blade override | only **koseki**: `effectColor(0x303030)` (`data/builtin/SlashBladeBuiltInRegistry.java:60`) |
| Data-pack blades | can set `summon_sword_color` (int) |
| `color_inverse` / `SummonedSwordColorInverse` | stored and synced but **never used** by any renderer |
| doSlash fallback (no blade state) | `0xFFFFFF`, but doSlash returns early without a state anyway. Drive / WaveEdge fallback is `0xFF3333FF` |
| Slash rank override | colour becomes **0x555555** when rank < C (i.e. NONE or D). Players without rank points and all mobs therefore produce **grey** slashes with only the core pass |
| Slash fixed pass colours | inner dark pass `0x222222` (additive); outer white pass `0x404040` (additive) |
| JC | spheres use `HSB(h + 0.5, s, 0.2)` subtractive (the complement, so the bubble looks tinted toward the original hue); wind crescents use the blade colour |
| Lock-on reticle | `effectColor & 0xFFFFFF | 0xAA000000` |
| Renderers mask `& 0xFFFFFF` before adding their own alpha | slash, JC, drive. Summoned swords use `setCol(color, false)` (alpha 255) |

**Random variation:**
* no colour randomness anywhere
* random roll for rush slashes and basic summoned swords
* random JC seed (client-local)
* random wave-edge speeds
* random heavy-rain spread
* random vertical rush offset

---

## 7. Other small effect renders

* **Lock-on reticle** (`client/renderer/LockonCircleRender.java:46-122`). Drawn only while the local player is sneaking and the entity is the blade's lock target.
  * Model `model/util/lockon.obj`, texture `lockon.png` (4×4 white).
  * Transform: `translate(0, bbHeight/2, 0)`, then `translate((camera − entityCentre)*0.5)`, then billboard `cameraOrientation()`, then `scale(0.0025, −0.0025, 0.0025)`.
  * Passes:
    1. `lockonBase` (reticle, radius 59.7) with colour `col`, Luminous
    2. `lockonHealthMask`, translated z += `health*10`, colour `0x20000000`, LuminousDepthWrite
    3. `lockonHealth` (ring, radius 100) with colour `col`, LuminousDepthWrite
  * The mask hides part of the health ring by depth.
* **Charge glow:** §4.1.
* **Particles without entities:**
  * `DAMAGE_INDICATOR` on melee hits
  * `ANGRY_VILLAGER` when sneak + M marks a revenge target
  * `PORTAL` at full charge
  * `REVERSE_PORTAL` while charging a super art
  * `BLOCK` on aerial-cleave landing (`fallFactor 20` → 230 particles, speed 0.15)
* **Unused assets:** `model/util/trail.obj/png`, `ring.obj/png`, `grim_grip.obj`, `slash1.png`, `ss.obj` group `sb`. No Java code references them (`durability.obj` is the HUD durability bar).

---

## 8. Porting checklist (1.7.10)

1. Copy `slash.obj`, `slashdim.obj`, `drive.obj`, `ss.obj` and their PNGs. Load them with Forge 1.7.10's `WavefrontObject`, which also flips V. Iterate `groupObjects → faces` yourself so you can apply the global UV offset and the **alpha = 0 when model-space y == 0** rule per vertex.
2. Use the GL states from §0.2. All passes are fullbright, use alpha test > 0.1, and never write depth (except the lock-on mask).
3. Slash (§1.4): `glRotatef(-yaw-90, Y)`, `glRotatef(pitch, Z)`, `glRotatef(roll, X)`, `glRotatef(off − 135p, Y)`, `glScalef(1, .25, 1)`, `glScalef(1.2)`, then per pass `glScalef(s, .03, s)`. Lifetime 10 on the client; `p = (age + pt)/10`; `alpha = 1 − p^4`. Rank-gated passes; grey below C.
4. The server needs to send: position, yaw, roll, colour, rank (float), baseSize, flags. The server-side hit test is player-centred and reach-based, **not arc-shaped**.
5. JC: reproduce the 5 + 3 subtractive spheres (`GL14.glBlendEquation(GL_FUNC_REVERSE_SUBTRACT)`) and the buggy wind alpha.
6. Drive: arrow-style orientation, `0.015` scale, alpha `0.75 − t^4` (per tick, no partial ticks).
7. Summoned sword: arrow-style orientation, `0.0075` scale, opaque additive colour, no fade.
