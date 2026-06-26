# CODEGEN: baking world maps into code vs. shipping them as files

This note works through how the `.world` (and `.dungeon`) editors should turn
their documents into something the game consumes on **Playdate**, and how that
choice interacts with the one hard requirement of this project: **the save file
is the SPINE document, and we want to synchronize game state to it meaningfully
without inventing a runtime reflection system.**

The short version of the recommendation is at the bottom. The reasoning matters
more than the verdict, because the verdict shifts as the game grows.

> **Status (front end started).** `mg.codegen.Compiler` is the work-in-progress
> code generator: it resolves the project-relative output directory
> (`ProjectSettings.outputDir`), parses the `.rpg` schema, validates content, and
> emits a `spine.gen.h` skeleton — the authoritative field/effect inventory plus
> `EFF_*` ids. It runs from **Build ▸ Compile…** (`CompilerWindow`, a streaming
> status screen). The load/save and dungeon/story VM **bodies are still TODO**; this
> document is the design they implement.

## What the device actually constrains

Playdate is small but not _that_ small, and the limits point in a clear
direction:

- **CPU:** 168 MHz Cortex-M7. Plenty for grid/graph traversal; nothing here is
  compute-bound. Irrelevant to this decision.
- **RAM:** 16 MB, and the game image runs *from* RAM — `const` data is **not**
  free the way it would be on an execute-in-place microcontroller. Anything you
  bake in is resident for the whole session unless you structure it so it can be
  dropped. This is the constraint that actually bites.
- **Storage:** the game ships as a read-only `.pdx` bundle in flash. Files in
  the bundle are readable at runtime via the file API but **not writable**.
- **Save data:** writable state lives in a *separate* datastore
  (`/Data/<bundleid>/…`), reached through `playdate->file`. This is the only
  place a save file can go, and it is exactly where the SPINE blob belongs.
- **No reflection, no dlopen for C:** C is compiled into the binary. There is no
  runtime type information, no field-name lookup, no dynamic dispatch you didn't
  generate yourself. Lua can load at runtime, but the spine is a C-first design
  and we should not make Lua load-bearing for the data model.

The takeaway: the device does **not** meaningfully limit the *expressiveness* of
either approach. What it limits is **how much can stay resident at once**, and it
forbids the one thing (reflection) that would make a naive "just save the whole
world" design easy. Both constraints push toward the same architecture, so this
is less of a dilemma than it first looks.

## The decision is really two decisions

The mistake is treating "the world map" as one thing. Every map has two layers
with opposite lifecycles:

1. **The static layer** — geometry, textures, wall/door topology, where towns
   and dungeon entrances sit, the canonical placement of scene objects. This is
   *authored* and only changes when **we** ship a content patch. The player never
   mutates it.

2. **The mutable overlay** — what the *player* has done to the world: which
   locations are discovered, which paths are unlocked, which scene objects have
   been revealed/looted/triggered, which doors are now open. This is small, and
   it is the *only* part that ever needs to enter a save file.

Once you separate these, "bake vs. file" stops being one question:

- The **static layer** is a publishing/RAM question → *bake or file?*
- The **mutable overlay** is the SPINE save question → it is **always** SPINE
  fields, addressed by stable field codes, never baked and never a side file.

Keeping these from bleeding into each other is what avoids the reflection trap.
You never serialize "the world"; you serialize a handful of flags and counters
that the static world is interpreted *against*.

## Option A — bake maps into C

The editor emits `.c`/`.h` with `static const` tables:

```c
/* generated from overworld.world — DO NOT EDIT */
typedef struct { uint16_t x, y; uint8_t type; uint16_t name_id; } world_loc;
static const world_loc OVERWORLD_LOCS[] = {
  { 120,  80, LOC_TOWN,  STR_BRIAR },
  { 300, 200, LOC_CAVE,  STR_DARK_CAVE },
};
#define LOC_BRIAR     0   /* stable index → used by the overlay */
#define LOC_DARK_CAVE 1
```

**Pros**
- **The C compiler is your validator.** This is the README's stated principle:
  if codegen emits something inconsistent, the build breaks. Indices, enum
  values, and string ids are all checked at compile time. No loader can desync
  from the schema because there is no loader.
- Zero parse/load code, zero load-time failure modes, instant access.
- Trivial to cross-reference other generated symbols (string tables, sprite ids).

**Cons**
- **Resident forever.** Every baked map sits in the 16 MB budget for the whole
  session. A large overworld plus a dozen 20×20 dungeon levels with per-cell
  specials adds up; you cannot page it out because it is part of the program
  image. This is the real ceiling.
- **Every content edit is a recompile + reflash.** Patching a typo in a town
  name, or shipping a content update, means rebuilding the binary. The README
  explicitly anticipates "patches for both bugs and content updates" — baking
  makes content patches as heavy as code patches.
- Slow author iteration: edit map → regenerate → compile → deploy, even to tweak
  a single wall.

## Option B — ship maps as data files, load at runtime

The editor emits a compact binary blob per area into the bundle; C loads it on
entry and frees it on exit.

**Pros**
- **Bounded RAM.** Load the area you are in, free it when you leave. This is how
  you fit an arbitrarily large world into 16 MB. For a game that wants many
  dungeon levels, this is decisive.
- **Content patches are data patches.** Update a `.pdx` data file without
  touching the binary. Faster author loop too: no recompile to test a map.
- Naturally mirrors the SPINE save loader you are already building — a read-only
  cousin of `load(byte* data, int size)`.

**Cons**
- **You lose the compiler as validator** *unless you reintroduce it on purpose.*
  A hand-written loader can drift from the data format and fail at runtime
  instead of build time — exactly the failure mode this project is trying to
  design out.
- Load-time error surface: missing file, truncated blob, version skew.
- A little more code (a loader + a validator).

## The hybrid (recommended): typed boundary, file payload, SPINE overlay

You do not have to trade type-safety for RAM. Make codegen emit **three**
artifacts from one `.world`/`.dungeon` document, so each layer lands where it
belongs:

1. **A generated header (`.h`)** — the struct layout, the area's stable ids as
   `#define`/enum, format version, and counts. The game's hand-written C
   `#include`s this. *This* is where the compiler keeps you honest: the ids the
   gameplay code references are generated constants, so a renamed/removed
   location is a build error, even though the bulk data is a file.

2. **A binary data file in the bundle** — the static layer, laid out to match
   the header exactly. Loaded on area-enter, freed on area-exit. A generated
   `validate(blob)` checks magic, version, and counts against the header so any
   desync is caught at load with a clear message rather than corrupting memory.
   Reuse the SPINE command-list discipline so C ingest is one well-understood
   path.

3. **A SPINE schema fragment** — the mutable overlay, as ordinary spine fields
   with **stable field codes**, e.g.

   ```c
   /* generated overlay for overworld.world */
   7000: private bool   loc_discovered[];   /* indexed by LOC_* from the header */
   7100: private bool   path_unlocked[];     /* indexed by EDGE_* */
   7200: private u8      obj_state[];          /* per scene object: hidden/revealed/looted */
   ```

   These live in the save document like everything else, survive renames via the
   field-code mechanism, and are the *only* world bytes that ever get written.

### How variable binding works without reflection

The editor's `reveal=` / `blocked=` strings (`flag:found_cave`,
`flag:bridge_built`, …) are **resolved at generation time**, not at runtime.
Codegen walks the bindings, allocates/looks up the SPINE field for each, and
emits a static dispatch — no name lookup on device:

```c
/* generated: binding id -> concrete SPINE accessor */
bool spine_binding_get(SPINE* s, uint16_t binding_id) {
  switch (binding_id) {
    case BIND_FOUND_CAVE:    return gen_spine_flag_found_cave_get(s);
    case BIND_BRIDGE_BUILT:  return gen_spine_flag_bridge_built_get(s);
  }
  return false;
}
```

A location's `reveal` becomes a single `binding_id` stored in the static blob;
revealing it is `spine_binding_get(s, loc.reveal_binding)`. The string only ever
exists in the editor and the generator. On device there are no strings, no maps,
no reflection — just an integer and a generated `switch`. This is the crux: the
binding indirection that *feels* like it needs reflection is collapsed to a
compile-time table.

### What this buys for save synchronization

- The save blob is tiny and stable: a few bit-arrays/counters keyed by field
  code. No geometry, no textures, no names — those are static and reconstructed
  from the bundle on load.
- "Sync game state to the save file" = serialize the overlay fields through the
  existing SPINE mutation-command path. Nothing world-specific is invented.
- A content patch that adds locations appends new ids/fields; existing field
  codes are untouched, so **old saves stay valid** (the milestone-1 promise).
  Removing content leaves a dead field code reserved — never reused — and the
  symbol table flags it.

## So — does Playdate limit us here?

Not in the way the question fears. The device does not stop us from baking, and
it does not stop us from loading files. What it does is:

- make **RAM residency** the deciding factor for the *static* layer (→ prefer
  files once the world outgrows a single resident image), and
- forbid **reflection**, which would only have been needed if we tried to save
  "the whole world" as a dynamic object graph (→ don't; save only the overlay).

Both nudges land on the same design. The simplicity you want is real and
reachable, but it comes from the **split**, not from picking one storage mode:
type-checked ids at the boundary, a pageable file for the bulk, and a small
stable SPINE overlay for everything the player can change. Reflection never
enters the picture because binding resolution is a generation-time concern, not
a runtime one.

### Pragmatic staging

1. **Now, while the world is small:** bake. One `const` table per area is the
   least code and gives you the compiler-as-validator immediately. Keep the
   static/overlay split in the *schema* from day one even while baking, so the
   save format never has to change later.
2. **When RAM or patch cadence starts to hurt:** flip the static layer from
   `const` table to bundle file + generated loader. The header and the SPINE
   overlay do not change, so gameplay code and existing saves are unaffected —
   only the codegen backend swaps. Designing for that swap now is the cheap
   insurance.
