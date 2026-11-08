package com.anvil;

import com.google.gson.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Every clickable thing on every panel page, clicked the way AWT delivers a click: a press and a release
 * on the deepest component under the pointer that takes mouse events (a label with a tooltip does).
 * Each must do something — call the host, or change the page (a segment lit, a fold opened, a pane
 * swapped in). Then everything new that click showed (a pane's Back, a drill-down) is clicked too.
 */
public class PanelClickTest {
	private static final int WIDTH = 225;
	private static final String[] FIXTURES = {"race-start", "race-mid", "race-detail", "clan-list", "clan-bingo", "clan-home",
		"clan-event", "clan-event-registered", "clan-event-going", "clan-weekly", "feed", "you", "lookup-player", "lookup-clog", "lookup-clan", "lookup-player-img", "you-img", "lookup-clan-img", "lookup-clan-plain", "feed-clan", "lookup-clan-long"};

	private static final class Host implements UiView.Host {
		int calls;
		String view;
		Map<String, String> args;
		JsonObject answer;

		@Override public void load(String view, Map<String, String> args, Consumer<JsonObject> done) {
			calls++;
			this.view = view;
			this.args = args;
			if (answer != null) done.accept(answer);
		}
		@Override public void post(String act, Map<String, String> args, Map<String, String> fields, Consumer<JsonObject> done) { calls++; }
		@Override public void site(String path) { calls++; }
		@Override public void lookup(String rsn) { calls++; }
		@Override public void clan(String slug) { calls++; }
		@Override public void pin(int event, int tile, boolean on) { calls++; }
		@Override public void switchClan(String slug) { calls++; }
		@Override public void openTab(String name, Map<String, String> args) { calls++; }
		@Override public JComponent nativePart(String name) { return null; }
	}

	private static JsonObject fixture(String name) {
		try (Reader r = new InputStreamReader(PanelClickTest.class.getResourceAsStream("race/" + name + ".json"), StandardCharsets.UTF_8)) {
			return new JsonParser().parse(r).getAsJsonObject();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void layOut(Component c) {
		if (c instanceof Container) {
			((Container) c).doLayout();
			for (Component k : ((Container) c).getComponents()) layOut(k);
		}
	}

	/** The page sized to what it wants at panel width, laid out. */
	private static void size(JComponent root) {
		invalidate(root);
		root.setSize(WIDTH, 4000);
		layOut(root);
		root.setSize(WIDTH, root.getPreferredSize().height);
		layOut(root);
	}

	private static void invalidate(Component c) {
		c.invalidate();
		if (c instanceof Container) for (Component k : ((Container) c).getComponents()) invalidate(k);
	}

	/** What AWT would hand a click at {@code p}: the deepest component there that takes mouse events. */
	private static Component target(JComponent root, Point p) {
		Component c = SwingUtilities.getDeepestComponentAt(root, p.x, p.y);
		while (c != null && c.getMouseListeners().length == 0 && c.getMouseMotionListeners().length == 0 && c.getMouseWheelListeners().length == 0)
			c = c.getParent();
		return c;
	}

	private static boolean shown(Component c, JComponent root) {
		for (Component at = c; at != null && at != root; at = at.getParent()) if (!at.isVisible() || at.getWidth() <= 0 || at.getHeight() <= 0) return false;
		return true;
	}

	/** Everything with a click of its own (a hand cursor set on it), in tree order. */
	private static List<Component> clickables(Component c, JComponent root, List<Component> out) {
		if (c.isCursorSet() && c.getCursor().getType() == Cursor.HAND_CURSOR && shown(c, root)
			&& !(c instanceof JList) && !(c instanceof Round && ((Round) c).filled() && c.getParent() instanceof Round))
			out.add(c); // a lit segment of a switch is already picked: clicking it again rightly does nothing
		if (c instanceof Container) for (Component k : ((Container) c).getComponents()) clickables(k, root, out);
		return out;
	}

	private static String describe(Component c) {
		List<String> texts = new ArrayList<>();
		texts(c, texts);
		return c.getClass().getSimpleName() + texts;
	}

	private static void texts(Component c, List<String> out) {
		if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty() && out.size() < 3)
			out.add(((JLabel) c).getText().replaceAll("<[^>]*>", "").trim());
		if (c instanceof Container) for (Component k : ((Container) c).getComponents()) texts(k, out);
	}

	/** What a click can change: which components are there and shown, and how labels and rounds paint. */
	private static String state(Component c) {
		StringBuilder b = new StringBuilder();
		b.append(System.identityHashCode(c)).append(c.isVisible() ? 'v' : 'h');
		if (c instanceof JLabel) b.append(((JLabel) c).getForeground().getRGB());
		if (c instanceof Round) b.append(((Round) c).filled()).append(((Round) c).edge);
		if (c instanceof Container) for (Component k : ((Container) c).getComponents()) b.append('(').append(state(k)).append(')');
		return b.toString();
	}

	/** A real left click at {@code c}'s centre; whether anything happened. */
	private static boolean click(JComponent root, Host host, Component c) {
		Point p = SwingUtilities.convertPoint(c, c.getWidth() / 2, c.getHeight() / 2, root);
		Component t = target(root, p);
		if (t == null) return false;
		lastTarget = describe(t) + "@" + p;
		Point at = SwingUtilities.convertPoint(root, p, t);
		String before = state(root);
		int calls = host.calls;
		t.dispatchEvent(new MouseEvent(t, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, at.x, at.y, 1, false, MouseEvent.BUTTON1));
		t.dispatchEvent(new MouseEvent(t, MouseEvent.MOUSE_RELEASED, 0, 0, at.x, at.y, 1, false, MouseEvent.BUTTON1));
		t.dispatchEvent(new MouseEvent(t, MouseEvent.MOUSE_CLICKED, 0, 0, at.x, at.y, 1, false, MouseEvent.BUTTON1));
		return host.calls != calls || !state(root).equals(before);
	}

	static String lastTarget;

	private static final class Page {
		final Host host = new Host();
		final UiView view = new UiView(host, null, null, null);

		Page(String fixture) {
			UiView.TABS.clear();
			UiView.FLIPS.clear();
			view.render(fixture(fixture));
			size(view);
		}

		List<Component> clickables() {
			return PanelClickTest.clickables(view, view, new ArrayList<>());
		}
	}

	private static List<String> clickAll(String fixture) {
		List<String> broken = new ArrayList<>();
		int n = new Page(fixture).clickables().size();
		assertTrue(fixture + " has something to click", n > 0 || fixture.startsWith("clan-event"));
		for (int i = 0; i < n; i++) {
			Page page = new Page(fixture);
			Component c = page.clickables().get(i);
			String what = fixture + ": " + describe(c);
			Set<Component> before = Collections.newSetFromMap(new IdentityHashMap<>());
			before.addAll(page.clickables());
			if (!click(page.view, page.host, c)) {
				broken.add(what + " got " + lastTarget);
				continue;
			}
			size(page.view);
			// What that click showed (a pane's Back, a section's rows), each clicked once on a fresh copy.
			List<Component> after = page.clickables();
			for (int j = 0; j < after.size(); j++) {
				if (before.contains(after.get(j))) continue;
				Page again = new Page(fixture);
				click(again.view, again.host, again.clickables().get(i));
				size(again.view);
				List<Component> shown = again.clickables();
				if (j >= shown.size()) continue;
				if (!click(again.view, again.host, shown.get(j))) broken.add(what + " > " + describe(shown.get(j)) + " got " + lastTarget);
			}
		}
		return broken;
	}

	@Test
	public void everyClickOnEveryPageDoesSomething() throws Exception {
		List<String> broken = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> {
			for (String f : FIXTURES) broken.addAll(clickAll(f));
		});
		assertEquals("clicks that did nothing:\n" + String.join("\n", broken), 0, broken.size());
	}

	/** Lookup's Players | Clans (drawn by the panel over the view): each segment picks. */
	@Test
	public void everySegmentOfASwitchPicks() throws Exception {
		List<String> picked = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> {
			JComponent strip = UiView.segments(Arrays.asList("Players", "Clans"), "Players", picked::add);
			size(strip);
			for (Component seg : strip.getComponents()) click(strip, new Host(), seg);
		});
		assertEquals(Arrays.asList("Players", "Clans"), picked);
	}

	private static JLabel label(Component c, String text) {
		if (c instanceof JLabel && text.equals(((JLabel) c).getText())) return (JLabel) c;
		if (c instanceof Container) for (Component k : ((Container) c).getComponents()) {
			JLabel l = label(k, text);
			if (l != null && l.isVisible()) return l;
		}
		return null;
	}

	/** Log → a category → one of its pages (a site answer into the slot) → Back lands on the category. */
	@Test
	public void backFromALogPageLandsOnItsCategory() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			Page page = new Page("lookup-clog");
			click(page.view, page.host, label(page.view, "Log"));
			size(page.view);
			click(page.view, page.host, label(page.view, "7/12 · 58%"));
			size(page.view);
			page.host.answer = new JsonParser().parse("{\"ui\":{\"t\":\"col\",\"c\":[{\"t\":\"text\",\"s\":\"Zulrah\",\"font\":\"bold\","
				+ "\"icon\":{\"t\":\"icon\",\"glyph\":\"chevron-left\",\"w\":10,\"h\":10},\"tip\":\"Back\",\"on\":{\"a\":\"pane\",\"into\":\"prof\",\"pane\":\"cat:Bosses\"}}]}}").getAsJsonObject();
			click(page.view, page.host, label(page.view, "812 kc"));
			size(page.view);
			assertNull("the page replaced the category", label(page.view, "812 kc"));
			assertTrue("Back clicked", click(page.view, page.host, label(page.view, "Zulrah")));
			size(page.view);
			assertNotNull("back on the category", label(page.view, "812 kc"));
			int calls = page.host.calls;
			page.view.refresh();
			size(page.view);
			assertEquals("a refresh after Back asks for nothing the reader left", calls, page.host.calls);
			assertNotNull("still on the list after a refresh", label(page.view, "812 kc"));
		});
	}

	private static Component find(Component c, String text) {
		String t = c instanceof JLabel ? ((JLabel) c).getText() : c instanceof AbstractButton ? ((AbstractButton) c).getText() : null;
		if (t != null && text.equals(t.replaceAll("<[^>]*>", "").trim()) && c.isVisible()) return c;
		if (c instanceof Container) for (Component k : ((Container) c).getComponents()) {
			Component f = find(k, text);
			if (f != null) return f;
		}
		return null;
	}

	private static Component tip(Component c, String text) {
		if (c instanceof JComponent && ((JComponent) c).getToolTipText() != null && ((JComponent) c).getToolTipText().contains(text) && shown(c, (JComponent) c)) return c;
		if (c instanceof Container) for (Component k : ((Container) c).getComponents()) {
			Component f = tip(k, text);
			if (f != null) return f;
		}
		return null;
	}

	private static final String PAGE = "{\"ui\":{\"t\":\"col\",\"c\":[{\"t\":\"text\",\"s\":\"BossPage\",\"font\":\"bold\","
		+ "\"icon\":{\"t\":\"icon\",\"glyph\":\"chevron-left\",\"w\":10,\"h\":10},\"tip\":\"Back\",\"on\":{\"a\":\"pane\",\"into\":\"prof\",\"pane\":\"main\"}}]}}";

	/** A boss on the Bosses tab opens its log page as a page of its own; Back returns to Bosses. */
	@Test
	public void aBossClickOpensItsLogPageAndBackReturnsToBosses() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			boolean opened = false;
			for (int i = 0; i < 60 && !opened; i++) {
				Page page = new Page("lookup-player");
				page.host.answer = new JsonParser().parse(PAGE).getAsJsonObject();
				click(page.view, page.host, find(page.view, "Bosses"));
				size(page.view);
				List<Component> cs = page.clickables();
				if (i >= cs.size()) break;
				click(page.view, page.host, cs.get(i));
				size(page.view);
				if (!"clog-page".equals(page.host.view)) continue;
				opened = true;
				assertEquals("main", page.host.args.get("back"));
				assertNotNull("the page replaced the card", find(page.view, "BossPage"));
				assertNull("Bosses tab gone while the page is open", find(page.view, "Bosses"));
				assertTrue(click(page.view, page.host, find(page.view, "BossPage")));
				size(page.view);
				assertNotNull("Back lands on the profile", find(page.view, "Bosses"));
			}
			assertTrue("some boss cell opens its page", opened);
		});
	}

	/** Typing an item or page name in the Log tab lists its page; clicking it opens that page directly. */
	@Test
	public void theLogSearchListsPagesAndOpensOne() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			Page page = new Page("lookup-clog");
			click(page.view, page.host, find(page.view, "Log"));
			size(page.view);
			assertNull("pages are listed only while searching", find(page.view, "Vorkath"));
			List<javax.swing.text.JTextComponent> boxes = new ArrayList<>();
			boxes(page.view, boxes);
			javax.swing.text.JTextComponent box = boxes.get(boxes.size() - 1);
			box.setText("head");
			size(page.view);
			Component row = find(page.view, "Vorkath");
			assertNotNull("an item's name finds its page", row);
			assertTrue(click(page.view, page.host, row));
			assertEquals("clog-page", page.host.view);
			assertEquals("Vorkath", page.host.args.get("page"));
			assertEquals("main", page.host.args.get("back"));
		});
	}

	private static void boxes(Component c, List<javax.swing.text.JTextComponent> out) {
		if (c instanceof javax.swing.text.JTextComponent && c.isShowing() == c.isShowing()) out.add((javax.swing.text.JTextComponent) c);
		if (c instanceof Container) for (Component k : ((Container) c).getComponents()) boxes(k, out);
	}

	/** A goal's visible x asks inline first; No puts it back. */
	@Test
	public void aGoalsRemoveButtonAsksInline() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			Page page = new Page("you");
			Component x = tip(page.view, "Remove goal");
			assertNotNull("each goal has a visible remove x", x);
			assertTrue(click(page.view, page.host, x));
			size(page.view);
			Component no = find(page.view, "No");
			assertNotNull("asked inline", no);
			assertNotNull(find(page.view, "Yes, remove"));
			assertTrue(click(page.view, page.host, no));
			size(page.view);
			assertNull("back to the goal", find(page.view, "No"));
		});
	}

	/** A filter click that is a site round trip shows "Loading…" in its slot at once, then the answer. */
	@Test
	public void aSlotLoadShowsLoadingAtOnce() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			Page page = new Page("lookup-clog");
			JsonObject view = new JsonParser().parse("{\"ui\":{\"t\":\"slot\",\"id\":\"cas\",\"c\":{\"t\":\"text\",\"s\":\"Old list\"}}}").getAsJsonObject();
			page.view.render(view);
			size(page.view);
			assertNotNull(find(page.view, "Old list"));
			Host held = new Host();
			UiView v = new UiView(held, null, null, null);
			v.render(view);
			size(v);
			v.act(new JsonParser().parse("{\"a\":\"load\",\"v\":\"cas\",\"into\":\"cas\"}").getAsJsonObject());
			size(v);
			assertNotNull("loading shows at once", find(v, "Loading..."));
			assertNull(find(v, "Old list"));
		});
	}
}
