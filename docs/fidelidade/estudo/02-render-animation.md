<!-- Estudo do código do SlashBlade: Resharped (MIT, (c) 2024 M Mysterious Mountain Forging-shop Group), do Double Edged Reforged (GPL-3.0) e, como referência, do SlashBlade_2 (flammpfeil). Só leitura; os trechos citados são curtos e servem à análise. A arte desses projetos é "All Rights Reserved" e não está neste repositório. -->

# SlashBlade: Resharped (Forge 1.20.1): how blades and the player are rendered and animated

Read-only study. Paths are relative to
`/tmp/claude-0/-home-user/578f82f5-7501-52c9-9180-a224fa679aab/scratchpad/ref/resharped/`.

* `J/` = `src/main/java/`
* `SB/` = `J/mods/flammpfeil/slashblade/`
* `R/` = `src/main/resources/assets/slashblade/`

Everything below comes from reading the code. The numerical checks in §3.8 and §4.6 come from small Python scripts
I wrote myself (they only parse the PMD/VMD/OBJ files; no repository code was run). Those scripts are in
`/tmp/claude-0/-home-user/578f82f5-7501-52c9-9180-a224fa679aab/scratchpad/tools/` (`pmd.py`, `vmd.py`, `sim.py`, `pa.py`).

---

## 0. TL;DR (the facts that matter most for a 1.7.10 port)

1. **Blades are Wavefront OBJ files** (Metasequoia exports, all faces triangles, `v/vt/vn`). They are drawn by
   group name with a modified copy of Forge's old `WavefrontObject` loader. Group names:
   `blade`, `blade_damaged`, `blade_fragment`, `sheath`, `effect`, `item_blade`, `item_damaged`, `item_bladens`,
   plus optional `<group>_luminous` overlays. Some models also have unused groups (`handle`, `item_back`, `item_blade1`).
2. **The blade in hand is not attached to the player's arm.** A separate invisible MMD rig, `R/model/bladeholder.pmd`
   (9 bones, every bone at the origin), is animated by `R/combostate/motion.vmd`. Two bone matrices are read:
   * **`hardpointA`** places the blade.
   * **`hardpointB`** places the sheath.

   They are multiplied into the render matrix under a fixed MMD-to-MC conversion: translate 1.5, scale 1/8,
   rotate Z 180°, mirror X. OBJ units come out at **1/128 block** (then the player's 0.9375 scale applies).
3. **VMD playback:** 30 fps, time in ms = 1000/30 per frame. A `ComboState` has `[start,end]` frames. The
   current time is `(worldTime - lastActionTime + partialTicks) * 50 ms`, walked through the timeout chain,
   then clamped or looped within the span. **There is no Bézier interpolation.** The VMD interpolation bytes are
   read and thrown away. Positions are lerped linearly and rotations use normalized lerp (nlerp, called "Slerp"
   in the code). There is no blending between states and no IK (neither PMD has IK).
4. **The player body is animated only when the optional mod PlayerAnimator (KosmX) is installed.** In that case
   `R/model/pa/player_motion.vmd` (rig `R/model/pa/alex.pmd`) drives vanilla `ModelPart` angles and pivots:
   * The MMD bones `head`, `torso`, `left arm`, `right arm`, `left leg`, `right leg` map to the vanilla parts.
   * The MMD bone `body` moves the whole model (a pose-stack transform in `setupRotations`).

   Rotation = ZYX Euler angles of the bone's **local** VMD quaternion, with sign flips `(-x,+y,-z)` for parts
   and `(+x,-y,-z)` for the whole body. Position = VMD offset × (1,-1,1) × 2 px, added to the vanilla pivot.
   * Arms: vanilla rotation is **replaced**.
   * Legs: vanilla rotation is **added to** (except aerial moves, where it is replaced).
   * Head and torso: vanilla rotation is added to.
   * Without PlayerAnimator the player model is never posed; there is only a whole-body Y spin for two combos.
5. **The vanilla model is never replaced.** No layer draws arms or the body. In first person only the blade and
   sheath are drawn (no arms), from a reset view-space matrix.
6. **There are two render modes** (plus glint and charge):
   * **Normal blade:** translucent blend `SRC_ALPHA, ONE_MINUS_SRC_ALPHA`, entity diffuse lighting from
     per-vertex normals, lightmap, back-face culling, depth write, nearest filtering, alpha test 0.1.
   * **`*_luminous`:** additive `SRC_ALPHA, ONE` (alpha `ONE, ZERO`), **no lighting and no lightmap**
     (emissive), no culling, **no depth write**, **linear filtering**, alpha test 0.1.

---

## 1. Class inventory

| File | Role |
|---|---|
| `SB/client/renderer/layers/LayerMainBlade.java` | **Core.** A `RenderLayer` added to every living-entity renderer. Resolves the combo, plays `motion.vmd` on `bladeholder.pmd`, draws the blade at `hardpointA` and the sheath at `hardpointB`, draws the "standby" blade (hotbar slot 0 or off-hand) at a fixed carry position, and applies the inverse body pose (`setUserPose`). |
| `SB/client/renderer/model/BladeFirstPersonRender.java` | First person: resets the matrix to identity, applies a fixed view transform and calls `LayerMainBlade.render` for the local player. |
| `SB/client/renderer/SlashBladeTEISR.java` | Item renderer (BEWLR). Hand contexts call first person only (third person draws nothing here). GUI, ground and fixed contexts draw `item_*` groups. The blade stand draws `blade` and `sheath` with stand offsets. Also draws the GUI durability bar. |
| `SB/client/renderer/model/BladeModel.java` | Baked-model wrapper: `isCustomRenderer()=true`, `usesBlockLight()=false` (flat GUI lighting). |
| `SB/client/renderer/model/BladeModelManager.java` | Cache of `WavefrontObject` by ResourceLocation. Default model is `slashblade:model/blade.obj`. |
| `SB/client/renderer/model/BladeMotionManager.java` | Cache of `MmdVmdMotionMc` by ResourceLocation. Default motion is `slashblade:combostate/motion.vmd`. |
| `SB/client/renderer/model/obj/WavefrontObject.java`, `GroupObject.java`, `Face.java`, `Vertex.java`, `TextureCoordinate.java`, `ModelFormatException.java` | OBJ loader and renderer: a port of Forge 1.7.10's `WavefrontObject` with changes (per-vertex normals, vertex colour, alpha and UV operators, relaxed number regex). |
| `SB/client/renderer/util/BladeRenderState.java` | All RenderTypes (blend, luminous, colour-write, reverse-luminous, luminous-depth-write, charge effect, glint) and the `renderOverrided*` entry points. Holds a static draw colour `col`. |
| `SB/client/renderer/util/MSAutoCloser.java` | try-with-resources `pushPose`/`popPose`. |
| `SB/client/renderer/CarryType.java` | `NONE, DEFAULT, PSO2, NINJA, KATANA, RNINJA`: carry pose of the standby blade. |
| `SB/client/renderer/event/ModelResourceLoader.java`, `PreloadedModelEvent.java` | Reload listener that preloads every `slashblade:model/**.obj`. |
| `SB/client/ClientHandler.java` | Registers the layer on the player skins ("default", "slim") and every `LivingEntityRenderer`. Registers PlayerAnimationOverrider if PlayerAnimator is present, else UserPoseOverrider. Registers the BladeModel bake. |
| `SB/compat/playerAnim/VmdAnimation.java` | **Player animation.** A PlayerAnimator `IAnimation` that samples `player_motion.vmd` on `alex.pmd` and returns per-part rotation and position. |
| `SB/compat/playerAnim/PlayerAnimationOverrider.java` | Maps ComboState ids to `VmdAnimation` frame ranges. Starts them on `BladeMotionEvent`. |
| `SB/event/client/UserPoseOverrider.java` | Fallback without PlayerAnimator: rotates the whole player around Y by a per-entity angle (`sb_yrot`), plus `invertRot` for the blade. |
| `SB/event/client/SneakingMotionCanceller.java` | When holding a blade (and not in own first person), forces `crouching=false` and cancels the crouch render offset. |
| `SB/event/client/RenderOverrideEvent.java` | Cancellable event fired for every OBJ part draw. Can swap model, target group, texture or render type. |
| `SB/event/BladeMotionEvent.java`, `SB/event/BladeMotionEventBroadcaster.java`, `SB/network/MotionBroadcastMessage.java` | Combo-start event. The server broadcasts it to players within 20 blocks, which re-post it client-side (drives PlayerAnimator). |
| `SB/registry/combo/ComboState.java`, `SB/registry/ComboStateRegistry.java` | Frame ranges, loop flag, speed, timeout, next-on-timeout, motion file. |
| `SB/util/TimeValueHelper.java` | Tick, ms and frame conversions (20 ticks/s, 30 frames/s). |
| `SB/util/VectorHelper.java` | `matrix4fFromArray` (MMD row-major array to JOML column-major, i.e. a transpose; see §3.6). |
| `J/jp/nyatla/nymmd/**` | NyMMD (MIT): PMD/VMD parser, bone hierarchy, IK, keyframe sampling. Used only for bone matrices; the PMD meshes are never drawn. |
| `SB/client/renderer/entity/BladeStandEntityRenderer.java` | Blade stand: draws the stand item and the blade item (FIXED context to TEISR `renderModel`). |
| `SB/client/renderer/entity/BladeItemEntityRenderer.java` | Dropped blade entity: OBJ `blade`/`blade_damaged`/`blade_fragment` and `sheath` with custom transforms. |
| `SB/client/renderer/entity/{SummonedSword,Drive,SlashEffect,JudgementCut}Renderer.java`, `LockonCircleRender.java`, `gui/RankRenderer.java` | Effects and HUD. They use the same OBJ and BladeRenderState machinery (covered only briefly here, §6.6). |
| `SB/mixin/client/compat/ar/AcceleratedGroupObject.java` | Optional "Accelerated Rendering" mesh-cache mixin on `GroupObject.render`. Performance only; no visual change. |
| `SB/item/ItemSlashBlade.java` | `initializeClient` installs the TEISR. `onEntitySwing` cancels the vanilla swing except on the action tick (§5.3). |
| `R/models/item/slashblade.json` (and `slashblade_*.json`) | `builtin/entity` parent plus display transforms (only GUI and the hand contexts are given). |

---

## 2. Blade model files (OBJ)

### 2.1 Files and groups

All OBJ files are "Created by Metasequoia". They contain only `v`, `vt`, `vn`, `f v/vt/vn` (triangles) and `g`
lines (some util files also have `mtllib`/`usemtl`/`s`, which the loader ignores). I checked every file: no
integer-only coordinates and no scientific notation, so **Forge 1.7.10's stock `WavefrontObject` regexes accept
them**.

| File | Groups (in file order) |
|---|---|
| `R/model/blade.obj` (default, texture `R/model/blade.png` 64×128) | `sheath, blade, blade_damaged, blade_fragment, effect, item_blade, item_damaged, item_bladens` |
| `R/model/named/yamato.obj` | same as blade.obj |
| `R/model/named/agito.obj`, `sange/sange.obj`, `muramasa/muramasa.obj`, `dios/dios.obj` | `item_blade, item_damaged, item_bladens, sheath, effect, (handle), blade, blade_damaged, blade_fragment, (item_back)` |
| `R/model/named/yasha/yasha.obj` | `sheath, sheath_luminous, blade, blade_damaged, blade_fragment, effect, item_blade, item_damaged, item_bladens, item_blade1, item_blade_luminous` |
| `R/model/named/yasha/yasha_true.obj` | `sheath, sheath_luminous, blade, …, item_blade, item_blade_luminous, item_damaged, item_bladens` |

Only yasha ships `_luminous` groups, but every draw site also asks for `<part>_luminous`, so add-on models can
use them. `handle`, `item_back` and `item_blade1` are never referenced by code.

**Bounding boxes of `blade.obj`** (OBJ units):

| Group | x | y | z |
|---|---|---|---|
| `blade` | −290.96 … 41.74 | −27.48 … 18.04 | ±10.08 |
| `sheath` | −296.23 … −35.40 | −29.94 … 9.39 | ±4.33 |
| `blade_damaged` | −110.23 … 41.74 | | |
| `blade_fragment` | −290.96 … −109.80 | | |
| `effect` (charge glow shell) | −297.12 … −35.14 | | |
| `item_blade` (GUI icon pose, diagonal) | −86.7 … 70.7 | −70.7 … 80.2 | −4 … 29.75 |

Taken together:

* The **long axis is −X**: the tip is at about −291 and the pommel at about +42.
* **+Y is the edge or back side.**
* The sheath covers x −296 … −35, so the handle (−35 … +42) sticks out of the sheath.
* The OBJ origin is near the tsuba/hilt.

### 2.2 Loader (`SB/client/renderer/model/obj/WavefrontObject.java`)

* Reads line by line, `replaceAll("\\s+"," ").trim()`, and skips `#` and empty lines (L73-L77).
* `v` regex `v( -?\d+(\.\d+)?){3,4}`. The decimal part is optional, unlike 1.7.10 Forge (L25-L26).
* `vt`: V is flipped, `new TextureCoordinate(u, 1 - v)` (L236-L238).
* `f`: four syntaxes (`v/vt/vn`, `v/vt`, `v//vn`, `v`). A group's draw mode is fixed by its first face
  (3 points = GL_TRIANGLES, 4 = GL_QUADS). Mixing throws (L262-L278). A face normal is always computed
  (`calculateFaceNormal`, `(v1-v0)×(v2-v0)` normalized, `Face.java` L123-L129).
* `g`/`o` starts a new group (name regex `[\w\d\.]+`). Faces before any `g` go to group `"Default"` (L95-L112).
* `tessellateOnly(vc, ms, light, color, names...)` draws every group whose name `equalsIgnoreCase` a target
  (L143-L151). Several groups with the same name are all drawn.

### 2.3 Face emission (`Face.java` L47-L121): differences from Forge 1.7.10

For each vertex `i`:

```java
wr.vertex(pose, v.x, v.y, v.z);                         // pose = PoseStack.last().pose()
wr.color(r, g, b, alphaOverride.apply(vec4(v), a));    // col from BladeRenderState (default white 255)
u = tc.u*uvOp.x + uvOp.z;  vv = tc.v*uvOp.y + uvOp.w;  // uvOperator default (1,1,0,0)
offU = (u > avgU) ? -0.0005 : +0.0005;  offV likewise  // pull UV 0.0005 toward the face's UV centroid
wr.uv(u+offU, vv+offV);   (no vt -> uv(0,0))
wr.overlayCoords(NO_OVERLAY); wr.uv2(light);
n = vertexNormals!=null ? vn[i] : faceNormal;          // PER-VERTEX normal (smooth shading)
n = normalize(Matrix3f(pose) * n); wr.normal(n);
```

* **Per-vertex normals** give smooth shading. Stock 1.7.10 Forge calls `tessellator.setNormal(faceNormal)` once
  per face, so the port must call `setNormal` per vertex to match.
* **Vertex colour/alpha.** `Face.alphaOverride` (`alphaOverrideYZZ`: alpha=0 where `y==0`) and `uvOperator` are
  static hooks. Only the slash-effect renderer uses them. `renderOverrided` resets both after every draw
  (`BladeRenderState.java` L119-L122).
* There is no smoothing-group logic. `s` lines are ignored.
* Every RenderType uses `VertexFormat.Mode.TRIANGLES`, so models **must be triangulated** (all shipped ones are).

### 2.4 Model and texture selection

* The model and texture come from the blade capability (`ISlashBladeState.getModel()/getTexture()`), with
  fallbacks `slashblade:model/blade.obj` and `slashblade:model/blade.png` (`SB/init/DefaultResources.java`).
* Named blades set them through `RenderDefinition` (`SB/registry/slashblade/RenderDefinition.java`): `texture`,
  `model`, `summon_sword_color` (default 0xFF3333FF), `color_inverse`, and `carry_type` (codec default `PSO2`,
  builder default `DEFAULT`).
* **The texture is bound as a plain file** (`TextureStateShard(tex, blur, mipmap)`), not from an atlas.
  One texture per blade covers all groups, luminous ones included.

### 2.5 Which group is drawn where

| Context | Groups (in draw order) |
|---|---|
| In hand (LayerMainBlade main) | at hardpointA: `blade` (or `blade_damaged` if `isBroken()`), then `<that>_luminous`. At hardpointB: `sheath`, `sheath_luminous`, then `effect` with the charge render type if `isCharged(entity)`. |
| Standby (offhand / hotbar 0) | `blade`/`blade_damaged`, `_luminous`, `sheath`, `sheath_luminous`, all with **one** matrix (no VMD) |
| GUI / ground / fixed (item frame) | `item_damaged` if BROKEN, else `item_bladens` if NOSCABBARD, else `item_blade`; then `_luminous`. GUI adds the durability bar. |
| Blade stand (FIXED in a BladeStandEntity) | `blade`/`blade_damaged` + `_luminous`; `sheath` + `sheath_luminous` (unless pose 4/5) |
| Dropped item entity | `blade_fragment` (EDGEFRAGMENT) / `blade_damaged` (BROKEN) / `blade`, + `_luminous`; `sheath` (+lum) when on ground (unless NOSCABBARD) or in water |

---

## 3. Blade in hand and at the hip: `LayerMainBlade`

`SB/client/renderer/layers/LayerMainBlade.java`. Added to every `LivingEntityRenderer` (players and mobs)
in `ClientHandler.addLayers` (L131-L169).

### 3.1 The invisible rig `bladeholder.pmd`

Parsed with my script (`R/model/bladeholder.pmd`, 3301 bytes, model name "BladeHolder"):

```
bone 0 'センター'  (center)  parent -1  pos (0,0,0)
bone 1 'JointA1'  parent 0   bone 2 'JointA2' parent 1   bone 3 'JointA3' parent 2   bone 4 'hardpointA' parent 3
bone 5 'JointB1'  parent 0   bone 6 'JointB2' parent 5   bone 7 'JointB3' parent 6   bone 8 'hardpointB' parent 7
all bone positions = (0,0,0); IK count = 0; 24 verts / 1 material (never drawn)
```

Because every rest position is 0, `m_matInvTransform = I` and each bone's offset is 0. So the skinning matrix of
`hardpointX` is the plain product of the local VMD transforms down the chain (row-vector convention):

```
S_hp = L_hp · L_J3 · L_J2 · L_J1 · L_center,   L_b = [ R(q_b) | t_b ]  (t_b = VMD position of bone b)
```

`R/combostate/motion.vmd` (model "BladeHolder", 1689 bone keys, frames 0…2299) keys all 9 bones:

| Bone | Keys | Max \|position\| (MMD units) |
|---|---|---|
| JointA1 | 371 | 20.3 |
| JointA2 | 144 | 13.3 |
| JointA3 | 345 | 17.0 |
| hardpointA | 40 | 0 |
| JointB1 | 306 | 18.4 |
| JointB2 | 129 | 11.5 |
| JointB3 | 213 | 11.6 |
| hardpointB | 40 | 0 |
| center | 101 | 0 |

### 3.2 `render()` step by step (L199-L364)

```java
renderOffhandItem(ms, buf, light, entity);                 // standby blade, §3.5
motionYOffset = 1.5f; motionScale = 1.5/12.0 (=0.125); modelScaleBase = 0.0078125 (=1/128)
stack = entity.getMainHandItem(); return if empty or entity type in tag RENDER_LAYER_BLACKLIST
// --- combo resolution (§4.2) ---
time_ms = msec( max(0, gameTime - s.getLastActionTime()) + partialTicks )      // ticks*50
while (combo != NONE && combo.getTimeoutMS() < time_ms) { time_ms -= combo.getTimeoutMS(); combo = next(combo.getNextOfTimeout(entity)); }
if (combo == NONE) combo = s.getComboRoot()  (default "standby")
motion = BladeMotionManager.getMotion(combo.getMotionLoc())   // default combostate/motion.vmd
mmp.setVmd(motion); maxMs = frames2ms(motion.getMaxFrame())
start = frames2ms(combo.start); end = frames2ms(combo.end); span = min(maxMs, |end-start|)
if (combo.loop) time_ms %= span;  time_ms = min(span, time_ms);  time_ms += start
mmp.updateMotion(time_ms)                                    // frame = ms / (100/3)
push
  setUserPose(ms, entity, partialTicks)                      // §5.4
  ms.translate(0, 1.5, 0)
  ms.scale(0.125, 0.125, 0.125)
  ms.mulPose(Axis.ZP.rotationDegrees(180))                   // "transpoze mmd to mc"
  push   // BLADE
    S = mmp._skinning_mat[idx("hardpointA")] -> float[16] (m00,m01,m02,m03,m10,...,m33)
    M = new Matrix4f(buf[0..15])   // JOML column-major ctor => M = S^T (column-vector form)
    ms.scale(-1,1,1); ms.last().pose().mul(M); ms.scale(-1,1,1)
    ms.scale(1/16)   // modelScale = (1/128)/(0.125)
    draw part ("blade"|"blade_damaged") normal; draw part+"_luminous" luminous
  pop
  push   // SHEATH
    same with "hardpointB"; scale(1/16)
    draw "sheath" normal; "sheath_luminous" luminous
    if s.isCharged(entity): renderChargeEffect(f = tickCount+partialTicks, group "effect",
                            texture "minecraft:textures/entity/creeper/creeper_armor.png")
  pop
pop
```

The light passed is the layer's `packedLight` (the entity's light). The colour is white.

### 3.3 Coordinate conversion and units (exact)

The layer's PoseStack is the vanilla `LivingEntityRenderer` model space:
`translate(entityPos) · Ry(180-bodyYaw) · [PlayerAnimator body transform] · scale(-1,-1,1) · scale(0.9375) · translate(0,-1.501,0)`.
In that space Y points down, X is mirrored and y=0 is the neck pivot.

* Rz(180)·Sx(−1) = diag(1,−1,1), and diag(−1,−1,1)·diag(1,−1,1)·… cancels.
* Write **F** for the entity frame after `Ry(180−yaw)`: Y up, feet at 0, **player front = −Z, player right = +X**.
* An OBJ vertex `v` then lands in F at

  ```
  p_F = 0.9375 · ( (1/8) · Sx · M · Sx · (v/16)  +  (0, 0.001, 0) )        Sx = diag(-1,1,1)
  ```

* **1 MMD unit = 1/8 block** (the "neck height" comment: MC 1.5 blocks = MMD 12 units). The translate(0,1.5,0)
  in Y-down model space actually moves the origin from the neck down to the feet. MMD origin = feet.
* **1 OBJ unit = 1/16 MMD unit = 1/128 block** (before the 0.9375 player scale). The default blade (−291 … +42)
  is therefore about 2.6 blocks long, or about 2.44 with the player scale.
* **Mirror X.** MMD is left-handed: the model faces −Z, and the character's right side is −X. MC frame F has
  right = +X. The mirrored X fixes this. OBJ coordinates are mirrored in X *before* the bone matrix, so OBJ
  files are authored in the MC-handed sense.
* There are no Euler angles here: the full bone matrix is used. In OpenGL 1.7.10 the float[16] in MMD order
  `(m00,m01,m02,m03,m10,…,m33)` **is already column-major** for `glMultMatrix`. Load it into a FloatBuffer as is:

  ```java
  glScalef(-1,1,1); glMultMatrix(buf /*m00..m33 in MMD order*/); glScalef(-1,1,1);
  ```

  The translation sits in `m30,m31,m32` (indices 12, 13, 14).

### 3.4 Sample poses (computed from motion.vmd, frame F, blocks, without the 0.9375 scale)

Frames 0, 1 and 41 (standby / end of A1) give the same pose for both hardpoints:

| Point | Position in F | Meaning |
|---|---|---|
| hardpoint origin | (−0.522, 0.796, −0.547) | left hip, 0.8 up, 0.55 forward |
| OBJ (−291,0,0) (tip) | (0.089, 0.663, 1.639) | trails behind |
| OBJ (+42,0,0) (pommel) | (−0.61, 0.815, −0.862) | in front |

So **hardpointA = hardpointB at rest: the blade sits inside the sheath at the left hip**, hilt forward.

During combo_a1 (frames 1-10) the blade leaves the sheath and swings to the right. At frame 10:

| | hardpointA |
|---|---|
| origin | (0.413, 1.844, 0.661) |
| tip | (2.638, 1.913, 0.2) |

These numbers are a good check for a port.

### 3.5 Standby / carry blade (`renderStandbyBlade`, L117-L197)

`renderOffhandItem`:

* If the off-hand holds a blade, draw it.
* Otherwise, if the entity is a Player with `selected != 0` and **hotbar slot 0** holds a blade, draw that one.
  This runs even while another blade is in the main hand.

The draw has no VMD and no `setUserPose`:

```
push; translate(0, 1.5, 0)
switch carryType:
 PSO2   : translate( 1.00, -1.125,  0.20); rotateZYX(-0.122173, 0, 0)        ; skip if own first person
 KATANA : translate( 0.25, -0.875, -0.55); rotateZYX(3.1415927, 1.570796, 0.261799)
 DEFAULT: translate( 0.25, -0.875, -0.55); rotateZYX(0,          1.570796, 0.261799)
 NINJA  : translate(-0.50, -2.000,  0.20); rotateZYX(-2.094395, 0, 3.1415927) ; skip if own first person
 RNINJA : translate( 0.50, -2.000,  0.20); rotateZYX(-1.047198, 0, 0)         ; skip if own first person
 NONE   : return
scale(0.125); scale(1/16)    // = 1/128
draw blade|blade_damaged, +_luminous, sheath, sheath_luminous   (same matrix)
```

* `Quaternionf().rotateZYX(z,y,x)` = Rz(z)·Ry(y)·Rx(x) (X is applied to the vector first). In GL:
  `glRotatef(z°,0,0,1); glRotatef(y°,0,1,0); glRotatef(x°,1,0,0)`.
* All of this is in **model space** (Y down, X mirrored). Example for DEFAULT: the net translate is
  (0.25, 0.625, −0.55) model, which is about (−0.23, 0.82, −0.52) in frame F. That is the left hip, with the
  hilt forward. `Ry(90°)` sends OBJ −X (tip) to +Z model (backwards).

### 3.6 Matrix library facts needed to port NyMMD (`J/jp/nyatla/nymmd/types/MmdMatrix.java`)

* Row-vector convention: `v' = v·M`, with the translation in `m30..m32`.
* `mul(l, r)` = l·r, so `L_child·L_parent` means "child first, then parent".
* `QuaternionToMatrix` writes `m01 = 2(xy+zw)`, `m02 = 2(zx−yw)`, `m10 = 2(xy−zw)`, …. That is the transpose of
  the usual column-vector rotation matrix of the same quaternion, so it describes the same rotation.
* `PmdBone.updateMatrix()` (`core/PmdBone.java` L132-L149):

  ```
  tmp = QuaternionToMatrix(m_vec4Rotate); tmp.m3x = m_vec3Position + m_vec3Offset;   (offset = pos - parentPos)
  m_matLocal = parent ? tmp·parent.m_matLocal : tmp
  ```

  `updateSkinningMat`: `skin = m_matInvTransform(translate(-pos))·m_matLocal` (L127-L130).
* `updateMotion` (`MmdMotionPlayer.java` L174-L223) runs: `frame = ms/(100/3)`, clamp to maxFrame, faces,
  `bone.reset()` (pos 0, quat identity), sample all keyed bones, bone matrices, IK (none), bone matrices
  again, skinning matrices.
* Caveat on parents: a parent with a higher index than its child is null at construction and stays a root,
  with offset `pos-(0,1,0)` (`PmdBone.java` L75-L81). Neither shipped PMD triggers this.

---

## 4. VMD motion playback

### 4.1 Files

| Resource | Rig | Used by |
|---|---|---|
| `slashblade:combostate/motion.vmd` (`ExMotionLocation`) | bladeholder.pmd | every built-in ComboState: blade and sheath |
| `slashblade:combostate/old_motion.vmd` (`BaseMotionLocation`) | | referenced but not present in resources |
| `slashblade:combostate/piercing.vmd`, `piercing_pl.vmd`, `test*.vmd` | Bladeholder_arm / Alex_arm | piercing (test) |
| `slashblade:model/pa/player_motion.vmd` | alex.pmd | PlayerAnimator body motion |
| `slashblade:model/pa/alex.pmd` | | player rig (23 bones, IK 0) |
| `slashblade:model/bladeholder.pmd` | | blade rig |

* **Loading:** `MmdVmdMotionMc(loc)` reads the resource stream. `DataReader` copies `stream.available()` bytes
  (2 MB if `available()<1`) into a little-endian ByteBuffer (`struct/DataReader.java` L49-L64). For the port,
  read the whole stream instead; `available()` is not reliable on zip streams.
* **Header:** `"Vocaloid Motion Data 0002"` (30 bytes) plus a 20-byte model name. Then an int count, followed
  by 111-byte bone keys: name 15 bytes Shift_JIS NUL-terminated, frame u32, pos 3f, quat 4f (x,y,z,w),
  interpolation 64 bytes **(ignored)**. Then face keys (none in these files).
* Keys are grouped by bone name and sorted by frame. Every quaternion is normalized on load
  (`MmdVmdMotion_BasicClass.java` L251-L323).
* `getMaxFrame()` = the largest frame number in the file: 2299 for motion.vmd and for player_motion.vmd.
* **Caches:** `BladeMotionManager` (a Guava LoadingCache, invalidated on `TextureStitchEvent.Post`; a failed load
  falls back to the default motion). One `MmdMotionPlayerGL2` exists per `LayerMainBlade` instance, so one per
  entity renderer, shared by all entities of that renderer. VmdAnimation uses one static player for all players.

### 4.2 Time to frame for the blade (LayerMainBlade)

`SB/util/TimeValueHelper.java`: 1 tick = 50 ms, 1 frame = 1000/30 ms, so 1 tick = 1.5 frames.

`ComboState` (`SB/registry/combo/ComboState.java`):

```java
getTimeoutMS() = (int)(frames2ms(|end-start|) / speed) + timeout    // L82-L85
```

* `speed` (default 1) only lengthens the time spent in a state. **It does not slow the VMD playback**
  (LayerMainBlade never uses speed; the helper `modifiedSpeed` is dead code). Example: `judgement_cut_slash`
  1923-1928 has speed 0.4. It plays 166 ms of motion, then holds the last pose until 416 ms have passed.
* `loop` states (`none`, `standby`, `aerial_cleave_loop`) wrap with `%`.
* `lastActionTime` = world `gameTime` when `updateComboSeq` ran (`ISlashBladeState.updateComboSeq`, L411-L423).
  It is synced through the capability.
* The timeout chain walk is in §3.2. For example combo_a1 (1-10) times out into combo_a1_end (10-21), then into
  combo_a1_end2 (21-41). Each state samples its own frame range, so a chain plays continuous frames as long as
  consecutive states share boundaries.
* Main frame ranges (motion.vmd), for reference:

| State | Frames |
|---|---|
| standby, none | 0-1, loop |
| combo_a1 | 1-10 |
| combo_a1_end | 10-21 |
| combo_a1_end2 | 21-41 |
| combo_a2 | 100-115 |
| combo_a2_end | 115-132 |
| combo_a2_end2 | 132-151 |
| combo_c | 400-459 |
| combo_c_end | 459-488 |
| combo_a3 | 200-218 |
| combo_a3_end | 218-230 |
| combo_a3_end2 | 230-281 |
| combo_a3_end3 | 281-306 |
| combo_a4 | 500-576 |
| combo_a4_end | 576-608 |
| combo_a4_ex | 800-839 |
| combo_a4_ex_end | 839-877 |
| combo_a4_ex_end2 | 877-894 |
| combo_a5ex | 900-1013 |
| combo_a5ex_end | 1013-1061 |
| combo_b1 | 700-720 |
| combo_b1_end | 720-743 |
| combo_b1_end2 | 743-764 |
| combo_b1_end3 | 764-787 |
| combo_b2 … b6 | 710-720 |
| combo_b7 | 710-764 |
| aerial_rave_a1 | 1100-1122 |
| aerial_rave_a2 | 1200-1210 |
| aerial_rave_a3 | 1300-1328 |
| aerial_rave_b3 | 1400-1437 |
| aerial_rave_b4 | 1500-1537 |
| upperslash | 1600-1659 |
| upperslash_jump | 1700-1713 |
| aerial_cleave | 1800-1812 |
| aerial_cleave_loop | 1812-1817, loop, timeout 1000 |
| aerial_cleave_landing | 1816-1859 |
| rapid_slash | 2000-2019 |
| rising_star | 2100-2137 |
| judgement_cut | 1900-1923 |
| judgement_cut_slash | 1923-1928 |
| judgement_cut_sheath | 1928-1963 |
| void slash | 2200-2299 |

  The full table is in `SB/registry/ComboStateRegistry.java`.

### 4.3 Keyframe sampling (`types/MotionData.getMotionPosRot`, L52-L87)

```
if f > lastKey.frame: f = lastKey.frame
i = first index with f < key[i].frame  (binary search, linear below 8; returns n-1 if none)
k0 = i-1, k1 = k0+1; if k1==n: k1=n-1; if k0<0: k0=0
if k0!=k1: t=(f-f0)/(f1-f0); pos = lerp(p0,p1,t); rot = nlerp(q0,q1,t)   // "QuaternionSlerp"
else      : pos = p0; rot = q0
```

`MmdVector4.QuaternionSlerp` (L51-L71) is really **nlerp**:

```
dot = q0·q1
q = (dot < 0) ? q0*(1-t) - q1*t : q0*(1-t) + q1*t
normalize(q)
```

* **No Bézier curves.** The four 16-byte interpolation tables in `VMD_Motion.read` (L59-L74) are read and discarded.
* Bones not keyed in the file keep their reset state (identity).
* There is no cross-fade between combo states: the pose snaps when the state changes.

### 4.4 IK and look-at

* `PmdIK` is a CCD solver with a knee limit for 左ひざ/右ひざ. Both PMDs have **0 IK chains**, so it is unused.
* "Look-me" neck tracking is disabled (`_lookme_enabled=false`).

---

## 5. The player model and its animation

### 5.1 Two paths (chosen in `ClientHandler.doClientStuff`, L52-L56)

* **PlayerAnimator present** (`dev.kosmx.playerAnim.api.layered.AnimationStack` exists; the dependency is
  optional, version `1.0.2-rc1+1.20`): `PlayerAnimationOverrider.register()`. `UserPoseOverrider.UsePoseOverrider`
  stays false.
* **Absent:** `UserPoseOverrider.register()` sets `UsePoseOverrider=true`. The player model is not posed by VMD
  at all; vanilla animation stays. The only change is the whole-body Y spin below.

The vanilla player renderer and `PlayerModel` are never replaced, and no layer draws limbs. Only the vanilla
part angles and pivots are rewritten (by PlayerAnimator's mixins), plus the root PoseStack.

### 5.2 PlayerAnimator path

**Trigger** (`PlayerAnimationOverrider.onBladeAnimationStart`, file L42-L58). On `BladeMotionEvent` for an
`AbstractClientPlayer`:

```java
stack.removeLayer(0); template.play(); stack.addAnimLayer(0, template.getClone())
```

The clone starts at currentTick 0. `BladeMotionEvent` is posted:

* client-side by the local `updateComboSeq` (client prediction), and
* for remote players (the actor included) by the server's `MotionBroadcastMessage` (`PacketDistributor.NEAR`,
  radius 20) through `MotionBroadcastMessage.setPoint`.

Timeout successor states (for example a1 to a1_end) fire no event. The player clip for the root state covers
the whole chain instead.

**ComboState to player_motion.vmd frames** (`initAnimations`, file L60-L144; `bL` = setBlendLegs(false)):

| ComboState | Frames | Notes |
|---|---|---|
| piercing | 1-90 | from `piercing_pl.vmd` |
| piercing_just | 34-90 | from `piercing_pl.vmd` |
| combo_a1_end2 | 21-41 | |
| combo_a1 | 1-41 | |
| combo_a2 | 100-151 | |
| combo_c | 400-488 | |
| combo_a3 | 200-306 | |
| combo_a4 | 500-608 | |
| combo_a4_ex | 800-894 | |
| combo_a5ex | 900-1061 | |
| combo_b1 | 700-787 | |
| combo_b2 … b7 | 710-787 | |
| circle_slash | 725-787 | |
| aerial_rave_a1 | 1100-1132 | bL |
| aerial_rave_a2 | 1200-1241 | bL |
| aerial_rave_a3 | 1300-1338 | bL |
| aerial_rave_b3 | 1400-1443 | bL |
| aerial_rave_b4 | 1500-1547 | bL |
| upperslash | 1600-1693 | |
| upperslash_jump | 1700-1717 | bL |
| aerial_cleave | 1800-1817 | bL |
| aerial_cleave_loop | 1812-1817 | loop flag, bL |
| aerial_cleave_landing | 1816-1886 | |
| rapid_slash, rapid_slash_quick | 2000-2073 | bL |
| rising_star | 2100-2147 | bL |
| judgement_cut | 1900-1963 | bL |
| judgement_cut_slash_air, judgement_cut_slash_just | 1923-1963 | bL |
| void_slash | 2200-2299 | bL |
| sakura_end_left | 1816-1859 | bL |
| sakura_end_right | 204-314 | bL |
| sakura_end_left_air | 1300-1328 | bL |
| sakura_end_right_air | 1200-1241 | bL |
| drive_horizontal | 400-488 | |
| drive_vertical | 1600-1693 | |
| wave_edge_vertical | 1600-1693 | |
| judgement_cut_end | 1923-1963 | |

**Timing** (`VmdAnimation`):

* `span = (end-start)·20/30` ticks.
* `tick()` (called each client tick by PlayerAnimator): `currentTick++`, `loop=false`, and stop when
  `span <= currentTick` (file L112-L123). The **loop flag is ignored**.
* When the clip stops, the pose snaps back to vanilla. There is no blend-out.
* `setupAnim(tickDelta)` (file L283-L308):

  ```
  ms = min(frames2ms(maxFrame), ticks2ms(currentTick + tickDelta))
  ms += frames2ms(start)
  updateMotion(ms)
  ```

  The clamp is to the **file's** max frame, not to `end`; the clip simply ends after `span` ticks.
* `setupAnim` runs again on **every** `get3DTransform` call (each part and each transform type). The shared
  static player is not thread-safe, but it is deterministic.

**Bone mapping** (`nameMap`, file L65-L74). The playerAnimator part name becomes the MMD bone name:

| playerAnimator part | MMD bone | Vanilla part |
|---|---|---|
| `head` | `head` | head (and hat copies it) |
| `torso` | `torso` | body ModelPart |
| `body` | `body` | root PoseStack transform |
| `leftArm` | `left arm` | leftArm |
| `rightArm` | `right arm` | rightArm |
| `leftLeg` | `left leg` | leftLeg |
| `rightLeg` | `right leg` | rightLeg |

alex.pmd bones (parsed):

```
0 全ての親 root (0,0,0)            1 body  (0,11.889,-0.032) parent 0
2 torso (0,11.889,0.041) p1        3 首 neck (0,11.791,0.296) p2       4 head (0,12.236,0.174) p1
5-7 eyes p4                         8 左肩 p2   9 left arm (2.7346,11.2858,0.0741) p1   10 左ひじ p9  11 左手首 p10
12 右肩 p2  13 right arm (-2.7346,11.2858,0.0741) p1   14 右ひじ p13  15 右手首 p14
16 下半身 p1  17 left leg (1.0438,6.2867,0.0292) p1  18 左ひざ p17  19 左足首 p18
20 right leg (-1.0438,6.2867,0.0292) p1  21 右ひざ p20  22 右足首 p21          (IK = 0)
```

All six limb bones are direct children of `body`, which matches MC, where the parts are siblings under the root
transform. Only the **local** values `bone.m_vec3Position` (the VMD translation, relative to rest) and
`bone.m_vec4Rotate` (the VMD quaternion) are used, never the accumulated matrices.

player_motion.vmd keys: body 191, torso 74, head 115, left arm 301, right arm 312, left leg 133, right leg 129.
The other bones are static.

**`get3DTransform(modelName, type, tickDelta, value0)`** (file L140-L227), with
`value0` = the current vanilla value (pivot in px or angle in rad):

```
blend = value0
if (type != POSITION && ((!blendArms && part in {leftArm,rightArm}) || (!blendLegs && part in {leftLeg,rightLeg})))
    blend = 0                       // defaults: blendArms=false, blendLegs=true
bone = alex.getBoneByName(mapped name)
POSITION:
   p = bone.m_vec3Position
   body : out = p * (1/16) * 2  + blend        // = p/8 blocks  (blend = Vec3f.ZERO from the mixin)
   other: out = (p.x, -p.y, p.z) * 2 + blend   // MMD units -> px (1 MMD = 2 px), Y flipped
ROTATION:
   (ex,ey,ez) = QuaternionToEulerZYX(q)     // R = Rz(ez)·Ry(ey)·Rx(ex), column-vector convention
      m00=1-2(yy+zz), m01=2(xy+wz), m02=2(xz-wy), m12=2(yz+wx), m22=1-2(xx+yy)
      ez=atan2(m01,m00); ey=asin(-m02); ex=atan2(m12,m22)
   body : out = ( ex, -ey, -ez) + blend
   other: out = (-ex,  ey, -ez) + blend
```

* **Why these signs.**
  * Parts live in model space, whose difference from MMD axes is only Y flipped:
    diag(1,−1,1)·Rz·Ry·Rx·diag(1,−1,1) = Rz(−z)·Ry(y)·Rx(−x).
  * The root lives in frame F, where only X is mirrored (MMD right = −X, F right = +X):
    Rz(−z)·Ry(−y)·Rx(x).
  * The **body position is not X-mirrored** (+x is kept). This looks inconsistent but should be copied as is.
* **How PlayerAnimator 1.0.2 applies this.** This is from memory of that library, which is not in the repo.
  `LayerMainBlade.setUserPose` mirrors the body part of it exactly.
  * `PlayerModel.setupAnim` HEAD: reset the part pivots to their defaults.
  * `PlayerModel.setupAnim` TAIL, while the animation is active and the render is not first person:
    `updatePart` for "head", copy hat from head, then "leftArm", "rightArm", "leftLeg", "rightLeg", "torso"
    (the body part). Each call sets `part.x/y/z` from POSITION, then `part.setRotation(x,y,z)` from ROTATION.
    Sleeves, pants and jacket are copied afterwards.
  * `PlayerRenderer.setupRotations` RETURN, while active (in frame F, before `scale(-1,-1,1)`):

    ```
    v = get3DTransform("body", POSITION, ZERO); ms.translate(v.x, v.y + 0.7, v.z)
    r = get3DTransform("body", ROTATION, ZERO)
    ms.mulPose(ZP r.z); ms.mulPose(YP r.y); ms.mulPose(XP r.x)
    ms.translate(0, -0.7, 0)
    ```

    The body pivot is 0.7 blocks above the feet. Note that this does not match the MMD body bone pivot
    (11.889 units, about 1.49 blocks).
* **ModelPart angle order.** MC 1.20 ModelPart uses `rotationZYX(zRot,yRot,xRot)`; 1.7.10 `ModelRenderer.render`
  calls glRotatef Z, then Y, then X. Same order, so the angles transfer directly to `rotateAngleX/Y/Z`.
* **Sanity values** (player_motion.vmd, converted to MC; degrees x,y,z; px offset):

  | Frame | Part | Rotation | Offset |
  |---|---|---|---|
  | 0 | right arm | z = +7.4 | |
  | 0 | left arm | z = −4.6 | |
  | 6 | right arm | (31.0, 29.3, 59.9) | (1.22, 2.23, 0.54) |
  | 6 | left arm | (19.4, 8.4, −73.6) | |
  | 6 | body (root, blocks) | y = −11.7 | (0.038, −0.1, −0.038) |
  | 6 | head | y = −28.5 | |
  | 11 | left arm | (−135, −16, −72) | |
  | 11 | body | y = −65.5 | |

### 5.3 Vanilla animations while holding a blade

* **Arms** (PlayerAnimator active): `blendArms=false`, so vanilla rotation (walk swing, held-item pose,
  attack swing, aiming) is **discarded**. Positions keep the vanilla pivot (which includes the attack-swing
  pivot shift) plus the VMD offset.
* **Legs:** vanilla walk rotation plus VMD, except clips with `setBlendLegs(false)` (aerial moves etc.), where
  the legs take the VMD rotation only.
* **Head:** vanilla look (yaw/pitch) plus the VMD offset. **Torso** (body part): vanilla plus VMD.
* **While no clip is active** (standby, walking): a fully vanilla pose, with the right arm in the "held item"
  pose (`xRot = xRot*0.5 - π/10`). The blade stays in the sheath at the left hip; it is **not** in the hand.
* **Vanilla swing:** `ItemSlashBlade.onEntitySwing` (`SB/item/ItemSlashBlade.java` L735-L738) returns true
  (cancel) unless `s.getLastActionTime() == world gameTime`. So `swing()` only starts on the tick a combo
  action fired, then runs its normal 6 ticks. It shows on the torso yRot (blended) and on the arms when no
  clip zeroes them (the non-PlayerAnimator path).
* **Use animation:** `getUseDuration` 72000, UseAnim NONE, so the vanilla "item" arm pose applies while the
  button is held (overridden by the clip on the arms).
* **Sneaking** (`SneakingMotionCanceller`, file L29-L55): on `RenderPlayerEvent.Pre`, if the main hand has a
  blade, the model is crouching, and this is not the own first-person view: set `model.crouching=false` and
  translate by `-getRenderOffset()` (cancels the 1.20 crouch y offset of −0.125). **Holding a blade suppresses
  the crouch pose visually.**

### 5.4 `setUserPose`: the blade's counter-transform (LayerMainBlade L366-L383)

This is applied in model space before the 1.5 / (1/8) / Rz180 chain.

```java
if (!UsePoseOverrider && entity instanceof AbstractClientPlayer) {          // PlayerAnimator path
   anim.setTickDelta(partialTicks);
   if (anim.isActive()) {
      v = anim.get3DTransform("body", POSITION, ZERO);   ms.translate(-v.x, v.y + 0.7, -v.z);
      r = anim.get3DTransform("body", ROTATION, ZERO);
      ms.mulPose(ZP.rotation(r.z)); ms.mulPose(YP.rotation(r.y)); ms.mulPose(XP.rotation(r.x));
      ms.translate(0, -0.7, 0);
   }
} else {
   UserPoseOverrider.invertRot(ms, entity, partialTicks);  // ms.mulPose(YP deg(rotLerp(pt, rot, rotPrev)))
}
```

Model space is frame F conjugated by diag(−1,−1,1), and conjugation flips Ry and Rx. So `invertRot` is the
exact inverse of the whole-player Y spin from `UserPoseOverrider` (see below): the blade does not spin along
with the body. The PlayerAnimator branch is an **approximate** counter of the body transform. It uses a
different pivot and sign pattern, so it is not a mathematical inverse. Intent: the blade motion (motion.vmd)
already contains the body movement, so cancel the root transform that the layer inherited. For fidelity,
reproduce it literally.

**`UserPoseOverrider`** (no PlayerAnimator; `SB/event/client/UserPoseOverrider.java` L39-L65). On
`RenderLivingEvent.Pre`, for any living entity holding an `ItemSlashBlade`:

```
f = rotLerp(pt, yBodyRotO, yBodyRot)
ms.mulPose(YP(180-f)); anotherPoseRotP(...)          // elytra/swim pitch (np=+1)
ms.mulPose(YP(rotLerp(pt, rot, rotPrev)))            // note: arguments reversed, lerps rot -> rotPrev
anotherPoseRotN(...); ms.mulPose(YN(180-f))           // undo (np=-1)
```

The angle comes from persistent data: `sb_yrot` / `sb_yrot_prev`. It is set by `setRot(entity, 72, true)`
(+72° per tick, cumulative) on ticks 8-12 of combo_a4 and 13-17 of combo_a5ex (`ComboStateRegistry.java`
L140-L144 and L188-L192): a 360° spin over 5 ticks. It is reset by the tick actions of `none`/`standby`.

---

## 6. Rendering pipeline details (`SB/client/renderer/util/BladeRenderState.java`)

### 6.1 Entry point

`renderOverrided(stack, model, target, texture, ms, buf, light, renderTypeFn, enableEffect)` (L90-L123):

1. Post `RenderOverrideEvent`. Return if it was cancelled.
2. `rt = fn(texture)`.
3. `color = ARGB(col)`; `col` defaults to opaque white.
4. `model.tessellateOnly(vc, ms, light, color, target)`.
5. If `stack.hasFoil() && enableEffect`, draw the same geometry again with `SLASHBLADE_ITEM_GLINT` (target starts
   with `item_`) or `SLASHBLADE_GLINT`.
6. Reset `Face.alphaOverride`, `uvOperator` and `col`.

The variants are:

| Method | RenderType | Glint |
|---|---|---|
| `renderOverrided` | getSlashBladeBlend | yes |
| `renderOverridedLuminous` | getSlashBladeBlendLuminous | no |
| `renderOverridedColorWrite` | getSlashBladeBlendColorWrite | yes |
| `renderOverridedLuminousDepthWrite` | | no |
| `renderOverridedReverseLuminous` | | no |
| `renderChargeEffect` | getChargeEffect | no |

### 6.2 RenderTypes (all `NEW_ENTITY` format, mode TRIANGLES, buffer 256)

| Name | Shader | Texture (blur, mipmap) | Blend | Cull | Depth write | Lightmap | Used for |
|---|---|---|---|---|---|---|---|
| `slashblade_blend_<tex>` (L133-L148) | `rendertype_item_entity_translucent_cull` | (false, true) → MAG NEAREST | TRANSLUCENT: `SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA` | default CULL (back faces) | color+depth | yes | normal parts (blade, sheath, item_*) |
| `slashblade_blend_luminous_<tex>` (L214-L230) | `rendertype_entity_translucent_emissive` | (true, true) → **LINEAR** | **additive**: `blendFuncSeparate(SRC_ALPHA, ONE, ONE, ZERO)` | **NO_CULL** | **color only** | state on, but the emissive shader ignores light → **fullbright** | `*_luminous` parts, summoned swords, drive, lock-on |
| `slashblade_blend_write_color_<tex>` (L186-L201) | `…translucent_emissive` | (false, true) | additive (same) | default CULL | color only | (fullbright) | slash effect, JC wind |
| `slashblade_blend_luminous_depth_write_<tex>` (L252-L267) | emissive | (true, true) | additive | CULL | color+depth | fullbright | (unused by blades) |
| `slashblade_blend_reverse_luminous_<tex>` (L269-L296) | emissive | (true, true) | `SRC_ALPHA, ONE, ONE, ONE` + **`GL_FUNC_REVERSE_SUBTRACT`** | CULL | color only | fullbright | judgement-cut darkening |
| `slashblade_charge_effect_<tex>_<x>_<y>` (L232-L250) | `rendertype_energy_swirl` | (false, true), texture matrix translate(x,y) | additive | NO_CULL | color only | fullbright | `effect` group when charged |
| `slashblade_glint` (L150-L184) | `rendertype_glint_translucent` | enchanted glint (entity or item), blur=true | GLINT: `SRC_COLOR, ONE, ZERO, ONE` | NO_CULL | color only | — | foil pass, depth test **EQUAL** |

* The blend type uses `createCompositeState(true)` (draws in the outline/glowing pass) with
  `affectsCrumbling=true` and `sortOnUpload=false`. The emissive types use `false, true` (sorted).
* Shader behaviour that matters for the port:
  * **item_entity_translucent_cull:**

    ```
    color = tex * vertexColor * lightmap(UV2) * diffuse
    diffuse = min(1, 0.4 + 0.6*(max(0,N·L0) + max(0,N·L1)))
    L0 = norm(0.2,1,-0.7), L1 = norm(-0.2,1,0.7)
    discard if a < 0.1
    ```

    This is **the same model and the same light vectors as 1.7.10's `RenderHelper.enableStandardItemLighting`**
    (ambient 0.4, diffuse 0.6 per light).
  * **entity_translucent_emissive:** `tex * vertexColor`, no lighting, no lightmap, `discard a < 0.1`, fog applies.
  * **energy_swirl:** `tex(uv shifted by texture matrix) * vertexColor`, no lighting.
* **Overlay** = `NO_OVERLAY`, so the blade never flashes red when hurt.
* **Charge effect UV scroll:**
  * `f = entity.tickCount + partialTicks`; `x = f*0.1 % 1`, `y = f*0.01 % 1`.
  * Texture `minecraft:textures/entity/creeper/creeper_armor.png` (it must wrap/repeat).
  * Vertex colour white (vanilla creeper charge uses 0.5 grey; here it is full white).
* **Glint:** 1.20 `setupGlintTexturing(scale)`:

  ```
  t = millis * glintSpeed(0.5) * 8
  texMatrix = translate(-(t%110000)/110000, (t%30000)/30000) · rotZ(10°) · scale(s)
  s = 0.16 (entity glint) or 8.0 (item glint)
  ```

  1.7.10 has its own glint routine; use it or approximate.
* **Draw order:** 1.20 batches by RenderType (MultiBufferSource), so the opaque-ish blend parts are flushed
  separately from the additive ones. For immediate mode, draw all normal parts first, then the luminous ones;
  per part, blade then luminous is what the code does.

### 6.3 Colour

`BladeRenderState.col` defaults to `Color.white` (ARGB FFFFFFFF). Only effects set it. The blades are always
white.

### 6.4 GUI, ground and frame (`SlashBladeTEISR.renderBlade`, L68-L131)

* **Hand contexts** (THIRD_PERSON_LEFT/RIGHT, FIRST_PERSON_LEFT/RIGHT, NONE) draw nothing directly. In the
  first-person main-arm context (`user.getMainArm()==RIGHT ? FIRST_PERSON_RIGHT_HAND : FIRST_PERSON_LEFT_HAND`)
  they call `BladeFirstPersonRender.render`. The third-person hand item is therefore invisible: the layer does
  the drawing.
* **Other contexts:** `translate(0.5,0.5,0.5)`, then:

  | Context | Transform |
  |---|---|
  | GROUND | `translate(0,0.15,0)`, `renderIcon(scale 0.005)` |
  | GUI | `renderIcon(scale 0.008, durability=true)` |
  | FIXED in a BladeStandEntity | `renderModel` (below) |
  | FIXED (item frame) | `Ry(180)`, `renderIcon(0.0095)` |
  | else | `renderIcon(0.0095)` |
* `renderIcon` (L138-L191): `scale(s)`, draw the `item_*` group and `_luminous`. With durability:

  ```
  translate(0,0,0.1)
  color = lerp((64,64,64), 0xA52C63, damage/maxDamage) per channel
  draw durability.obj "base" with that colour
  translate(0,0,-2*durability)
  draw "color" (or "color_r" if broken)     // texture model/util/durability.png
  ```

  Note `Mth.lerp(a,b,t)` is called as `lerp(aCol, bCol, durability)`, i.e. delta=aCol, which is effectively
  odd. Copy it literally if exact.
* **Item JSON display transforms** (`R/models/item/slashblade.json`; the bamboo/white/etc. variants are similar):

  | Context | Rotation | Translation | Scale |
  |---|---|---|---|
  | gui | [15,−25,−5] | [2,3,0] | 0.65 |
  | thirdperson_righthand | [45,135,0] | [3.51,11,−2] | |
  | thirdperson_lefthand | [45,135,0] | [13.51,3,5] | |
  | firstperson_righthand | [0,180,−5] | [−15,5,−11] | 1.25 |

  The hand transforms are irrelevant because nothing is drawn there, or the pose is reset.
  `BladeModel.usesBlockLight()=false`, so flat GUI lighting is used.
* **Blade stand** (`renderModel`, L256-L408):
  * `scale(0.003125)`, `translate(130,0,0)`.
  * Per stand type and pose (0 normal, 1 vFlip, 2 v+hFlip, 3 hFlip, 4 no scabbard, 5 hFlip+no scabbard):
    blade/sheath offsets, e.g. BLADESTAND_2: blade (0,21.5,0), sheath (±40,−27,0), sheath base rot −4°;
    V: (−100,230,0) with rot 80°; S: (∓60,−25,0).
  * vFlip = `Rx(180)` + `translate(0,-10,0)`. hFlip = `translate(-130,0,0)`, `Ry(180)`, `translate(130,0,0)`.
  * Then `Rz(baseRot = -3°)`.
  * `BladeStandEntityRenderer` (L49-L93): translate to the block centre (y+0.75), `Rx(xRot)`, `Ry(180-yRot)`,
    `Rz(rotation*45°)`, `scale 2`, a per-type `Rx(-90)` (or `Ry(180)` + z−0.15 for wall types), then
    `Ry(-180)` before the FIXED item render.

### 6.5 Dropped blade (`BladeItemEntityRenderer.renderBlade`, file L50-L146)

Shadow 0, no bob, no spread. `Ry(entityYaw)`, `scale = 0.00625`.

| State | Transform |
|---|---|
| in water | `translate(0,0.025,0)`, `Ry(bobOffs)`, `scale`, `Rx(90)` |
| airborne | `scale`, `Rz(-81° * (tickCount+pt))` (spinning), `translate(xOffset,0,0)` |
| on ground | `scale`, `Rz(60 + deg(bobOffs/6))`, `translate(heightOffset,0,0)` |

The offsets are (height, x): fragment (225,200), broken (100,30), normal (225,120).

Sheath (ground without NOSCABBARD, or water): `translate(0,0.025,0)`, `Ry(bobOffs)`,
`translate(0.75,0,-0.4)` if not in water, `scale`, `Rx(90)`.

### 6.6 Effect renderers (brief)

* **SummonedSword:** `ss.obj` "ss", luminous, colour = blade summon colour.
* **Drive:** `drive.obj` "base", luminous, alpha fade, scale 0.015.
* **SlashEffect:** `slash.obj` "base" with `alphaOverrideYZZ` and UV scrolling. ColorWrite and Luminous types.
* **JudgementCut:** `slashdim.obj` "base" reverse-luminous, "wind" ColorWrite, `MAX_LIGHT = 15728864`.
* **LockonCircle:** `lockon.obj` luminous, at MAX_LIGHT.

---

## 7. First-person rendering (`BladeFirstPersonRender`, file L56-L85)

Called from the TEISR in the main-arm first-person context. This is the 1.20 hand pass: the projection uses a
fixed FOV of 70, and the pose stack starts at identity in view space.

```java
if (!(FIRST_PERSON && !sleeping && !hideGui && !spectatorAlwaysFlying)) return;
if main hand not a blade: return
push
  pose.identity(); normal.identity();          // discards item display transform, equip/swing anim, view bob
  translate(0, 0, -0.5)                         // body pivot 0.5 block in front of the eye
  mulPose(ZP 180°)                              // emulate the Y-down / X-mirrored model space
  scale(1.2, 1.0, 1.0)
  mulPose(XP deg(-player.getXRot()))            // "no sync pitch": in this flipped frame it equals Rx(+pitch) in view
                                                // space, so the blade stays body-horizontal, not camera-locked
  layer.render(ms, buf, light, mc.player, 0,0, partialTicks, 0,0,0)   // full LayerMainBlade incl. standby & setUserPose
pop
```

* The whole VMD blade and sheath animation is drawn as seen from 0.5 block behind the body pivot.
* **No arms or body are drawn.** In 1.20 an item in hand suppresses the arm, and this code draws only OBJ parts.
* PSO2, NINJA and RNINJA standby blades are skipped for the own first-person view; DEFAULT and KATANA are drawn.
* The light is the hand packed light (player position).

---

## 8. 1.7.10 port recipe (derived from the above)

### 8.1 OBJ

* Forge's `net.minecraftforge.client.model.obj.WavefrontObject` parses these files. However, its `Face`
  uses the face normal and a fixed white colour, so write your own `Face` emission:

  ```java
  t.startDrawing(GL_TRIANGLES);
  per vertex: t.setNormal(vn...); t.addVertexWithUV(x, y, z, u±0.0005, v±0.0005);   // V already flipped by loader
  t.draw();
  ```

* Normalize normals after scaling: 1.7.10 `RendererLivingEntity` enables `GL_RESCALE_NORMAL`, which is enough
  because all scales are uniform except the first-person 1.2 X scale (use `GL_NORMALIZE` there).

### 8.2 Third person

Hook `RenderPlayerEvent.Specials.Post`; for mobs use your own layer or hook. The GL matrix is then model space,
matching 1.20's, except that 1.7.10 translates by `-1.5078125` instead of `-1.501`. Add `glTranslatef(0, 0.0068125f, 0)`
to be exact. Then:

```
glPushMatrix(); setUserPose(); glTranslatef(0,1.5f,0); glScalef(.125f,.125f,.125f); glRotatef(180,0,0,1);
  glPushMatrix(); glScalef(-1,1,1); glMultMatrix(hardpointA as MMD-order float[16]); glScalef(-1,1,1);
                  glScalef(1/16f,1/16f,1/16f); drawNormal(blade); drawLuminous(blade_luminous); glPopMatrix();
  glPushMatrix(); ... hardpointB ... sheath / sheath_luminous / charge effect; glPopMatrix();
glPopMatrix();
```

### 8.3 GL state per pass

1.7.10 `RendererLivingEntity` **disables GL_CULL_FACE** during entity rendering, so enable it for the normal pass.

**Normal pass:**

```
glEnable(GL_CULL_FACE); glEnable(GL_BLEND); OpenGlHelper.glBlendFunc(770,771,1,771); glDepthMask(true);
glEnable(GL_LIGHTING)   (standard item lighting is already set for entities)
lightmap = entity brightness (already set)
texture MIN/MAG = GL_NEAREST; glAlphaFunc(GL_GREATER, 0.1f); color 1,1,1,1
```

**Luminous pass:**

```
glDisable(GL_CULL_FACE); glDisable(GL_LIGHTING);
OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f)   (or disable the lightmap unit)
glEnable(GL_BLEND); OpenGlHelper.glBlendFunc(GL_SRC_ALPHA, GL_ONE, GL_ONE, GL_ZERO); glDepthMask(false);
texture MIN/MAG = GL_LINEAR; alpha test 0.1
afterwards restore depth mask, blend func, lighting, lightmap, cull
```

**Charge pass:** the luminous state plus the creeper armour texture with
`glMatrixMode(GL_TEXTURE); glLoadIdentity(); glTranslatef(f*0.1f%1, f*0.01f%1, 0)`, then restore.

### 8.4 First person

From an `IItemRenderer` (EQUIPPED_FIRST_PERSON) or a `RenderHandEvent` replacement:

```
glLoadIdentity()   // the modelview is camera-relative identity-based in EntityRenderer.renderHand
glTranslatef(0,0,-0.5f); glRotatef(180,0,0,1); glScalef(1.2f,1,1); glRotatef(-player.rotationPitch,1,0,0)
then the layer code
```

Make sure vanilla draws no arm and no item.

### 8.5 Player animation on ModelBiped

1. After vanilla `setRotationAngles` (subclass ModelBiped, or hook `RenderPlayer` via an ASM or Mixin backport),
   set the parts. For rotations:

   ```
   rotateAngleX = (-ex) + blendX;  rotateAngleY = ey + blendY;  rotateAngleZ = (-ez) + blendZ
   blend = 0 for arms (and for legs when blendLegs == false), vanilla value otherwise
   ```

2. For positions: `rotationPointX/Y/Z = vanillaPivot + (px, -py, pz)*2`. **Reset `bipedBody` and `bipedHead`
   rotation points to (0,0,0) each frame first.** 1.7.10 never resets them itself (it only touches head Y and
   the legs and arms).
3. Copy the head onto `bipedHeadwear`.
4. Apply the root "body" transform at the end of `RenderPlayer.rotateCorpse`:

   ```
   glTranslatef(bx, by+0.7f, bz); glRotatef(deg(-ez),0,0,1); glRotatef(deg(-ey),0,1,0); glRotatef(deg(ex),1,0,0); glTranslatef(0,-0.7f,0)
   (bx,by,bz) = VMD body pos / 8
   ```

5. Tick the clip on the client tick: `currentTick++`, stop at `span = (end-start)*2/3`. Sample at
   `ms = min(maxFrameMs, (currentTick+pt)*50) + start*1000/30`.
6. Suppress the crouch pose and the −0.125 sneak offset while holding a blade. In 1.7.10 `RenderPlayer.doRender`
   sets `isSneak` after the Pre event, so override it in your model or hook.

### 8.6 Blade timing

* `time = (worldTotalTime - lastActionTime + pt)*50 ms`.
* Walk `getTimeoutMS` chains (the speed only stretches the timeout), then loop or clamp within `|end-start|`
  frames, add start, `frame = ms*0.03`.
* Sample with **linear lerp plus nlerp** and identity for unkeyed bones. Multiply the chain
  center to J1 to J2 to J3 to hardpoint (row-vector `L_child·L_parent`).

---

## 9. Uncertainties

* **PlayerAnimator internals.** The library source is not in the repository. The body-transform mixin
  (`translate(x, y+0.7, z)`, Z/Y/X rotations, `translate(0,-0.7,0)`), the `updatePart` order and the
  pivot-reset behaviour are from my memory of playerAnimator 1.0.2. `LayerMainBlade.setUserPose` copies the
  same formula, which supports this, but it is not verified against the library source.
* **`setUserPose`** is not an exact inverse of the PlayerAnimator body transform (different pivot frame and
  signs), and the body position X is not mirrored in `VmdAnimation`. These look like upstream quirks; reproduce
  them literally if you want pixel parity.
* **Blade size.** By the math, the default blade is about 2.44 blocks long in third person (1/128 block per OBJ
  unit × 0.9375). I did not confirm this against a screenshot.
* **Vanilla swing.** Whether a left-click attack swing passes the `onEntitySwing` filter depends on the order of
  input processing (`lastActionTime` vs gameTime). It needs runtime checking.
* **`DataReader` size.** It depends on `InputStream.available()`, which works on Forge 1.20 resources. Read
  whole streams in a port.
