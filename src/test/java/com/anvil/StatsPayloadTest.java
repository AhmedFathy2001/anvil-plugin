package com.anvil;

import com.anvil.api.StatSubmissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The wire format of the three stat pushes, held to the byte.
 *
 * <p>These expectations were taken from the output of the three separate methods that
 * {@code submitStats} replaced, not generated from it. The server reads these bodies; a field
 * renamed here is a tile that silently stops scoring, and nothing in the plugin would notice.</p>
 *
 * <p>{@link LinkedHashMap} throughout so the array order is the map's insertion order and the
 * assertion can be a literal. In production these arrive as {@code HashMap}s, so the real order is
 * hash order — the server keys off the name field and does not care.</p>
 */
public class StatsPayloadTest
{
	private static Map<String, Integer> map(Object... pairs)
	{
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			m.put((String) pairs[i], (Integer) pairs[i + 1]);
		}
		return m;
	}

	@Test
	public void bossKillCountsGoUnderStatsAsNameAndKc()
	{
		assertEquals("{\"stats\":[{\"name\":\"Vorkath\",\"kc\":412},{\"name\":\"Zulrah\",\"kc\":38}]}",
			StatSubmissions.statsPayload(map("Vorkath", 412, "Zulrah", 38), "stats", "name", "kc").toString());
	}

	@Test
	public void skillXpGoesUnderSkillsAsNameAndXp()
	{
		assertEquals("{\"skills\":[{\"name\":\"Slayer\",\"xp\":13034431}]}",
			StatSubmissions.statsPayload(map("Slayer", 13034431), "skills", "name", "xp").toString());
	}

	/** Activities are the odd one out: the plugin knows the site's own key, so it sends key/value. */
	@Test
	public void activitiesGoUnderActivitiesAsKeyAndValue()
	{
		assertEquals("{\"activities\":[{\"key\":\"clue_scrolls_master\",\"value\":91}]}",
			StatSubmissions.statsPayload(map("clue_scrolls_master", 91), "activities", "key", "value").toString());
	}

	/**
	 * A half-read counter must not reach the server, which would store it as a real one.
	 */
	@Test
	public void anEntryWithANullKeyOrValueIsDroppedRatherThanSent()
	{
		Map<String, Integer> m = map("Vorkath", 412);
		m.put("Zulrah", null);
		m.put(null, 7);
		assertEquals("{\"stats\":[{\"name\":\"Vorkath\",\"kc\":412}]}",
			StatSubmissions.statsPayload(m, "stats", "name", "kc").toString());
	}

	@Test
	public void anEmptyMapStillSendsTheArrayRatherThanOmittingIt()
	{
		assertEquals("{\"stats\":[]}",
			StatSubmissions.statsPayload(map(), "stats", "name", "kc").toString());
	}
}
