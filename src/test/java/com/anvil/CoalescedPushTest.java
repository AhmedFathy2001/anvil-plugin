package com.anvil;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CoalescedPushTest
{
	@Test
	public void aStreakSettlesButNeverHoldsPastTheCap()
	{
		long settle = 15_000;
		assertEquals(settle, CoalescedPush.settleWait(0, 0, settle));
		assertEquals(settle, CoalescedPush.settleWait(40_000, 0, settle));
		assertEquals(5_000, CoalescedPush.settleWait(55_000, 0, settle));   // 60s since the first value
		assertEquals(0, CoalescedPush.settleWait(90_000, 0, settle));      // overdue: now
	}
}
