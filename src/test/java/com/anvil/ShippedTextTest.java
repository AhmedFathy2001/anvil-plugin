package com.anvil;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The lists that moved out of AnvilPlugin into anvil_text.json load back intact — same entries, same
 * order, emoji and quotes included — through the same Gson path startUp uses.
 */
public class ShippedTextTest
{
	@Test
	public void loadsEveryList() throws Exception
	{
		AnvilPlugin.ShippedText text;
		try (Reader r = new InputStreamReader(AnvilPlugin.class.getResourceAsStream("/anvil_text.json"), StandardCharsets.UTF_8))
		{
			text = new Gson().fromJson(r, AnvilPlugin.ShippedText.class);
		}
		assertEquals(24, text.alwaysNotify.size());
		assertEquals("infernal cape", text.alwaysNotify.get(0));
		assertEquals("fire cape", text.alwaysNotify.get(23));
		assertEquals(Arrays.asList(
			"{name} has been sent to Lumbridge to think about their choices.",
			"{name} forgot to flick Protect from Magic. Classic.",
			"{name} died doing what they loved: not eating.",
			"Press F for {name}.",
			"{name} just speedran a trip to Lumbridge."), text.funDeaths);
		assertEquals(Arrays.asList(
			"Sit. 🪑", "L + ratio.", "Skill issue.", "Couldn't be me.", "GG go next.",
			"Have you tried eating?", "That's gotta hurt.", "Prayer was off, wasn't it?", "Get good. 🤡"), text.deathTaunts);
		assertEquals(Arrays.asList(
			"SPOONED. 🥄", "Way under rate — absolute spoon.", "RNG said \"here you go champ\".",
			"Some of us are 5x dry. Disgusting.", "No skill, all luck. Congrats. 🥄",
			"Hand it over — someone drier deserved that."), text.spoonTaunts);
		assertEquals(7, text.grandmasterQuests.size());
		assertTrue(text.grandmasterQuests.contains("desert treasure ii"));
		assertEquals(20, text.masterQuests.size());
		assertTrue(text.masterQuests.contains("recipe for disaster - culinaromancer"));
		assertTrue(text.masterQuests.contains("legends' quest"));
	}
}
