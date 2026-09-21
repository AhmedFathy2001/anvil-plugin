package com.anvil;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When the plugin may push a live boss KC or skill XP.
 *
 * <p>THE BUG THIS PINS. The gate used to demand an ACTIVE bingo, so being drafted into a board that
 * starts in six weeks turned the live weekly off: the server was asking for `kalphite queen` in
 * trackedKcNames, and the plugin refused to send it because of a bingo neither of them was playing
 * yet. Seven Kalphite Queen kills, "+0 kc" on the Boss of the Week, and nothing visibly broken —
 * the competition moved only on the hiscores sweep, which lags by design.</p>
 *
 * <p>What decides now is what the SERVER asked to watch. Sending during an inactive board is safe
 * there: completions are refused before a start and baselines re-anchor at it.</p>
 */
public class StatPushGateTest
{
	private static PluginConfigResponse cfgWithEvent(boolean started)
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.event = new PluginConfigResponse.EventInfo();
		cfg.event.startDate = started ? "2020-01-01T00:00:00.000Z" : "2099-01-01T00:00:00.000Z";
		return cfg;
	}

	@Test
	public void aWeeklyOnlyConfigMaySend()
	{
		assertTrue(AnvilPlugin.statPushAllowed(new PluginConfigResponse()));
	}

	/** The case that was broken: a board exists, has not started, and the weekly still has to move. */
	@Test
	public void aBoardThatHasNotStartedDoesNotSilenceTheWeekly()
	{
		assertTrue(AnvilPlugin.statPushAllowed(cfgWithEvent(false)));
	}

	@Test
	public void aRunningBoardMaySendToo()
	{
		assertTrue(AnvilPlugin.statPushAllowed(cfgWithEvent(true)));
	}

	@Test
	public void nothingSendsBeforeThereIsAConfig()
	{
		assertFalse("no config means nothing is known to be tracked",
			AnvilPlugin.statPushAllowed(null));
	}

}
