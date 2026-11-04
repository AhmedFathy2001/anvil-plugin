package com.anvil;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import org.junit.Test;

public class FeedListRunTest
{
	private static JsonObject line(String who, String at)
	{
		JsonObject o = new JsonObject();
		o.addProperty("who", who);
		o.addProperty("tag", "HOST");
		o.addProperty("at", at);
		return o;
	}

	@Test
	public void aLineJoinsTheRunOnlyWithinFiveMinutes()
	{
		assertTrue(FeedList.sameRun(line("Drenvox", "2026-11-04T01:00:00Z"), line("Drenvox", "2026-11-04T01:04:00Z")));
		assertFalse("16 minutes later: its own name and time", FeedList.sameRun(line("Drenvox", "2026-11-04T01:00:00Z"), line("Drenvox", "2026-11-04T01:16:00Z")));
		assertFalse(FeedList.sameRun(line("Drenvox", "2026-11-04T01:00:00Z"), line("Mossy", "2026-11-04T01:00:30Z")));
		assertTrue("the site's space form parses too", FeedList.sameRun(line("Drenvox", "2026-11-04 01:00:00"), line("Drenvox", "2026-11-04T01:01:00Z")));
	}
}
