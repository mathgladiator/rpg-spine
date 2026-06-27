package mg.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

import mg.editor.ProjectSettings;

public class CompilerTests {

  @Test
  public void emitsHeaderAndStorybinAndFlagsUndeclaredEffect() throws Exception {
    File root = Files.createTempDirectory("spineproj").toFile();
    Files.writeString(new File(root, "schema.rpg").toPath(),
        "10: long score; effect 5: kill_player; effect 9: grant_gold;");
    // a clean story that compiles to a .storybin
    Files.writeString(new File(root, "ok.story").toPath(),
        "story id=ok name=Ok start=a\n"
            + "node id=a kind=beat text=hi next=b\n"
            + "effect from=a name=kill_player param=2\n"
            + "node id=b kind=outcome result=survive\n");

    ProjectSettings ps = new ProjectSettings();
    ps.outputDir = "gen";
    ps.assetsDir = "assets";
    Compiler.Result r = Compiler.run(root, ps, null);

    assertEquals("no errors: " + r.messages, 0, r.errors);
    File header = new File(root, "gen/spine.gen.h");
    assertTrue("header written", header.isFile());
    String h = Files.readString(header.toPath());
    assertTrue(h.contains("#ifndef SPINE_GEN_H"));
    assertTrue(h.contains("#define EFF_KILL_PLAYER 5"));
    assertTrue(h.contains("#define EFF_GRANT_GOLD 9"));
    assertTrue(h.contains("#define EFF_COUNT 2"));
    assertTrue(h.contains("void story_effect(SPINE *doc, uint16_t code, int param);"));
    assertTrue(new File(root, "gen/spine_effects.gen.c").isFile());
    assertTrue("storybin emitted", new File(root, "assets/stories/ok.storybin").isFile());
    assertEquals(new File(root, "gen"), r.outputDir);

    // a story invoking an effect not declared in any .rpg cannot be compiled → error
    Files.writeString(new File(root, "bad.story").toPath(),
        "story id=bad name=Bad start=a\n"
            + "node id=a kind=outcome result=survive\n"
            + "effect from=a name=ghost_effect param=0\n");
    Compiler.Result r2 = Compiler.run(root, ps, null);
    assertTrue("undeclared effect is an error", r2.errors >= 1);
    assertTrue(r2.messages.stream().anyMatch(m -> m.contains("ghost_effect")));
  }

  @Test
  public void emptyOutputDirDefaultsToOut() throws Exception {
    File root = Files.createTempDirectory("spineproj2").toFile();
    Files.writeString(new File(root, "s.rpg").toPath(), "10: int a;");
    ProjectSettings ps = new ProjectSettings();
    ps.outputDir = "";
    Compiler.Result r = Compiler.run(root, ps, null);
    assertEquals(0, r.errors);
    assertTrue(new File(root, "out/spine.gen.h").isFile());
  }
}
