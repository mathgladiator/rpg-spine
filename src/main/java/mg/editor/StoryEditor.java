package mg.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import mg.editor.story.Story;
import mg.editor.story.Story.Choice;
import mg.editor.story.Story.Effect;
import mg.editor.story.Story.Kind;
import mg.editor.story.Story.Result;
import mg.tree.Parser;
import mg.tree.Root;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A node-graph editor for {@code .story} files. The center pane is a draggable
 * canvas of node boxes (beat / choice / outcome) joined by directed edges; the
 * right pane inspects the selected node and lists live graph diagnostics. See
 * {@code documents/story.vm.md}.
 */
public class StoryEditor implements Editor {

  private static final double NW = 174, NH = 74; // node box size (model units)
  private static final Color BEAT = Color.web("#1e88e5");
  private static final Color CHOICE = Color.web("#f9a825");
  private static final Color SURVIVE = Color.web("#43a047");
  private static final Color DIE = Color.web("#e53935");
  private static final Color EDGE = Color.web("#607d8b");

  private enum Tool { SELECT, CONNECT, ADD_BEAT, ADD_CHOICE, ADD_OUTCOME, DELETE }

  private final File file;
  private final Label status;
  private final Story story;
  private final BorderPane root = new BorderPane();
  private final Canvas canvas = new Canvas();
  private final VBox inspector = new VBox(8);
  private final Label lintLabel = new Label();

  /** the full-screen image dimensions every beat/reward image must match. */
  private static final int IMG_W = 400, IMG_H = 240;

  private boolean dirty;
  private boolean populating;
  private double scale = 1.0;
  private Tool tool = Tool.SELECT;
  private Story.Node selected;
  /** effect names discovered from the project's .rpg schemas (for the on-enter dropdown). */
  private final List<String> effectNames = new ArrayList<>();

  private final java.util.Map<Tool, ToggleButton> toolButtons = new java.util.EnumMap<>(Tool.class);
  private ScrollPane scroll;

  // drag state
  private Story.Node dragNode;       // node being moved (SELECT) or connected from (CONNECT)
  private double dragDX, dragDY;      // grab offset within the node (move)
  private boolean connecting;
  private double connX, connY;        // live connect endpoint (model space)
  // pan state (drag empty canvas to scroll a big graph)
  private boolean panning;
  private double panAnchorX, panAnchorY, panH0, panV0;

  public StoryEditor(File file, Label status) throws Exception {
    this.file = file;
    this.status = status;
    this.story = Story.load(file);
    discoverEffects();
    buildUi();
    redraw();
    rebuildInspector();
  }

  // ---------------------------------------------------------------- UI assembly

  private void buildUi() {
    root.setLeft(buildToolPanel());
    root.setCenter(buildCanvasPane());
    root.setRight(buildSidePanel());
  }

  private Node buildToolPanel() {
    VBox box = new VBox(8);
    box.setPadding(new Insets(10));
    box.setPrefWidth(190);
    ToggleGroup tg = new ToggleGroup();
    box.getChildren().add(section("Tools"));
    box.getChildren().add(toolButton(tg, Tool.SELECT, "Select / Move"));
    box.getChildren().add(toolButton(tg, Tool.CONNECT, "Connect (drag A→B)"));
    box.getChildren().add(toolButton(tg, Tool.ADD_BEAT, "+ Beat (image+text)"));
    box.getChildren().add(toolButton(tg, Tool.ADD_CHOICE, "+ Choice (decisions)"));
    box.getChildren().add(toolButton(tg, Tool.ADD_OUTCOME, "+ Outcome (end)"));
    box.getChildren().add(toolButton(tg, Tool.DELETE, "Delete node"));

    box.getChildren().add(section("Story"));
    TextField name = new TextField(story.name);
    name.textProperty().addListener((o, a, b) -> { story.name = b; markDirty(); redraw(); });
    box.getChildren().add(labeled("name", name));
    TextField id = new TextField(story.id);
    id.textProperty().addListener((o, a, b) -> { story.id = b; markDirty(); });
    box.getChildren().add(labeled("id", id));
    Button rescan = new Button("Rescan effects (.rpg)");
    rescan.setMaxWidth(Double.MAX_VALUE);
    rescan.setOnAction(e -> { discoverEffects(); rebuildInspector(); status.setText(effectNames.size() + " effect(s) from .rpg"); });
    box.getChildren().add(rescan);

    Label legend = new Label("beat = image + a line\nchoice = text + decisions\noutcome = survive / die\n\n"
        + "Connect from a beat sets its\nnext; from a choice adds a\ndecision. Drag empty space to\npan. Add/Delete snaps back to\nSelect (hold Ctrl to keep the\ntool). Enter once — no re-entry.");
    legend.setWrapText(true);
    legend.setStyle("-fx-text-fill:#555; -fx-font-size:11;");
    box.getChildren().add(legend);

    ScrollPane sp = new ScrollPane(box);
    sp.setFitToWidth(true);
    return sp;
  }

  private ToggleButton toolButton(ToggleGroup tg, Tool t, String label) {
    ToggleButton b = new ToggleButton(label);
    b.setToggleGroup(tg);
    b.setMaxWidth(Double.MAX_VALUE);
    if (t == Tool.SELECT) {
      b.setSelected(true);
    }
    toolButtons.put(t, b);
    b.setOnAction(e -> {
      if (b.isSelected()) {
        tool = t;
      } else {
        b.setSelected(true);
      }
    });
    return b;
  }

  private Node buildCanvasPane() {
    canvas.setOnMousePressed(this::onPress);
    canvas.setOnMouseDragged(this::onDrag);
    canvas.setOnMouseReleased(this::onRelease);
    ScrollPane sp = new ScrollPane(canvas);
    sp.setPannable(false); // we pan manually on empty-space drag (nodes are draggable)
    this.scroll = sp;

    Slider zoom = new Slider(0.5, 1.5, scale);
    zoom.setPrefWidth(140);
    zoom.valueProperty().addListener((o, a, b) -> { scale = b.doubleValue(); redraw(); });

    HBox bar = new HBox(8, new Label("Zoom:"), zoom, spacer(), lintLabel);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.setPadding(new Insets(8));
    BorderPane pane = new BorderPane(sp);
    pane.setTop(bar);
    return pane;
  }

  private Node buildSidePanel() {
    TitledPane props = new TitledPane("Node", new ScrollPane(inspector) {{ setFitToWidth(true); }});
    props.setCollapsible(false);
    ReferencePanel refs = new ReferencePanel(story.references, file, story.id, IMG_W,
        null, null, ReferencePanel.FLAT, this::markDirty);
    TitledPane refsPane = new TitledPane("References → Extract a 400×240 B&W image", refs);
    refsPane.setExpanded(false);
    VBox box = new VBox(props, refsPane);
    box.setPadding(new Insets(10));
    box.setPrefWidth(340);
    VBox.setVgrow(props, Priority.ALWAYS);
    inspector.setPadding(new Insets(8));
    return box;
  }

  // ------------------------------------------------------------- mouse / canvas

  private void onPress(MouseEvent e) {
    double mx = e.getX() / scale, my = e.getY() / scale;
    boolean ctrl = e.isControlDown() || e.isMetaDown();
    Story.Node hit = nodeAt(mx, my);
    switch (tool) {
      case SELECT -> {
        if (hit != null) {
          select(hit);
          dragNode = hit;
          dragDX = mx - hit.x;
          dragDY = my - hit.y;
        } else {
          select(null);
          startPan(e); // drag empty space to scroll a big graph
        }
      }
      case CONNECT -> {
        if (hit != null && hit.kind != Kind.OUTCOME) {
          dragNode = hit;
          connecting = true;
          connX = mx;
          connY = my;
          redraw();
        }
      }
      case ADD_BEAT -> { addNode(Kind.BEAT, mx, my); if (!ctrl) { revertToSelect(); } }
      case ADD_CHOICE -> { addNode(Kind.CHOICE, mx, my); if (!ctrl) { revertToSelect(); } }
      case ADD_OUTCOME -> { addNode(Kind.OUTCOME, mx, my); if (!ctrl) { revertToSelect(); } }
      case DELETE -> { if (hit != null) { deleteNode(hit); if (!ctrl) { revertToSelect(); } } }
    }
  }

  private void onDrag(MouseEvent e) {
    if (panning) {
      doPan(e);
      return;
    }
    double mx = e.getX() / scale, my = e.getY() / scale;
    if (tool == Tool.SELECT && dragNode != null) {
      dragNode.x = Math.max(0, mx - dragDX);
      dragNode.y = Math.max(0, my - dragDY);
      markDirty();
      redraw();
    } else if (tool == Tool.CONNECT && connecting) {
      connX = mx;
      connY = my;
      redraw();
    }
  }

  private void onRelease(MouseEvent e) {
    if (panning) {
      panning = false;
      return;
    }
    if (tool == Tool.CONNECT && connecting) {
      Story.Node target = nodeAt(e.getX() / scale, e.getY() / scale);
      if (target != null && dragNode != null) {
        connect(dragNode, target);
      }
      connecting = false;
    }
    dragNode = null;
    redraw();
  }

  /** snap the tool back to Select/Move (after an add or delete, unless Ctrl held). */
  private void revertToSelect() {
    tool = Tool.SELECT;
    ToggleButton b = toolButtons.get(Tool.SELECT);
    if (b != null) {
      b.setSelected(true);
    }
  }

  private void startPan(MouseEvent e) {
    panning = true;
    panAnchorX = e.getSceneX();
    panAnchorY = e.getSceneY();
    panH0 = scroll.getHvalue();
    panV0 = scroll.getVvalue();
  }

  private void doPan(MouseEvent e) {
    double scrollableX = canvas.getWidth() - scroll.getViewportBounds().getWidth();
    double scrollableY = canvas.getHeight() - scroll.getViewportBounds().getHeight();
    if (scrollableX > 0) {
      scroll.setHvalue(clamp01(panH0 - (e.getSceneX() - panAnchorX) / scrollableX));
    }
    if (scrollableY > 0) {
      scroll.setVvalue(clamp01(panV0 - (e.getSceneY() - panAnchorY) / scrollableY));
    }
  }

  private static double clamp01(double v) {
    return Math.max(0, Math.min(1, v));
  }

  /** topmost node containing model-space point (px,py), or null. */
  private Story.Node nodeAt(double px, double py) {
    for (int i = story.nodes.size() - 1; i >= 0; i--) {
      Story.Node n = story.nodes.get(i);
      if (px >= n.x && px <= n.x + NW && py >= n.y && py <= n.y + NH) {
        return n;
      }
    }
    return null;
  }

  private void connect(Story.Node from, Story.Node to) {
    if (from.kind == Kind.BEAT) {
      from.next = to.id;
    } else if (from.kind == Kind.CHOICE) {
      Choice c = new Choice();
      c.text = "choice " + (from.choices.size() + 1);
      c.to = to.id;
      from.choices.add(c);
    }
    markDirty();
    rebuildInspector();
  }

  private void addNode(Kind kind, double mx, double my) {
    Story.Node n = new Story.Node();
    n.id = uniqueId(kind.name().toLowerCase());
    n.kind = kind;
    n.x = Math.max(0, mx - NW / 2);
    n.y = Math.max(0, my - NH / 2);
    if (kind == Kind.BEAT) {
      n.text = "...";
    } else if (kind == Kind.CHOICE) {
      n.text = "What do you do?";
    } else {
      n.result = Result.SURVIVE;
    }
    story.nodes.add(n);
    if (story.start.isBlank()) {
      story.start = n.id;
    }
    select(n);
    markDirty();
    redraw();
  }

  private void deleteNode(Story.Node n) {
    story.nodes.remove(n);
    for (Story.Node o : story.nodes) {
      if (o.next.equals(n.id)) {
        o.next = "";
      }
      for (Choice c : o.choices) {
        if (c.to.equals(n.id)) {
          c.to = "";
        }
      }
    }
    if (story.start.equals(n.id)) {
      story.start = story.nodes.isEmpty() ? "" : story.nodes.get(0).id;
    }
    if (selected == n) {
      selected = null;
    }
    markDirty();
    redraw();
    rebuildInspector();
  }

  private String uniqueId(String base) {
    if (story.nodeById(base) == null) {
      return base;
    }
    int i = 2;
    while (story.nodeById(base + i) != null) {
      i++;
    }
    return base + i;
  }

  private void select(Story.Node n) {
    selected = n;
    rebuildInspector();
    redraw();
  }

  // ------------------------------------------------------------------- drawing

  private void redraw() {
    double maxX = 400, maxY = 300;
    for (Story.Node n : story.nodes) {
      maxX = Math.max(maxX, n.x + NW + 60);
      maxY = Math.max(maxY, n.y + NH + 60);
    }
    canvas.setWidth(maxX * scale);
    canvas.setHeight(maxY * scale);
    GraphicsContext g = canvas.getGraphicsContext2D();
    g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    g.save();
    g.scale(scale, scale);

    for (Story.Node n : story.nodes) {
      drawEdges(g, n);
    }
    if (connecting && dragNode != null) {
      g.setStroke(EDGE);
      g.setLineWidth(2);
      g.setLineDashes(6, 4);
      g.strokeLine(dragNode.x + NW, dragNode.y + NH / 2, connX, connY);
      g.setLineDashes();
    }
    for (Story.Node n : story.nodes) {
      drawNode(g, n);
    }
    g.restore();

    List<String> problems = story.lint();
    lintLabel.setText(problems.isEmpty() ? "✓ graph ok"
        : "⚠ " + problems.size() + " issue" + (problems.size() == 1 ? "" : "s"));
    lintLabel.setStyle(problems.isEmpty() ? "-fx-text-fill:#2e7d32;" : "-fx-text-fill:#c62828;");
    status.setText(story.name + " — " + story.nodes.size() + " nodes"
        + (problems.isEmpty() ? "" : " · " + problems.size() + " issue(s)") + (dirty ? " *" : ""));
  }

  private void drawEdges(GraphicsContext g, Story.Node n) {
    if (n.kind == Kind.BEAT) {
      edge(g, n, story.nodeById(n.next), n.y + NH / 2, null);
    } else if (n.kind == Kind.CHOICE) {
      int i = 0;
      int count = Math.max(1, n.choices.size());
      for (Choice c : n.choices) {
        String to = c.to.isBlank() ? n.next : c.to;
        double sy = n.y + NH * (i + 1.0) / (count + 1.0);
        edge(g, n, story.nodeById(to), sy, c.text);
        i++;
      }
      if (n.choices.isEmpty() && !n.next.isBlank()) {
        edge(g, n, story.nodeById(n.next), n.y + NH / 2, null);
      }
    }
  }

  private void edge(GraphicsContext g, Story.Node from, Story.Node to, double sy, String label) {
    if (to == null) {
      return;
    }
    double sx = from.x + NW;
    double tx = to.x, ty = to.y + NH / 2;
    g.setStroke(EDGE);
    g.setLineWidth(1.8);
    g.strokeLine(sx, sy, tx, ty);
    // arrowhead at the target
    double ang = Math.atan2(ty - sy, tx - sx);
    double ah = 9;
    g.setFill(EDGE);
    g.fillPolygon(
        new double[] {tx, tx - ah * Math.cos(ang - 0.4), tx - ah * Math.cos(ang + 0.4)},
        new double[] {ty, ty - ah * Math.sin(ang - 0.4), ty - ah * Math.sin(ang + 0.4)}, 3);
    if (label != null && !label.isBlank()) {
      g.setFill(Color.web("#455a64"));
      g.setFont(Font.font(10));
      g.setTextAlign(TextAlignment.LEFT);
      g.fillText(clip(label, 18), sx + 6, sy - 3);
    }
  }

  private void drawNode(GraphicsContext g, Story.Node n) {
    Color c = switch (n.kind) {
      case BEAT -> BEAT;
      case CHOICE -> CHOICE;
      case OUTCOME -> n.result == Result.SURVIVE ? SURVIVE : DIE;
    };
    boolean isStart = n.id.equals(story.start);
    g.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.14));
    g.fillRoundRect(n.x, n.y, NW, NH, 12, 12);
    g.setStroke(n == selected ? Color.web("#1565c0") : c);
    g.setLineWidth(n == selected ? 3 : 1.6);
    g.strokeRoundRect(n.x, n.y, NW, NH, 12, 12);

    g.setTextAlign(TextAlignment.LEFT);
    g.setFill(c.darker());
    g.setFont(Font.font("System", FontWeight.BOLD, 11));
    String head = (isStart ? "▶ " : "") + n.id + "  ·  " + n.kind.name().toLowerCase();
    g.fillText(head, n.x + 8, n.y + 16);

    g.setFill(Color.web("#37474f"));
    g.setFont(Font.font(11));
    if (n.kind == Kind.OUTCOME) {
      g.fillText(n.result == Result.SURVIVE ? "SURVIVE" : "DIE", n.x + 8, n.y + 36);
      if (!n.reason.isBlank()) {
        g.fillText(clip(n.reason, 22), n.x + 8, n.y + 52);
      }
    } else {
      g.fillText(clip(n.text, 24), n.x + 8, n.y + 36);
      if (n.kind == Kind.BEAT && !n.image.isBlank()) {
        g.fillText("🖼 " + clip(shortName(n.image), 20), n.x + 8, n.y + 54);
      } else if (n.kind == Kind.CHOICE) {
        g.fillText(n.choices.size() + " decision" + (n.choices.size() == 1 ? "" : "s"), n.x + 8, n.y + 54);
      }
    }
    if (!n.onEnter.isEmpty()) {
      g.setFill(Color.web("#6a1b9a"));
      g.setFont(Font.font(9));
      g.fillText("⚡" + n.onEnter.size(), n.x + NW - 22, n.y + 14);
    }
  }

  // ----------------------------------------------------------------- inspector

  private void rebuildInspector() {
    inspector.getChildren().clear();
    populating = true;
    if (selected == null) {
      inspector.getChildren().add(new Label("No node selected.\nClick a node, or use a + tool to add one."));
      populating = false;
      addLintPanel();
      return;
    }
    Story.Node n = selected;

    TextField id = new TextField(n.id);
    id.focusedProperty().addListener((o, was, now) -> { if (!now) { renameNode(n, id.getText()); } });
    inspector.getChildren().add(labeled("id (rename rewires edges)", id));

    ComboBox<Kind> kind = new ComboBox<>();
    kind.getItems().setAll(Kind.values());
    kind.setValue(n.kind);
    kind.valueProperty().addListener((o, a, b) -> { if (!populating && b != null) { n.kind = b; markDirty(); redraw(); rebuildInspector(); } });
    inspector.getChildren().add(labeled("kind", kind));

    ToggleButton startBtn = new ToggleButton(n.id.equals(story.start) ? "★ start node" : "Make start node");
    startBtn.setSelected(n.id.equals(story.start));
    startBtn.setMaxWidth(Double.MAX_VALUE);
    startBtn.setOnAction(e -> { story.start = n.id; markDirty(); redraw(); rebuildInspector(); });
    inspector.getChildren().add(startBtn);

    TextArea text = new TextArea(n.text);
    text.setWrapText(true);
    text.setPrefRowCount(3);
    text.textProperty().addListener((o, a, b) -> { if (!populating) { n.text = b; markDirty(); redraw(); } });
    inspector.getChildren().add(labeled(n.kind == Kind.CHOICE ? "prompt" : "text", text));

    if (n.kind == Kind.BEAT) {
      buildBeat(n);
    } else if (n.kind == Kind.CHOICE) {
      buildChoice(n);
    } else {
      buildOutcome(n);
    }

    buildEffects(n);
    populating = false;
    addLintPanel();
  }

  private void buildBeat(Story.Node n) {
    HBox img = new HBox(6);
    Label path = new Label(n.image.isBlank() ? "(no image)" : shortName(n.image));
    Button pick = new Button("Image…");
    pick.setOnAction(e -> pickImage(p -> { n.image = p; markDirty(); redraw(); rebuildInspector(); }));
    Button clear = new Button("✕");
    clear.setOnAction(e -> { n.image = ""; markDirty(); redraw(); rebuildInspector(); });
    img.getChildren().addAll(pick, clear, path);
    img.setAlignment(Pos.CENTER_LEFT);
    inspector.getChildren().add(labeled("full-screen image (400×240, black & white)", img));
    inspector.getChildren().add(imageReport(n.image));
    inspector.getChildren().add(labeled("next", targetCombo(n.next, v -> { n.next = v; markDirty(); redraw(); })));
  }

  private void buildChoice(Story.Node n) {
    inspector.getChildren().add(labeled("default next (for empty-target choices)",
        targetCombo(n.next, v -> { n.next = v; markDirty(); redraw(); })));
    inspector.getChildren().add(section("Decisions"));
    int idx = 0;
    for (Choice c : new ArrayList<>(n.choices)) {
      final Choice cc = c;
      VBox row = new VBox(3);
      row.setPadding(new Insets(4));
      row.setStyle("-fx-border-color:#ddd; -fx-border-radius:4;");
      TextField label = new TextField(c.text);
      label.setPromptText("option label");
      label.textProperty().addListener((o, a, b) -> { if (!populating) { cc.text = b; markDirty(); redraw(); } });
      HBox top = new HBox(6, label, removeBtn(() -> { n.choices.remove(cc); markDirty(); redraw(); rebuildInspector(); }));
      HBox.setHgrow(label, Priority.ALWAYS);
      row.getChildren().add(top);
      row.getChildren().add(new HBox(6, new Label("→"), targetCombo(c.to, v -> { cc.to = v; markDirty(); redraw(); })));
      inspector.getChildren().add(row);
      idx++;
    }
    Button add = new Button("+ decision");
    add.setOnAction(e -> { Choice c = new Choice(); c.text = "choice " + (n.choices.size() + 1); n.choices.add(c); markDirty(); redraw(); rebuildInspector(); });
    inspector.getChildren().add(add);
  }

  private void buildOutcome(Story.Node n) {
    ComboBox<Result> res = new ComboBox<>();
    res.getItems().setAll(Result.values());
    res.setValue(n.result);
    res.valueProperty().addListener((o, a, b) -> { if (!populating && b != null) { n.result = b; markDirty(); redraw(); } });
    inspector.getChildren().add(labeled("result", res));
    TextField reason = new TextField(n.reason);
    reason.textProperty().addListener((o, a, b) -> { if (!populating) { n.reason = b; markDirty(); redraw(); } });
    inspector.getChildren().add(labeled("reason (cause of death / note)", reason));
    HBox rw = new HBox(6);
    Label path = new Label(n.reward.isBlank() ? "(none)" : shortName(n.reward));
    Button pick = new Button("Reward image…");
    pick.setOnAction(e -> pickImage(p -> { n.reward = p; markDirty(); rebuildInspector(); }));
    Button clear = new Button("✕");
    clear.setOnAction(e -> { n.reward = ""; markDirty(); rebuildInspector(); });
    rw.getChildren().addAll(pick, clear, path);
    rw.setAlignment(Pos.CENTER_LEFT);
    inspector.getChildren().add(labeled("reward / epilogue image (400×240, black & white)", rw));
    inspector.getChildren().add(imageReport(n.reward));
  }

  /** the on-enter effects editor: a list of (effect name dropdown + int param). */
  private void buildEffects(Story.Node n) {
    inspector.getChildren().add(section("On-enter effects (compose in order)"));
    if (effectNames.isEmpty()) {
      Label hint = new Label("No effects declared. Add 'effect <name>;' to a .rpg schema, then Rescan.");
      hint.setWrapText(true);
      hint.setStyle("-fx-text-fill:#888;");
      inspector.getChildren().add(hint);
    }
    for (Effect ef : new ArrayList<>(n.onEnter)) {
      final Effect e = ef;
      ComboBox<String> name = new ComboBox<>();
      name.setEditable(true);
      name.getItems().setAll(effectNames);
      if (!e.name.isBlank() && !effectNames.contains(e.name)) {
        name.getItems().add(e.name); // keep an unknown (stale) name visible/selectable
      }
      name.setValue(e.name);
      name.valueProperty().addListener((o, a, b) -> { if (!populating) { e.name = b == null ? "" : b; markDirty(); redraw(); } });
      Spinner<Integer> param = new Spinner<>(-99999, 99999, e.param);
      param.setEditable(true);
      param.setPrefWidth(96);
      param.valueProperty().addListener((o, a, b) -> { if (!populating && b != null) { e.param = b; markDirty(); } });
      HBox row = new HBox(6, name, new Label("param"), param,
          removeBtn(() -> { n.onEnter.remove(e); markDirty(); redraw(); rebuildInspector(); }));
      HBox.setHgrow(name, Priority.ALWAYS);
      row.setAlignment(Pos.CENTER_LEFT);
      inspector.getChildren().add(row);
    }
    Button add = new Button("+ effect");
    add.setOnAction(ev -> {
      n.onEnter.add(new Effect(effectNames.isEmpty() ? "" : effectNames.get(0), 0));
      markDirty();
      redraw();
      rebuildInspector();
    });
    inspector.getChildren().add(add);
  }

  /** a status line reporting an image's size and whether it is 400×240 and pure B&W. */
  private Node imageReport(String rel) {
    Label l = new Label();
    l.setWrapText(true);
    if (rel == null || rel.isBlank()) {
      return l;
    }
    File f = new File(file.getParentFile(), rel);
    if (!f.isFile()) {
      f = new File(rel);
    }
    if (!f.isFile()) {
      l.setText("⚠ image file not found");
      l.setStyle("-fx-text-fill:#c62828;");
      return l;
    }
    try {
      BufferedImage img = javax.imageio.ImageIO.read(f);
      if (img == null) {
        l.setText("⚠ not a readable image");
        l.setStyle("-fx-text-fill:#c62828;");
        return l;
      }
      int w = img.getWidth(), h = img.getHeight();
      boolean sizeOk = w == IMG_W && h == IMG_H;
      int nonBw = countNonBW(img);
      boolean bwOk = nonBw == 0;
      l.setText(w + "×" + h + (sizeOk ? " ✓" : " ✗ need " + IMG_W + "×" + IMG_H)
          + "   ·   " + (bwOk ? "black & white ✓" : nonBw + " non-B&W pixels ✗"));
      l.setStyle(sizeOk && bwOk ? "-fx-text-fill:#2e7d32;" : "-fx-text-fill:#c62828;");
    } catch (Exception ex) {
      l.setText("⚠ " + ex.getMessage());
      l.setStyle("-fx-text-fill:#c62828;");
    }
    return l;
  }

  /** count pixels that are not opaque pure-black or opaque pure-white. */
  private static int countNonBW(BufferedImage img) {
    int bad = 0;
    for (int y = 0; y < img.getHeight(); y++) {
      for (int x = 0; x < img.getWidth(); x++) {
        int argb = img.getRGB(x, y);
        int a = (argb >>> 24) & 0xff, r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
        boolean white = a == 255 && r == 255 && g == 255 && b == 255;
        boolean black = a == 255 && r == 0 && g == 0 && b == 0;
        if (!white && !black) {
          bad++;
        }
      }
    }
    return bad;
  }

  /** scan the project's .rpg schemas for {@code effect <name>;} declarations. */
  private void discoverEffects() {
    LinkedHashSet<String> set = new LinkedHashSet<>();
    File scanRoot = ProjectSettings.root();
    if (scanRoot == null) {
      scanRoot = file.getParentFile();
    }
    if (scanRoot != null) {
      collectRpg(scanRoot, set);
    }
    effectNames.clear();
    effectNames.addAll(set);
  }

  private void collectRpg(File dir, LinkedHashSet<String> out) {
    File[] kids = dir.listFiles();
    if (kids == null) {
      return;
    }
    for (File k : kids) {
      if (k.getName().startsWith(".") || k.getName().equals("target")) {
        continue;
      }
      if (k.isDirectory()) {
        collectRpg(k, out);
      } else if (k.getName().toLowerCase().endsWith(".rpg")) {
        try {
          Root r = new Root();
          Parser.merge_string(r, k.getName(), Files.readString(k.toPath()));
          for (mg.tree.Effect ef : r.effects) {
            out.add(ef.name);
          }
        } catch (Exception ex) {
          Log.error("could not parse " + k.getName() + " for effects", ex);
        }
      }
    }
  }

  /** a combo of all node ids (+ "(none)") that writes the chosen id (or "") via sink. */
  private ComboBox<String> targetCombo(String current, java.util.function.Consumer<String> sink) {
    ComboBox<String> cb = new ComboBox<>();
    cb.getItems().add("(none)");
    for (Story.Node n : story.nodes) {
      cb.getItems().add(n.id);
    }
    cb.setValue(current == null || current.isBlank() ? "(none)" : current);
    cb.valueProperty().addListener((o, a, b) -> {
      if (!populating && b != null) {
        sink.accept("(none)".equals(b) ? "" : b);
      }
    });
    return cb;
  }

  private Button removeBtn(Runnable r) {
    Button b = new Button("✕");
    b.setOnAction(e -> r.run());
    return b;
  }

  private void addLintPanel() {
    List<String> problems = story.lint();
    VBox box = new VBox(2);
    if (problems.isEmpty()) {
      Label ok = new Label("✓ no problems");
      ok.setStyle("-fx-text-fill:#2e7d32;");
      box.getChildren().add(ok);
    } else {
      for (String p : problems) {
        Label l = new Label("• " + p);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill:#c62828;");
        box.getChildren().add(l);
      }
    }
    inspector.getChildren().add(new TitledPane("Diagnostics", box) {{ setExpanded(!problems.isEmpty()); }});
  }

  private void renameNode(Story.Node n, String raw) {
    String nid = raw == null ? "" : raw.strip();
    if (nid.isEmpty() || nid.equals(n.id)) {
      return;
    }
    if (story.nodeById(nid) != null) {
      status.setText("id '" + nid + "' is already taken");
      return;
    }
    String old = n.id;
    for (Story.Node o : story.nodes) {
      if (o.next.equals(old)) {
        o.next = nid;
      }
      for (Choice c : o.choices) {
        if (c.to.equals(old)) {
          c.to = nid;
        }
      }
    }
    if (story.start.equals(old)) {
      story.start = nid;
    }
    n.id = nid;
    markDirty();
    redraw();
    rebuildInspector();
  }

  private void pickImage(java.util.function.Consumer<String> sink) {
    File base = file.getParentFile();
    ImagePicker.pick(canvas.getScene() == null ? null : canvas.getScene().getWindow(), base, "Choose image")
        .ifPresent(chosen -> sink.accept(relativize(base, chosen)));
  }

  private static String relativize(File base, File chosen) {
    try {
      Path rel = base.toPath().toAbsolutePath().relativize(chosen.toPath().toAbsolutePath());
      return rel.toString().replace('\\', '/');
    } catch (Exception e) {
      return chosen.getName();
    }
  }

  // ------------------------------------------------------------------- helpers

  private static String clip(String s, int n) {
    if (s == null) {
      return "";
    }
    String one = s.replaceAll("\\s+", " ").strip();
    return one.length() <= n ? one : one.substring(0, n - 1) + "…";
  }

  private static String shortName(String path) {
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  private static Label section(String text) {
    Label l = new Label(text);
    l.setFont(Font.font("System", FontWeight.BOLD, 12));
    return l;
  }

  private static Node labeled(String label, Node control) {
    if (control instanceof Region r) {
      r.setMaxWidth(Double.MAX_VALUE);
    }
    return new VBox(2, new Label(label), control);
  }

  private static Region spacer() {
    Region r = new Region();
    HBox.setHgrow(r, Priority.ALWAYS);
    return r;
  }

  private void markDirty() {
    dirty = true;
  }

  // -------------------------------------------------------------------- Editor

  @Override public Node getNode() {
    return root;
  }

  @Override public void save() throws Exception {
    story.save(file);
    dirty = false;
    redraw();
  }

  @Override public boolean isDirty() {
    return dirty;
  }

  @Override public String title() {
    return "story";
  }
}
