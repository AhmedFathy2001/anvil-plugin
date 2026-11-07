package com.anvil;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The client setting a live board silently depends on.
 *
 * <p>A switch the member is not looking at turns their event off, and the board gives them no way to
 * tell. The in-game loot-drop line is what corpse-boss loot credits off, and the reminder to enable
 * it used to be gated on clan rare-drop POSTS being on, which has nothing to do with whether the
 * board needs it.</p>
 *
 * <p>There used to be a second one here: "Auto Submit Drops" governed every tile kind and the stat
 * pushes, not just drops, so one flick of it played a whole bingo for nothing — and the nudge was
 * the apology for a switch that should not have existed. The setting is gone, and submitting is
 * simply what the plugin does, so the nudge went with it.</p>
 *
 * <p>Asked only while an event is actually running: a plugin that lectures about settings for
 * something that is not happening is noise, and noise is what gets a nudge ignored.</p>
 */
public class EventNudgeTest
{
	private static PluginConfigResponse live()
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.event = new PluginConfigResponse.EventInfo();
		cfg.event.name = "July Bingo";
		cfg.event.startDate = "2020-01-01T00:00:00.000Z";
		return cfg;
	}

	private static PluginConfigResponse notStarted()
	{
		PluginConfigResponse cfg = live();
		cfg.event.startDate = "2099-01-01T00:00:00.000Z";
		return cfg;
	}

	private static RuleEngine rules(boolean drop, boolean value)
	{
		RuleEngine.Table t = new RuleEngine.Table();
		if (drop) t.drops.put("4151", Collections.singletonList(new RuleEngine.DropRule()));
		if (value) t.values.add(new RuleEngine.ValueRule());
		RuleEngine e = new RuleEngine();
		e.set(t);
		return e;
	}

	@Test
	public void aBoardWithDropTilesNeedsTheInGameDropLine()
	{
		assertTrue(AnvilPlugin.eventNeedsDropLines(live(), rules(true, false)));
	}

	@Test
	public void soDoesOneWithValueTiles()
	{
		// Same signal, same reason: a haul from a corpse boss reaches neither tile kind without it.
		assertTrue(AnvilPlugin.eventNeedsDropLines(live(), rules(false, true)));
	}

	@Test
	public void aBoardWithNeitherIsLeftAlone()
	{
		assertFalse("a KC-only board never reads that line", AnvilPlugin.eventNeedsDropLines(live(), rules(false, false)));
		assertFalse(AnvilPlugin.eventNeedsDropLines(null, rules(true, true)));
		assertFalse("no table yet", AnvilPlugin.eventNeedsDropLines(live(), new RuleEngine()));
		assertFalse("not until it starts", AnvilPlugin.eventNeedsDropLines(notStarted(), rules(true, false)));
	}
}
