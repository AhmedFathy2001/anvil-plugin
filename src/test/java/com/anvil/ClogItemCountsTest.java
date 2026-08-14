package com.anvil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The ordinal helper is what turns a raw clog quantity into the phrase a clan post uses ("their
 * 3rd"), so the English has to be right for the cases that actually occur — including the teens,
 * which every naive implementation gets wrong.
 */
public class ClogItemCountsTest
{
	@Test
	public void ordinalsReadCorrectly()
	{
		assertEquals("1st", ClogItemCounts.ordinal(1));
		assertEquals("2nd", ClogItemCounts.ordinal(2));
		assertEquals("3rd", ClogItemCounts.ordinal(3));
		assertEquals("4th", ClogItemCounts.ordinal(4));
		assertEquals("21st", ClogItemCounts.ordinal(21));
		assertEquals("22nd", ClogItemCounts.ordinal(22));
		assertEquals("103rd", ClogItemCounts.ordinal(103));
	}

	/** 11/12/13 take "th" despite ending in 1/2/3 — the case a %10 switch alone gets wrong. */
	@Test
	public void teensAreNotFirstSecondThird()
	{
		assertEquals("11th", ClogItemCounts.ordinal(11));
		assertEquals("12th", ClogItemCounts.ordinal(12));
		assertEquals("13th", ClogItemCounts.ordinal(13));
		assertEquals("111th", ClogItemCounts.ordinal(111));
		assertEquals("112th", ClogItemCounts.ordinal(112));
	}
}
