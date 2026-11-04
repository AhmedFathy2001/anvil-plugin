package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.LinearGradientPaint;
import org.junit.Test;

public class FeedListNameTest
{
	@Test
	public void aSupporterNameKeepsItsColoursOverTheTeams()
	{
		Color jade = new Color(0x2ecc71);
		JsonObject plain = new JsonObject();
		assertEquals("no flair: the team's colour", jade, FeedList.namePaint(plain, jade, 0, 50));

		JsonObject gold = new JsonObject();
		gold.addProperty("whoColor", "#ffd35c");
		assertEquals("a Supporter: their colour over the team's", new Color(0xffd35c), FeedList.namePaint(gold, jade, 0, 50));

		JsonObject patron = new JsonObject();
		patron.addProperty("whoColor", "#ff7ab8");
		JsonArray grad = new JsonArray();
		grad.add("#ffd27a");
		grad.add("#ff7ab8");
		grad.add("#7ae0ff");
		patron.add("grad", grad);
		assertTrue(FeedList.namePaint(patron, jade, 0, 50) instanceof LinearGradientPaint);
		assertEquals(3, ((LinearGradientPaint) FeedList.namePaint(patron, jade, 0, 50)).getColors().length);
	}
}
