package mg.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import mg.editor.world.World;
import mg.editor.world.World.Edge;
import mg.editor.world.World.Location;
import mg.editor.world.World.LocationType;
import mg.editor.world.World.SceneObject;

import java.io.File;
import java.util.List;

/**
 * A pan/zoom graph + scene editor for .world files: drop location nodes, wire
 * them with bendable paths, and scatter scene objects that appear as the player
 * trips variable bindings. It is both a travel graph and a reveal-over-time
 * scene graph; nothing here is dynamic — every gate is a plain binding string
 * the codegen later resolves against the save document.
 */
public class WorldEditor implements Editor {
  private enum Tool { SELECT, ADD_LOCATION, ADD_OBJECT, CONNECT, ADD_BEND, DELETE }

  private enum SelKind { NONE, LOCATION, OBJECT, EDGE, BEND }

  private final File file;
  private final Label status;
  private final World world;
  private final BorderPane root = new BorderPane();
  private final Canvas canvas = new Canvas();
  private boolean dirty;

  private double scale = 1.0;
  private double offsetX = 40, offsetY = 40;
  private Tool tool = Tool.SELECT;

  private SelKind selKind = SelKind.NONE;
  private Location selLoc;
  private SceneObject selObj;
  private Edge selEdge;
  private int selBend = -1;

  private Location connectFrom;     // pending CONNECT source
  private boolean draggingPoint;    // moving the selected single-point element
  private boolean panning;
  private double panLastX, panLastY;

  private Image backgroundImage;

  private final VBox propsBox = new VBox(6);
  private final ListView<String> varsList = new ListView<>();

  public WorldEditor(File file, Label status) throws Exception {
    this.file = file;
    this.status = status;
    this.world = World.load(file);
    loadBackground();
    buildUi();
    rebuildProps();
    refreshVars();
    redraw();
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
    box.getChildren().add(toolButton(tg, Tool.ADD_LOCATION, "Add Location"));
    box.getChildren().add(toolButton(tg, Tool.ADD_OBJECT, "Add Object"));
    box.getChildren().add(toolButton(tg, Tool.CONNECT, "Connect Path"));
    box.getChildren().add(toolButton(tg, Tool.ADD_BEND, "Add Path Bend"));
    box.getChildren().add(toolButton(tg, Tool.DELETE, "Delete"));

    box.getChildren().add(spacerV(8));
    box.getChildren().add(section("Map"));
    Button setMap = new Button("Set Background…");
    setMap.setMaxWidth(Double.MAX_VALUE);
    setMap.setOnAction(e -> setBackground());
    box.getChildren().add(setMap);

    Label help = new Label("• left-drag pans is OFF;\n  use right-drag to pan\n• scroll to zoom\n• Connect: click A then B");
    help.setWrapText(true);
    help.setFont(Font.font(11));
    box.getChildren().add(spacerV(6));
    box.getChildren().add(help);

    return new ScrollPane(box) {{ setFitToWidth(true); }};
  }

  private ToggleButton toolButton(ToggleGroup tg, Tool t, String label) {
    ToggleButton b = new ToggleButton(label);
    b.setToggleGroup(tg);
    b.setMaxWidth(Double.MAX_VALUE);
    if (t == Tool.SELECT) {
      b.setSelected(true);
    }
    b.setOnAction(e -> {
      if (b.isSelected()) {
        tool = t;
        connectFrom = null;
        redraw();
      }
    });
    return b;
  }

  private Node buildCanvasPane() {
    canvas.setWidth(1400);
    canvas.setHeight(1000);
    canvas.setOnMousePressed(this::onPress);
    canvas.setOnMouseDragged(this::onDrag);
    canvas.setOnMouseReleased(e -> { draggingPoint = false; panning = false; });
    canvas.setOnScroll(e -> {
      double factor = e.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
      double wx = worldX(e.getX());
      double wy = worldY(e.getY());
      scale = Math.max(0.2, Math.min(4.0, scale * factor));
      offsetX = e.getX() - wx * scale;
      offsetY = e.getY() - wy * scale;
      redraw();
    });

    ScrollPane sp = new ScrollPane(canvas);
    sp.setPannable(false);
    return sp;
  }

  private Node buildSidePanel() {
    TitledPane props = new TitledPane("Properties", new ScrollPane(propsBox) {{ setFitToWidth(true); }});
    props.setCollapsible(false);

    varsList.setPrefHeight(160);
    TitledPane vars = new TitledPane("Variable Bindings", new VBox(6,
        new Label("Bindings referenced by reveal/blocked\nconditions in this world:"), varsList));

    Accordion accordion = new Accordion(vars);

    VBox box = new VBox(8, props, accordion);
    box.setPadding(new Insets(10));
    box.setPrefWidth(320);
    VBox.setVgrow(props, Priority.ALWAYS);
    return box;
  }

  // ----------------------------------------------------------------- mouse ops

  private void onPress(MouseEvent e) {
    if (e.getButton() == MouseButton.SECONDARY || e.getButton() == MouseButton.MIDDLE) {
      panning = true;
      panLastX = e.getX();
      panLastY = e.getY();
      return;
    }
    double wx = worldX(e.getX());
    double wy = worldY(e.getY());
    switch (tool) {
      case SELECT -> {
        hitTest(e.getX(), e.getY());
        draggingPoint = selKind == SelKind.LOCATION || selKind == SelKind.OBJECT || selKind == SelKind.BEND;
        rebuildProps();
        redraw();
      }
      case ADD_LOCATION -> {
        Location l = new Location();
        l.id = uniqueId("loc", world.locations.stream().map(x -> x.id).toList());
        l.name = "New Location";
        l.x = wx;
        l.y = wy;
        world.locations.add(l);
        select(SelKind.LOCATION, l);
        markDirty();
        rebuildProps();
        redraw();
      }
      case ADD_OBJECT -> {
        SceneObject o = new SceneObject();
        o.id = uniqueId("obj", world.objects.stream().map(x -> x.id).toList());
        o.x = wx;
        o.y = wy;
        world.objects.add(o);
        select(SelKind.OBJECT, o);
        markDirty();
        rebuildProps();
        redraw();
      }
      case CONNECT -> {
        Location hit = hitLocation(e.getX(), e.getY());
        if (hit != null) {
          if (connectFrom == null) {
            connectFrom = hit;
          } else if (connectFrom != hit) {
            Edge edge = new Edge();
            edge.id = uniqueId("edge", world.edges.stream().map(x -> x.id).toList());
            edge.from = connectFrom.id;
            edge.to = hit.id;
            world.edges.add(edge);
            connectFrom = null;
            select(SelKind.EDGE, edge);
            markDirty();
            rebuildProps();
          }
        } else {
          connectFrom = null;
        }
        redraw();
      }
      case ADD_BEND -> {
        Edge edge = hitEdge(e.getX(), e.getY());
        if (edge != null) {
          int idx = bendInsertIndex(edge, wx, wy);
          edge.bends.add(idx, new double[]{wx, wy});
          selEdge = edge;
          selBend = idx;
          selKind = SelKind.BEND;
          markDirty();
          rebuildProps();
          redraw();
        }
      }
      case DELETE -> {
        deleteAt(e.getX(), e.getY());
        markDirty();
        rebuildProps();
        refreshVars();
        redraw();
      }
    }
  }

  private void onDrag(MouseEvent e) {
    if (panning) {
      offsetX += e.getX() - panLastX;
      offsetY += e.getY() - panLastY;
      panLastX = e.getX();
      panLastY = e.getY();
      redraw();
      return;
    }
    if (tool == Tool.SELECT && draggingPoint) {
      double wx = worldX(e.getX());
      double wy = worldY(e.getY());
      switch (selKind) {
        case LOCATION -> { selLoc.x = wx; selLoc.y = wy; }
        case OBJECT -> { selObj.x = wx; selObj.y = wy; }
        case BEND -> {
          if (selEdge != null && selBend >= 0 && selBend < selEdge.bends.size()) {
            selEdge.bends.get(selBend)[0] = wx;
            selEdge.bends.get(selBend)[1] = wy;
          }
        }
        default -> { }
      }
      markDirty();
      redraw();
    }
  }

  // ----------------------------------------------------------------- hit tests

  private void hitTest(double sx, double sy) {
    SceneObject o = hitObject(sx, sy);
    if (o != null) { select(SelKind.OBJECT, o); return; }
    int[] bendHit = hitBend(sx, sy);
    if (bendHit != null) {
      selKind = SelKind.BEND;
      selEdge = world.edges.get(bendHit[0]);
      selBend = bendHit[1];
      selLoc = null;
      selObj = null;
      return;
    }
    Location l = hitLocation(sx, sy);
    if (l != null) { select(SelKind.LOCATION, l); return; }
    Edge e = hitEdge(sx, sy);
    if (e != null) { select(SelKind.EDGE, e); return; }
    selKind = SelKind.NONE;
    selLoc = null; selObj = null; selEdge = null; selBend = -1;
  }

  private Location hitLocation(double sx, double sy) {
    for (int i = world.locations.size() - 1; i >= 0; i--) {
      Location l = world.locations.get(i);
      if (dist(sx, sy, screenX(l.x), screenY(l.y)) <= 13) {
        return l;
      }
    }
    return null;
  }

  private SceneObject hitObject(double sx, double sy) {
    for (int i = world.objects.size() - 1; i >= 0; i--) {
      SceneObject o = world.objects.get(i);
      if (dist(sx, sy, screenX(o.x), screenY(o.y)) <= 9) {
        return o;
      }
    }
    return null;
  }

  private int[] hitBend(double sx, double sy) {
    for (int ei = 0; ei < world.edges.size(); ei++) {
      Edge e = world.edges.get(ei);
      for (int bi = 0; bi < e.bends.size(); bi++) {
        double[] b = e.bends.get(bi);
        if (dist(sx, sy, screenX(b[0]), screenY(b[1])) <= 8) {
          return new int[]{ei, bi};
        }
      }
    }
    return null;
  }

  private Edge hitEdge(double sx, double sy) {
    Edge best = null;
    double bestD = 7;
    for (Edge e : world.edges) {
      double[][] pts = edgePoints(e);
      for (int i = 0; i + 1 < pts.length; i++) {
        double d = pointSeg(sx, sy,
            screenX(pts[i][0]), screenY(pts[i][1]),
            screenX(pts[i + 1][0]), screenY(pts[i + 1][1]));
        if (d < bestD) {
          bestD = d;
          best = e;
        }
      }
    }
    return best;
  }

  private void deleteAt(double sx, double sy) {
    SceneObject o = hitObject(sx, sy);
    if (o != null) { world.objects.remove(o); clearSel(); return; }
    int[] bend = hitBend(sx, sy);
    if (bend != null) { world.edges.get(bend[0]).bends.remove(bend[1]); clearSel(); return; }
    Location l = hitLocation(sx, sy);
    if (l != null) {
      world.locations.remove(l);
      world.edges.removeIf(e -> e.from.equals(l.id) || e.to.equals(l.id));
      clearSel();
      return;
    }
    Edge e = hitEdge(sx, sy);
    if (e != null) { world.edges.remove(e); clearSel(); }
  }

  /** the ordered world-space points of an edge: from-location, bends, to-location. */
  private double[][] edgePoints(Edge e) {
    Location a = world.locationById(e.from);
    Location b = world.locationById(e.to);
    int n = e.bends.size() + 2;
    double[][] pts = new double[n][2];
    int i = 0;
    pts[i][0] = a != null ? a.x : 0;
    pts[i][1] = a != null ? a.y : 0;
    i++;
    for (double[] bend : e.bends) {
      pts[i][0] = bend[0];
      pts[i][1] = bend[1];
      i++;
    }
    pts[i][0] = b != null ? b.x : 0;
    pts[i][1] = b != null ? b.y : 0;
    return pts;
  }

  private int bendInsertIndex(Edge e, double wx, double wy) {
    double[][] pts = edgePoints(e);
    int bestSeg = 0;
    double bestD = Double.MAX_VALUE;
    for (int i = 0; i + 1 < pts.length; i++) {
      double d = pointSeg(wx, wy, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]);
      if (d < bestD) {
        bestD = d;
        bestSeg = i;
      }
    }
    // segment i sits between pts[i] and pts[i+1]; bend list index is bestSeg
    return bestSeg;
  }

  // ------------------------------------------------------------------- drawing

  private void redraw() {
    GraphicsContext g = canvas.getGraphicsContext2D();
    g.setFill(Color.web("#1f2329"));
    g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

    if (backgroundImage != null) {
      g.drawImage(backgroundImage, offsetX, offsetY,
          backgroundImage.getWidth() * scale, backgroundImage.getHeight() * scale);
    }
    drawGrid(g);

    // edges
    for (Edge e : world.edges) {
      drawEdge(g, e);
    }
    // objects
    for (SceneObject o : world.objects) {
      drawObject(g, o);
    }
    // locations
    for (Location l : world.locations) {
      drawLocation(g, l);
    }
    // connect pending highlight
    if (connectFrom != null) {
      g.setStroke(Color.LIME);
      g.setLineWidth(2);
      g.strokeOval(screenX(connectFrom.x) - 16, screenY(connectFrom.y) - 16, 32, 32);
    }
    drawSelection(g);

    status.setText(world.name + " — " + world.locations.size() + " locations, "
        + world.edges.size() + " paths, " + world.objects.size() + " objects"
        + (dirty ? " *" : ""));
  }

  private void drawGrid(GraphicsContext g) {
    g.setStroke(Color.rgb(255, 255, 255, 0.05));
    g.setLineWidth(1);
    double step = 50 * scale;
    if (step < 8) {
      return;
    }
    double startX = offsetX % step;
    double startY = offsetY % step;
    for (double x = startX; x < canvas.getWidth(); x += step) {
      g.strokeLine(x, 0, x, canvas.getHeight());
    }
    for (double y = startY; y < canvas.getHeight(); y += step) {
      g.strokeLine(0, y, canvas.getWidth(), y);
    }
  }

  private void drawEdge(GraphicsContext g, Edge e) {
    double[][] pts = edgePoints(e);
    boolean blocked = !e.blocked.isEmpty();
    g.setStroke(blocked ? Color.web("#e57373") : Color.web("#9ccc65"));
    g.setLineWidth(e == selEdge ? 4 : 2.5);
    if (blocked) {
      g.setLineDashes(8, 6);
    } else {
      g.setLineDashes();
    }
    for (int i = 0; i + 1 < pts.length; i++) {
      g.strokeLine(screenX(pts[i][0]), screenY(pts[i][1]),
          screenX(pts[i + 1][0]), screenY(pts[i + 1][1]));
    }
    g.setLineDashes();
    // bends
    for (double[] b : e.bends) {
      g.setFill(Color.web("#fff176"));
      double bx = screenX(b[0]);
      double by = screenY(b[1]);
      g.fillRect(bx - 3, by - 3, 6, 6);
    }
    // cost label at midpoint
    if (pts.length >= 2) {
      int mid = pts.length / 2;
      g.setFill(Color.web("#cccccc"));
      g.setFont(Font.font(10));
      g.setTextAlign(TextAlignment.CENTER);
      g.fillText(String.valueOf(e.cost), screenX(pts[mid][0]), screenY(pts[mid][1]) - 6);
    }
  }

  private void drawLocation(GraphicsContext g, Location l) {
    double x = screenX(l.x);
    double y = screenY(l.y);
    double r = 11;
    Color c = typeColor(l.type);
    if (l.discovered) {
      g.setFill(c);
      g.fillOval(x - r, y - r, r * 2, r * 2);
    } else {
      g.setFill(Color.web("#1f2329"));
      g.fillOval(x - r, y - r, r * 2, r * 2);
    }
    g.setLineWidth(l.reveal.isEmpty() ? 2 : 2);
    g.setStroke(c.brighter());
    if (!l.reveal.isEmpty()) {
      g.setLineDashes(4, 4);
    } else {
      g.setLineDashes();
    }
    g.strokeOval(x - r, y - r, r * 2, r * 2);
    g.setLineDashes();

    g.setFill(Color.WHITE);
    g.setFont(Font.font("System", FontWeight.BOLD, 11));
    g.setTextAlign(TextAlignment.CENTER);
    g.fillText(l.name, x, y - r - 4);
    g.setFill(Color.web("#bbbbbb"));
    g.setFont(Font.font(9));
    g.fillText(l.type.name().toLowerCase(), x, y + r + 11);
  }

  private void drawObject(GraphicsContext g, SceneObject o) {
    double x = screenX(o.x);
    double y = screenY(o.y);
    double s = 7;
    g.setFill(kindColor(o.kind));
    g.fillRect(x - s, y - s, s * 2, s * 2);
    g.setStroke(Color.WHITE);
    g.setLineWidth(1);
    if (!o.reveal.isEmpty()) {
      g.setLineDashes(3, 3);
    } else {
      g.setLineDashes();
    }
    g.strokeRect(x - s, y - s, s * 2, s * 2);
    g.setLineDashes();
    if (!o.label.isEmpty()) {
      g.setFill(Color.web("#dddddd"));
      g.setFont(Font.font(9));
      g.setTextAlign(TextAlignment.CENTER);
      g.fillText(o.label, x, y - s - 3);
    }
  }

  private void drawSelection(GraphicsContext g) {
    g.setStroke(Color.DODGERBLUE);
    g.setLineWidth(2);
    g.setLineDashes();
    switch (selKind) {
      case LOCATION -> { if (selLoc != null) ring(g, screenX(selLoc.x), screenY(selLoc.y), 16); }
      case OBJECT -> { if (selObj != null) ring(g, screenX(selObj.x), screenY(selObj.y), 12); }
      case BEND -> {
        if (selEdge != null && selBend >= 0 && selBend < selEdge.bends.size()) {
          double[] b = selEdge.bends.get(selBend);
          ring(g, screenX(b[0]), screenY(b[1]), 9);
        }
      }
      default -> { }
    }
  }

  private void ring(GraphicsContext g, double x, double y, double r) {
    g.strokeOval(x - r, y - r, r * 2, r * 2);
  }

  // ---------------------------------------------------------------- properties

  private void rebuildProps() {
    propsBox.getChildren().clear();
    propsBox.setPadding(new Insets(8));
    switch (selKind) {
      case LOCATION -> buildLocationProps();
      case OBJECT -> buildObjectProps();
      case EDGE -> buildEdgeProps();
      case BEND -> propsBox.getChildren().add(new Label("Path bend selected.\nDrag to reshape, Delete tool to remove."));
      default -> propsBox.getChildren().add(new Label("Nothing selected.\nUse a tool, or Select and click an item."));
    }
  }

  private void buildLocationProps() {
    Location l = selLoc;
    TextField id = field(l.id, v -> { l.id = v; markDirty(); redraw(); });
    TextField name = field(l.name, v -> { l.name = v; markDirty(); redraw(); });
    ComboBox<LocationType> type = new ComboBox<>();
    type.getItems().setAll(LocationType.values());
    type.setValue(l.type);
    type.setMaxWidth(Double.MAX_VALUE);
    type.setOnAction(e -> { l.type = type.getValue(); markDirty(); redraw(); });
    CheckBox disc = new CheckBox("discovered at start");
    disc.setSelected(l.discovered);
    disc.setOnAction(e -> { l.discovered = disc.isSelected(); markDirty(); redraw(); });
    TextField reveal = field(l.reveal, v -> { l.reveal = v; markDirty(); refreshVars(); redraw(); });
    reveal.setPromptText("flag:visited_town");
    TextField meta = field(World.metaToTextPublic(l.meta), v -> { World.metaFromTextPublic(l.meta, v); markDirty(); });
    meta.setPromptText("ruler=Mara;population=1200");

    propsBox.getChildren().addAll(
        section("Location"),
        labeled("id", id), labeled("name", name), labeled("type", type),
        disc, labeled("reveal binding", reveal), labeled("metadata", meta),
        coordLabel(l.x, l.y));
  }

  private void buildObjectProps() {
    SceneObject o = selObj;
    TextField id = field(o.id, v -> { o.id = v; markDirty(); redraw(); });
    TextField kind = field(o.kind, v -> { o.kind = v; markDirty(); redraw(); });
    kind.setPromptText("chest / npc / monster / portal");
    TextField label = field(o.label, v -> { o.label = v; markDirty(); redraw(); });
    TextField reveal = field(o.reveal, v -> { o.reveal = v; markDirty(); refreshVars(); redraw(); });
    reveal.setPromptText("flag:found_cave");
    TextField meta = field(World.metaToTextPublic(o.meta), v -> { World.metaFromTextPublic(o.meta, v); markDirty(); });
    meta.setPromptText("gold=250;trapped=true");

    propsBox.getChildren().addAll(
        section("Scene Object"),
        labeled("id", id), labeled("kind", kind), labeled("label", label),
        labeled("reveal binding", reveal), labeled("metadata", meta),
        coordLabel(o.x, o.y));
  }

  private void buildEdgeProps() {
    Edge e = selEdge;
    TextField id = field(e.id, v -> { e.id = v; markDirty(); redraw(); });
    ComboBox<String> from = locationCombo(e.from, v -> { e.from = v; markDirty(); redraw(); });
    ComboBox<String> to = locationCombo(e.to, v -> { e.to = v; markDirty(); redraw(); });
    Spinner<Integer> cost = new Spinner<>(0, 9999, e.cost);
    cost.setMaxWidth(Double.MAX_VALUE);
    cost.valueProperty().addListener((o, a, b) -> { e.cost = b; markDirty(); redraw(); });
    CheckBox bidir = new CheckBox("bidirectional");
    bidir.setSelected(e.bidirectional);
    bidir.setOnAction(ev -> { e.bidirectional = bidir.isSelected(); markDirty(); });
    TextField blocked = field(e.blocked, v -> { e.blocked = v; markDirty(); refreshVars(); redraw(); });
    blocked.setPromptText("flag:bridge_built");
    Button clearBends = new Button("Clear " + e.bends.size() + " bend(s)");
    clearBends.setOnAction(ev -> { e.bends.clear(); markDirty(); rebuildProps(); redraw(); });

    propsBox.getChildren().addAll(
        section("Path"),
        labeled("id", id), labeled("from", from), labeled("to", to),
        labeled("cost", cost), bidir, labeled("blocked binding", blocked), clearBends);
  }

  private ComboBox<String> locationCombo(String value, java.util.function.Consumer<String> onChange) {
    ComboBox<String> cb = new ComboBox<>();
    for (Location l : world.locations) {
      cb.getItems().add(l.id);
    }
    cb.setValue(value);
    cb.setMaxWidth(Double.MAX_VALUE);
    cb.setOnAction(e -> { if (cb.getValue() != null) onChange.accept(cb.getValue()); });
    return cb;
  }

  private void refreshVars() {
    varsList.getItems().setAll(world.bindings());
    if (varsList.getItems().isEmpty()) {
      varsList.getItems().add("(none yet)");
    }
  }

  // ----------------------------------------------------------------- selection

  private void select(SelKind kind, Object ref) {
    selKind = kind;
    selLoc = ref instanceof Location l ? l : null;
    selObj = ref instanceof SceneObject o ? o : null;
    selEdge = ref instanceof Edge e ? e : null;
    selBend = -1;
  }

  private void clearSel() {
    selKind = SelKind.NONE;
    selLoc = null; selObj = null; selEdge = null; selBend = -1;
  }

  // --------------------------------------------------------------- background

  private void loadBackground() {
    if (world.mapImage == null || world.mapImage.isEmpty()) {
      return;
    }
    try {
      File img = new File(world.mapImage);
      if (!img.isAbsolute()) {
        img = new File(file.getParentFile(), world.mapImage);
      }
      if (img.exists()) {
        backgroundImage = new Image(img.toURI().toString());
      }
    } catch (Exception ignore) {
      backgroundImage = null;
    }
  }

  private void setBackground() {
    TextInputDialog d = new TextInputDialog(world.mapImage);
    d.setHeaderText("Background image path (relative to the .world file)");
    d.setContentText("path:");
    d.showAndWait().ifPresent(v -> {
      world.mapImage = v.strip();
      loadBackground();
      markDirty();
      redraw();
    });
  }

  // -------------------------------------------------------------- coord transforms

  private double screenX(double wx) { return wx * scale + offsetX; }
  private double screenY(double wy) { return wy * scale + offsetY; }
  private double worldX(double sx) { return (sx - offsetX) / scale; }
  private double worldY(double sy) { return (sy - offsetY) / scale; }

  // ------------------------------------------------------------------- helpers

  private static double dist(double x1, double y1, double x2, double y2) {
    double dx = x1 - x2, dy = y1 - y2;
    return Math.sqrt(dx * dx + dy * dy);
  }

  /** distance from a point to a line segment. */
  private static double pointSeg(double px, double py, double x1, double y1, double x2, double y2) {
    double dx = x2 - x1, dy = y2 - y1;
    double len2 = dx * dx + dy * dy;
    if (len2 == 0) {
      return dist(px, py, x1, y1);
    }
    double t = ((px - x1) * dx + (py - y1) * dy) / len2;
    t = Math.max(0, Math.min(1, t));
    return dist(px, py, x1 + t * dx, y1 + t * dy);
  }

  private static Color typeColor(LocationType t) {
    return switch (t) {
      case TOWN -> Color.web("#64b5f6");
      case CITY -> Color.web("#1e88e5");
      case VILLAGE -> Color.web("#4dd0e1");
      case CASTLE -> Color.web("#ba68c8");
      case DUNGEON -> Color.web("#e57373");
      case CAVE -> Color.web("#8d6e63");
      case RUIN -> Color.web("#a1887f");
      case SHRINE -> Color.web("#fff176");
      case CAMP -> Color.web("#aed581");
      case PORT -> Color.web("#4db6ac");
      case LANDMARK -> Color.web("#ffb74d");
    };
  }

  private static Color kindColor(String kind) {
    return switch (kind == null ? "" : kind.toLowerCase()) {
      case "chest" -> Color.web("#ffd54f");
      case "npc" -> Color.web("#81c784");
      case "monster" -> Color.web("#e53935");
      case "portal" -> Color.web("#ce93d8");
      default -> Color.web("#90a4ae");
    };
  }

  private TextField field(String value, java.util.function.Consumer<String> onChange) {
    TextField tf = new TextField(value);
    tf.setMaxWidth(Double.MAX_VALUE);
    tf.textProperty().addListener((o, a, b) -> onChange.accept(b));
    return tf;
  }

  private static Label coordLabel(double x, double y) {
    return new Label("@ " + Math.round(x) + ", " + Math.round(y));
  }

  private static String uniqueId(String base, List<String> taken) {
    if (!taken.contains(base)) {
      return base;
    }
    int i = 2;
    while (taken.contains(base + i)) {
      i++;
    }
    return base + i;
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

  private static Region spacerV(double h) {
    Region r = new Region();
    r.setPrefHeight(h);
    return r;
  }

  private void markDirty() {
    dirty = true;
  }

  // -------------------------------------------------------------------- Editor

  @Override
  public Node getNode() {
    return root;
  }

  @Override
  public void save() throws Exception {
    world.save(file);
    dirty = false;
    redraw();
  }

  @Override
  public boolean isDirty() {
    return dirty;
  }

  @Override
  public String title() {
    return "world";
  }
}
