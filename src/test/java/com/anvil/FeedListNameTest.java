package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.JsonArray;
import org.junit.Test;

public class FeedListNameTest
{
	private static JsonArray stops(String... s)
	{
		JsonArray a = new JsonArray();
		for (String x : s)
		{
			a.add(x);
		}
		return a;
	}

	@Test
	public void aSupporterNameIsDrawnLetterByLetter()
	{
		assertEquals("<span style='color:#ff0000'>A</span><span style='color:#800080'>b</span><span style='color:#0000ff'>c</span>",
			FeedList.gradientHtml("Abc", stops("#ff0000", "#0000ff")));
		assertEquals("letters are escaped", "<span style='color:#ff0000'>&lt;</span><span style='color:#0000ff'>b</span>",
			FeedList.gradientHtml("<b", stops("#ff0000", "#0000ff")));
		assertNull(FeedList.gradientHtml("Abc", stops("#ff0000")));
		assertNull(FeedList.gradientHtml("Abc", stops("#ff0000", "nope")));
	}
}
