package com.anvil;

import lombok.AllArgsConstructor;
/**
 * Which level-ups a destination wants posted: 99s, every {@code skillStep} levels from
 * {@code skillFrom}, every {@code totalStep} total from {@code totalFrom}, and maxing. The site sends
 * one set for the clan's channel and one for the player's own webhooks; a null set wants nothing.
 * {@link #LEGACY_CLAN} is the fixed 99s-and-high-totals set the highlight feed keeps.
 */
@AllArgsConstructor
public class LevelRules {
	boolean ninetyNines;
	int skillStep;
	int skillFrom;
	int totalStep;
	int totalFrom;
	boolean maxTotal;

	@AllArgsConstructor
	static class Sets {
		LevelRules clan;
		LevelRules personal;

		Sets() {
		}

	}

	static final LevelRules LEGACY_CLAN = new LevelRules(true, 0, 1, 100, 1800, true);

	LevelRules() {
	}

	/**
	 * The sets this site asks for, with the clan's dropped when it has no channel for level posts
	 * (the player's own webhooks can still want one — the site routes it). On a game-mode world
	 * ({@code mode} non-null) only the mode's own rules: its stats are a separate character.
	 */
	static Sets resolve(PluginConfigResponse cfg, boolean clanChannel, PluginConfigResponse.ModeThresholds mode) {
		if (mode != null) return new Sets(clanChannel ? mode.levelRules : null, null);
		var s = cfg != null && cfg.levelRules != null ? cfg.levelRules : new Sets();
		return new Sets(clanChannel ? s.clan : null, s.personal);
	}

	boolean matchesSkill(int level) {
		return (level == 99 && ninetyNines)
			|| (skillStep > 0 && level >= skillFrom && (level - skillFrom) % skillStep == 0);
	}

	/** The highest level crossed ({@code prev < L <= now}) that either set wants, or 0. */
	static int skillMilestone(int prev, int now, Sets s) {
		for (int l = now; l > prev; l--) {
			if ((s.clan != null && s.clan.matchesSkill(l)) || (s.personal != null && s.personal.matchesSkill(l))) return l;
		}
		return 0;
	}

	/** The highest round total crossed ({@code prev < M <= now}) that either set wants, or 0. */
	static int totalMilestone(int prev, int now, Sets s) {
		return Math.max(totalMilestone(prev, now, s.clan), totalMilestone(prev, now, s.personal));
	}

	static int totalMilestone(int prev, int now, LevelRules r) {
		if (r == null || r.totalStep <= 0) return 0;
		// The highest multiple reached; if that is under the floor, every lower one is too.
		int m = now / r.totalStep * r.totalStep;
		return m > prev && m >= r.totalFrom ? m : 0;
	}

	static boolean wantsMax(Sets s) {
		return (s.clan != null && s.clan.maxTotal) || (s.personal != null && s.personal.maxTotal);
	}
}
