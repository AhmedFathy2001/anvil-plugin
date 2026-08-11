package com.anvil;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * How a drop should be *talked about*: was it lucky, was it a long-overdue grind, or is it a rare
 * roll on something worthless?
 *
 * Rarity alone can't answer that. A 1/5000 roll on a player's second kill is a spoon; the same roll
 * at 20,000 kills is the opposite. So everything here is expressed against the kill count: with a
 * per-kill probability p, the share of players who would already hold the drop after kc kills is
 *
 *     P(at least one by now) = 1 - (1 - p)^kc
 *
 * A small share means you beat almost everyone to it (spooned); a large one means you were dry.
 *
 * Pure static logic with no RuneLite dependencies, so it's unit-tested directly (DropLuckTest).
 */
public final class DropLuck
{
	private DropLuck()
	{
	}

	/** Beat ~90% of players to it. */
	private static final double SPOONED_SHARE = 0.10;
	/** Ahead of the pack, but not absurd. */
	private static final double LUCKY_SHARE = 0.35;
	/** Most players already had it by this point — the grind was real. */
	private static final double DRY_SHARE = 0.85;

	/**
	 * A drop this rare that's worth less than this is a punchline, not a prize — the 1/10,000 roll
	 * that hands you a Dragon spear. Above it, it's simply a rare drop.
	 */
	public static final long TROLL_VALUE_CEILING = 250_000L;

	/**
	 * Awards you're GIVEN for finishing something, not rolled for: the cape you get for clearing the
	 * Inferno, the quiver for the Colosseum. Calling these "spooned" reads as an insult to the run,
	 * so the reaction lines are suppressed for them. Substring-matched, lowercased — the same style
	 * as the always-notify allowlist, so "Infernal cape (broken)" still matches.
	 */
	private static final List<String> EARNED_AWARDS = Arrays.asList(
		"infernal cape",
		"fire cape",
		"dizana's quiver",
		"champion's cape",
		"quest point cape",
		"music cape",
		"achievement diary cape",
		"max cape");

	public enum Verdict
	{
		/** Well ahead of the drop rate. */
		SPOONED,
		/** Ahead of the drop rate. */
		LUCKY,
		/** About when you'd expect it. */
		TYPICAL,
		/** Long past due. */
		DRY,
		/** No rate or no kill count — say nothing. */
		UNKNOWN
	}

	/** True when this item is handed out for completing content rather than rolled for. */
	public static boolean isEarnedAward(String itemName)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return false;
		}
		String n = itemName.toLowerCase(Locale.ROOT);
		for (String award : EARNED_AWARDS)
		{
			if (n.contains(award))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Share of players (0..1) who would already hold a drop of probability {@code p} after
	 * {@code kc} kills. Computed as 1-(1-p)^kc, via expm1/log1p so tiny rates don't lose precision.
	 */
	public static double obtainedShare(double p, int kc)
	{
		if (p <= 0 || kc <= 0)
		{
			return 0;
		}
		if (p >= 1)
		{
			return 1;
		}
		// 1 - exp(kc * ln(1-p)); log1p/expm1 keep this accurate for p in the 1e-5 range.
		return -Math.expm1(kc * Math.log1p(-p));
	}

	/** Where this drop sits against the grind. UNKNOWN when either input is missing. */
	public static Verdict classify(Double dropRate, Integer killCount)
	{
		if (dropRate == null || dropRate <= 0 || killCount == null || killCount <= 0)
		{
			return Verdict.UNKNOWN;
		}
		double share = obtainedShare(dropRate, killCount);
		if (share < SPOONED_SHARE)
		{
			return Verdict.SPOONED;
		}
		if (share < LUCKY_SHARE)
		{
			return Verdict.LUCKY;
		}
		if (share >= DRY_SHARE)
		{
			return Verdict.DRY;
		}
		return Verdict.TYPICAL;
	}

	/**
	 * The "Luck" field value, e.g. {@code "Top 8%"} or {@code "Dry — 93% had it by now"}. Null for a
	 * typical or unknown result, which is the common case and shouldn't add a field.
	 */
	public static String luckLabel(Double dropRate, Integer killCount)
	{
		Verdict verdict = classify(dropRate, killCount);
		if (verdict == Verdict.UNKNOWN || verdict == Verdict.TYPICAL)
		{
			return null;
		}
		int pct = (int) Math.max(1, Math.round(obtainedShare(dropRate, killCount) * 100));
		switch (verdict)
		{
			case SPOONED:
				return "Top " + pct + "% — spooned";
			case LUCKY:
				return "Top " + pct + "%";
			case DRY:
				return "Dry — " + pct + "% had it by now";
			default:
				return null;
		}
	}

	/**
	 * A "troll": rare enough to trip the rarity gate, but worth almost nothing. Worth calling out as
	 * a joke rather than dressing up as treasure.
	 *
	 * @param dropRate     per-kill probability, or null when the drop qualified on value alone
	 * @param value        the drop's gp value
	 * @param rarityFloor  1-in-N the drop must beat to count as troll-rare (the clan's rarity floor)
	 */
	public static boolean isTrollDrop(Double dropRate, long value, int rarityFloor)
	{
		if (dropRate == null || dropRate <= 0 || rarityFloor <= 0)
		{
			return false;
		}
		if (value >= TROLL_VALUE_CEILING)
		{
			return false;
		}
		return MathUtils.lessThanOrEqual(dropRate, 1.0 / rarityFloor);
	}

	/**
	 * Whether to append a lucky-drop reaction line. Earned awards never qualify (see
	 * {@link #EARNED_AWARDS}); otherwise it's a genuine spoon against the kill count, or a drop
	 * valuable enough to be a moment regardless of how long it took.
	 */
	public static boolean deservesSpoonLine(String itemName, long value, Double dropRate, Integer killCount, long bigValue)
	{
		if (isEarnedAward(itemName))
		{
			return false;
		}
		if (classify(dropRate, killCount) == Verdict.SPOONED)
		{
			return true;
		}
		// No kill count to judge against (untracked NPC, or the clan hides KC): fall back to value.
		return value >= bigValue;
	}
}
