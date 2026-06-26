# CLAUDE.md

Guidance for working in this repository. Read `README.md` for the product vision
and the milestone roadmap; this file is the engineering map.

## What this project is

`rpg-spine` is a toolkit for authoring a single-player RPG whose entire game
state lives in one global "spine" document. The end goal is to **generate C**
that loads/saves that document on a Playdate (168 MHz Cortex-M7, 16 MB RAM,
1-bit display), using a compact mutation-command file format and stable integer
**field codes** so save files survive field renames and content patches.

There are two pillars, both in Java:

1. **The spine schema language** (`mg.tokens` + `mg.tree`) — a lossless
   tokenizer and a recursive-descent parser for `.rpg` schema files.
2. **A JavaFX visual editor** (`mg.editor`) — authors `.rpg`, `.dungeon`,
   `.world`, `.monster`, and `.item` content over a project folder.

### Implementation status (important)

- **Implemented:** the tokenizer, the parser (root fields + structs), and the
  full JavaFX editor suite.
- **NOT yet implemented:** the C code generation, the binary save/file format,
  the symbol table, and the `private`/`filter`/clamping features. `README.md`
  Milestones 1–2 and `documents/CODEGEN.md` describe the *intended* design, not
  current behavior. `mg.Main` with no `--output` just parses and prints a debug
  dump of structs/fields. Field bodies (`code: type name { ... }`) currently
  throw `NOT YET IMPLEMENTED` in the parser.

Treat the design docs as the north star, but don't assume codegen exists when
reasoning about the code.

## Build, run, test

Java 17, Maven. A `justfile` wraps the common commands:

```sh
just build      # mvn package -DskipTests, then mv the fat jar to ./spine.jar
just demo       # build, then: java -jar spine.jar --input demo/schema.rpg
just edit       # build, then launch the JavaFX editor over demo/
mvn test        # JUnit 4 tests (surefire workingDirectory = project basedir)
```

The fat jar (`maven-assembly-plugin`, `jar-with-dependencies`) bundles JavaFX
natives for **all** desktop platforms (win / linux / mac / mac-aarch64) so the
single `spine.jar` is double-clickable on Windows and runnable on Linux/macOS.
Main class: `mg.Main`.

### CLI surface (`mg.Main`)

- no args → launch the editor on the most recent project (or prompt for one).
- `--input` / `-i <file>` → parse and merge a `.rpg` schema into the `Root`.
- `--output` / `-o <file>` → (placeholder; codegen not implemented).
- `--symbols` / `-s <file>` → (placeholder; symbol table not implemented).
- `--editor` / `-e <dir>` → launch the editor over an existing directory.

## Architecture

### `mg.tokens` — lossless lexer

- `TokenReaderStateMachine` consumes Unicode **codepoints one at a time** and
  emits `Token`s via a callback. Classification uses boolean lookup arrays in
  `Tables` keyed by codepoint; `ScannerState` tracks the in-progress token.
- `TokenEngine` wraps the state machine and provides a buffered token stream
  with arbitrary **lookahead** (`peek(n)`), `pop`, and conditional pops
  (`popIf`, `popNextAdjSymbolPairIf` — used to merge adjacent symbols like `[]`).
- **Lossless / round-trippable:** whitespace and comments are "hidden"
  (`MajorTokenType.hidden`) and attached to adjacent semantic tokens as
  `nonSemanticTokensPrior` / `nonSemanticTokensAfter`, never discarded.
- `MajorTokenType` (Comment, Identifier, NumberLiteral, StringLiteral, Symbol,
  Whitespace) + `MinorTokenType` (block/EOL comment, int/double) classify tokens.
- `DocumentPosition` aggregates line/char/byte spans; `SpineLangException`
  carries a `DocumentPosition` for error reporting.

### `mg.tree` — parser and AST

- `Parser` is recursive descent over a `TokenEngine`, merging results into a
  shared `Root`. Entry points: `Parser.merge_string(root, name, src)` and
  `merge_tokens(root, tokens)`.
- `Root` holds top-level `Field`s and `Struct`s; a `Struct` holds `Field`s.
- **Actual field syntax** (what the parser accepts today):
  ```
  <code>: [private|public] <type>[] <name>;
  struct <Name> { <fields...> }
  ```
  e.g. `10: long score;`, `1000: Character[] party;`. Note the `[]` is **postfix
  on the type** (`Character[] party`), which differs from the milestone-1 README
  sketch (`Character characters[]`). `private`/`public` parse but carry no codegen
  behavior yet.
- **Field-code uniqueness is NOT enforced in the parser.** It is checked as a
  live diagnostic in `RpgEditor` (`checkCode` over root + every struct). Keep that
  in mind: a raw `--input` parse will happily accept duplicate codes.

### `mg.editor` — JavaFX editor

Launch flow: `Main` → `EditorLauncher` (sets a static `ROOT`, calls
`Application.launch`) → `EditorApp` (menu bar + file tree + center pane).
`EditorApp.makeEditor(File)` **dispatches by file extension**:

| Extension  | Editor            | Model (`mg.editor.*`)        |
|------------|-------------------|------------------------------|
| `.rpg`     | `RpgEditor`       | parsed by `mg.tree.Parser`   |
| `.dungeon` | `DungeonEditor`   | `dungeon.Dungeon`            |
| `.template`| `TemplateEditor`  | `dungeon.Template`           |
| `.world`   | `WorldEditor`     | `world.World`                |
| `.monster` | `MonsterEditor`   | `monster.Monster`            |
| `.item`    | `ItemEditor`      | `item.Item`                  |
| (other)    | `PlainTextEditor` | —                            |

- `Editor` is the interface (`getNode`, `save`, `isDirty`, `title`).
- `RpgEditor` extends `PlainTextEditor`; it debounces input (~250ms), runs this
  project's own parser, and shows parse + field-code-collision diagnostics.
- `RecentProjects` persists up to 10 recent folders to a per-OS config dir
  (`%APPDATA%\rpg-spine` / `~/Library/Application Support/rpg-spine` /
  `$XDG_CONFIG_HOME`|`~/.config`/`rpg-spine`).
- `Settings` persists app settings (the xAI **Grok** key/model and the
  **PixelLab** key) to `settings.properties` in that same config dir, owner-only
  on POSIX. Edited via the **Settings ▸ Grok API Key…** dialog in `EditorApp`.

### `mg.assets` — black-and-white asset pipeline

- `Mono` is the single chokepoint enforcing the project-wide B&W rule: indexed
  images on a 3-entry palette {white=0, black=1, **transparent=2**} (a real PNG
  alpha entry; the editor shows it green). State via `state`/`setState`
  (`WHITE`/`BLACK`/`TRANSPARENT`); `isBlack`/`set` for the B&W case;
  `setTransparent`, `backgroundMask` (edge-scan exterior). Provides 1-bit
  conversion (`toMonoThreshold`,
  `toMonoDither` via 4×4 Bayer), `region` extraction, file IO (`load`/`savePng`),
  and the **documented resize algorithm**: `reduceHalf(img, threshold)` is a 2×2
  box vote (`PRESERVE_INK`=1 / `MAJORITY`=2 / `PRESERVE_PAPER`=3) and
  `reduceQuarter` = two halvings. Tested in `MonoTests`.
- `PixelLab` wraps the PixelLab.ai REST API (`https://api.pixellab.ai/v1`, bearer
  auth, **synchronous**, base64 PNG in/out): `pixflux` (text→image), `bitforge`
  (text+style ref), `rotate` (re-facing), `animateWithText` and
  `animateWithSkeleton` (→ frame lists). Independent of the editor (key from
  `Settings`). Meshy was removed.
- Image transforms are **pure Java / in-process** (no external binary). A prior
  ImageMagick (`convert`) integration was removed for cross-platform reasons;
  richer filters should be added on `BufferedImage` via Java2D as real use
  demands. `tool-png-to-pbm.sh` remains a standalone shell helper for PBM export.
- `Mono` also has `trim(trimBlack)` (crop uniform margin), `resizeCanvas(w,h,
  centered)` (no scaling), and `scaleTo(w,h)` (area-vote arbitrary scale), all
  tested in `MonoTests`.
- Design + workflows (monster directional walks, item icon/usage animation, the
  resize algorithm) live in `documents/ASSET_PIPELINE.md`.

### Asset editor UI (`mg.editor`)

- `BwCanvas` — reusable zoomable 1-bit paint surface (pencil/erase/fill, invert,
  clear) rendered pixel-by-pixel onto a JavaFX `Canvas` (no javafx-swing dep);
  `BwCanvas.toFxImage` converts a `Mono` image to a JavaFX image for previews.
- The B&W editor supports a third pixel state, **transparent** (rendered green):
  a Transparent paint tool, and the extract dialog's alpha/background controls
  produce it. Saved PNGs carry real transparency.
- `BwImageEditor implements Editor` — toolbars (Undo, paint tools, zoom,
  invert/clear, import via threshold/dither, ½/¼ reduce, `Trim white`/`Trim black`,
  `Canvas…`, `Resize…`, `Crop region…`, `→ icon`/`→ cell`) + an **animation
  stepper** (frames/fps, step/play) slicing the image as a horizontal strip.
  `BwCanvas` keeps a 5-deep **undo** buffer (each transform and each paint stroke
  is one step; Ctrl/Cmd-Z or the Undo button rolls back).
  Wired to `.png` in `EditorApp.makeEditor`; **renders via one scaled
  `drawImage`** with a fit-to-view zoom (so large generated PNGs load — the prior
  per-pixel/zoom-8 approach hung). New/empty files prompt for a size.
- `RegionSelectDialog` — drag-to-select a sub-rect of an image (returns image-px
  coords); used by Crop region.
- `Log` + `LogWindow` — process-wide ring buffer of the last 1000 actions/
  exceptions (uncaught handler installed in `EditorApp.start`); **View ▸ Log**
  shows it live. Load failures show an in-pane "failed to load" panel.
- `ProjectSettings` — `.project` (KV) in the project root: `icon_size`,
  `icon_med`, `icon_small`, `anim_cell_w/h`. Loaded on `openProject`; edited via
  **Settings ▸ Project Settings…**; read by the image editor (`→ icon`/`→ cell`)
  and item editor (three icon-size slots).
- Tree right-click **context menu**: Open / Rename… / Clone / Delete
  (`EditorApp.installContextMenu`). Cancelling a dirty-discard keeps the prior
  file selected (`suppressSelection`).
- `ImagePicker` — project-scoped image chooser rooted at the editing file's
  folder (can't navigate above it), so every image path is relative and inside
  the project. Used by all image selections.
- Asset building blocks: `asset.Animation` (explicit frame-file list + fps +
  loop) with `AnimationPanel` (add via ImagePicker, reorder, play/visualize).
  `ReferencePanel` manages full-colour `.ref.png` references (prompt-new,
  prompt-onto, add, remove). `ReferenceExtractDialog` selects a region of a
  reference, resamples (Java2D bilinear) to a target size, and converts to 1-bit
  (any `Dither.Algo`) with a **live** preview (dropdowns update immediately;
  numeric fields commit on focus-loss/Enter) that also shows the encoded `.bwa`
  size vs the PNG — the high-fidelity→B&W bridge. The crop region is editable by
  mouse (a **Select** mode drags a new rect, a **Move** mode drags the existing
  one) and numerically (X/Y/W/H spinners), with buttons **Whole / Center /
  Half & center / Square / Expand +1 / Shrink −1 / Crop black** (snap to the
  opaque-dark content bbox). Last-used settings persist in `Monster.extract`
  (`asset.ExtractSettings`) and reseed the next extract.
- `Dither` (`mg.assets`) — pure-Java B&W conversions: threshold; ordered Bayer
  2/4/8 + clustered halftone; error diffusion (Floyd–Steinberg, Atkinson, JJN,
  Stucki, Burkes, Sierra, Sierra Lite, serpentine); a marching-squares
  contour; and **outline** (`OUTLINE`) — marching squares on the opaque
  *silhouette* (white/black both count as inside, transparent as outside) thickened
  to a Chebyshev `distance` (the threshold), drawn black on a transparent
  background. `apply(src, Algo, threshold)` plus a 4-arg form taking an `AlphaMode`
  (WHITE/BLACK/TRANSPARENT) for transparent source pixels; tested in `DitherTests`. The reference
  extract dialog and image-editor Import expose the full `Algo` list.
- `PromptDialog.ask(...)` — a roomy multi-line prompt input used by the reference
  generation actions.
- `PixelLabGen` — async PixelLab generation with a progress window (pixflux /
  bitforge / rotate / animate / animateSkeleton), writing full-colour PNG references.
- `mg.assets.Skeleton` generates a bipedal walk-cycle in PixelLab's
  `skeleton_keypoints` format (18 labels; front vs profile; `templateNormalized`
  / `toPixelJson`). `SkeletonAnimateDialog` (ReferencePanel **Skeleton…**) maps
  towards/away/left/right → south/north/west/east at `side` view, lets you
  **drag joints** per frame, and generates walk frames into `ref/`. The posed
  skeleton is cached in `Monster.skeletons` (`asset.SkeletonData`, normalized,
  keyed by reference path) and persisted in the `.monster`.
  `PixelLab.animateWithSkeleton` sends `inpainting_images`/`mask_images`
  null-arrays matching the frame count (required, else "Expected N pose images").
  `PixelLab.estimateSkeleton` (`/estimate-skeleton`) detects joints from an image
  (one rest pose; the dialog auto-detects normalized-vs-pixel coords). The flow:
  **Estimate from image** → **Animate walk** (`Skeleton.walkFromRest` swings the
  detected limbs into a walk cycle, no hand-posing).
  `PixelLabGen.scaledPng` rescales the reference to the request `image_size`
  before animate/skeleton calls (PixelLab requires reference==image_size; a 128px
  ref vs 64px animate-with-text caused a tensor-size 500). The fully no-skeleton
  path is **Animate…** = `animate-with-text` (reference + action verb → frames)
  via `AnimateTextDialog` with a **follow-reference** strength
  (`image_guidance_scale`, default ~6; PixelLab's own 1.4 ignores the reference).
  Extracted B&W frames default into an `ext/` subfolder.
- `ReferencePanel` references are stored in a `ref/` subfolder next to the
  document; actions (wrapped in a FlowPane so none clip): Generate / Style onto /
  Rotate / Animate / Skeleton (PixelLab), Extract → B&W, Add existing, Remove.
  Generate/Style use `GeneratePromptDialog` (prompt + output **size**, default
  128, clamped to the endpoint max). Selection-dependent actions warn if no
  reference is selected.
- `AssetAudit.run(root)` finds **lost** (referenced-but-missing) and **orphan**
  (on-disk-unreferenced) images and the set of **errored** files (parse failure /
  lost ref), using `imageRefs()` on each model. `AuditDialog` (Audit ▸ Project
  Assets…) reports them with Delete-orphans and Open-owner. The tree paints
  errored files (and their ancestor folders) red; missing image refs show red
  with a `*` in editor lists/slots. `PromptDialog` is the multi-line prompt.
- `MonsterEditor` (no Gen/Edit on frames — select files + build sequences):
  References panel; Battle = `battleStance`/`battleDamage` images + `battleAttack`
  animation; Dungeon = `dungeonIdle`/`dungeonWalk` maps, each with four
  orientations (towards/away/left/right) as animations. Serialized via `ref`,
  `battle`, and `anim role=…` lines.
- `ItemEditor` — three icon-size slots (`Item.icons`, select-only) + a `usage`
  animation (`AnimationPanel`, loop shown). No gen/edit/resize buttons.
- `WorldEditor` — no background image (`World.mapImage` removed). Adds an image
  **palette** (`World.palette`) painted via the `PLACE_IMAGE` tool onto
  `SceneObject.image` (placing reverts to SELECT); a `DRAW_BOUNDARY` tool that
  builds `World.Boundary` polylines (Finish/Cancel); and rubber-band / shift-click
  **multi-select** of objects (`multiSel`) with a common-property apply panel.
  `WorldModelTests` covers palette/image/boundary round-trip.

Model round-trips are covered by `MonsterModelTests`; `MonoTests` covers the
image core. The JavaFX UI compiles and the jar assembles, but the interactive
editors and live Meshy calls have not been smoke-tested in a running app.
- `KV` is the shared serialization helper: line-oriented `verb key=value
  key="quoted"` with `\"`/`\\` escapes. All four binary-document editors
  (`.dungeon`/`.world`/`.monster`/`.item`) use this flat, diff-friendly text
  format so files version well and are trivial for the eventual C ingest.

## Conventions

- Java 17, 2-space indentation, package root `mg`.
- AST/token classes favor `public final` fields over getters; the lexer/parser
  prefer explicit state machines over regex.
- Errors in the language layer are surfaced as `SpineLangException` with a
  `DocumentPosition`, not generic exceptions.
- Tests are JUnit 4 under `src/test/java/mg/...`, mirroring the main packages
  (`tokens`, `tree`). `LanguageBaseTest` is the shared base. Surefire runs with
  the working directory set to the project root, so test fixtures resolve
  relative to the repo.
- `spine.jar` is committed at the repo root and regenerated by `just build`.

## Docs map

- `README.md` — vision + milestone roadmap + editor user guide.
- `documents/CODEGEN.md` — bake-vs-file analysis for Playdate map data and the
  recommended hybrid (typed header + bundle file + small SPINE overlay).
- `documents/ASSETS.md` — `.monster` art requirements (two views, four
  animation states, 1-bit sheet format).
- `documents/ASSET_PIPELINE.md` — the B&W rule, `Mono`, Meshy workflows, resize
  algorithm, `.project` settings, and the in-editor transform tools.
- `documents/BWA_FORMAT.md` — the `.bwa` single-file animation bank (magic `0x42`,
  per-animation header + checksum) and its single-byte RLE/detail pixel codec
  (`mg.assets.BwAnimBank` / `AnimType` / `BwCodec`). The B&W image editor shows the
  encoded size next to the PNG size in its status bar.
- `documents/DUNGEON_WALLS.md` — the redesigned `.dungeon` model: a micro/macro
  occupancy grid (walls implicit) whose wall algorithm is chosen **per macro cell**
  (`Level.macroFill`) — `MARCHING` (original per-cell rounded blob), `DIAGONAL`
  (dual-grid marching squares that infers diagonal lines), or `SQUARES` (on/off,
  weight ignored) — all in `dungeon.WallRenderer` (shared with the C ray caster;
  **no colour blending** — DIAGONAL takes the majority material per contour cell).
  The editor adds a brush (shape + size), a
  line tool with live preview, and stampable `Template`s (`dungeon.Template`) —
  **macro-sized rooms** (built-ins + project `.template` files) previewed under the
  cursor and **stamped aligned to the macro grid**. Features
  (ladder/hole/portal/**target**) anchor to macro centers; ladders/holes/portals
  reference a named `TARGET` **by id**, not coordinates. Monsters (size 1–5) sit on
  micro cells and are validated by the asset audit against project `.monster` ids.
  **Regions** (`dungeon.Region`) are named rectangles with on/off material indices
  and a default-off boolean; toggling repaints the rect (hidden doorways) and the
  boolean is meant to be flipped at runtime in C. A micro cell may also hold up to
  three **doodads** (`dungeon.Doodad`, `{id, dir}`); placing one infers the facing
  from the first open neighbour clockwise from north (`Dungeon.inferDir`).
- `documents/AI.GEN.md` — options analysis for the image-gen backend (Meshy ≈
  Google nano-banana; PixelLab/Retro Diffusion for native sprites; FLUX/Gemini
  direct), and the recommended `ImageGen` provider abstraction.
- `demo/` — sample content: `schema.rpg`, `level1.dungeon`, `overworld.world`,
  plus `.monster`/`.item` examples.
