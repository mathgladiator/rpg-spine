# DVM — the Dungeon VM

This is a **design document**, not a description of current behavior. Today a
`.dungeon` is a flat, line-oriented [`KV`](../src/main/java/mg/editor/KV.java)
text file the editor reads and writes (see `DUNGEON_WALLS.md`). This note designs
what it should **compile into** for the Playdate: a compact, op-code command
stream that (a) reconstructs the static map cheaply, and (b) carries the dungeon's
interactive logic — doors, hidden regions, levers, triggers — as **data plus a
tiny scripting language**, never as hand-written C.

It builds directly on two existing docs and should be read after them:

- `documents/CODEGEN.md` — the **static layer vs. mutable overlay** split, and the
  rule that all runtime indirection is resolved to integer ids at *generation*
  time so the device needs **no reflection**. The DVM is the concrete encoding of
  that idea for dungeons.
- `documents/BWA_FORMAT.md` — the `.bwa` animation bank, whose single-byte RLE/
  detail codec and per-chunk-checksum container we deliberately echo here so the C
  ingest is one familiar shape.

## Why a VM at all

A dungeon is not just geometry; it is geometry **plus rules**: "this door opens
when you hold the brass key", "this wall slides away when the lever is pulled",
"this region of floor is lava until the valve event fires". The authoring model
already has the nouns for the static half (cells, macro-fill, features, monsters,
doodads) and two *mutable* nouns — `Region` (a rectangle that repaints on a
boolean) and now `Door` (a macro-cell gate). What it lacks is a way to express
**when** those booleans flip without writing per-dungeon C.

Three forces push toward a small VM rather than more flags:

1. **RAM & patch cadence** (CODEGEN.md): the static map should be a *pageable
   file*, loaded on level-enter and freed on exit. A command stream is exactly
   that file.
2. **No reflection** (CODEGEN.md): rules must compile to integer op-codes + a
   generated dispatch `switch`, so a `(open-door vault)` in the editor becomes a
   couple of bytes on device, never a string lookup.
3. **One ingest path**: the SPINE save format is already a mutation-command list.
   Reusing that discipline for the map — a list of typed commands a small
   interpreter replays — means the C side has *one* well-understood loader shape
   for both maps and saves.

So the DVM is two cooperating command lists: a **map image** (static, replayed
once at load to build the level) and a **script** (event-driven, replayed in
response to player actions to mutate the overlay).

## The two halves, and where state lives

```
.dungeon  ──compile──►  level.dvm  =  [ MAP IMAGE ops ] + [ SCRIPT ops ]
                                          static, filed        static, filed
                              + a generated SPINE overlay fragment (mutable, saved)
```

Following CODEGEN.md precisely:

- **Map image** and **script** are *static*. They ship read-only in the `.pdx`
  bundle, are loaded on level-enter, and are freed on exit. Editing the dungeon
  re-emits them; it never touches a save file.
- The only **mutable** state is a handful of bits and counters — `region.on`,
  `door.open`, and author-named flags — emitted as ordinary SPINE fields with
  **stable field codes**. These are the *only* dungeon bytes that ever get
  written to `/Data/<bundleid>/`.

The compiler assigns each mutable cell a stable **slot id** and emits the SPINE
overlay fragment, e.g.

```c
/* generated overlay for crypt.dungeon — indices are stable across patches */
8000: private bool door_open[];     /* indexed by DOOR_* */
8100: private bool region_on[];     /* indexed by RGN_*  */
8200: private bool flag[];          /* indexed by FLAG_* (author script flags) */
```

A door's `open` bit is `door_open[DOOR_VAULT]`; a lever's memory is
`flag[FLAG_LEVER_1]`. The map image carries the *initial* values; the overlay
carries the *current* ones. On load the engine seeds the overlay from the image
the first time and from the save thereafter.

## Part 1 — encoding the map image

### Container

A little-endian binary blob, one per level (levels are paged independently):

```
magic   "DVM1"            4 bytes
flags   u8                bit0 = has-script
level   u8                level index
micro   u8 u8             width, height in micro cells (each a multiple of 5)
palette u8 count, then `count` × { rgb565 u16, kind/weight u8 }
MAP     op stream … OP_END
SCRIPT  op stream … OP_END   (only if flags.has-script)
crc32   u32               over everything above (BWA-style integrity check)
```

The palette is inlined (it is tiny and the renderer needs it immediately); the
`kind/weight` byte packs `kind` in bit 7 and `weight` (0..100) in the low 7 bits,
matching the `Material` model.

### Map op-codes

The occupancy grid is the bulk of the bytes, and it is **extremely
compressible**: dungeons are mostly solid rock with carved corridors and rooms.
We encode it with two complementary primitives plus a few placement ops. All
coordinates are micro cells written as **LEB128 varints** (1 byte for the common
< 128 case).

| op     | name         | operands                                   | meaning |
|--------|--------------|--------------------------------------------|---------|
| `0x01` | `FILL_ALL`   | idx:u8                                      | set the whole grid to one material (the base rock) |
| `0x02` | `RECT`       | x,y,w,h:varint, idx:u8                      | fill an axis-aligned rectangle |
| `0x03` | `RLE_ROW`    | y:varint, then runs … `0x00` terminator    | one row as (idx:u8, len:varint) runs |
| `0x04` | `MACROFILL`  | runs over macro cells: (code:u8, len:varint) | per-macro wall algorithm m/d/s, row-major |
| `0x10` | `FEATURE`    | type:u8, mx,my:varint, [id\|dest ref:u16]  | ladder/hole/portal/target (CODEGEN string ids) |
| `0x11` | `MONSTER`    | mid:u16, x,y:varint                         | monster placement (mid → generated MON_*) |
| `0x12` | `DOODAD`     | did:u16, x,y:varint, dir:u8                 | doodad with facing |
| `0x13` | `REGION`     | slot:u16, x,y,w,h:varint, on:u8, off:u8     | mutable rectangle; runtime bit is `region_on[slot]` |
| `0x14` | `DOOR`       | slot:u16, mx,my:varint, packed:u8, [ref:u16]| macro-cell door (see Part 3) |
| `0xFF` | `OP_END`     | —                                           | end of stream |

`FEATURE`/`MONSTER`/`DOODAD`/`DOOR` string fields (target ids, monster ids, key/
event ids) are **not strings on device** — the compiler interns them into
generated `#define`s and emits the integer index, exactly the CODEGEN.md binding
table.

### The compression: RLE + rectangle cover

The grid is emitted by choosing, **per region, whichever primitive is smallest** —
the same "pick the cheaper encoding" discipline as `Mono.reduceHalf`'s threshold
vote:

1. Emit `FILL_ALL base` where `base` is the most common index (usually solid
   rock). This alone makes a sparse dungeon nearly free.
2. **Greedy maximal-rectangle cover** of every cell that differs from the
   running reconstruction: repeatedly find the largest axis-aligned rectangle of a
   single non-base index and emit `RECT`. Big rooms and solid pillars collapse to
   one op each. This is the "rectangle region encoding" the brief calls for, and
   it composes naturally with the editor's existing `Region` rectangles and
   `Template` rooms (both are already rectangular intent).
3. Whatever is left — ragged corridor edges, diagonal-ish carving — is emitted as
   `RLE_ROW` runs for just the affected rows. Within a row, a maximal run of one
   index is `(idx, len)`; a fully-base row is skipped entirely.

The compiler keeps a virtual reconstruction as it goes and only emits ops that
*change* it, so the three passes never fight: a `RECT` that a later `RLE_ROW`
would overwrite is simply never chosen. Decoding is trivial and allocation-free:
walk the ops, apply each to the `cells` array.

`MACROFILL` is its own run-length stream because it is a *separate* small grid
(`micro/5` per axis) and is almost always uniform (all `MARCHING`).

A worked size intuition: a 40×40 micro level (1600 cells) that is 80% rock with
a dozen rooms compiles to `FILL_ALL` + ~12 `RECT`s + a few dozen short
`RLE_ROW`s — low hundreds of bytes, versus ~1.7 KB of base-36 text today.

## Part 2 — events and the script

The script is the *when*. It is a list of **rules**, each a `(when EVENT GUARD?
ACTIONS…)` triple, authored in a small **LISP-like surface language** and compiled
to a stack-machine bytecode.

### Events (triggers)

Events are an enumerated set the engine raises; a rule subscribes to one:

| event                | raised when                                   |
|----------------------|-----------------------------------------------|
| `(on-load)`          | the level finishes loading (seed initial state) |
| `(on-enter MACRO)`   | the party steps onto a macro cell             |
| `(on-interact CELL)` | the player presses A facing a cell/doodad/door |
| `(on-pickup ITEM)`   | an item enters the inventory                   |
| `(on-flag FLAG)`     | a named flag changes value                     |
| `(on-defeat MON)`    | a monster/encounter id is defeated             |

Each event carries a small, statically-typed payload (the macro coords, the
interacted slot, etc.) the guard/actions can read.

### Surface language

S-expressions, because they parse with a stack and a handful of token types — a
natural fit for this repo's existing hand-written tokenizer instincts (`mg.tokens`)
and for a generator that emits bytecode in one pass.

```lisp
; a lever (doodad "lever1") that opens the vault door and remembers it
(when (on-interact lever1)
  (do (set-flag vault-unlocked true)
      (open-door vault)
      (play "clank")))

; a hidden passage that appears once the boss is dead
(when (on-defeat gatekeeper)
  (toggle-region secret-hall true))

; a key door, expressed as sugar (see Part 3) — author rarely writes this by hand
(when (on-interact vault)
  (if (has-item brass-key) (open-door vault) (msg "It's locked.")))
```

Grammar (informal):

```
rule    := '(' 'when' event guard? action+ ')'
event   := '(' EVENTNAME symbol? ')'
guard   := '(' 'if' expr ')'        ; optional fast-reject before actions
action  := '(' VERB arg* ')'
expr    := bool | symbol | '(' OP expr* ')'
arg     := symbol | number | string | expr
```

`symbol`s are author-facing names (`vault`, `brass-key`, `lever1`); the compiler
resolves each to a generated integer id and a kind (door slot, item id, region
slot, flag slot). An unknown or mistyped symbol is a **compile error** — the
generator is the validator, mirroring CODEGEN.md.

### Script bytecode

A tiny stack VM. Values are 32-bit (bool/int/id). The op set is intentionally
small; everything dungeon-specific is a single op so the interpreter is a flat
`switch`:

| op            | stack effect                | note |
|---------------|-----------------------------|------|
| `PUSH_IMM n`  | → n                         | immediate int/bool |
| `GET_FLAG s`  | → flag[s]                   | overlay read |
| `SET_FLAG s`  | v →                         | overlay write (may raise `on-flag`) |
| `HAS_ITEM i`  | → bool                      | SPINE inventory query (generated accessor) |
| `DOOR_OPEN d` | →                           | door_open[d] = true |
| `DOOR_CLOSE d`| →                           | door_open[d] = false |
| `RGN_SET r`   | v →                         | region_on[r] = v, repaint rect |
| `NOT/AND/OR`  | … → bool                    | boolean logic for guards |
| `JZ off`      | v →                         | branch if zero (compiles `if`) |
| `MSG k`/`PLAY k` | →                        | UI string / sfx by interned id |
| `RET`         | —                           | end of rule body |

A rule compiles to: an event-table entry `(event, payload-id) → byte offset`,
then the guard as a `JZ` over the action block, then the actions, then `RET`. The
engine dispatches an event by binary-searching the table and running the body to
`RET`. No heap, no recursion limit issues (bodies are straight-line + one branch
depth for `if`).

### Why this stays reflection-free

Every `symbol` is gone by the time bytes exist. `(open-door vault)` is
`DOOR_OPEN 3`, where `3 == DOOR_VAULT` from the generated header. The *only*
runtime tables are: the event dispatch table (ints), the overlay bit arrays
(SPINE), and the interned string pool for `MSG`/`PLAY` (UI text, never logic).
This is the CODEGEN.md binding `switch` applied uniformly.

## Part 3 — doors, the worked example

Doors are the first-class reason to build the DVM, and they exercise every layer:
a static op, an overlay bit, and (for keyed/evented variants) a compiled rule.

### The authoring contract (implemented today)

A `Door` lives on a **macro cell** and is *orthogonal* to the occupancy grid — it
never repaints cells; it is a movement gate at the macro center. It is valid only
when (`Dungeon.inferDoorAxis` / `doorValid`):

- the macro cell is **entirely open floor** (a chamber), and
- the cell has **exactly two anchor points opposite each other through the
  center**: the two macro-edge neighbours along the door's `axis` are solid rock
  (the jambs), and the perpendicular pair are open (the corridor it gates).

`axis = EW` means the panel spans East–West (anchored E & W) and gates N–S travel;
`axis = NS` is the rotation. Dead ends, corners, T- and cross-junctions have no
unambiguous axis and are rejected at placement and flagged red in the editor.

Each door carries a **lock mode**: `UNLOCKED`, `KEY` (with a key item id), or
`EVENT` (with a controlling event id), plus an initial `open` bit.

### How a door compiles

1. **Static `DOOR` op** in the map image:
   `0x14 slot:u16 mx,my:varint packed:u8 [ref:u16]` where `packed` bit-packs
   `axis` (1 bit), `lock` (2 bits), and the initial `open` (1 bit); `ref` is the
   interned key/event id when `lock != UNLOCKED`.
2. **Overlay bit** `door_open[slot]`, seeded from the packed `open` on first load,
   saved thereafter.
3. **A desugared rule**, by lock mode — the door's behavior is just script the
   compiler writes for you:

```lisp
; UNLOCKED — interacting toggles it
(when (on-interact <door>)            →  DOOR table entry; body:
  (if (door-open <door>)                    GET door_open ; JZ L
      (close-door <door>)                   DOOR_CLOSE <slot> ; RET
      (open-door <door>)))            L:    DOOR_OPEN  <slot> ; RET

; KEY — opens only while the key is held; never re-locks
(when (on-interact <door>)
  (if (has-item <key>) (open-door <door>) (msg "Locked.")))
                                      →     HAS_ITEM <item> ; JZ L
                                            DOOR_OPEN <slot> ; RET
                                      L:    MSG <"Locked."> ; RET

; EVENT — the door ignores interaction; only the named event drives it.
; The lever/boss/trap that owns <event> emits (open-door <door>) in its own rule.
(when (on-interact <door>) (msg "It won't budge."))
```

So the three lock modes are not three code paths in C — they are three
*compilations* of the same `DOOR_OPEN`/`DOOR_CLOSE` ops against the same overlay
bit. The C engine has exactly one door concept: a gate whose `door_open[slot]`
the script mutates and the movement code consults.

### Doors and regions are the same shape

A `Region` toggle (hidden doorway) and a `Door` are the two mutable-map nouns, and
they unify cleanly: both reserve an overlay slot, both have an `(on-…)`/`(…-door)`
op, both are seeded from the map image and saved through SPINE. A region mutates
*occupancy* (repaint a rect, the ray caster sees new walls); a door mutates a
*gate* (no occupancy change, the mover sees a closed cell). The DVM treats them as
two ops over the same overlay machinery, which is why adding doors needed no new
runtime *kind* — only a new op and a new slot array.

## Part 4 — runtime model on Playdate (C)

```
level_load(idx):
  blob = file_read(bundle, "lvl<idx>.dvm"); crc_check(blob)
  apply_map(blob.map → cells[], macroFill[], features[], doors[], regions[])
  overlay = save_has(idx) ? save_load(idx) : seed_from_map(blob)
  apply_overlay(overlay → door_open[], region_on[], flag[])   ; repaint regions
  raise(on-load)

on player action:
  raise(on-enter mx,my) / raise(on-interact slot) / raise(on-pickup item) …
    → dispatch table → run bytecode to RET → mutates overlay → repaint if needed

movement test at macro (mx,my) crossing edge e:
  if door at (mx,my) and !door_open[slot] and e ⟂ axis: blocked
  else: normal wall test (DUNGEON_WALLS.md)

level_exit(): save_store(idx, overlay); free(blob)
```

The interpreter, the DDA wall test (DUNGEON_WALLS.md), and the door gate are each
a small flat function; the heavy data (geometry) is paged; the tiny data (overlay)
is the save. Nothing here needs reflection, a heap allocator in the hot path, or a
recompile to patch content.

## Status & staging

- **Implemented now:** the *authoring* model — `Region` (existing) and `Door`
  (new: axis inference + the two-anchor validity contract + lock modes + KV
  round-trip + editor tool/inspector). This is the front end the DVM compiles.
- **Not yet built:** the `.dvm` emitter, the script surface parser/bytecode
  compiler, the generated SPINE overlay fragment, and the C interpreter. Treat
  every byte layout and op-code value above as a *proposal* to be pinned down when
  codegen starts (CODEGEN.md staging: bake first, flip to filed `.dvm` when RAM or
  patch cadence bites).

### Open questions

- **Op-code stability vs. patches.** Like SPINE field codes, map slot ids
  (`DOOR_*`, `RGN_*`, `FLAG_*`) must be *stable across content patches* so old
  saves keep meaning. The symbol table (README Milestone 2, also unbuilt) should
  own these allocations, not the per-compile order.
- **Event granularity.** `on-enter` per macro vs. per micro; whether
  `on-interact` should fan out to doodads automatically or require an explicit
  rule. Lean explicit (the project's standing "everything explicit" rule).
- **Script size budget.** Whether to cap rules/bytecode per level so the
  interpreter's tables stay in a fixed arena; probably yes, with a `log()`-style
  loud diagnostic when a dungeon overflows it rather than a silent truncation.

Keep this file in lockstep with the `Door`/`Region` model in
`mg.editor.dungeon.Dungeon`, `DUNGEON_WALLS.md`, and (once it exists) the codegen
backend.
