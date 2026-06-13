rpg-spine is a toolkit for building a single player RPG. The baseline functionality is to be able to define the save state of the game in a precise way, and the primary code generation is for C since the RPG being built will run on play.date which is an extremely limited device. As such, the goal of rpg-spine is to build the data model as the spine of the game as a giant global document.

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
- **Close** — quits the application. The left pane
is a file tree; selecting a file opens the editor matching its extension:

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
  waypoints, and scatter scene objects that are revealed over time. Reveal/blocked
  conditions are plain variable-binding strings collected in a side panel, ready
  for the codegen to resolve against the SPINE document.

Both `.dungeon` and `.world` use a flat, diff-friendly `key=value` text format so
they version well and are trivial for the eventual C codegen to ingest.

See [documents/CODEGEN.md](documents/CODEGEN.md) for how these documents should
be turned into Playdate-friendly code/data — and why the world splits into a
static layer (bake or file) and a small mutable overlay that lives in the save
document, with variable bindings resolved at generation time so the device never
needs reflection.