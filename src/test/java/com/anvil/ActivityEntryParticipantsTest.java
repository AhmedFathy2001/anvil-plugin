package com.anvil;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public class ActivityEntryParticipantsTest
{
	private static ActivityEntry done(String player, boolean self, String... participants)
	{
		return new ActivityEntry("c1", "t", player, 1, "Kill Brutus 500 times", ActivityEntry.Kind.COMPLETE, 0, self,
			Arrays.asList(participants));
	}

	@Test
	public void aTeamKcTileLeadsWithItsBiggestContributor()
	{
		ActivityEntry e = done(null, false, "Kurrozive", "H A R L E M", "Minjoll");
		assertEquals("Kurrozive & 2 more completed Kill Brutus 500 times", e.summary());
		assertEquals("Kurrozive completed Kill Brutus 500 times — with H A R L E M, Minjoll", e.detail());
	}

	@Test
	public void theFinisherLeadsAndTheRestAreWith()
	{
		ActivityEntry e = done("Minjoll", false, "Kurrozive", "Minjoll");
		assertEquals("Minjoll & 1 more completed Kill Brutus 500 times", e.summary());
		assertEquals("Minjoll completed Kill Brutus 500 times — with Kurrozive", e.detail());
	}

	@Test
	public void aSoloFinishReadsAsBefore()
	{
		assertEquals("You completed Kill Brutus 500 times", done("Me", true).summary());
		assertEquals("Team completed Kill Brutus 500 times", done(null, false).summary());
	}
}
