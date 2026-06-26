# SVM — the Story VM and the `.story` editor

A **design document** for a new content type. A `.story` is a **node-graph** the
player walks through: a sequence of beats — a full-screen image with a line of
text, or a passage of text with **decisions** — where each decision can apply
**side effects** (enumerated as SPINE mutations) and/or **navigate** to another
node. It is the narrative counterpart to the dungeon, and it is governed by the
**same rule**: you enter once, you cannot back out, and a story ends in **survival
or death** — there is no re-entry.

Stories are how a cleared dungeon pays off (`documents/level.design.theory.md`):
the dungeon is the question, the `.story` is the answer. They are authored in the
visual editor as a graph, serialized as flat [`KV`](../src/main/java/mg/editor/KV.java)
text like every other document, and compiled to a compact binary the Playdate
replays through a tiny interpreter — exactly the shape of the dungeon VM
(`documents/design.dvm.md`), which this doc deliberately parallels.

Read `documents/design.dvm.md` first; the static/overlay split, the no-reflection
binding-id discipline (`documents/CODEGEN.md`), and the container/codec style are
shared, and this doc leans on them rather than re-deriving them.

## 1. What a story is

A directed graph of **nodes** connected by **edges**. Three node kinds cover the
brief:

- **`beat`** — a single presentation: an optional full-screen image plus a line (or
  short passage) of text, and a single forward edge (`Continue`). This is "a bit of
  story, a full-screen image with a line of text" *and* the plain text-only beat.
- **`choice`** — a prompt plus **N decisions**. Each decision just **navigates** to a
  target node — it carries no effects of its own. This is "a bit of text and some
  decisions."
- **`outcome`** — a **terminal** node: the run ends here in `survive` or `die`,
  optionally applying on-enter effects and naming a reward image. Survive/die is the
  whole-story analogue of the dungeon's cleared/died.

Every node — beat, choice, or outcome — may carry **on-enter effects** (§2). A
decision does not carry effects; instead it routes to a node, and that node's
on-enter effects do the work. This keeps the model uniform ("everything happens
on-enter") and lets several effects on one node **compose in order**.

One node is the **start**. Edges may loop (a "you examine each object" hub), but the
graph is *forward-committed*: there is no "exit to menu" node, mirroring the dungeon
— the only ways out are `survive` and `die`.

```
        ┌──────┐  Continue   ┌────────┐  "Take the crown"   ┌──────────────┐
 start ▶│ beat │────────────▶│ choice │────────────────────▶│ beat         │▶ outcome(die)
        └──────┘             └────────┘  "Leave it"          │ ⚡give_crown │   ⚡kill_player
                                  └──────────────────────────└──────────────┘▶ outcome(survive)
                                                                                ⚡resolve_vault
```

## 2. On-enter effects — named, declared in `.rpg`

A node may carry a list of **on-enter effects**: bounded mutations of game state,
applied in order when the node is entered. Because the device has no reflection
(`CODEGEN.md`), an effect is **not** an ad-hoc string — it is a **name registered in
a `.rpg` schema** plus an integer parameter:

```
effect kill_player;     // in a .rpg schema — registers the name
effect grant_gold;
```

Each declared effect maps, at codegen, to a single C function over the whole
document plus the editor-supplied parameter:

```c
void effect_kill_player(spine_t* doc, int param);
void effect_grant_gold (spine_t* doc, int param);   // e.g. param = amount
```

In a story, an effect invocation is `{name, param}` with **param defaulting to 0**.
The editor offers the effect *name* as a dropdown of everything declared across the
project's `.rpg` files (parsed via `mg.tree.Parser` → `Root.effects`), so a story can
only invoke effects the schema actually defines — the generator stays the validator,
and the full set of save mutations a story can perform is statically enumerable.
Composition is just a list: `[give_crown(1), mark_greedy(0)]` runs both. An effect
that zeroes HP (e.g. `kill_player`) pushes the run toward a `die` outcome — that is
how a story can *kill you*, honoring the survive-or-die rule.

This deliberately supersedes an earlier sketch of an ad-hoc `flag:`/`item:`/`stat:`
mini-language: a named-effect-with-a-C-body is simpler, type-safe, and matches how
the rest of the project resolves bindings at generation time.

## 3. KV authoring format

Flat, line-oriented, diff-friendly — like `.dungeon`/`.world`/`.monster`/`.item`.
A `story` header, one `node` line per node, then `effect` lines (the node's on-enter
effects) and — for a choice node — `choice` lines, each attached to its node by
`from=` (the same pattern as `region`/`door` lines hanging off a `level`).

```
story id=vault name="The Vault's Price" start=open
node id=open kind=beat text="The seal cracks. Gold light spills from the vault." next=pedestal pos=30,338
node id=pedestal kind=choice text="A crown rests on a pedestal; a corpse clutches a warning." next=leave pos=245,399
choice from=pedestal text="Take the crown" to=worn
choice from=pedestal text="Read the warning first" to=warned
choice from=pedestal text="Leave it and go"
node id=worn kind=beat text="It is warm. Too warm." next=dead pos=469,181
effect from=worn name=give_crown param=1
effect from=worn name=mark_greedy param=0
node id=dead kind=outcome result=die reason="the crown" pos=715,145
effect from=dead name=kill_player param=0
node id=leave kind=outcome result=survive reason="you kept your head" pos=753,457
effect from=leave name=resolve_vault param=0
```

Attributes (all optional unless noted):

- `node` — `id` (req), `kind` ∈ {beat, choice, outcome} (req), `text`, `image`
  (beat, full-screen — **400×240 and black & white**, validated in the editor),
  `next` (beat/choice → target id), `pos` (editor layout, ignored by the VM), and
  for `outcome`: `result` ∈ {survive, die} (req), `reason`, `reward` (a 400×240 B&W
  epilogue image).
- `effect` — `from` (its node id, req), `name` (a `.rpg`-declared effect, req),
  `param` (int, default 0). Multiple per node, applied in order.
- `choice` — `from` (its choice node id, req), `text` (the option label, req), `to`
  (target node id). A choice with no `to` falls through to the node's own
  `next`/outcome (a "flavor" pick).

`fx` values use the §2 grammar; `KV.q` handles quoting/escapes as everywhere else.
Image paths are project-relative (via `ImagePicker`, like every other asset ref) so
the asset audit (`AssetAudit`) can find lost/orphan story art too.

## 4. The `.story` editor — a graph editor

A new `EditorApp.makeEditor` dispatch on `.story`, model `mg.editor.story.Story`,
editor `StoryEditor` (extends `Editor`). Its center pane is a **node-graph canvas**,
the one genuinely new UI in the suite:

- **Nodes** are draggable boxes positioned on a pannable/zoomable canvas (drawn on a
  JavaFX `Canvas`, consistent with `BwCanvas`/`DungeonEditor` rather than adding a
  graph library). A `beat` node shows a thumbnail of its image (reuse
  `BwCanvas.toFxImage`/a scaled draw) + its text; a `choice` shows its prompt and a
  port per decision; an `outcome` is color-coded survive/die.
- **Edges** are drawn from a node's output port (a beat's `Continue`, each choice's
  decision, an outcome has none) to a target node; drag-from-port-to-node creates
  the link, matching the `WorldEditor`'s place/connect interaction grammar.
- **Inspector** (right pane, like the others): per selected node — kind, text
  (multi-line via `PromptDialog`), image (`ImagePicker`), and for `choice` a list of
  decisions each with a label, a target dropdown (the node id list), and an **effects
  editor**. For `outcome`: result + reason + reward.
- **Effects editor** — a small typed list builder: pick a kind (flag/item/stat/set),
  then autocomplete the name against the project's SPINE schema (`.rpg`) fields and
  the item/monster ids the project already discovers. This is where "enumeration in
  the spine format" becomes a real, validated dropdown rather than free text.
- **Node positions** are editor metadata; persist them as an unobtrusive `pos`
  attribute on each `node` line (`pos="x,y"`) so the graph reopens laid out, without
  affecting the compiled story (the VM ignores layout).
- **Live lint**, like `RpgEditor`'s diagnostics: unreachable nodes, edges to missing
  ids, a `choice` with no decisions, a graph with no reachable `outcome`, and an
  `fx` naming an unknown flag/item/field — all surfaced inline (and red in the tree
  via the existing errored-file mechanism).

The model round-trips through KV (a `StoryModelTests` parallel to
`DungeonModelTests`), and `imageRefs()` feeds the asset audit.

## 5. Binary `.svm` and the Story VM

### Container

Mirrors the dungeon `.dvm` (and `BWA_FORMAT.md`) — a little-endian blob with a
checksum:

```
magic    "SVM1"          4 bytes
start    u16             start node index
strings  u16 count, then count × (u16 len, utf8 bytes)   ; interned text + image-asset ids
nodes    u16 count, then per node a NODE record
effects  op stream pool (referenced by offset from nodes/choices)
crc32    u32             over everything above
```

Text and image references live in the **string pool** (text must be *displayed*, so
unlike pure logic it survives to the device as data, exactly like the DVM's
`MSG`/`PLAY` pool). Everything that is *logic* — targets, effects, results — is an
integer.

### Node record

```
kind     u8              0=beat 1=choice 2=outcome
nfx      u8              count of on-enter effects, then nfx × { effect_id:u16  param:i32 }
─ beat:    text:u16(str)  image:u16(str,0=none)  next:u16(node, 0xFFFF=fallthrough)
─ choice:  text:u16(str)  n:u8 decisions, each { label:u16(str)  to:u16(node) }
─ outcome: result:u8 (0=survive 1=die)  reason:u16(str)  reward:u16(str,0=none)
```

### Effects

There is no per-effect bytecode — an effect *is* a generated C function. The node's
on-enter list is a sequence of `(effect_id, param)`; the device runs each through a
single generated dispatch, the CODEGEN.md binding `switch`:

```c
void story_effect(spine_t* doc, uint16_t effect_id, int32_t param) {
  switch (effect_id) {
    case EFF_KILL_PLAYER: effect_kill_player(doc, param); break;
    case EFF_GRANT_GOLD:  effect_grant_gold (doc, param); break;
    /* … one case per `effect <name>;` declared in the .rpg schemas … */
  }
}
```

`effect_id` is interned at compile time from the effect name; the C bodies are
authored once and are the *only* place a story can mutate save state. After a node's
on-enter effects run, the engine runs the **death check** (HP≤0 ⇒ jump to an
implicit `die`), so an effect like `kill_player` ends the run without every author
wiring an edge to a death node by hand.

### Interpreter

```
story_run(svm, spine):
  crc_check(svm)
  n = svm.start
  loop:
    for (id,param) in node[n].onEnter: story_effect(spine, id, param)
    if dead: return DIED
    switch node[n].kind:
      beat:    show_image(node.image); show_text(node.text); wait_continue()
               n = node.next  (or terminal if fallthrough)
      choice:  show_text(node.text)
               d = wait_choice(node.decisions)          ; player picks (navigation only)
               n = (d.to == NONE) ? node.next : d.to
      outcome: show reward if any
               return node.result == SURVIVE ? SURVIVED : DIED
```

Terminal, deterministic, allocation-free in the hot loop. The two return states —
`SURVIVED` / `DIED` — are the story's whole contract; there is no third door, by
design.

## 6. How dungeon and story hand off

The integration is one edge in each direction, both already anticipated by the
dungeon VM:

- **Clear → story.** A dungeon's clear condition (`future.dungeon.ideas.md` #16: the
  boss lock-in sets the `cleared` terminal bit) carries a **reward story id**. On
  `cleared`, the engine runs that `.story`. The dungeon's `Dungeon` model gains a
  `reward` field (a `.story` id), authored via a picker — the dungeon's *answer*.
- **Lore → story.** A `lore` doodad (#19) or a riddle clue (#8) runs a `.story` id
  on `on-interact` — short stories embedded mid-run.
- **Story → state.** A story's effects write SPINE flags/items/stats; a story can
  *kill* the party (`die` ⇒ the run ends as surely as a dungeon death) or grant the
  progression that unlocks the next dungeon. The save document is the single shared
  truth both VMs mutate.

Because both compile to the same op-stream-over-SPINE-overlay shape, the C engine
has **one interpreter pattern** for two content types, and **one save model** for
both — the project's recurring discipline of many editors, one ingest.

## 7. Robustness & regression safety

Identical guarantees to the dungeon VM (`level.design.theory.md` §8), because the
structure is the same:

- **Round-trip + golden files** for `compile(.story) → .svm → decode` and the
  byte/op-dump snapshot.
- **Reachability lint** (build error): every node reachable from `start`; every path
  reaches an `outcome`; no edge to a missing id; no `choice` with zero decisions.
- **Effect enumeration** is the safety: the full set of save mutations a story can
  perform is statically listable, so the SPINE overlay it touches is known at
  compile time and stays patch-stable via field codes.
- **Headless simulation harness**: feed a decision path (a list of choice indices)
  into the interpreter and assert the terminal state + resulting SPINE overlay —
  "choosing *Take the crown* then *wear it* ends in `DIED` and consumes the crown"
  is a unit test, the same harness shape as the dungeon's scripted-playthrough test.

## 8. Status & staging

- **Built (steps 1–2):** `mg.editor.story.Story` — the node model (`beat`/`choice`/
  `outcome`, decisions with `fx`, editor `pos` metadata), KV serialize/load with
  round-trip + lint tests (`StoryModelTests`), `imageRefs()`, `reachable()`, and a
  `lint()` graph checker (bad start, dangling edges, empty choice, unreachable node,
  no reachable ending). `StoryEditor` — a node-graph canvas (add/move/connect/delete
  tools, beat/choice/outcome boxes, directed edges with decision labels), a per-node
  inspector (kind, start-node, text, image/reward via `ImagePicker`, decisions =
  label + target), and a live diagnostics panel — wired into `EditorApp` on `.story`
  and into `AssetAudit` (image refs + lint → errored-file flag). A `demo/vault.story`
  exercises it.
- **On-enter effects (named, from `.rpg`):** the `effect <name>;` grammar is parsed
  by `mg.tree.Parser` into `Root.effects` (test `ParserTests.test_effects`); a node's
  `onEnter` is a list of `{name, param}` (`Story.Effect`). The editor offers each
  effect as a **dropdown of names discovered across the project's `.rpg` schemas**
  (+ a Rescan button) with an int param spinner, and decisions no longer carry
  effects — composition lives on the target node.
- **Image validation:** beat/reward images report their size and B&W status against
  **400×240, pure black & white** in the inspector (red when off-spec).
- **Not built yet (steps 3–5):** the dungeon `reward` story-id link; the binary
  `.svm` emitter + simulation harness; the device interpreter. Byte layouts/op values
  above are still *proposals* to pin when codegen starts.
- **Shared imaging (done):** `ReferencePanel` is now generic (an `Action` set; null
  skeleton/extract allowed) and mounted in the story editor (and item editor) over a
  `references` list, so the artist can generate → extract a 400×240 B&W full-screen
  image here too. References live in the document's `<base>.ref/` folder and extracted
  frames in `<base>.ext/` (`RefLayout`); creating files fires `ProjectRefresh`.
- **C codegen front end (started):** `mg.codegen.Compiler` resolves the project's
  `output_dir`, parses the schema, validates content (warns on a story effect not
  declared in any `.rpg`), and emits a `spine.gen.h` inventory skeleton; `CompilerWindow`
  (Build ▸ Compile…) shows status. The `.svm`/load-save/VM bodies are the next step.
- **Build order remainder:** (3) dungeon `reward` story-id link; (4) `.svm` codec +
  simulation harness; (5) device interpreter (the C bodies behind the header skeleton).

> Keep this file in lockstep with `documents/design.dvm.md` (shared container/codec/
> overlay discipline), `documents/level.design.theory.md` (the survive/die/no-re-entry
> rule it implements), and — once it exists — the `mg.editor.story` model.
