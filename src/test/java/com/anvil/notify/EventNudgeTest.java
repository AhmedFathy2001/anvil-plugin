package com.anvil.notify;

import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.EventInfo;
import com.anvil.api.dto.TrackedDrop;
import com.anvil.api.dto.TrackedValue;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The two client settings a live board silently depends on.
 *
 * <p>THE SHAPE OF BOTH BUGS IS THE SAME: a switch the member is not looking at turns their whole
 * event off, and the board gives them no way to tell. "Auto Submit Drops" governs every tile kind
 * and the stat pushes, not just drops — so one flick of it plays a whole bingo for nothing. The
 * in-game loot-drop line is what corpse-boss loot credits off, and the reminder to enable it used to
 * be gated on clan rare-drop POSTS being on, which has nothing to do with whether the board needs
 * it.</p>
 *
 * <p>Both are asked only while an event is actually running: a plugin that lectures about settings
 * for something that is not happening is noise, and noise is what gets a nudge ignored.</p>
 */
public class EventNudgeTest
{
	private static PluginConfigResponse live()
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.event = new EventInfo();
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

	@Test
	public void autoSubmitOffDuringALiveEventIsWorthSaying()
	{
		assertTrue(NudgeService.autoSubmitBlocksEvent(live(), false));
	}

	@Test
	public void aWorkingSetupIsNotLecturedAt()
	{
		assertFalse("nothing is being lost", NudgeService.autoSubmitBlocksEvent(live(), true));
		assertFalse("no board, nothing to miss", NudgeService.autoSubmitBlocksEvent(null, false));
		assertFalse("a board that hasn't started loses nothing yet",
			NudgeService.autoSubmitBlocksEvent(notStarted(), false));
	}

	@Test
	public void aBoardWithDropTilesNeedsTheInGameDropLine()
	{
		PluginConfigResponse cfg = live();
		cfg.trackedDrops = Collections.singletonList(new TrackedDrop());
		assertTrue(NudgeService.eventNeedsDropLines(cfg));
	}

	@Test
	public void soDoesOneWithValueTiles()
	{
		// Same signal, same reason: a haul from a corpse boss reaches neither tile kind without it.
		PluginConfigResponse cfg = live();
		cfg.trackedValues = Collections.singletonList(new TrackedValue());
		assertTrue(NudgeService.eventNeedsDropLines(cfg));
	}

	@Test
	public void aBoardWithNeitherIsLeftAlone()
	{
		assertFalse("a KC-only board never reads that line", NudgeService.eventNeedsDropLines(live()));
		assertFalse(NudgeService.eventNeedsDropLines(null));

		PluginConfigResponse upcoming = notStarted();
		upcoming.trackedDrops = Collections.singletonList(new TrackedDrop());
		assertFalse("not until it starts", NudgeService.eventNeedsDropLines(upcoming));
	}
}
