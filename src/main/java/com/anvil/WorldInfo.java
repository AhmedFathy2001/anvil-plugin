package com.anvil;

import java.util.*;
import java.util.stream.Collectors;
import net.runelite.api.WorldType;

/**
 * What the client knows about the world the player is on, in the form the site reads it.
 *
 * <p>The plugin only REPORTS the world: its type flags and the activity line from the world list
 * ("Grid Master", "Deadman Annihilation", "Leagues VI"). Which game mode that is, where its posts
 * go and whether it counts for anything is the site's call (lib/worldModes), so a new mode Jagex
 * ships is recognised on release day without a plugin update — the activity text names it even
 * before RuneLite has a flag for it.</p>
 */
final class WorldInfo {
	WorldInfo() {
	}

	/**
	 * Worlds that run their own save — a separate character, its own drops and kill counts — so
	 * nothing earned there is the player's main-game progression. The site has the authoritative
	 * list; this one only gates what the plugin itself suppresses before sending (level posts).
	 */
	static final Set<WorldType> SEPARATE_SAVE = Collections.unmodifiableSet(EnumSet.of(
		WorldType.SEASONAL, WorldType.DEADMAN, WorldType.HARDCORE_MAIN, WorldType.TOURNAMENT_WORLD,
		WorldType.BETA_WORLD, WorldType.FRESH_START_WORLD));

	/** Minigame worlds with a preset loadout or no save: their levels never post. */
	static final Set<WorldType> PRESET = Collections.unmodifiableSet(EnumSet.of(
		WorldType.PVP_ARENA, WorldType.QUEST_SPEEDRUNNING, WorldType.LAST_MAN_STANDING, WorldType.NOSAVE_MODE));

		/** Longest activity line sent. World-list activities are short; this only bounds a surprise. */
	static final int MAX_ACTIVITY = 64;

	/** The world's flags as a stable, comma-separated list of names ("DEADMAN,MEMBERS"); null for none. */
	static String types(Collection<WorldType> types) {
		if (types == null || types.isEmpty()) return null;
		return types.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
	}

	/**
	 * The world-list activity, cleaned to something that can ride in an HTTP header: printable ASCII
	 * only (OkHttp rejects anything else outright), whitespace collapsed, bounded. Null when blank or
	 * a placeholder ("-").
	 */
	static String activity(String raw) {
		if (raw == null) return null;
		var b = new StringBuilder(Math.min(raw.length(), MAX_ACTIVITY));
		var space = false;
		for (int i = 0; i < raw.length() && b.length() < MAX_ACTIVITY; i++) {
			char c = raw.charAt(i);
			if (c == ' ' || c == '\t' || c == ' ') {
				space = b.length() > 0;
				continue;
			}
			if (c < 0x21 || c > 0x7e) continue;
			if (space) {
				b.append(' ');
				space = false;
			}
			b.append(c);
		}
		var s = b.toString().trim();
		return s.isEmpty() || s.equals("-") ? null : s;
	}

	/** Is this world a separate save (Leagues, Deadman, hardcore, …)? */
	static boolean separateSave(Collection<WorldType> types) {
		return types != null && !Collections.disjoint(types, SEPARATE_SAVE);
	}

	/** May a level-up post here? The main game, or a separate save the site sent level rules for. */
	static boolean levelsPost(Collection<WorldType> types, boolean modeRules) {
		return (types == null || Collections.disjoint(types, PRESET)) && (!separateSave(types) || modeRules);
	}
}
