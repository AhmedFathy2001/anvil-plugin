package com.anvil;

import java.util.List;

/**
 * One character as the side panel's Lookup and You tabs show it — the JSON of
 * {@code GET /api/players/<rsn>/card} (the {@code player-card} capability).
 *
 * <p>What's notable about the account and nothing that's zero: the site already left out empty boss
 * rows and log groups, so the panel draws whatever arrives.</p>
 */
public final class PlayerCard
{
	public String rsn;
	public String accountType;
	public Clan clan;
	public int totalLevel;
	public Integer combatLevel;
	public Long overallXp;
	public Double ehp;
	public Double ehb;
	public String statsAt;
	/** Null until they've synced their collection log. */
	public Clog clog;
	public List<Latest> latest;
	public List<Boss> topBosses;
	// The in-panel profile behind the summary. Absent from an older site; treat null as empty.
	public List<Skill> skills;
	public List<Boss> bosses;
	public List<Activity> activities;
	public List<Page> clogPages;
	/** Uploaded personal bests: one entry per boss and mode, its best time first, team sizes behind it. */
	public List<PbEntry> times;

	public static final class PbEntry
	{
		/** "Chambers of Xeric: Challenge Mode", "Duke Sucellus (Awakened)", "Vorkath". */
		public String name;
		/** The boss alone — what the icon is found by. */
		public String base;
		public String mode;
		public String best;
		/** Team sizes, Solo first; empty when there's only the one time. */
		public List<PbVariant> variants;
	}

	public static final class PbVariant
	{
		/** "Solo", "2", "11-15", or "" when the time has no team size. */
		public String team;
		public String time;
		public String activity;
	}

	public static final class Skill
	{
		public String key;
		public int level;
		public long xp;
	}

	public static final class Activity
	{
		public String key;
		public String label;
		public String shortLabel;
		/** "clues", "minigames" or "collection". */
		public String group;
		public int score;
	}

	public static final class Page
	{
		public String name;
		public String group;
		public int obtained;
		public int total;
		/** An item to draw the page by — its first slot. Null on an older site. */
		public Integer iconItemId;
	}

	public static final class Clan
	{
		public String slug;
		public String name;
	}

	public static final class Clog
	{
		public int obtained;
		public int total;
		public List<Group> groups;
	}

	public static final class Group
	{
		public String name;
		public int obtained;
		public int total;
	}

	public static final class Latest
	{
		/** "item" or "milestone". */
		public String kind;
		public String label;
		public Integer itemId;
		public String at;
	}

	public static final class Boss
	{
		public String key;
		/** The game's name, matched to RuneLite's hiscore sprites. */
		public String label;
		public int kc;
		/** The log page its kills fill (a raid's modes share one); null when it has none. */
		public String clogPage;
	}
}
