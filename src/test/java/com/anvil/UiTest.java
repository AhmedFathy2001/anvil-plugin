package com.anvil;

import static org.junit.Assert.assertEquals;

import java.awt.Color;
import java.time.Instant;
import org.junit.Test;

public class UiTest
{
	private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

	@Test
	public void agoReadsBothTimestampFormats()
	{
		assertEquals("3h ago", Ui.ago("2026-10-12 09:00:00", NOW));
		assertEquals("3h ago", Ui.ago("2026-10-12T09:00:00.000Z", NOW));
		assertEquals("2d ago", Ui.ago("2026-10-10T12:00:00Z", NOW));
		assertEquals("3w ago", Ui.ago("2026-09-21T12:00:00Z", NOW));
		assertEquals("just now", Ui.ago("2026-10-12T11:59:30Z", NOW));
		assertEquals("", Ui.ago("not a date", NOW));
		assertEquals("", Ui.ago(null, NOW));
	}

	@Test
	public void countsReadTheSameEverywhere()
	{
		assertEquals("812", Ui.count(812));
		assertEquals("-5", Ui.count(-5));
		assertEquals("9,999", Ui.count(9_999));
		assertEquals("12K", Ui.count(12_999));
		assertEquals("999K", Ui.count(999_999));
		assertEquals("2M", Ui.count(2_000_000));
		assertEquals("4.1M", Ui.count(4_199_999));
		assertEquals("4.6B", Ui.count(4_600_000_000L));
	}

	@Test
	public void pluralsAndPercent()
	{
		assertEquals("1 member", Ui.plural(1, "member"));
		assertEquals("1,200 members", Ui.plural(1200, "member"));
		assertEquals("0 matches", Ui.plural(0, "match", "matches"));
		assertEquals(51, Ui.percent(812, 1568));
		assertEquals(0, Ui.percent(5, 0));
		assertEquals(100, Ui.percent(10, 10));
		assertEquals("capped", 100, Ui.percent(12, 10));
	}

	@Test
	public void teamColourFallsBack()
	{
		assertEquals(new Color(0xff0000), Ui.teamColor("#FF0000", Ui.MUTED));
		assertEquals(Ui.MUTED, Ui.teamColor("red", Ui.MUTED));
		assertEquals(Color.WHITE, Ui.teamColor(null, Color.WHITE));
	}
}
