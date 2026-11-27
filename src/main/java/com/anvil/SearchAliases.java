package com.anvil;

import java.util.*;
import java.util.regex.*;

/**
 * Boss nicknames both ways, so a search finds a thing by whichever name the player knows it by:
 * "toa" finds Tombs of Amascut, and "tombs" finds a tile someone labelled "ToA uniques".
 */
class SearchAliases {
	/** {nickname, full name} as both read once lower-cased with punctuation turned to spaces (search-aliases.json). */
	static final List<List<String>> PAIRS = DiskFiles.table("search-aliases.json");

	static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

	/** Lower-cased with every run of punctuation and spaces turned into one space: "K'ril  Tsutsaroth" → "k ril tsutsaroth". */
	static String fold(String s) {
		return NON_ALNUM.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
	}

	/** {@code text} plus the other name of every boss it mentions — search it like any other text. */
	static String augment(String text) {
		if (text == null || text.isEmpty()) return "";
		var words = " " + fold(text) + " ";
		var out = new StringBuilder(text);
		for (var p : PAIRS) {
			if (words.contains(" " + p.get(0) + " ")) {
				out.append(' ').append(p.get(1));
			} else if (words.contains(" " + p.get(1) + " ")) out.append(' ').append(p.get(0));
		}
		return out.toString();
	}
}
