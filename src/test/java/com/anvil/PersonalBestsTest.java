package com.anvil;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PB capture: correlating a time with the activity that earned it, and never letting a slower run
 * overwrite a record.
 *
 * <p>Both message orderings are tested because the game uses both — the Inferno prints its duration
 * before the kill count, most bosses print it after.
 */
public class PersonalBestsTest
{
	@Test
	public void killCountThenDuration()
	{
		PersonalBests pbs = new PersonalBests();
		pbs.onActivitySeen("Zulrah", 1_000);
		assertTrue(pbs.onChatLine("Fight duration: 1:36 (new personal best)", 1_100));
		assertEquals(Integer.valueOf(9600), pbs.nextBatch().get("zulrah"));
	}

	@Test
	public void durationThenKillCount()
	{
		PersonalBests pbs = new PersonalBests();
		// The Inferno's ordering: the time lands with nothing to attach it to yet.
		assertFalse(pbs.onChatLine("Duration: 2:58.03 (new personal best)", 1_000));
		assertTrue(pbs.nextBatch().isEmpty());
		pbs.onActivitySeen("TzKal-Zuk", 1_200);
		assertEquals(Integer.valueOf(17803), pbs.nextBatch().get("tzkal-zuk"));
	}

	@Test
	public void aStaleActivityIsNotCredited()
	{
		PersonalBests pbs = new PersonalBests();
		pbs.onActivitySeen("Zulrah", 0);
		// Ten minutes later, an unrelated PB line must not be filed under Zulrah.
		assertFalse(pbs.onChatLine("Duration: 1:00 (new personal best)", 600_000));
		assertTrue(pbs.nextBatch().isEmpty());
	}

	@Test
	public void anOrphanTimeExpiresRatherThanAttachingToTheNextBoss()
	{
		PersonalBests pbs = new PersonalBests();
		pbs.onChatLine("Duration: 1:00 (new personal best)", 0);
		// A different boss, long after: the loose time must not land on it.
		pbs.onActivitySeen("Vorkath", 600_000);
		assertTrue(pbs.nextBatch().isEmpty());
	}

	@Test
	public void onlyFasterTimesReplaceARecord()
	{
		PersonalBests pbs = new PersonalBests();
		pbs.onActivitySeen("Vorkath", 0);
		assertTrue(pbs.onChatLine("Fight duration: 1:30 (new personal best)", 10));
		pbs.onActivitySeen("Vorkath", 1_000);
		// A slower run reported as an explicit PB line still can't raise the record.
		assertFalse(pbs.onChatLine("Fight duration: 2:00. Personal best: 1:30.", 1_010));
		assertEquals(Integer.valueOf(9000), pbs.nextBatch().get("vorkath"));
		// A faster one does.
		pbs.onActivitySeen("Vorkath", 2_000);
		assertTrue(pbs.onChatLine("Fight duration: 1:12 (new personal best)", 2_010));
		assertEquals(Integer.valueOf(7200), pbs.nextBatch().get("vorkath"));
	}

	@Test
	public void seedingAdoptsOnlyImprovements()
	{
		PersonalBests pbs = new PersonalBests();
		pbs.onActivitySeen("Zulrah", 0);
		pbs.onChatLine("Fight duration: 1:36 (new personal best)", 10);

		Map<String, Integer> imported = new HashMap<>();
		imported.put("Zulrah", 12000);  // slower than what we have — ignored
		imported.put("Vorkath", 8000);  // new — adopted
		assertEquals(1, pbs.seed(imported, 100));
		assertEquals(Integer.valueOf(9600), pbs.nextBatch().get("zulrah"));
		assertEquals(Integer.valueOf(8000), pbs.nextBatch().get("vorkath"));
	}

	@Test
	public void flushWaitsForQuiet()
	{
		PersonalBests pbs = new PersonalBests();
		pbs.onActivitySeen("Zulrah", 0);
		pbs.onChatLine("Fight duration: 1:36 (new personal best)", 1_000);
		assertFalse(pbs.isDue(1_000 + PersonalBests.QUIET_MS - 1));
		assertTrue(pbs.isDue(1_000 + PersonalBests.QUIET_MS));
	}

	@Test
	public void stateSurvivesARestartAndIsNotResent()
	{
		PersonalBests pbs = new PersonalBests();
		pbs.onActivitySeen("Chambers of Xeric: Challenge Mode", 0);
		pbs.onChatLine("Duration: 40:00 (new personal best)", 10);
		pbs.onSent(pbs.nextBatch());
		String state = pbs.serializeState();

		PersonalBests restarted = new PersonalBests();
		restarted.restoreState(state);
		assertEquals(1, restarted.knownCount());
		assertEquals(0, restarted.pendingCount());
		// A slower run after the restart is still not a record.
		restarted.onActivitySeen("Chambers of Xeric: Challenge Mode", 100);
		assertFalse(restarted.onChatLine("Duration: 45:00 (new personal best)", 110));
	}

	@Test
	public void anImprovementMidFlightStaysPending()
	{
		PersonalBests pbs = new PersonalBests();
		pbs.onActivitySeen("Zulrah", 0);
		pbs.onChatLine("Fight duration: 1:36 (new personal best)", 10);
		Map<String, Integer> inFlight = pbs.nextBatch();
		// A better time lands while the request is on the wire.
		pbs.onActivitySeen("Zulrah", 20);
		pbs.onChatLine("Fight duration: 1:20 (new personal best)", 30);
		pbs.onSent(inFlight);
		assertEquals(1, pbs.pendingCount());
		assertEquals(Integer.valueOf(8000), pbs.nextBatch().get("zulrah"));
	}

	@Test
	public void ordinaryChatCostsNothingAndRecordsNothing()
	{
		PersonalBests pbs = new PersonalBests();
		pbs.onActivitySeen("Zulrah", 0);
		assertFalse(pbs.onChatLine("Your Zulrah kill count is: 1,204.", 10));
		assertFalse(pbs.onChatLine("Oh dear, you are dead!", 20));
		assertNull(pbs.nextBatch().get("zulrah"));
	}
}
