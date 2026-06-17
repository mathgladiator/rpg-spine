rpg-spine is a toolkit for building a single player RPG. The baseline functionality is to be able to define the save state of the game in a precise way, and the primary code generation is for C since the RPG being built will run on play.date which is an extremely limited device. As such, the goal of rpg-spine is to build the data model as the spine of the game as a giant global document.

## Status

What exists today:

- A **lossless tokenizer** and a **recursive-descent parser** for `.rpg` schema
  files (`mg.tokens` + `mg.tree`): root-level fields and `struct` blocks.
- The **JavaFX visual editor** suite described below (`mg.editor`).

What is still **design, not code** — the milestones and codegen notes below are
the roadmap, not current behavior:

- C code generation, the binary save / mutation-command file format, and the
  symbol table are **not implemented yet**. Running the CLI without an editor
  flag just parses the input and prints a debug dump of structs and fields.
- The `private` keyword and field bodies (`{ filter = ... }`) parse but have no
  generator behavior; a field body currently raises `NOT YET IMPLEMENTED`.

The schema syntax the parser accepts today is
`<code>: [private|public] <type>[] <name>;` (the `[]` is postfix on the type,
e.g. `1000: Character[] party;`) plus `struct <Name> { ... }`. Field-code
uniqueness is validated live in the `.rpg` editor rather than in the core parser.

## Building and running

Java 17 and Maven; a [`justfile`](justfile) wraps the common commands:

```
just build      # mvn package, then move the fat jar to ./spine.jar
just demo       # build, then parse demo/schema.rpg and print a debug dump
just edit       # build, then launch the editor over demo/
mvn test        # run the JUnit test suite
```

`spine.jar` is a self-contained fat jar (main class `mg.Main`) that bundles
JavaFX natives for every desktop platform, so the same artifact runs on Windows,
Linux and macOS. CLI flags: `--input`/`-i` (parse a schema), `--editor`/`-e`
(launch the editor over a directory), and the not-yet-wired `--output`/`-o` and
`--symbols`/`-s`.

## Milestone 1: Defining the schema for serializing game state

At core, we want to be able to have a flexible file format that can grow during development but also have a compact representation that is easy for C to ingest, validate, and apply. We avoid JSON because field names may change for prettiness, and we have to consider long term upgrades capability as the game will have patches for both bugs and content updates. In defining a schema, we start with root level document globals like this:

```C
100: int party_xp;
```

The 100 is a field code that is used for serialization, so this means that if we rename party_xp to team_xp for some autistic reason, the save data is still valid. In C, the following structure is generated along with a core library to do the bulk of the work.
```C
struct SPINE {
   int party_xp;
};

SPINE* load(byte* data, int size);
byte* save(SPINE* spine, int* size);
```
The entire game has access to the entire SPINE definition, so everything is available as a code generated document object model inspired data structure. As such, this primes us for an opportunity to provide some more code generation. As a principle, we want to fully leverage the C compiler and type checker as a productivity tool for ourselves (and the future AI agent). This means if the output causes an error in C, then something bad happened and it requires debugging.

To keep the file format simple, we will ensure all field codes are unique across the root document and all structures. The file format is a mutation command list. For example, an integer is persisted a the command LOAD_INT16 (0x05), followed by the field code, followed by the value. We will use a variety of codes to encode the length of values and each command is ultimately a finite bundle for manipulating the document. Since the field codes are unique across the root document and all structures, we can exploit this to build structural registers to manipulate. For example, suppose we have

```C
struct Character {
  100: byte profile;
  110: int xp;
  120: int hp;
  121: int mp;
}
1000: Character characters[];
```
When we load characters, we will need a SIZE8 instruction which has args for the field (i.e 1000 in this case) and the number of characters to create. This will both create the array with appropriate size, and the temporary space will have a pointer to the first element. From here, we either have a NEXT instruction to move to the next element.

For now, we don't consider recursion as a property of the data model, but we can add this fairly straightforward with a stack.

For development purposes, the toolkit will read and write a symbol table such that if there is a detected changes that will break the save file, it will be alerted.
## Milestone 2: Private/Public/Accessors/Clamping/Filtering

We are going to focus on XP for a tiny second. When the XP of the player goes up, then there is a level check required to see if the player levels up and then advance the level as needed. Since C is very basic, we don't really have a notion of public or private. However, because we have the field code, we can both enforce good behavior and be flexible. When we define something like this using the new private keyword (let public be the default since most things are not as reactive, I suspect)

```C
1000: private int xp;
```
This will generate the structure field
```C
struct SPINE {
  int xp_65f12e;
}
```
The 65f12e is a hash of the input source file, and this means that any hard coded reference to xp_65f12e will break. This requires the generator to create these methods:

```C
int gen_spine_xp_get(SPINE* spine);
int gen_spine_xp_set(SPINE* spine, int value);
```
At this point, this is some needless ceremony, but we now go deeper into the definition and have the filter construct.
```C
1000: private int xp {
  filter = core_rule_xp_table;
}
```
The filter will effectively generate the set function ala:
```C
int gen_spine_xp_set(SPINE* spine, int value) {
  int new_value = value;
  new_value = core_rule_xp_table(spine, new_value);
  spine->xp_65f12e = new_value;
  return new_value;
}
```

And the generator will define the function prototype of core_rule_xp_table as well, and this will force the linker to throw an error if the user doesn't define the function somewhere. This provides the developer a way to hook into updates within the document. We will support multiple filters as well which are executed in the order defined. This filter capability provides the C developer a handy way of enforcing data validity.

This is a straight forward filter, but it lacks context, so we have a context-filter that is available for structures only and passes the owning structure into the filter. This changes the signature of the method, so sharing between filter and context-filter will create problems

## The visual editor

The toolkit ships a JavaFX editor for authoring game content. Launch it over a
content directory:

```
java -jar spine.jar --editor demo
# or
just edit
```

The directory must exist; the editor refuses to start otherwise. `spine.jar` is
also a self-contained, double-clickable application: launching it with no
arguments (e.g. double-clicking on Windows, which requires a JRE with the `.jar`
file association) auto-opens your **most recent project**, and only prompts for a
folder when there is no recent project. The jar bundles JavaFX natives for
Windows, Linux and macOS, so the same artifact runs on any of them regardless of
which OS built it.

The **Project** menu manages folders:

- **Open Folder…** — pick a content folder to edit (it becomes the most recent).
- **Recent Projects** — re-opens a previous folder; the list is ordered
  most-recent-first and persisted to a per-user config location
  (`%APPDATA%\rpg-spine` on Windows, `~/Library/Application Support/rpg-spine`
  on macOS, `$XDG_CONFIG_HOME`/`~/.config/rpg-spine` on Linux).
- **Close** — quits the application.

The **Settings** menu holds **Grok API Key…** (⌘/Ctrl-,), where you enter the
xAI **Grok** API key (AI assistance, plus model) and the **PixelLab** API key
(pixel-art image generation). Keys are masked by default with a **Show** toggle
and persisted to `settings.properties` in the same per-user config directory as
the recent-projects list — owner-only readable where the OS supports POSIX
permissions. Get keys at https://console.x.ai and https://www.pixellab.ai/account.

### Asset pipeline

All images are **1-bit black-and-white PNGs** for the Playdate display, enforced
by `mg.assets.Mono`, which also implements the documented half/quarter resize
algorithm. `mg.assets.PixelLab` integrates the PixelLab.ai pixel-art API
(pixflux text→image, bitforge style, rotate, and text/skeleton animation). See
[documents/ASSET_PIPELINE.md](documents/ASSET_PIPELINE.md) for the
black-and-white rule, the monster/item generation workflows, the item
usage-animation spec, and the exact resize algorithm.
[documents/AI.GEN.md](documents/AI.GEN.md) surveys the image-generation backends
and why PixelLab was chosen.

The **Audit** menu's **Project Assets…** report lists **lost** images
(referenced but missing — open the owning file) and **orphan** images (on disk
but unreferenced — delete them all). Files with errors (a lost reference or a
parse failure) — and folders containing them — are shown **red** in the tree, and
missing image references appear red with a `*` in the editors.

In the editor:

- **`.png`** files open a **black-and-white image editor**: pencil / eraser /
  flood-fill, import-and-convert (threshold or Bayer dither), ½ / ¼ reduce, and
  an **animation stepper** that treats the image as a horizontal strip of cells
  so you can step or play frames to check alignment.
- The **monster editor** builds art from explicit files (no per-frame
  generation or editing — you select images and build sequences):
  - **References** — full-colour `.ref.png` images (stored in a `ref/` subfolder)
    that you **generate** from a prompt with PixelLab, or derive by **styling**,
    **rotating**, or **animating** an existing reference. From a reference you
    **extract a region**, resample it to a target resolution, and convert it to
    black-and-white — choosing among many algorithms (threshold, ordered Bayer,
    clustered halftone, Floyd–Steinberg and other error-diffusion kernels, or a
    marching-squares contour) with a live preview — letting you work in high
    fidelity and then sample down. Extracted 1-bit frames become files
    you select into animations.
  - **Battle** — a stance image, a damaged-stance image, and an attack animation.
  - **Dungeon** — idle and walk, each in four viewer orientations (towards /
    away / left / right), every one an animation.
- The **item editor** has three icon-size slots (you select an image for each
  project icon size) and a **usage animation** with frame speed and loop count.
- Every **animation** is an explicit, reorderable list of frame files with a
  frame speed (and loop where relevant) and a play/visualize preview.
- Image selections use a **project-scoped picker** rooted at the file's folder,
  so paths stay relative and inside the project.

Reference generation needs a PixelLab API key (Settings); references download as
full-colour PNG, and the region-extract step converts to 1-bit.

The B&W image editor also offers **Trim white/black** (crop a uniform margin),
**Canvas…** (resize the canvas without scaling), **Resize…** / **→ icon** /
**→ cell** (scale, the latter two to the `.project` sizes), and **Crop region…**
(a drag-to-select popup). All transforms are pure-Java and in-process (no
external tools), so the editor behaves identically on every platform.

**Project tree** entries can be **renamed**, **cloned**, and **deleted** via
right-click. A
**Project Settings…** dialog edits the `.project` file (item icon size, animation
cell size). **View ▸ Log** opens a window with the last 1000 actions and
exceptions, and any file that fails to load shows a "failed to load" panel with
the error in place.

The left pane is a file tree; selecting a file opens the editor matching its
extension:

- **`.rpg`** — a text editor for spine schema, validated live by this project's
  own parser. Parse errors are reported with line/column, and field-code
  collisions across the root and every struct (the milestone-1 uniqueness rule)
  are flagged before the C codegen ever sees them.
- **`.dungeon`** — a Wizardry/Bard's-Tale-style grid map editor. Each cell has a
  floor and wall texture and a state (open / closed-rock / hole / ladder), with
  per-edge walls, doors, locked doors, secret doors, grates and archways. Drop
  special tiles (spinners, teleporters, chutes, anti-magic zones, fountains,
  darkness), wire fixed/random encounters to a shared bestiary and encounter
  tables, and stack multiple levels connected by stairs and holes.
- **`.world`** — a pan/zoom graph + scene editor. Place location nodes (town,
  city, dungeon, …) with metadata, connect them with paths that bend through
  waypoints, and scatter scene objects that are revealed over time. Build an
  **image palette** (Add Image…) and paint those images onto the map with the
  **Place Image** tool (placing reverts to Select). **Draw Boundary** lays down
  boundary polylines for the map. In Select, **drag a box or shift-click** to
  multi-select objects and set common properties (kind / label / reveal) on all
  at once. Reveal/blocked conditions are plain variable-binding strings collected
  in a side panel, ready for the codegen to resolve against the SPINE document.
- **`.monster`** — a monster definition with two art sets (the 2D JRPG battle
  view and the first-person 3D dungeon view), each with idle / walk / fighting
  stance / attack animations (see [documents/ASSETS.md](documents/ASSETS.md)).
  Its design lives in two editable tables: a per-level stat table with
  configurable columns (HP, MP, ATK, …; add/remove both rows and columns) and a
  skills table.
- **`.item`** — an item with a name, icon, gold value and description, plus
  **type-specific properties**: a weapon exposes attack/speed/range, armor an
  armor value and slot, a consumable heal/restore, and so on. Changing the type
  swaps the property fields to match.

The `.dungeon`, `.world`, `.monster` and `.item` editors all use a flat,
diff-friendly `key=value` text format so they version well and are trivial for
the eventual C codegen to ingest.

The toolbar's **New File** / **New Folder** create content relative to the folder
(or file's folder) currently selected in the tree, or the project root if nothing
is selected. Newly created folders appear in the tree immediately, including empty
ones.

**Reorganize by dragging:** drag any file or folder in the tree onto a folder (or
onto a file, meaning that file's folder) to move it there on disk; drop on the
empty space below the tree to move it to the project root. Moves that would
collide with an existing name, or drop a folder into its own descendant, are
refused. If you move the file you're currently editing, the editor reopens it
from its new location.

See [documents/CODEGEN.md](documents/CODEGEN.md) for how these documents should
be turned into Playdate-friendly code/data — and why the world splits into a
static layer (bake or file) and a small mutable overlay that lives in the save
document, with variable bindings resolved at generation time so the device never
needs reflection.