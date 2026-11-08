package com.anvil;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The tile race page (site lib/pluginUi/raceUi, fixtures written by its test) drawn offscreen the way
 * the panel draws it, to build/race-*.png — and beside the approved mockup when build/race-mockup/
 * holds its artboards — so the two can be compared by eye. Also: a tile opens in place of the page and
 * Back brings the page back with that tile's row outlined.
 */
public class RacePanelRenderTest {
	private static final int WIDTH = 225;
	private static final int BORDER = 6;

	private static final class Host implements UiView.Host {
		@Override public void load(String view, Map<String, String> args, Consumer<JsonObject> done) { }
		@Override public void post(String act, Map<String, String> args, Map<String, String> fields, Consumer<JsonObject> done) { }
		@Override public void site(String path) { }
		@Override public void lookup(String rsn) { }
		@Override public void clan(String slug) { }
		@Override public void pin(int event, int tile, boolean on) { }
		@Override public void switchClan(String slug) { }
		@Override public JComponent nativePart(String name) { return null; }
	}

	private static JsonObject fixture(String name) throws IOException {
		try (Reader r = new InputStreamReader(RacePanelRenderTest.class.getResourceAsStream("race/" + name + ".json"), StandardCharsets.UTF_8)) {
			return new JsonParser().parse(r).getAsJsonObject();
		}
	}

	/** The view inside a panel-sized scroll pane, the way the sidebar holds it. */
	private static JScrollPane panel(UiView view) {
		JPanel body = new JPanel(new BorderLayout());
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setBorder(BorderFactory.createEmptyBorder(BORDER, BORDER, BORDER, BORDER));
		body.add(view, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(null);
		sp.setSize(WIDTH, 2000);
		layOut(sp);
		return sp;
	}

	private static void layOut(Component c) {
		if (c instanceof Container) {
			((Container) c).doLayout();
			for (Component child : ((Container) c).getComponents()) layOut(child);
		}
	}

	private static BufferedImage paint(JScrollPane sp) {
		Component body = sp.getViewport().getView();
		int h = body.getPreferredSize().height;
		body.setSize(WIDTH, h);
		layOut(body);
		int scale = 2;
		BufferedImage img = new BufferedImage(WIDTH * scale, h * scale, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		body.paint(g);
		g.dispose();
		return img;
	}

	private static File out(String name) {
		File dir = new File("build");
		dir.mkdirs();
		return new File(dir, name);
	}

	/** The render beside the mockup's artboard (build/race-mockup/<mock>.png), when there is one. */
	private static void compare(BufferedImage ours, String mock, String name) throws IOException {
		File m = new File("build/race-mockup/" + mock + ".png");
		if (!m.exists()) return;
		BufferedImage theirs = ImageIO.read(m);
		int h = Math.max(ours.getHeight(), theirs.getHeight()) + 40;
		BufferedImage both = new BufferedImage(theirs.getWidth() + ours.getWidth() + 30, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = both.createGraphics();
		g.setColor(new Color(0x111111));
		g.fillRect(0, 0, both.getWidth(), h);
		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
		g.drawString("Mockup", 10, 26);
		g.drawString("Plugin render", theirs.getWidth() + 40, 26);
		g.drawImage(theirs, 0, 40, null);
		g.drawImage(ours, theirs.getWidth() + 30, 40, null);
		g.dispose();
		ImageIO.write(both, "png", out(name));
	}

	private static void render(String fixture, String mock) throws Exception {
		UiView view = new UiView(new Host(), null, null, null);
		SwingUtilities.invokeAndWait(() -> view.render(fixtureOrThrow(fixture)));
		BufferedImage[] img = new BufferedImage[1];
		SwingUtilities.invokeAndWait(() -> img[0] = paint(panel(view)));
		ImageIO.write(img[0], "png", out(fixture + ".png"));
		compare(img[0], mock, fixture + "-vs-mockup.png");
	}

	private static JsonObject fixtureOrThrow(String n) {
		try {
			return fixture(n);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Test
	public void rendersTheThreeStates() throws Exception {
		render("race-start", "Main");
		render("race-mid", "Mid");
		render("race-detail", "Detail");
		assertTrue(out("race-mid.png").exists());
	}

	/** The Clan tab's other pages (site lib/pluginUi/homeUi, fixtures written by its test), beside their mockups. */
	@Test
	public void rendersTheClanTabPages() throws Exception {
		render("clan-list", "Home");
		render("clan-bingo", "Bingo");
		render("clan-home", "ClanHome");
		render("clan-event", "ClanEvent");
		render("clan-event-registered", "ClanEvent");
		render("clan-event-going", "ClanEvent");
		render("clan-weekly", "Weekly");
		assertTrue(out("clan-bingo.png").exists());
	}

	private static List<JLabel> labels(Component c, List<JLabel> out) {
		if (c instanceof JLabel) out.add((JLabel) c);
		if (c instanceof Container) for (Component k : ((Container) c).getComponents()) labels(k, out);
		return out;
	}

	private static JLabel label(Component root, String text) {
		return labels(root, new ArrayList<>()).stream().filter(l -> text.equals(l.getText())).findFirst().orElse(null);
	}

	/** A left press and release on the nearest ancestor that listens. */
	private static void click(Component c) {
		Component target = c;
		while (target.getMouseListeners().length == 0) target = target.getParent();
		for (int id : new int[]{java.awt.event.MouseEvent.MOUSE_PRESSED, java.awt.event.MouseEvent.MOUSE_RELEASED}) {
			java.awt.event.MouseEvent e = new java.awt.event.MouseEvent(target, id, 0,
				id == java.awt.event.MouseEvent.MOUSE_PRESSED ? java.awt.event.InputEvent.BUTTON1_DOWN_MASK : 0, 2, 2, 1, false, java.awt.event.MouseEvent.BUTTON1);
			for (java.awt.event.MouseListener m : target.getMouseListeners()) {
				if (id == java.awt.event.MouseEvent.MOUSE_PRESSED) m.mousePressed(e);
				else m.mouseReleased(e);
			}
		}
	}

	@Test
	public void aTileReplacesThePageAndBackOutlinesIt() throws Exception {
		UiView view = new UiView(new Host(), null, null, null);
		JScrollPane[] sp = new JScrollPane[1];
		SwingUtilities.invokeAndWait(() -> {
			view.render(fixtureOrThrow("race-mid"));
			sp[0] = panel(view);
		});
		JLabel tile = label(view, "Raid or Zulrah unique x3");
		assertNotNull(tile);
		Component page = view.getComponent(0);
		SwingUtilities.invokeAndWait(() -> click(tile));
		assertNull("the page is gone while the tile is open", label(view, "Your road"));
		assertNotNull(label(view, "Back to the race"));
		assertNotNull(label(view, "What counts"));
		assertNull("one way back only", label(view, "All events"));
		SwingUtilities.invokeAndWait(() -> {
			layOut(sp[0]);
			click(label(view, "Back to the race"));
		});
		assertNotNull(label(view, "Your road"));
		JLabel again = label(view, "Raid or Zulrah unique x3");
		assertSame("the page itself comes back, not a rebuild", tile, again);
		Round row = (Round) SwingUtilities.getAncestorOfClass(Round.class, again);
		assertEquals("the row clicked is outlined", ColorScheme.BRAND_ORANGE, row.edge);
		BufferedImage[] img = new BufferedImage[1];
		SwingUtilities.invokeAndWait(() -> img[0] = paint(sp[0]));
		ImageIO.write(img[0], "png", out("race-back.png"));
		assertNotNull(page);
	}
}
