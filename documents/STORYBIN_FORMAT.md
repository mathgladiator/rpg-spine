# STORYBIN — the compiled `.story` (`.storybin`)

A `.story` (authored as flat `KV`, see `mg.editor.story.Story` and
`documents/story.vm.md`) compiles to a single **`.storybin`** file that packs the
**whole story and all its images**. It is a runtime **data file**: the compiler
dumps it into the asset output directory, which is mirrored into `hero/assets/`
and read on device. The C side decodes it into a `story_t` (nodes, edges,
effects) with every image already **decoded** to a pixel buffer.

This is the SVM design from `documents/story.vm.md`, made concrete and extended
so images travel inside the file. It is also the project's **dry run for loading
images through the new `.bwa` codec**: story art is packed as a `.bwa` bank and
decoded by a reusable C bwa codec (`runtime/bwa.{h,c}`).

Everything multi-byte is **big-endian**, matching `SAVE_FORMAT.md` and
`BWA_FORMAT.md`. A trailing CRC32 guards the whole file.

## Why integers, not names

Logic — node targets, the start node, effects, results — is stored as **integers**.
Only data that must be *shown* (text) or *drawn* (images) survives as bytes.

- **Effects** are referenced by their **stable dispatch code** (`effect <code>:
  <name>;` in the schema), never by name or order. Renaming an effect keeps the
  code, so an old `.storybin` still links to the right C function through the
  generated `story_effect(doc, code, param)` dispatch. (This trades the strict
  no-reflection stance for runnable, rename-stable binaries — a deliberate call.)
- **Nodes** are referenced by index (position in the node array).
- **Strings** (node text, choice labels, outcome reason) live in one interned
  **string pool**, referenced by index.
- **Images** (beat image, outcome reward) live in one embedded **`.bwa` bank**,
  referenced by **animation index**. Each story image is a one-cell animation
  today; the bank is animation-ready for later.

`0xFFFF` is the reserved **NONE** sentinel for every `u16` index (no string, no
image, no target / terminal).

## Container

```
magic      "STB1"   4 bytes (0x53 0x54 0x42 0x31)
version    u16      = 1
start      u16      start node index (NONE if none)
bankLen    u32      byte length of the embedded image bank
bank       u8[bankLen]   a full BwAnimBank (BWA_FORMAT.md): one anim per image
strCount   u16
strings    strCount × { u16 len ; u8[len] utf8 }
nodeCount  u16
nodes      nodeCount × NODE
crc32      u32      over every byte from magic through the last node
```

The embedded bank is itself a complete `.bwa` (its own `0x42` magic, count, and
per-anim records), so the same `bwa` codec reads it here and standalone.

## NODE record

```
kind   u8     0 = beat, 1 = choice, 2 = outcome
nfx    u8     on-enter effect count
nfx × { effectCode u16 ; param i32 }      ; applied in order on enter

kind == beat:
   text   u16   string index (NONE = none)
   image  u16   bank anim index (NONE = none)
   next   u16   node index (NONE = terminal/fallthrough)

kind == choice:
   text   u16   string index
   next   u16   fallthrough node index (NONE = none)
   ndec   u8
   ndec × { label u16 (string) ; to u16 (node index, NONE = fall through to next) }

kind == outcome:
   result u8    0 = survive, 1 = die
   reason u16   string index (NONE = none)
   reward u16   bank anim index (NONE = none)
```

## Decode (C, `runtime/storybin.{h,c}`)

1. Verify `magic`, `version`, and the trailing `crc32` (reject the file on any
   mismatch — corruption fails loudly).
2. Read `start`.
3. Decode the embedded bank with the reusable bwa codec → an array of images,
   each a `width*height` pixel buffer (`0`=white, `1`=black, `2`=transparent).
4. Read the string pool.
5. Read each NODE into a `story_node` (kind, the `(code,param)` effect list, and
   the kind-specific fields, with `0xFFFF` mapped to "none").

The result is a `story_t` the story system walks: on entering a node it runs each
effect through `story_effect(doc, code, param)`, shows `story_string(text)` and
`story_image(image)`, then follows `next` / a chosen decision / returns the
outcome. The interpreter is `documents/story.vm.md` §5; this file is its on-disk
form.

## Effect dispatch (generated)

`spine_effects.gen.c` emits, from the schema's coded effects:

```c
void story_effect(SPINE *doc, uint16_t code, int param) {
  switch (code) {
    case 1: effect_give_crown(doc, param); break;   /* effect 1: give_crown; */
    case 3: effect_kill_player(doc, param); break;  /* effect 3: kill_player; */
    /* … */
    default: break;   /* unknown code: no-op, forward-compatible */
  }
}
```

A `.storybin` stores `1`/`3`; the names can change freely.

## Where it lands

The compiler writes `<assetsDir>/stories/<story.id>.storybin` (assetsDir is a
project setting, default `assets`, CLI `--assets`). That tree mirrors into
`hero/assets/` (`just sync-spine` copies it), which is the read-only filesystem
each platform exposes. The build is: edit schema/story → `just compile` (rpg-spine)
→ `just sync-spine` (hero) → run.
