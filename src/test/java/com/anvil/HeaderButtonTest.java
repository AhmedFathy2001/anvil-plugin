package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.FontTypeFace;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import org.junit.Test;

/** The title-bar button without a client: drawn once, redrawn after a wipe, tucked away under a long title. */
public class HeaderButtonTest {
	static final int BAR = 1, ANCHOR = 2, FRAME = 3;

	/** A widget that remembers what it is told and hands out children; everything else answers zero. */
	static final class Fake implements InvocationHandler {
		final Map<String, Object> set = new HashMap<>();
		final List<Widget> kids = new ArrayList<>();
		Rectangle bounds = new Rectangle();
		final Widget w = (Widget) Proxy.newProxyInstance(Fake.class.getClassLoader(), new Class<?>[]{Widget.class}, this);

		@Override
		public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] a) {
			var n = m.getName();
			if (n.equals("createChild")) {
				var k = new Fake();
				k.set.put("Type", a[1]);
				kids.add(k.w);
				return k.w;
			}
			if (n.equals("getDynamicChildren") || n.equals("getChildren")) return kids.toArray(new Widget[0]);
			if (n.equals("getBounds")) return bounds;
			if (n.equals("getFont")) return (FontTypeFace) Proxy.newProxyInstance(Fake.class.getClassLoader(),
					new Class<?>[]{FontTypeFace.class}, (p, fm, fa) -> fa == null ? 0 : ((String) fa[0]).length() * 6);
			if (n.equals("equals")) return proxy == a[0];
			if (n.equals("hashCode")) return System.identityHashCode(proxy);
			if (n.startsWith("set") && a != null && a.length == 1) {
				set.put(n.substring(3), a[0]);
				return m.getReturnType() == Widget.class ? proxy : null;
			}
			var v = set.get(n.startsWith("is") ? n.substring(2) : n.startsWith("get") ? n.substring(3) : n);
			if (v != null) return v;
			var r = m.getReturnType();
			return r == int.class ? (Object) 0 : r == boolean.class ? (Object) false : r == String.class ? "" : null;
		}
	}

	final Fake bar = new Fake(), anchor = new Fake(), frame = new Fake();
	final Map<Integer, Widget> open = new HashMap<>(Map.of(BAR, bar.w, ANCHOR, anchor.w, FRAME, frame.w));
	final Client client = (Client) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Client.class},
			(p, m, a) -> m.getName().equals("getWidget") && a.length == 1 ? open.get(a[0]) : null);
	int clicks;
	final HeaderButton button = new HeaderButton(client, BAR, ANCHOR, 109, 33, "Anvil", "Sync to", () -> true, () -> clicks++);

	long shown() {
		return bar.kids.stream().filter(k -> !k.isHidden()).count();
	}

	/** A log this wide, with the title centred across all of it. */
	void width(int w, String title) {
		bar.bounds = new Rectangle(10, 10, w, 30);
		frame.kids.clear();
		var t = frame.w.createChild(-1, WidgetType.TEXT);
		t.setText(title);
		((Fake) Proxy.getInvocationHandler(t)).bounds = new Rectangle(10, 10, w, 30);
	}

	@Test
	public void drawnOnceHoweverOftenItIsAsked() {
		button.render();
		button.render();
		button.refresh();
		assertEquals(HeaderButton.PARTS, bar.kids.size());
		assertEquals(HeaderButton.PARTS, shown());
	}

	@Test
	public void drawnAgainAfterANeighbourClearsTheBar() {
		button.render();
		bar.kids.clear();
		button.render();
		button.render();
		assertEquals(HeaderButton.PARTS, bar.kids.size());
	}

	@Test
	public void overlapIsTheTitlesLastLetterAgainstOurLeftEdge() {
		// Bar 500 wide ending at 510; near slot: our left edge at 510 - 33 - 71 = 406.
		// A 200px title centred in it ends at 10 + (500 + 200) / 2 = 360: clear.
		assertFalse(HeaderButton.overlaps(10, 500, 200, 510, 33));
		// The far slot's left edge is 510 - 109 - 71 = 330: under the title.
		assertTrue(HeaderButton.overlaps(10, 500, 200, 510, 109));
		// Touching within the gap counts.
		assertTrue(HeaderButton.overlaps(0, 400, 200, 400, 100 - HeaderButton.WIDTH + 1));
		assertFalse(HeaderButton.overlaps(0, 400, 200, 400, 100 - HeaderButton.WIDTH - HeaderButton.GAP));
	}

	@Test
	public void tuckedAwayWhileTheTitleReachesItAndBackWhenThereIsRoom() {
		button.frameId = FRAME;
		var title = "Collection Log - 493/1,568"; // 26 chars = 156px
		width(600, title); // title ends at 10 + 378 = 388; our left edge at 610 - 104 = 506
		button.render();
		assertFalse(button.tucked);
		assertEquals(HeaderButton.PARTS, shown());

		width(320, title); // title ends at 10 + 238 = 248; our left edge at 330 - 104 = 226
		button.refresh();
		assertTrue(button.tucked);
		assertEquals(0, shown());
		button.render();
		assertEquals("nothing new is made while tucked", HeaderButton.PARTS, bar.kids.size());

		width(600, title);
		button.refresh();
		assertFalse(button.tucked);
		assertEquals(HeaderButton.PARTS, shown());
	}

	@Test
	public void noTitleToMindMeansNeverTucked() {
		width(100, "Collection Log - 493/1,568");
		button.render(); // frameId unset: the clan window
		assertFalse(button.tucked);
		assertEquals(HeaderButton.PARTS, shown());
	}

	@Test
	public void closedWindowForgetsTheButton() {
		button.render();
		open.remove(BAR);
		button.refresh();
		assertEquals(null, button.parts);
	}

	@Test
	public void aRebuiltNameListIsNoticedByItsFirstRow() {
		var p = new AnvilPlugin();
		p.client = client;
		assertFalse("no list open", p.nameListsRebuilt());
		var list = new Fake();
		open.put(InterfaceID.Friends.LIST, list.w);
		assertFalse("an empty list has nothing to paint", p.nameListsRebuilt());
		list.w.createChild(-1, WidgetType.TEXT);
		assertTrue(p.nameListsRebuilt());
		for (int i = 0; i < AnvilPlugin.FLAIR_LISTS.length; i++) p.listSeen[i] = p.firstRow(i); // as flairNameLists does
		assertFalse("painted: nothing to do until the game makes new rows", p.nameListsRebuilt());
		list.kids.clear();
		list.w.createChild(-1, WidgetType.TEXT);
		assertTrue(p.nameListsRebuilt());
	}

	@Test
	public void interfaceLoadsAskForAPaintBeforeTheNextFrame() {
		var p = new AnvilPlugin();
		p.client = client;
		var loaded = new net.runelite.api.events.WidgetLoaded();
		loaded.setGroupId(InterfaceID.CLANS_SIDEPANEL);
		try {
			p.onWidgetLoaded(loaded);
		} catch (RuntimeException afterTheFlags) {
			// The rest of the handler wants an injected plugin.
		}
		assertTrue(p.flairDue);
		assertFalse(p.headerDue);
		try {
			p.onScriptPostFired(new ScriptPostFired(net.runelite.api.ScriptID.COLLECTION_DRAW_LIST));
		} catch (RuntimeException afterTheFlag) {
			// Reading the page wants an injected plugin too.
		}
		assertTrue("a redrawn log header gets its button back before the frame", p.headerDue);
	}
}
