package com.anvil.clog;

import com.anvil.api.dto.TierBand;
import java.util.ArrayList;
import java.util.List;

/**
 * What a task's points are worth calling it.
 *
 * <p>Bands are thresholds with names — "Easy" up to some number of points, "Elite" above another —
 * and a clan can redefine them, so the plugin never hard-codes the labels it shows. What it does
 * hard-code is a default set, used when the site sends none: a board with no bands is still a board,
 * and refusing to name anything would be worse than naming it conventionally.</p>
 *
 * <p>A key is matched to the HIGHEST band whose minimum it clears, so bands arriving out of order
 * (or overlapping) still classify the same way.</p>
 */
public final class TierBands
{
	private TierBands()
	{
	}

	/**
	 * Difficulty-tier bands are no longer hardcoded — the server sends them (admin-configurable) in
	 * the config/board payload. The Tier filter is the selected band's key, or "" for all tiers.
	 * {@link #defaultTierBands()} is the baked-in fallback for older/offline servers.
	 */
	public static List<TierBand> defaultTierBands()
	{
		List<TierBand> bands = new ArrayList<>();
		bands.add(tierBand("troll", "Troll", 0));
		bands.add(tierBand("easy", "Easy", 11));
		bands.add(tierBand("medium", "Medium", 100));
		bands.add(tierBand("hard", "Hard", 350));
		bands.add(tierBand("ultra", "Ultra", 700));
		return bands;
	}

	private static TierBand tierBand(String key, String label, int min)
	{
		TierBand b = new TierBand();
		b.key = key;
		b.label = label;
		b.min = min;
		return b;
	}

	/** Served bands with blanks dropped, falling back to the baked-in defaults when empty/null. */
	public static List<TierBand> tierBandsOrDefault(List<TierBand> bands)
	{
		if (bands == null)
		{
			return defaultTierBands();
		}
		List<TierBand> clean = new ArrayList<>();
		for (TierBand b : bands)
		{
			if (b != null && b.key != null && !b.key.isEmpty())
			{
				clean.add(b);
			}
		}
		return clean.isEmpty() ? defaultTierBands() : clean;
	}

	/**
	 * The key of the band a point value falls into — the highest band whose {@code min} it meets,
	 * with the lowest band as the floor. Returns null only when there are no bands.
	 */
	public static String tierKeyOf(int points, List<TierBand> bands)
	{
		if (bands == null || bands.isEmpty())
		{
			return null;
		}
		TierBand chosen = null;
		TierBand lowest = null;
		for (TierBand b : bands)
		{
			if (b == null)
			{
				continue;
			}
			if (lowest == null || b.min < lowest.min)
			{
				lowest = b;
			}
			if (points >= b.min && (chosen == null || b.min >= chosen.min))
			{
				chosen = b;
			}
		}
		if (chosen == null)
		{
			chosen = lowest; // points below every band's min → fall back to the lowest band
		}
		return chosen == null ? null : chosen.key;
	}

	/** Human label for a band key (falls back to "" when not found). */
	public static String tierLabel(String key, List<TierBand> bands)
	{
		if (key == null || key.isEmpty() || bands == null)
		{
			return "";
		}
		for (TierBand b : bands)
		{
			if (b != null && key.equalsIgnoreCase(b.key))
			{
				return b.label != null ? b.label : b.key;
			}
		}
		return "";
	}
}
