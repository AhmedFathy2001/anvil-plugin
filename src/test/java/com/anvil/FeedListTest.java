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

		f.box().postActionEvent();
		host.pending.get(1).accept(json("{\"ui\":{\"t\":\"gap\",\"h\":0}}"));
		assertNull(f.errorLine());
		assertEquals("", f.box().getText());
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
}
