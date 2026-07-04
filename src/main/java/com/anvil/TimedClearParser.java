package com.anvil;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, side-effect-free parsing for timed-clear tiles. Kept out of the plugin so the fragile,
 * game-version-specific bits (the exact completion strings) are unit-testable against real chat
 * messages without standing up RuneLite.
 *
 * Two responsibilities:
 *   1. {@link #parseDurationSeconds} — pull a clear time out of a "...duration: mm:ss(.ff)" /
 *      "...completion time: mm:ss" message (raids, gauntlet, colosseum, inferno, ToB/ToA).
 *   2. {@link #messageMatchesActivity} — decide whether a chat line identifies a given tile's
 *      activity, via a signature table. The identifying line is usually NOT the duration line
 *      (it's the adjacent "Your X kill/completion count is:" line), which is why the plugin
 *      correlates the two across a short window.
 *
 * Real message formats this was built against (verify/extend as Jagex changes them):
 *   Inferno      "Duration: 2:58.03 (new personal best)"  +  "Your TzKal-Zuk kill count is: 1."
 *   TzHaar-Ket-Rak "Challenge duration: 8:54.00"  +  "Your completion count for TzHaar-Ket-Rak's ... is: 1."
 *   Nightmare    "Team size: Solo Fight duration: 24:35"  +  "Your Nightmare kill count is: 11."
 *   Colosseum    "Wave 12 completed! Wave duration: 33:32.40"  +  "Your Sol Heredit kill count is: 100."
 *   CoX          "...your raid is complete! Duration: 15:39"  +  "Your completed Chambers of Xeric count is: 306."
 *   Boss timers  "Fight duration: 1:36 (new personal best)"  +  "Your <boss> kill count is: N." — the
 *                generic pair for every boss with an in-game kill timer (GWD, Zulrah, Vorkath, Hydra,
 *                Grotesque Guardians, Muspah, the DT2 four, Scurrius, Araxxor, Yama, etc.); the
 *                identity line names the boss, so the activity-name fallback covers them.
 *   Gauntlet     "Challenge duration: 10:24. Personal best: 7:59."  +  "Your Gauntlet completion count is: 45."
 */
final class TimedClearParser
{
	private TimedClearParser() {}

	// A "duration"/"completion time" keyword followed (within a few non-digits) by an
	// [h:]mm:ss token. Fractional ".ff" tails are intentionally left out of the capture group.
	// Minutes go to three digits: slow Inferno/Zuk clears print raw minutes past 99
	// ("Duration: 172:18. Personal best: 134:52") rather than rolling into h:mm:ss.
	private static final Pattern DURATION = Pattern.compile(
			"(?:duration|completion time)[^0-9]{0,16}((?:\\d{1,2}:)?\\d{1,3}:\\d{2})");

	// Lowercased activity (as configured on the tile) -> substrings that, if present in a nearby
	// chat line, identify that activity. The identifying line is typically the boss/raid
	// kill/completion-count message, which names the boss rather than the activity (e.g. Inferno
	// -> "tzkal-zuk", Colosseum -> "sol heredit"). Default signature is the activity name itself.
	private static final Map<String, List<String>> SIGNATURES = new HashMap<>();

	static
	{
		SIGNATURES.put("inferno", Arrays.asList("tzkal-zuk", "inferno"));
		SIGNATURES.put("fight caves", Arrays.asList("tztok-jad", "fight cave"));
		SIGNATURES.put("chambers of xeric", Collections.singletonList("chambers of xeric"));
		SIGNATURES.put("theatre of blood", Collections.singletonList("theatre of blood"));
		SIGNATURES.put("tombs of amascut", Collections.singletonList("tombs of amascut"));
		SIGNATURES.put("fortis colosseum", Arrays.asList("sol heredit", "colosseum"));
		SIGNATURES.put("colosseum", Arrays.asList("sol heredit", "colosseum"));
		SIGNATURES.put("the nightmare", Collections.singletonList("nightmare"));
		SIGNATURES.put("nightmare", Collections.singletonList("nightmare"));
		SIGNATURES.put("phosani's nightmare", Collections.singletonList("phosani's nightmare"));
		SIGNATURES.put("tzhaar-ket-rak", Collections.singletonList("tzhaar-ket-rak"));
		// The completion line reads "Your Gauntlet completion count is: N" — it never contains
		// "the gauntlet", so a self-signature could never match. "your gauntlet completion" is
		// also Corrupted-safe: CG's line reads "your corrupted gauntlet completion count".
		SIGNATURES.put("the gauntlet", Arrays.asList("your gauntlet completion", "the gauntlet"));
		SIGNATURES.put("corrupted gauntlet", Collections.singletonList("corrupted gauntlet"));
		// Bosses whose kill-count line drops the article ("Your Leviathan kill count is: N"),
		// so a tile named with "The …" would miss on the plain-substring fallback.
		SIGNATURES.put("the leviathan", Collections.singletonList("leviathan"));
		SIGNATURES.put("the whisperer", Collections.singletonList("whisperer"));
		SIGNATURES.put("the hueycoatl", Collections.singletonList("hueycoatl"));
		SIGNATURES.put("the royal titans", Collections.singletonList("royal titans"));
		// "nex" is too short for the contains-fallback — "next" in any nearby line would
		// false-match. Pin it to the real kill-count line.
		SIGNATURES.put("nex", Collections.singletonList("your nex kill count"));
	}

	/** Returns the clear time in whole seconds, or null if the message carries no duration. */
	static Integer parseDurationSeconds(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = DURATION.matcher(message.toLowerCase());
		if (!m.find())
		{
			return null;
		}
		String[] p = m.group(1).split(":");
		try
		{
			int s = p.length == 3
					? Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2])
					: Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
			return s > 0 ? s : null;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	static List<String> signaturesFor(String activity)
	{
		String a = activity == null ? "" : activity.toLowerCase().trim();
		List<String> s = SIGNATURES.get(a);
		if (s != null)
		{
			return s;
		}
		return a.isEmpty() ? Collections.emptyList() : Collections.singletonList(a);
	}

	/** True when {@code message} contains any of the activity's identifying signatures. */
	static boolean messageMatchesActivity(String message, String activity)
	{
		if (message == null)
		{
			return false;
		}
		String lower = message.toLowerCase();
		for (String sig : signaturesFor(activity))
		{
			if (lower.contains(sig))
			{
				return true;
			}
		}
		return false;
	}

	static String formatClock(int totalSeconds)
	{
		int mm = totalSeconds / 60;
		int ss = totalSeconds % 60;
		return mm + ":" + (ss < 10 ? "0" + ss : String.valueOf(ss));
	}
}
