package com.anvil;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JComponent;
import org.junit.Test;

import static org.junit.Assert.*;

public class FeedListTest
{
	private static final class Host implements UiView.Host
	{
		final List<String> posts = new ArrayList<>();
		int gameFocus;

		@Override
		public void gameFocus()
		{
			gameFocus++;
		}
		final List<Consumer<JsonObject>> pending = new ArrayList<>();

		@Override
		public void load(String view, Map<String, String> args, Consumer<JsonObject> done)
		{
			pending.add(done);
		}

		@Override
		public void post(String act, Map<String, String> args, Map<String, String> fields, Consumer<JsonObject> done)
		{
			posts.add(act + args + fields);
			pending.add(done);
		}

		@Override
		public void site(String path)
		{
		}

		@Override
		public void lookup(String rsn)
		{
		}

		@Override
		public void clan(String slug)
		{
		}

		@Override
		public void pin(int event, int tile, boolean on)
		{
		}

		@Override
		public void switchClan(String slug)
		{
		}

		@Override
		public JComponent nativePart(String name)
		{
			return null;
		}
	}

	private static JsonObject json(String s)
	{
		return new JsonParser().parse(s).getAsJsonObject();
	}

	private static FeedList find(Component c)
	{
		if (c instanceof FeedList)
		{
			return (FeedList) c;
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				FeedList f = find(child);
				if (f != null)
				{
					return f;
				}
			}
		}
		return null;
	}

	private static String row(String id)
	{
		return "{\"id\":\"" + id + "\",\"title\":\"Item " + id + "\",\"who\":\"Rikkert\",\"at\":\"2026-11-04 10:00:00\"}";
	}

	private static UiView drawn(Host host, String feed)
	{
		UiView v = new UiView(host, null, null, null);
		v.render(json("{\"ui\":{\"t\":\"col\",\"c\":[" + feed + "]}}"));
		return v;
	}

	@Test
	public void drawsItsRowsThenGrowsFromPushNewestFirstWithoutDuplicates()
	{
		Host host = new Host();
		UiView v = drawn(host, "{\"t\":\"feed\",\"id\":\"moments:clan:3\",\"rows\":[" + row("m2") + "," + row("m1") + "]}");
		FeedList f = find(v);
		assertNotNull(f);
		assertEquals(2, f.rowCount());

		v.feedPush(json("{\"t\":\"rows\",\"feeds\":[\"moments:clan:3\",\"moments:clan:3:drops\"],\"rows\":[" + row("m3") + "]}"));
		v.feedPush(json("{\"t\":\"rows\",\"feeds\":[\"moments:clan:3\"],\"rows\":[" + row("m5") + "," + row("m4") + "]}"));
		v.feedPush(json("{\"t\":\"rows\",\"feeds\":[\"moments:clan:3\"],\"rows\":[" + row("m2") + "]}"));
		v.feedPush(json("{\"t\":\"rows\",\"feeds\":[\"moments:clan:9\"],\"rows\":[" + row("x1") + "]}"));
		assertEquals("nothing lands before the flush", 2, f.rowCount());
		f.flush();
		assertEquals(5, f.rowCount());
		assertEquals("m5", f.rowId(0));
		assertEquals("m4", f.rowId(1));
		assertEquals("m3", f.rowId(2));
		assertEquals("m2", f.rowId(3));
	}

	@Test
	public void keepsToItsCap()
	{
		Host host = new Host();
		UiView v = drawn(host, "{\"t\":\"feed\",\"id\":\"chat:9\",\"cap\":20,\"rows\":[]}");
		FeedList f = find(v);
		StringBuilder rows = new StringBuilder();
		for (int i = 30; i > 0; i--)
		{
			rows.append(i < 30 ? "," : "").append(row("c" + i));
		}
		v.feedPush(json("{\"t\":\"rows\",\"feeds\":[\"chat:9\"],\"rows\":[" + rows + "]}"));
		f.flush();
		assertEquals(20, f.rowCount());
		assertEquals("c30", f.rowId(0));
		assertEquals("c11", f.rowId(19));
	}

	@Test
	public void aDeleteTakesTheRowOutEvenBeforeItLanded()
	{
		Host host = new Host();
		UiView v = drawn(host, "{\"t\":\"feed\",\"id\":\"chat:9\",\"rows\":[" + row("c1") + "]}");
		FeedList f = find(v);
		v.feedPush(json("{\"t\":\"rows\",\"feeds\":[\"chat:9\"],\"rows\":[" + row("c2") + "]}"));
		v.feedPush(json("{\"t\":\"rowsdel\",\"feeds\":[\"chat:9\"],\"ids\":[\"c2\",\"c1\"]}"));
		f.flush();
		assertEquals(0, f.rowCount());
	}

	@Test
	public void theBoxSendsWithoutRedrawingAndShowsAnErrorLine()
	{
		Host host = new Host();
		UiView v = drawn(host, "{\"t\":\"feed\",\"id\":\"chat:9:team:4\",\"rows\":[],"
			+ "\"send\":{\"act\":\"chat-send\",\"args\":{\"e\":\"9\",\"to\":\"team\"},\"hint\":\"Message your team\",\"max\":10}}");
		FeedList f = find(v);
		f.box().setText("hello there everyone");
		assertEquals("capped at max", "hello ther", f.box().getText());
		f.box().postActionEvent();
		assertEquals(1, host.posts.size());
		assertTrue(host.posts.get(0).startsWith("chat-send{"));
		assertTrue(host.posts.get(0).contains("msg=hello ther"));
		host.pending.get(0).accept(json("{\"ui\":{\"t\":\"text\",\"s\":\"Slow mode — wait 4s.\",\"color\":\"error\"}}"));
		assertEquals("Slow mode - wait 4s.", f.errorLine());
		assertEquals("kept for another try", "hello ther", f.box().getText());
		assertSame("the view was not redrawn", f, find(v));

		assertEquals("a refused line keeps the keyboard in the box", 0, host.gameFocus);
		f.box().postActionEvent();
		host.pending.get(1).accept(json("{\"ui\":{\"t\":\"gap\",\"h\":0}}"));
		assertNull(f.errorLine());
		assertEquals("", f.box().getText());
		assertEquals("a sent line hands the keyboard back to the game", 1, host.gameFocus);
	}

	@Test
	public void aLockGreysTheBoxOutOnEveryListOfThatBoard()
	{
		Host host = new Host();
		UiView v = drawn(host, "{\"t\":\"feed\",\"id\":\"chat:9:team:4\",\"rows\":[],\"send\":{\"act\":\"chat-send\",\"hint\":\"x\"}}");
		FeedList f = find(v);
		assertTrue(f.box().isEnabled());
		v.feedPush(json("{\"t\":\"rowslock\",\"feeds\":[\"chat:9\"],\"locked\":\"The host has paused chat.\"}"));
		assertFalse(f.box().isEnabled());
		v.feedPush(json("{\"t\":\"rowslock\",\"feeds\":[\"chat:99\"],\"locked\":null}"));
		assertFalse("another board's lock is not ours", f.box().isEnabled());
		v.feedPush(json("{\"t\":\"rowslock\",\"feeds\":[\"chat:9\"],\"locked\":null}"));
		assertTrue(f.box().isEnabled());
	}

	@Test
	public void aRedrawnViewForgetsItsOldLists()
	{
		Host host = new Host();
		UiView v = drawn(host, "{\"t\":\"feed\",\"id\":\"chat:9\",\"rows\":[]}");
		FeedList old = find(v);
		v.render(json("{\"ui\":{\"t\":\"col\",\"c\":[{\"t\":\"feed\",\"id\":\"chat:9:team:4\",\"rows\":[]}]}}"));
		FeedList now = find(v);
		v.feedPush(json("{\"t\":\"rows\",\"feeds\":[\"chat:9\",\"chat:9:team:4\"],\"rows\":[" + row("c1") + "]}"));
		old.flush();
		now.flush();
		assertEquals(0, old.rowCount());
		assertEquals(1, now.rowCount());
	}

	@Test
	public void serverTextNeverRendersAsHtml()
	{
		Host host = new Host();
		UiView v = drawn(host, "{\"t\":\"feed\",\"id\":\"chat:9\",\"empty\":\"<html><img src=x>\",\"rows\":[],"
			+ "\"send\":{\"act\":\"chat-send\",\"hint\":\"<html><b>hi\"}}");
		FeedList f = find(v);
		f.box().setText("x");
		f.box().postActionEvent();
		host.pending.get(0).accept(json("{\"ui\":{\"t\":\"text\",\"s\":\"<html><font size=99>boom\",\"color\":\"error\"}}"));
		assertFalse(f.errorLine().startsWith("<html>"));
	}

	@Test
	public void aChatReadsOldestFirstAndGrowsAtTheBottom()
	{
		Host host = new Host();
		UiView v = drawn(host, "{\"t\":\"feed\",\"id\":\"chat:9\",\"cap\":20,\"rows\":[" + row("c2") + "," + row("c1") + "],"
			+ "\"send\":{\"act\":\"chat-send\",\"hint\":\"x\"}}");
		FeedList f = find(v);
		assertEquals("c1", f.rowId(0));
		assertEquals("c2", f.rowId(1));
		v.feedPush(json("{\"t\":\"rows\",\"feeds\":[\"chat:9\"],\"rows\":[" + row("c4") + "," + row("c3") + "]}"));
		f.flush();
		assertEquals("c1", f.rowId(0));
		assertEquals("c4", f.rowId(3));
		StringBuilder more = new StringBuilder();
		for (int i = 30; i >= 5; i--)
		{
			more.append(i < 30 ? "," : "").append(row("c" + i));
		}
		v.feedPush(json("{\"t\":\"rows\",\"feeds\":[\"chat:9\"],\"rows\":[" + more + "]}"));
		f.flush();
		assertEquals(20, f.rowCount());
		assertEquals("the oldest went from the top", "c11", f.rowId(0));
		assertEquals("c30", f.rowId(19));
	}

	@Test
	public void wrapsWordsToTheWidthAndBreaksAWordTooLongForALine()
	{
		java.awt.FontMetrics fm = new javax.swing.JLabel().getFontMetrics(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		int w = fm.stringWidth("abcdefghij");
		assertEquals(java.util.Arrays.asList("gz on the", "fang"), FeedList.wrap("gz on the fang", fm, fm.stringWidth("gz on the")));
		java.util.List<String> broken = FeedList.wrap("aaaaaaaaaaaaaaaaaaaaaaaaa", fm, w);
		for (String line : broken)
		{
			assertTrue(fm.stringWidth(line) <= w);
		}
		assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaa", String.join("", broken));
		assertEquals(java.util.Collections.singletonList(""), FeedList.wrap("", fm, w));
	}

	@Test
	public void aSupportersGradientIsReadAndBadStopsAreRefused()
	{
		JsonObject ok = json("{\"grad\":[\"#ff9a5a\",\"#e05ad0\",\"#7a8cff\"]}");
		assertEquals(3, FeedList.stops(ok).length);
		assertNull(FeedList.stops(json("{\"grad\":[\"#ff9a5a\"]}")));
		assertNull(FeedList.stops(json("{\"grad\":[\"#ff9a5a\",\"red\"]}")));
		assertNull(FeedList.stops(json("{}")));
		assertTrue(FeedList.isRole("HOST"));
		assertFalse(FeedList.isRole("Ruby"));
	}

	@Test
	public void aDeathRowsChipsReadAndClickRightToLeft()
	{
		java.awt.FontMetrics fm = new javax.swing.JLabel().getFontMetrics(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		com.google.gson.JsonArray reacts = json("{\"r\":[{\"kind\":\"sad\",\"label\":\"Sad\",\"n\":0},{\"kind\":\"sit\",\"label\":\"Sit\",\"n\":3,\"mine\":true}]}").getAsJsonArray("r");
		assertEquals("Sad", FeedList.chipText(reacts.get(0).getAsJsonObject()));
		assertEquals("Sit 3", FeedList.chipText(reacts.get(1).getAsJsonObject()));
		String html = FeedList.reactsHtml(reacts);
		assertTrue(html.contains(">Sad</font>") && html.contains(">Sit 3</font>"));
		assertTrue("the viewer's own is lit", html.indexOf("Sit 3") > html.lastIndexOf(String.format("#%06x", net.runelite.client.ui.ColorScheme.BRAND_ORANGE.getRGB() & 0xFFFFFF)));
		int sit = fm.stringWidth("Sit 3");
		assertEquals(1, FeedList.reactAt(reacts, fm, 2));
		assertEquals(1, FeedList.reactAt(reacts, fm, sit - 1));
		assertEquals(0, FeedList.reactAt(reacts, fm, sit + fm.stringWidth("   ") + 2));
		assertEquals("left of every chip is the row itself", -1, FeedList.reactAt(reacts, fm, 400));
	}

	@Test
	public void aChipsHoverZoneIsItsClickZoneAndCarriesWhoGaveIt()
	{
		java.awt.FontMetrics fm = new javax.swing.JLabel().getFontMetrics(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		JsonObject death = json("{\"reacts\":[{\"kind\":\"sad\",\"label\":\"Sad\",\"n\":1,\"who\":\"Sad \u00b7 Mossy\"},"
			+ "{\"kind\":\"sit\",\"label\":\"Sit\",\"n\":3,\"who\":\"Sit \u00b7 Mossy, Rikkert and 1 other\"}]}");
		int sit = fm.stringWidth("Sit 3");
		assertEquals("Sit \u00b7 Mossy, Rikkert and 1 other", UiView.str(FeedList.chipAt(death, fm, 2), "who"));
		assertEquals("Sad \u00b7 Mossy", UiView.str(FeedList.chipAt(death, fm, sit + fm.stringWidth("   ") + 2), "who"));
		assertNull("left of every chip is the row itself", FeedList.chipAt(death, fm, 400));

		JsonObject drop = json("{\"gz\":{\"n\":2,\"who\":\"GZ \u00b7 Mossy and Rikkert\"}}");
		assertEquals("GZ \u00b7 Mossy and Rikkert", UiView.str(FeedList.chipAt(drop, fm, 10), "who"));
		assertEquals("the inset still counts", "GZ \u00b7 Mossy and Rikkert", UiView.str(FeedList.chipAt(drop, fm, -4), "who"));
		assertNull("past the GZ's 44px is the row", FeedList.chipAt(drop, fm, 38));
		assertNull(FeedList.chipAt(json("{\"title\":\"Pet\"}"), fm, 2));
		// A site from before the who line: the chip is there, with nothing to say.
		assertEquals("", UiView.str(FeedList.chipAt(json("{\"gz\":{\"n\":2}}"), fm, 2), "who"));
	}

	@Test
	public void momentNameWearsSupporterColoursAndTheTeam()
	{
		java.awt.Color white = java.awt.Color.WHITE, team = new java.awt.Color(0xff8800);
		JsonObject patron = new JsonParser().parse("{\"who\":\"Drenvox\",\"whoColor\":\"#f5c542\",\"grad\":[\"#f5c542\",\"#c86bff\"],\"tag\":\"Lab\",\"tagColor\":\"#ff8800\"}").getAsJsonObject();
		String html = FeedList.whoHtml(patron, "Drenvox", "Lab", new java.awt.Color(0xf5c542), team);
		assertTrue(html, html.startsWith("<html><font color='#f5c542'>D</font>"));
		assertTrue(html, html.contains("<font color='#c86bff'>x</font>"));
		assertTrue(html, html.endsWith("<font color='#ff8800'> \u00b7 Lab</font>"));

		JsonObject plain = new JsonParser().parse("{\"who\":\"Mossy\",\"whoColor\":\"muted\",\"tag\":\"Ruby\"}").getAsJsonObject();
		assertEquals("<html><font color='#ff8800'>Mossy</font><font color='#ff8800'> \u00b7 Ruby</font>",
				FeedList.whoHtml(plain, "Mossy", "Ruby", white, team));

		JsonObject sneaky = new JsonParser().parse("{\"who\":\"<b>x\"}").getAsJsonObject();
		assertFalse(FeedList.whoHtml(sneaky, "<b>x", "", white, team).contains("<b>"));
	}
}
