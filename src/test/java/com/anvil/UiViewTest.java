package com.anvil;

import com.google.gson.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import net.runelite.client.hiscore.HiscoreSkill;
import org.junit.Test;

import static org.junit.Assert.*;

public class UiViewTest
{
	/** Records what a view asked for and answers when told to. */
	private static final class FakeHost implements UiView.Host
	{
		final List<String> loads = new ArrayList<>();
		final List<Consumer<JsonObject>> pending = new ArrayList<>();
		Map<String, String> postedFields;
		String site;
		String lookedUp;
		String pinned;
		String switched;

		@Override
		public void load(String view, Map<String, String> args, Consumer<JsonObject> done)
		{
			loads.add(view + args);
			pending.add(done);
		}

		@Override
		public void post(String act, Map<String, String> args, Map<String, String> fields, Consumer<JsonObject> done)
		{
			postedFields = fields;
			pending.add(done);
		}

		@Override
		public void site(String path)
		{
			site = path;
		}

		@Override
		public void lookup(String rsn)
		{
			lookedUp = rsn;
		}

		@Override
		public void clan(String slug)
		{
		}

		@Override
		public void pin(int event, int tile, boolean on)
		{
			pinned = event + ":" + tile + ":" + on;
		}

		@Override
		public void switchClan(String slug)
		{
			switched = slug;
		}

		@Override
		public JComponent nativePart(String name)
		{
			return "start-proof".equals(name) ? new JLabel("Starting shot") : null;
		}
	}

	private static JsonObject json(String s)
	{
		return new JsonParser().parse(s).getAsJsonObject();
	}

	private static JsonObject view(String ui)
	{
		return json("{\"ui\":" + ui + "}");
	}

	/** Every label's text under {@code c}, in order. */
	private static List<String> texts(Component c)
	{
		List<String> out = new ArrayList<>();
		if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty())
		{
			out.add(((JLabel) c).getText());
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				out.addAll(texts(child));
			}
		}
		return out;
	}

	private static void click(Component c, String text)
	{
		JLabel l = find(c, text);
		assertNotNull("no label " + text, l);
		Component target = l;
		while (target != null && target.getMouseListeners().length == 0)
		{
			target = target.getParent();
		}
		assertNotNull("nothing to click for " + text, target);
		for (java.awt.event.MouseListener m : target.getMouseListeners())
		{
			m.mouseClicked(new java.awt.event.MouseEvent(target, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
		}
	}

	private static JLabel find(Component c, String text)
	{
		if (c instanceof JLabel && text.equals(((JLabel) c).getText()))
		{
			return (JLabel) c;
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				JLabel l = find(child, text);
				if (l != null)
				{
					return l;
				}
			}
		}
		return null;
	}

	@Test
	public void drawsTextAndRunsItsActions()
	{
		FakeHost host = new FakeHost();
		UiView v = new UiView(host, null, null, null);
		v.render(view("{\"t\":\"col\",\"c\":[{\"t\":\"text\",\"s\":\"Zezima\",\"on\":{\"a\":\"lookup\",\"rsn\":\"Zezima\"}},"
			+ "{\"t\":\"text\",\"s\":\"Site\",\"on\":{\"a\":\"site\",\"path\":\"/p/Zezima\"}},"
			+ "{\"t\":\"text\",\"s\":\"Elsewhere\",\"on\":{\"a\":\"site\",\"path\":\"//evil.example\"}},"
			+ "{\"t\":\"mystery\"}]}"));
		assertEquals(Arrays.asList("Zezima", "Site", "Elsewhere"), texts(v));
		click(v, "Zezima");
		assertEquals("Zezima", host.lookedUp);
		click(v, "Elsewhere");
		assertNull("a link names a path on the site, never another host", host.site);
		click(v, "Site");
		assertEquals("/p/Zezima", host.site);
	}

	@Test
	public void markupInSiteTextStaysText()
	{
		UiView v = new UiView(new FakeHost(), null, null, null);
		v.render(view("{\"t\":\"col\",\"c\":[{\"t\":\"text\",\"s\":\"<html><b>x</b>\"},"
			+ "{\"t\":\"tabs\",\"key\":\"test-markup\",\"tabs\":[{\"label\":\"<html><i>t</i>\",\"c\":{\"t\":\"gap\",\"h\":1}}]},"
			+ "{\"t\":\"input\",\"name\":\"m\",\"kind\":\"select\",\"options\":[{\"v\":\"a\",\"label\":\"<html><u>o</u>\"}]}]}"));
		for (String t : texts(v))
		{
			assertFalse(t, t.toLowerCase().startsWith("<html><b>") || t.toLowerCase().startsWith("<html><i>"));
		}
		Object option = findCombo(v).getItemAt(0);
		assertFalse(String.valueOf(option).startsWith("<html><u>"));
	}

	@Test
	public void panesSwapInPlaceAndLoadsLandInTheirSlot()
	{
		FakeHost host = new FakeHost();
		UiView v = new UiView(host, null, null, null);
		v.render(view("{\"t\":\"col\",\"c\":[{\"t\":\"text\",\"s\":\"Head\"},{\"t\":\"slot\",\"id\":\"log\","
			+ "\"c\":{\"t\":\"text\",\"s\":\"Bosses ›\",\"on\":{\"a\":\"pane\",\"into\":\"log\",\"pane\":\"cat\"}},"
			+ "\"panes\":{\"cat\":{\"t\":\"text\",\"s\":\"Zulrah\",\"on\":{\"a\":\"load\",\"v\":\"clog-page\",\"args\":{\"page\":\"Zulrah\"},\"into\":\"log\"}}}}]}"));
		click(v, "Bosses ›");
		assertEquals(Arrays.asList("Head", "Zulrah"), texts(v));
		click(v, "Zulrah");
		assertEquals("clog-page{page=Zulrah}", host.loads.get(0));
		host.pending.get(0).accept(view("{\"t\":\"text\",\"s\":\"Pet\"}"));
		assertEquals(Arrays.asList("Head", "Pet"), texts(v));
	}

	@Test
	public void anAnswerForAViewNoLongerShownIsDropped()
	{
		FakeHost host = new FakeHost();
		UiView v = new UiView(host, null, null, null);
		v.show("player", Collections.singletonMap("rsn", "A"), "Looking up A…");
		v.show("player", Collections.singletonMap("rsn", "B"), "Looking up B…");
		host.pending.get(1).accept(view("{\"t\":\"text\",\"s\":\"B's card\"}"));
		host.pending.get(0).accept(view("{\"t\":\"text\",\"s\":\"A's card\"}"));
		assertEquals(Collections.singletonList("B's card"), texts(v));
	}

	@Test
	public void aSlotsLoadFromAnEarlierViewDoesNotLandInTheNext()
	{
		FakeHost host = new FakeHost();
		UiView v = new UiView(host, null, null, null);
		String withCas = "{\"t\":\"slot\",\"id\":\"cas\",\"c\":{\"t\":\"text\",\"s\":\"Loading\"},\"load\":{\"a\":\"load\",\"v\":\"cas\",\"into\":\"cas\"}}";
		v.render(view(withCas));
		v.render(view(withCas));
		host.pending.get(0).accept(view("{\"t\":\"text\",\"s\":\"first player's CAs\"}"));
		assertEquals(Collections.singletonList("Loading"), texts(v));
		host.pending.get(1).accept(view("{\"t\":\"text\",\"s\":\"CAs\"}"));
		assertEquals(Collections.singletonList("CAs"), texts(v));
	}

	@Test
	public void aFilterKeepsMatchesAndShowsQueryOnlyRowsWhileTyping()
	{
		UiView v = new UiView(new FakeHost(), null, null, null);
		v.render(view("{\"t\":\"filter\",\"box\":true,\"none\":\"Nothing\",\"c\":[{\"t\":\"grid\",\"cols\":3,\"c\":["
			+ "{\"t\":\"text\",\"s\":\"12\",\"k\":\"Vorkath\"},{\"t\":\"text\",\"s\":\"40\",\"k\":\"Zulrah\"},"
			+ "{\"t\":\"text\",\"s\":\"0\",\"k\":\"Kraken\",\"q\":true}]}]}"));
		assertEquals(Arrays.asList("Nothing", "12", "40"), texts(v).subList(0, 3));
		JTextField search = findField(v);
		search.setText("krak");
		List<String> shown = texts(v);
		assertTrue(shown.contains("0"));
		assertFalse(shown.contains("12"));
		search.setText("nothing like it");
		assertTrue(find(v, "Nothing").isVisible());
	}

	private static JTextField findField(Component c)
	{
		if (c instanceof JTextField)
		{
			return (JTextField) c;
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				JTextField f = findField(child);
				if (f != null)
				{
					return f;
				}
			}
		}
		return null;
	}

	@Test
	public void flipSwapsEveryToggleWithItsKey()
	{
		UiView v = new UiView(new FakeHost(), null, null, null);
		v.render(view("{\"t\":\"toggle\",\"key\":\"test-flip\",\"off\":{\"t\":\"text\",\"s\":\"99\",\"on\":{\"a\":\"flip\",\"key\":\"test-flip\"}},"
			+ "\"lit\":{\"t\":\"text\",\"s\":\"126\",\"on\":{\"a\":\"flip\",\"key\":\"test-flip\"}}}"));
		click(v, "99");
		assertEquals(Collections.singletonList("126"), texts(v));
		click(v, "126");
		assertEquals(Collections.singletonList("99"), texts(v));
	}

	@Test
	public void aPostCarriesTheViewsInputs()
	{
		FakeHost host = new FakeHost();
		UiView v = new UiView(host, null, null, null);
		v.render(view("{\"t\":\"col\",\"c\":[{\"t\":\"input\",\"name\":\"metric\",\"kind\":\"select\",\"value\":\"b\","
			+ "\"options\":[{\"v\":\"a\",\"label\":\"A\"},{\"v\":\"b\",\"label\":\"B\"}]},"
			+ "{\"t\":\"input\",\"name\":\"target\",\"kind\":\"number\",\"value\":\"500\",\"min\":1,\"max\":99},"
			+ "{\"t\":\"text\",\"s\":\"Add\",\"on\":{\"a\":\"post\",\"act\":\"goal-add\"}}]}"));
		click(v, "Add");
		assertEquals("b", host.postedFields.get("metric"));
		assertEquals("a number field keeps to its range", "99", host.postedFields.get("target"));
	}

	@Test
	public void bossNamesFindTheGamesPictures()
	{
		assertEquals(HiscoreSkill.VORKATH, UiView.hiscoreSkill("Vorkath"));
		assertEquals(HiscoreSkill.ALCHEMICAL_HYDRA, UiView.hiscoreSkill("alchemicalHydra"));
		assertEquals(HiscoreSkill.BARROWS_CHESTS, UiView.hiscoreSkill("barrows"));
		assertEquals(HiscoreSkill.CLUE_SCROLL_BEGINNER, UiView.hiscoreSkill("cluesBeginner"));
		assertEquals(HiscoreSkill.CLUE_SCROLL_ALL, UiView.hiscoreSkill("cluesAll"));
		assertEquals(HiscoreSkill.TOMBS_OF_AMASCUT_EXPERT, UiView.hiscoreSkill("tombsOfAmascutExpertMode"));
		assertEquals(HiscoreSkill.THE_CORRUPTED_GAUNTLET, UiView.hiscoreSkill("corruptedGauntlet"));
		assertEquals(HiscoreSkill.THEATRE_OF_BLOOD_HARD_MODE, UiView.hiscoreSkill("theatreOfBloodHardMode"));
		assertEquals(HiscoreSkill.CHAMBERS_OF_XERIC_CHALLENGE_MODE, UiView.hiscoreSkill("chambersOfXericChallengeMode"));
		assertEquals(HiscoreSkill.CHAMBERS_OF_XERIC, UiView.hiscoreSkill("Chambers of Xeric"));
		assertEquals(HiscoreSkill.TZKAL_ZUK, UiView.hiscoreSkill("tzKalZuk"));
		assertNull(UiView.hiscoreSkill("Not a boss"));
	}

	@Test
	public void aSelectFiresWithEveryInputAndAPinThenReloads()
	{
		FakeHost host = new FakeHost();
		UiView v = new UiView(host, null, null, null);
		v.render(view("{\"t\":\"col\",\"c\":[{\"t\":\"input\",\"name\":\"q\",\"kind\":\"search\",\"value\":\"zul\"},"
			+ "{\"t\":\"input\",\"name\":\"status\",\"kind\":\"select\",\"value\":\"all\","
			+ "\"options\":[{\"v\":\"all\",\"label\":\"All\"},{\"v\":\"done\",\"label\":\"Done\"}],"
			+ "\"on\":{\"a\":\"load\",\"v\":\"board\",\"args\":{\"part\":\"rows\"},\"into\":\"tiles\"}},"
			+ "{\"t\":\"slot\",\"id\":\"tiles\",\"c\":{\"t\":\"text\",\"s\":\"Pin\",\"on\":{\"a\":\"pin\",\"event\":7,\"tile\":5,\"on\":true,"
			+ "\"then\":{\"a\":\"load\",\"v\":\"board\",\"args\":{\"tile\":\"5\"},\"into\":\"tiles\"}}}}]}"));
		JComboBox<?> combo = findCombo(v);
		combo.setSelectedIndex(1);
		assertEquals("board{part=rows, q=zul, status=done}", host.loads.get(0));
		click(v, "Pin");
		assertEquals("7:5:true", host.pinned);
		assertEquals("board{tile=5}", host.loads.get(1));
	}

	@Test
	public void aDisabledButtonCannotBePressed()
	{
		UiView v = new UiView(new FakeHost(), null, null, null);
		v.render(view("{\"t\":\"button\",\"label\":\"Next\",\"off\":true,\"on\":{\"a\":\"lookup\",\"rsn\":\"x\"}}"));
		assertFalse(findButton(v).isEnabled());
	}

	@Test
	public void refreshAsksAgainForWhatTheSlotsLastLoaded()
	{
		FakeHost host = new FakeHost();
		UiView v = new UiView(host, null, null, null);
		v.render(view("{\"t\":\"slot\",\"id\":\"tiles\",\"c\":{\"t\":\"text\",\"s\":\"Page 2\",\"on\":{\"a\":\"load\",\"v\":\"board\",\"args\":{\"page\":\"2\"},\"into\":\"tiles\"}}}"));
		click(v, "Page 2");
		v.refresh();
		assertEquals(Arrays.asList("board{page=2}", "board{page=2}"), host.loads);
	}

	private static JComboBox<?> findCombo(Component c)
	{
		if (c instanceof JComboBox)
		{
			return (JComboBox<?>) c;
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				JComboBox<?> f = findCombo(child);
				if (f != null)
				{
					return f;
				}
			}
		}
		return null;
	}

	private static JButton findButton(Component c)
	{
		if (c instanceof JButton)
		{
			return (JButton) c;
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				JButton f = findButton(child);
				if (f != null)
				{
					return f;
				}
			}
		}
		return null;
	}

	@Test
	public void nativePartsCountdownsAndTheClanSwitch()
	{
		FakeHost host = new FakeHost();
		UiView v = new UiView(host, null, null, null);
		String soon = java.time.Instant.now().plusSeconds(3725).toString();
		v.render(view("{\"t\":\"col\",\"c\":[{\"t\":\"native\",\"name\":\"start-proof\"},{\"t\":\"native\",\"name\":\"nope\"},"
			+ "{\"t\":\"countdown\",\"at\":\"" + soon + "\",\"prefix\":\"Next mission in \",\"due\":\"Now\",\"none\":\"No clock\"},"
			+ "{\"t\":\"countdown\",\"at\":null,\"prefix\":\"x\",\"due\":\"y\",\"none\":\"No clock\"},"
			+ "{\"t\":\"text\",\"s\":\"Bravo\",\"on\":{\"a\":\"switch\",\"slug\":\"bravo\"}}]}"));
		List<String> shown = texts(v);
		assertEquals("Starting shot", shown.get(0));
		assertTrue(shown.get(1), shown.get(1).startsWith("Next mission in 1:02:0"));
		assertEquals("No clock", shown.get(2));
		click(v, "Bravo");
		assertEquals("bravo", host.switched);
	}

	@Test
	public void anUnchangedAnswerLeavesTheViewAlone()
	{
		FakeHost host = new FakeHost();
		UiView v = new UiView(host, null, null, null);
		v.show("home", Collections.emptyMap(), null);
		host.pending.get(0).accept(view("{\"t\":\"text\",\"s\":\"Board\"}"));
		Component first = v.getComponent(0);
		v.refresh();
		host.pending.get(1).accept(view("{\"t\":\"text\",\"s\":\"Board\"}"));
		assertSame("the same answer is not redrawn", first, v.getComponent(0));
		v.refresh();
		host.pending.get(2).accept(view("{\"t\":\"text\",\"s\":\"Board 2\"}"));
		assertEquals(Collections.singletonList("Board 2"), texts(v));
	}

	@Test
	public void anOpenPaneSurvivesARefreshButNotGoingSomewhere()
	{
		FakeHost host = new FakeHost();
		UiView v = new UiView(host, null, null, null);
		String board = "{\"t\":\"slot\",\"id\":\"tile\",\"c\":{\"t\":\"text\",\"s\":\"Page 1 %s\",\"on\":{\"a\":\"pane\",\"into\":\"tile\",\"pane\":\"t:5\"}},"
			+ "\"panes\":{\"t:5\":{\"t\":\"text\",\"s\":\"Tile 5 %s\",\"on\":{\"a\":\"load\",\"v\":\"home\"}}}}";
		v.show("board", Collections.emptyMap(), null);
		host.pending.get(0).accept(view(String.format(board, "a", "a")));
		click(v, "Page 1 a");
		v.refresh();
		host.pending.get(1).accept(view(String.format(board, "b", "b")));
		assertEquals("the reader stays on the tile they opened", Collections.singletonList("Tile 5 b"), texts(v));
		click(v, "Tile 5 b");
		host.pending.get(2).accept(view(String.format(board, "c", "c")));
		assertEquals(Collections.singletonList("Page 1 c"), texts(v));
	}

	@Test
	public void aWarmLoadIsFetchedAheadOnceAndALoadCanOpenAPane()
	{
		FakeHost host = new FakeHost();
		UiView v = new UiView(host, null, null, null);
		String warm = "{\"t\":\"warm\",\"on\":{\"a\":\"load\",\"v\":\"board\",\"args\":{\"event\":\"0\"}}}";
		v.render(view("{\"t\":\"col\",\"c\":[" + warm + ",{\"t\":\"text\",\"s\":\"Tile 5\","
			+ "\"on\":{\"a\":\"load\",\"v\":\"board\",\"args\":{\"event\":\"0\"},\"open\":{\"into\":\"tile\",\"pane\":\"t:5\"}}}]}"));
		assertEquals(Collections.singletonList("board{event=0}"), host.loads);
		v.render(view("{\"t\":\"col\",\"c\":[" + warm + ",{\"t\":\"text\",\"s\":\"Tile 5\","
			+ "\"on\":{\"a\":\"load\",\"v\":\"board\",\"args\":{\"event\":\"0\"},\"open\":{\"into\":\"tile\",\"pane\":\"t:5\"}}}]}"));
		assertEquals("a redraw doesn't fetch it again", 1, host.loads.size());
		click(v, "Tile 5");
		host.pending.get(1).accept(view("{\"t\":\"slot\",\"id\":\"tile\",\"c\":{\"t\":\"text\",\"s\":\"List\"},"
			+ "\"panes\":{\"t:5\":{\"t\":\"text\",\"s\":\"Detail 5\"}}}"));
		assertEquals(Collections.singletonList("Detail 5"), texts(v));
	}
}
