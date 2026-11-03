package com.anvil;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Boss nicknames both ways, so a search finds a thing by whichever name the player knows it by:
 * "toa" finds Tombs of Amascut, and "tombs" finds a tile someone labelled "ToA uniques".
 */
final class SearchAliases
{
	/** {nickname, full name} as both read once lower-cased with punctuation turned to spaces. */
	private static final String[][] PAIRS = {
		{"toa", "tombs of amascut"}, {"tob", "theatre of blood"}, {"cox", "chambers of xeric"}, {"tfa", "fractured archive"},
		{"cm", "challenge mode"}, {"hm", "hard mode"}, {"jad", "tztok jad"}, {"fight caves", "tztok jad"},
		{"zuk", "tzkal zuk"}, {"inferno", "tzkal zuk"}, {"colo", "sol heredit"}, {"colosseum", "sol heredit"},
		{"kq", "kalphite queen"}, {"kbd", "king black dragon"}, {"dks", "dagannoth"}, {"corp", "corporeal beast"},
		{"cerb", "cerberus"}, {"gg", "grotesque guardians"}, {"nm", "nightmare"}, {"pnm", "phosani"},
		{"cg", "corrupted gauntlet"}, {"sire", "abyssal sire"}, {"hydra", "alchemical hydra"}, {"vork", "vorkath"},
		{"zul", "zulrah"}, {"muspah", "phantom muspah"}, {"kril", "k ril tsutsaroth"}, {"zammy", "k ril tsutsaroth"},
		{"zily", "commander zilyana"}, {"sara", "commander zilyana"}, {"kree", "kree arra"}, {"arma", "kree arra"},
		{"bandos", "general graardor"}, {"graardor", "general graardor"}, {"gwd", "god wars"}, {"levi", "leviathan"},
		{"whisp", "whisperer"}, {"vard", "vardorvis"}, {"duke", "duke sucellus"}, {"dt2", "desert treasure"},
		{"rax", "araxxor"}, {"huey", "hueycoatl"}, {"amox", "amoxliatl"}, {"scur", "scurrius"},
		{"titans", "royal titans"}, {"wt", "wintertodt"}, {"gotr", "guardians of the rift"}, {"tempo", "tempoross"},
		{"mole", "giant mole"}, {"vene", "venenatis"}, {"calv", "calvar ion"}, {"sepulchre", "hallowed sepulchre"},
		{"ba", "barbarian assault"}, {"lms", "last man standing"}, {"pc", "pest control"}, {"ca", "combat achievement"},
	};

	private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

	private SearchAliases()
	{
	}

	/** Lower-cased with every run of punctuation and spaces turned into one space: "K'ril  Tsutsaroth" → "k ril tsutsaroth". */
	static String fold(String s)
	{
		return NON_ALNUM.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
	}

	/** {@code text} plus the other name of every boss it mentions — search it like any other text. */
	static String augment(String text)
	{
		if (text == null || text.isEmpty())
		{
			return "";
		}
		String words = " " + fold(text) + " ";
		StringBuilder out = new StringBuilder(text);
		for (String[] p : PAIRS)
		{
			if (words.contains(" " + p[0] + " "))
			{
				out.append(' ').append(p[1]);
			}
			else if (words.contains(" " + p[1] + " "))
			{
				out.append(' ').append(p[0]);
			}
		}
		return out.toString();
	}
}
