package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.LinearGradientPaint;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

public class FeedListNameTest
{
	@Test
	public void aSupporterNameKeepsItsColoursOnYourOwnLine()
	{
		JsonObject plain = new JsonObject();
		assertEquals("your line, no flair: orange", ColorScheme.BRAND_ORANGE, FeedList.namePaint(plain, true, 0, 50));
		assertEquals(Color.WHITE, FeedList.namePaint(plain, false, 0, 50));

		JsonObject gold = new JsonObject();
		gold.addProperty("whoColor", "#ffd35c");
		assertEquals("your line, a Supporter: their colour", new Color(0xffd35c), FeedList.namePaint(gold, true, 0, 50));

		JsonObject patron = new JsonObject();
		patron.addProperty("whoColor", "#ff7ab8");
		JsonArray grad = new JsonArray();
		grad.add("#ffd27a");
		grad.add("#ff7ab8");
		grad.add("#7ae0ff");
		patron.add("grad", grad);
		assertTrue(FeedList.namePaint(patron, true, 0, 50) instanceof LinearGradientPaint);
		assertEquals(3, ((LinearGradientPaint) FeedList.namePaint(patron, true, 0, 50)).getColors().length);
	}
}
