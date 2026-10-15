package com.anvil;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class ActiveTaskLabelTest
{
	private static final ClogTaskModel.TaskRow BRUTUS = new ClogTaskModel.TaskRow(1, "Kill Brutus 500 times", 300, 500, 300, false);

	@Test
	public void aKcTileNamesWhoIsLandingTheKills()
	{
		Map<String, Long> gains = new HashMap<>();
		gains.put("You", 45L);
		gains.put("Kurrozive", 312L);
		ConnectionView.ActiveTask t = new ConnectionView.ActiveTask(BRUTUS, Arrays.asList("You", "Kurrozive"), true, gains);
		assertEquals("You 45 · Kurrozive 312", t.workersLabel());
	}

	@Test
	public void moreThanThreeFoldIntoACount()
	{
		Map<String, Long> gains = new HashMap<>();
		gains.put("A", 1_500_000L);
		gains.put("B", 0L);
		ConnectionView.ActiveTask t = new ConnectionView.ActiveTask(BRUTUS, Arrays.asList("A", "B", "C", "D", "E"), false, gains);
		assertEquals("A 1.5M · B · C · +2", t.workersLabel());
	}

	@Test
	public void withoutNumbersItReadsAsBefore()
	{
		ConnectionView.ActiveTask t = new ConnectionView.ActiveTask(BRUTUS, Arrays.asList("You", "Kurrozive"), true);
		assertEquals("You + Kurrozive", t.workersLabel());
	}
}
