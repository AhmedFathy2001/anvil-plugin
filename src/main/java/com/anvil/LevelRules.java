package com.anvil;

/**
 * Which level-ups a destination wants posted (site capability {@code level-rules}): 99s, every
 * {@code skillStep} levels from {@code skillFrom}, every {@code totalStep} total from {@code totalFrom},
 * and maxing. The site sends one set for the clan's channel and one for the player's own webhooks; a
 * null set wants nothing. A site without the capability gets {@link #LEGACY_CLAN} for the clan — exactly the old
 * 99s-and-high-totals behaviour.
 */
public class LevelRules
{
	public boolean ninetyNines;
	public int skillStep;
	public int skillFrom;
	public int totalStep;
	public int totalFrom;
	public boolean maxTotal;

	public static class Sets
	{
		public LevelRules clan;
		public LevelRules personal;

		Sets()
		{
		}

		Sets(LevelRules clan, LevelRules personal)
		{
			this.clan = clan;
			this.personal = personal;
		}
	}

	static final LevelRules LEGACY_CLAN = new LevelRules(true, 0, 1, 100, 1800, true);

	LevelRules()
	{
	}

	LevelRules(boolean ninetyNines, int skillStep, int skillFrom, int totalStep, int totalFrom, boolean maxTotal)
	{
		this.ninetyNines = ninetyNines;
		this.skillStep = skillStep;
		this.skillFrom = skillFrom;
		this.totalStep = totalStep;
		this.totalFrom = totalFrom;
		this.maxTotal = maxTotal;
	}

	/**
	 * The sets this site asks for, with the clan's dropped when it has no channel for level posts
	 * (the player's own webhooks can still want one — the site routes it).
	 */
	static Sets resolve(PluginConfigResponse cfg, boolean clanChannel)
	{
		Sets s = cfg != null && cfg.levelRules != null && cfg.serverSupports("level-rules")
			? cfg.levelRules : new Sets(LEGACY_CLAN, null);
		return new Sets(clanChannel ? s.clan : null, s.personal);
	}

	boolean matchesSkill(int level)
	{
		return (level == 99 && ninetyNines)
			|| (skillStep > 0 && level >= skillFrom && (level - skillFrom) % skillStep == 0);
	}

	/** The highest level crossed ({@code prev < L <= now}) that either set wants, or 0. */
	static int skillMilestone(int prev, int now, Sets s)
	{
		for (int l = now; l > prev; l--)
		{
			if ((s.clan != null && s.clan.matchesSkill(l)) || (s.personal != null && s.personal.matchesSkill(l)))
			{
				return l;
			}
		}
		return 0;
	}

	/** The highest round total crossed ({@code prev < M <= now}) that either set wants, or 0. */
	static int totalMilestone(int prev, int now, Sets s)
	{
		return Math.max(totalMilestone(prev, now, s.clan), totalMilestone(prev, now, s.personal));
	}

	private static int totalMilestone(int prev, int now, LevelRules r)
	{
		if (r == null || r.totalStep <= 0)
		{
			return 0;
		}
		// The highest multiple reached; if that is under the floor, every lower one is too.
		int m = now / r.totalStep * r.totalStep;
		return m > prev && m >= r.totalFrom ? m : 0;
	}

	static boolean wantsMax(Sets s)
	{
		return (s.clan != null && s.clan.maxTotal) || (s.personal != null && s.personal.maxTotal);
	}
}
