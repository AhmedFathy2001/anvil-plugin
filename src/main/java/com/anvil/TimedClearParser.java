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
 *   Barracuda Trials (Sailing)
 *                "You have completed the Gwenith Glide and achieved the Marlin rank."
 *                + "Time: 6:04.20 (new personal best)."  +  "Your Gwenith Glide completion count is: 49"
 *                — no signature entry needed: both flanking lines name the trial, so the
 *                activity-name fallback matches tiles like "gwenith glide" / "tempor tantrum".
 */
final class TimedClearParser
{
	private TimedClearParser() {}

	// A duration keyword followed (within a few non-digits) by an [h:]mm:ss token. Fractional
	// ".ff" tails are intentionally left out of the capture group. Minutes go to three digits:
	// slow Inferno/Zuk clears print raw minutes past 99 ("Duration: 172:18. Personal best:
	// 134:52") rather than rolling into h:mm:ss. Keyword family, mirroring the formats
	// RuneLite's chat-commands PB tracker knows:
	//   duration        — "Fight/Wave/Challenge/Corrupted challenge duration:", raid "Duration:"
	//   completion time — ToB/ToA "total completion time:"
	//   time:           — Barracuda Trials "Time: 6:04.20", Sepulchre "Floor 5 time:"/"Overall
	//                     time:". Colon fused so countdown lines ("Time remaining: 5:00") don't
	//                     parse as a clear.
	//   subdued in      — Tempoross "Subdued in 6:32"
	// ToA's per-room / per-challenge sub-times also match "duration"/"time:" but are filtered
	// out before this (see RAID_SUBTIME) — they're not the raid clear time.
	private static final Pattern DURATION = Pattern.compile(
			"(?:duration|completion time|time:|subdued in)[^0-9]{0,16}((?:\\d{1,2}:)?\\d{1,3}:\\d{2})");

	// ToA reports several *sub*-times before the raid total, each carrying its own duration:
	//   "Challenge complete: The Wardens. Duration: 7:05"     — one room
	//   "Challenge time: 10:23"                                — one challenge
	//   "Tombs of Amascut: Expert Mode challenge completion time: 30:16" — rooms only, no overhead
	// None of these is the raid clear time — a "clear the raid under X" tile must use the
	// "… total completion time:" line (or CoX's "raid is complete! Duration:"). Crediting a room
	// duration was letting a 34:50 raid pass a 25-minute tile off its 7:05 final room. These are
	// the only "challenge …"/"… completion time" phrasings we reject; "Challenge duration:"
	// (Gauntlet / Corrupted Gauntlet / TzHaar-Ket-Rak) and "total completion time" are kept.
	private static final Pattern RAID_SUBTIME = Pattern.compile(
			"challenge complete|challenge time:|challenge completion time");

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
		// Raid-mode shorthands, so a mode tile matches however the admin phrased it. Colons and
		// spacing are already folded away by norm(), so we only enumerate the abbreviations here;
		// each pins the EXACT game count-line substring. A CM/HM/Expert tile must carry the full
		// mode text so a *base*-mode clear (whose line omits it) can't wrongly complete it — while a
		// base-raid tile keeps matching every harder mode (the mode line contains the base name).
		SIGNATURES.put("cox", Collections.singletonList("chambers of xeric"));
		SIGNATURES.put("cox cm", Collections.singletonList("chambers of xeric challenge mode"));
		SIGNATURES.put("cox challenge mode", Collections.singletonList("chambers of xeric challenge mode"));
		SIGNATURES.put("chambers of xeric cm", Collections.singletonList("chambers of xeric challenge mode"));
		SIGNATURES.put("tob", Collections.singletonList("theatre of blood"));
		SIGNATURES.put("tob hm", Collections.singletonList("theatre of blood hard mode"));
		SIGNATURES.put("tob hard mode", Collections.singletonList("theatre of blood hard mode"));
		SIGNATURES.put("theatre of blood hm", Collections.singletonList("theatre of blood hard mode"));
		SIGNATURES.put("toa", Collections.singletonList("tombs of amascut"));
		SIGNATURES.put("toa em", Collections.singletonList("tombs of amascut expert mode"));
		SIGNATURES.put("toa expert", Collections.singletonList("tombs of amascut expert mode"));
		SIGNATURES.put("toa expert mode", Collections.singletonList("tombs of amascut expert mode"));
		SIGNATURES.put("tombs of amascut em", Collections.singletonList("tombs of amascut expert mode"));
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
		// Sailing Barracuda Trials: no chat line ever says "barracuda", so a generic
		// any-trial tile needs the three course names. Tiles naming a specific course
		// ("Gwenith Glide") ride the plain fallback — both flanking lines name it.
		SIGNATURES.put("barracuda trials", Arrays.asList("tempor tantrum", "jubbly jive", "gwenith glide"));
		SIGNATURES.put("barracuda trial", Arrays.asList("tempor tantrum", "jubbly jive", "gwenith glide"));
		// Hallowed Sepulchre full runs: no chat line says "sepulchre" either — the exit
		// message is "Overall time: 26:39.20", which both carries the time and identifies
		// the run. Floor tiles ("Floor 5") ride the fallback against "Floor 5 time: ...".
		SIGNATURES.put("hallowed sepulchre", Collections.singletonList("overall time"));
		SIGNATURES.put("sepulchre", Collections.singletonList("overall time"));
	}

	/** Returns the clear time in whole seconds, or null if the message carries no duration. */
	static Integer parseDurationSeconds(String message)
	{
		if (message == null)
		{
			return null;
		}
		String lower = message.toLowerCase();
		// A ToA per-room / per-challenge sub-time — never the raid clear time. Ignore it so a
		// slow raid can't pass a timed tile off a fast final room (see RAID_SUBTIME).
		if (RAID_SUBTIME.matcher(lower).find())
		{
			return null;
		}
		Matcher m = DURATION.matcher(lower);
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
		String a = norm(activity);
		List<String> s = SIGNATURES.get(a);
		if (s != null)
		{
			return s;
		}
		return a.isEmpty() ? Collections.emptyList() : Collections.singletonList(a);
	}

	/**
	 * Folds the punctuation/spacing admins add inconsistently so an activity string still matches the
	 * game's count line. Colons especially: the game writes CoX Challenge Mode with NO colon, yet
	 * "Chambers of Xeric: Challenge Mode" is the natural way to type it, and ToB/ToA modes DO carry a
	 * colon — dropping it on BOTH sides of the compare makes colons irrelevant either way.
	 */
	static String norm(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.toLowerCase(java.util.Locale.ROOT).replace(':', ' ').replaceAll("\\s+", " ").trim();
	}

	/** True when {@code message} contains any of the activity's identifying signatures. */
	static boolean messageMatchesActivity(String message, String activity)
	{
		if (message == null)
		{
			return false;
		}
		String nmsg = norm(message);
		for (String sig : signaturesFor(activity))
		{
			if (nmsg.contains(norm(sig)))
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

	// ── Barracuda Trials (Sailing) rank tiles ──────────────────────────────────────────────────────
	// Each course awards a rank by time, but the three ranks are SEPARATE challenges (different routes,
	// each with its own PB) — so a rank tile must match the EXACT rank the game reports, never a time
	// cap (a Shark run is not just a slow Marlin run). The completion line names both:
	//   "You have completed the Gwenith Glide and achieved the Marlin rank."
	private static final String[] TRIAL_COURSES = { "tempor tantrum", "jubbly jive", "gwenith glide" };
	private static final String[] TRIAL_RANKS = { "swordfish", "shark", "marlin" };
	private static final Pattern TRIAL_COMPLETION = Pattern.compile("completed the (.+?) and achieved the (\\w+) rank");

	/** {course, rank} from a Barracuda Trials completion line, or null if this line isn't one. */
	static String[] parseTrialCompletion(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = TRIAL_COMPLETION.matcher(message.toLowerCase());
		if (!m.find())
		{
			return null;
		}
		String course = norm(m.group(1));
		String rank = m.group(2);
		for (String c : TRIAL_COURSES)
		{
			if (course.endsWith(c))
			{
				for (String r : TRIAL_RANKS)
				{
					if (rank.equals(r))
					{
						return new String[]{ c, r };
					}
				}
			}
		}
		return null;
	}

	/** {course, rank} a tile's activity targets (e.g. "Gwenith Glide — Marlin"), or null if it's not a rank tile. */
	static String[] trialTileTarget(String activity)
	{
		String a = norm(activity);
		if (a.isEmpty())
		{
			return null;
		}
		for (String r : TRIAL_RANKS)
		{
			if (a.endsWith(" " + r))
			{
				String course = a.substring(0, a.length() - r.length()).replaceAll("[^a-z]+$", "");
				for (String c : TRIAL_COURSES)
				{
					if (course.equals(c) || course.endsWith(c))
					{
						return new String[]{ c, r };
					}
				}
			}
		}
		return null;
	}
}
