package mg.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

import mg.editor.ProjectSettings;

public class CompilerTests {

  @Test
  public void emitsHeaderInRelativeOutputDirAndFlagsUndeclaredEffect() throws Exception {
    File root = Files.createTempDirectory("spineproj").toFile();
    Files.writeString(new File(root, "schema.rpg").toPath(),
        "10: long score; effect kill_player; effect grant_gold;");
    Files.writeString(new File(root, "tale.story").toPath(),
        "story id=tale name=Tale start=a\n"
            + "node id=a kind=beat text=hi next=b\n"
            + "effect from=a name=kill_player param=0\n"
            + "effect from=a name=ghost_effect param=0\n"
            + "node id=b kind=outcome result=survive\n");

    ProjectSettings ps = new ProjectSettings();
    ps.outputDir = "gen";
    Compiler.Result r = Compiler.run(root, ps, null);

    assertEquals("no errors: " + r.messages, 0, r.errors);
    File header = new File(root, "gen/spine.gen.h");
    assertTrue("header written", header.isFile());
    String h = Files.readString(header.toPath());
    assertTrue(h.contains("#ifndef SPINE_GEN_H"));
    assertTrue(h.contains("#define EFF_KILL_PLAYER 0"));
    assertTrue(h.contains("#define EFF_GRANT_GOLD 1"));
    assertTrue(h.contains("#define EFF_COUNT 2"));

    // a story node invoking an effect not declared in any .rpg → a warning, not an error
    assertTrue(r.warnings >= 1);
    assertTrue(r.messages.stream().anyMatch(m -> m.contains("ghost_effect")));

    assertEquals(new File(root, "gen"), r.outputDir);
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
