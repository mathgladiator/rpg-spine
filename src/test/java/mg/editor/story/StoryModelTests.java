package mg.editor.story;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

public class StoryModelTests {

  @Test
  public void blankIsValidAndHasReachableEnding() {
    Story s = Story.blank("The Vault's Price");
    assertEquals("the-vault-s-price", s.id);
    assertEquals("start", s.start);
    assertEquals(2, s.nodes.size());
    assertTrue("blank story lints clean: " + s.lint(), s.lint().isEmpty());
    assertTrue(s.reachable().contains("end"));
  }

  @Test
  public void fullRoundTrip() throws Exception {
    File f = File.createTempFile("test", ".story");
    f.deleteOnExit();

    Story s = new Story();
    s.id = "vault";
    s.name = "The Vault";
    s.start = "n1";
    s.references.add("vault.ref/backdrop.ref.png");

    Story.Node n1 = new Story.Node();
    n1.id = "n1";
    n1.kind = Story.Kind.BEAT;
    n1.image = "art/vault.png";
    n1.text = "The seal cracks.";
    n1.next = "n2";
    n1.onEnter.add(new Story.Effect("announce", 0));
    n1.x = 100;
    n1.y = 50;
    s.nodes.add(n1);

    Story.Node n2 = new Story.Node();
    n2.id = "n2";
    n2.kind = Story.Kind.CHOICE;
    n2.text = "A crown rests on a pedestal.";
    n2.next = "survive";
    Story.Choice take = new Story.Choice();
    take.text = "Take the crown";
    take.to = "die";
    Story.Choice leave = new Story.Choice();
    leave.text = "Leave it";
    leave.to = ""; // falls through to n2.next
    n2.choices.add(take);
    n2.choices.add(leave);
    s.nodes.add(n2);

    Story.Node die = new Story.Node();
    die.id = "die";
    die.kind = Story.Kind.OUTCOME;
    die.result = Story.Result.DIE;
    die.reason = "the crown";
    die.onEnter.add(new Story.Effect("kill_player", 0));
    die.onEnter.add(new Story.Effect("grant_gold", 7)); // multiple effects compose
    s.nodes.add(die);

    Story.Node survive = new Story.Node();
    survive.id = "survive";
    survive.kind = Story.Kind.OUTCOME;
    survive.result = Story.Result.SURVIVE;
    survive.reward = "art/epilogue.png";
    s.nodes.add(survive);

    assertTrue("authored story lints clean: " + s.lint(), s.lint().isEmpty());

    s.save(f);
    Story back = Story.load(f);

    assertEquals("The Vault", back.name);
    assertEquals("n1", back.start);
    assertEquals(4, back.nodes.size());

    Story.Node b1 = back.nodeById("n1");
    assertEquals(Story.Kind.BEAT, b1.kind);
    assertEquals("art/vault.png", b1.image);
    assertEquals("n2", b1.next);
    assertEquals(100, (int) b1.x);

    Story.Node b2 = back.nodeById("n2");
    assertEquals(Story.Kind.CHOICE, b2.kind);
    assertEquals(2, b2.choices.size());
    assertEquals("Take the crown", b2.choices.get(0).text);
    assertEquals("die", b2.choices.get(0).to);
    assertTrue("empty-to choice falls through", b2.choices.get(1).to.isEmpty());

    assertEquals("announce", back.nodeById("n1").onEnter.get(0).name);

    Story.Node bDie = back.nodeById("die");
    assertEquals(Story.Result.DIE, bDie.result);
    assertEquals("the crown", bDie.reason);
    assertEquals(2, bDie.onEnter.size());
    assertEquals("kill_player", bDie.onEnter.get(0).name);
    assertEquals("grant_gold", bDie.onEnter.get(1).name);
    assertEquals(7, bDie.onEnter.get(1).param);

    assertEquals(Story.Result.SURVIVE, back.nodeById("survive").result);
    assertEquals("art/epilogue.png", back.nodeById("survive").reward);

    // targetsOf resolves the fall-through choice to the node's next
    assertTrue(back.targetsOf(b2).contains("die"));
    assertTrue(back.targetsOf(b2).contains("survive"));

    assertEquals(List.of("vault.ref/backdrop.ref.png"), back.references);
    assertEquals(List.of("art/vault.png", "art/epilogue.png", "vault.ref/backdrop.ref.png"), back.imageRefs());
    assertTrue("round-tripped story still lints clean: " + back.lint(), back.lint().isEmpty());
    Files.deleteIfExists(f.toPath());
  }

  @Test
  public void lintCatchesDanglingEdgeAndEmptyChoice() {
    Story s = new Story();
    s.start = "a";
    Story.Node a = new Story.Node();
    a.id = "a";
    a.kind = Story.Kind.CHOICE; // no decisions
    s.nodes.add(a);
    Story.Choice c = new Story.Choice();
    c.text = "go nowhere";
    c.to = "ghost"; // missing target
    a.choices.add(c);

    List<String> problems = s.lint();
    assertFalse(problems.isEmpty());
    assertTrue(problems.toString().contains("missing node 'ghost'"));
    // adding a decision means it's no longer "no decisions", so re-check with a fresh empty one
    Story.Node b = new Story.Node();
    b.id = "b";
    b.kind = Story.Kind.CHOICE;
    s.nodes.add(b);
    assertTrue(s.lint().toString().contains("b: choice node has no decisions"));
  }

  @Test
  public void lintCatchesUnreachableAndNoEnding() {
    Story s = new Story();
    s.start = "a";
    Story.Node a = new Story.Node();
    a.id = "a";
    a.kind = Story.Kind.BEAT;
    a.next = "a"; // loops forever, never reaches an outcome
    s.nodes.add(a);
    Story.Node orphan = new Story.Node();
    orphan.id = "orphan";
    orphan.kind = Story.Kind.OUTCOME;
    s.nodes.add(orphan);

    String problems = s.lint().toString();
    assertTrue(problems.contains("orphan: unreachable from start"));
    assertTrue(problems.contains("no outcome (survive/die) is reachable"));
  }
}
