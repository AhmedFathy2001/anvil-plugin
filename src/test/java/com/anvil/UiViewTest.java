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
		v.render(view("{\"t\":\"text\",\"s\":\"<html><b>x</b>\"}"));
		assertFalse(texts(v).get(0).startsWith("<html><b>"));
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
}
