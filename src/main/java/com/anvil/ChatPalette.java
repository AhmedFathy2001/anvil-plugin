package com.anvil;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What colour an Anvil chat line is (site lib/chatLook). Every line has a {@link Tone}; the site hands
 * the palette over with the login brief. Until then, and against a site without one, the Anvil
 * colours apply. "Plain" means the plugin's own text colour for everything (its existing setting).
 */
final class ChatPalette
{
	enum Tone
	{
		DONE, NEAR, DROP, FEED, WARN, INFO;

		static Tone of(String s)
		{
			if (s != null)
			{
				for (Tone t : values())
				{
					if (t.name().equalsIgnoreCase(s))
					{
						return t;
					}
				}
			}
			return INFO;
		}
	}

	private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{6}");

	static final ChatPalette ANVIL = anvil();

	/** Null: plain. */
	private final Map<Tone, String> colors;
	/** Custom palettes colour INFO too; the Anvil one leaves it to the player's own text colour. */
	private final boolean custom;

	private ChatPalette(Map<Tone, String> colors, boolean custom)
	{
		this.colors = colors;
		this.custom = custom;
	}

	private static ChatPalette anvil()
	{
		Map<Tone, String> m = new EnumMap<>(Tone.class);
		m.put(Tone.DONE, "16c60c");
		m.put(Tone.NEAR, "ffb000");
		m.put(Tone.DROP, "ff7f27");
		m.put(Tone.FEED, "9a9a9a");
		m.put(Tone.WARN, "ff3b3b");
		m.put(Tone.INFO, "ffffff");
		return new ChatPalette(m, false);
	}

	/** The site's look ({palette, colors}); anything unreadable keeps the Anvil colour for that tone. */
	static ChatPalette from(String palette, Map<String, String> colors)
	{
		if ("plain".equals(palette))
		{
			return new ChatPalette(null, false);
		}
		Map<Tone, String> m = new EnumMap<>(ANVIL.colors);
		if (colors != null)
		{
			for (Map.Entry<String, String> e : colors.entrySet())
			{
				String v = e.getValue() == null ? "" : e.getValue().replace("#", "");
				if (HEX.matcher(v).matches())
				{
					m.put(Tone.of(e.getKey()), v.toLowerCase(Locale.ROOT));
				}
			}
		}
		return new ChatPalette(m, "custom".equals(palette));
	}

	/** The text colour for {@code tone}, {@code ownText} being the player's configured text colour. */
	String text(Tone tone, String ownText)
	{
		if (colors == null || (tone == Tone.INFO && !custom))
		{
			return ownText;
		}
		String c = colors.get(tone);
		return c != null ? c : ownText;
	}

	/** A name colour from the site ("#rrggbb"), or null. */
	static String nameColor(String s)
	{
		String v = s == null ? "" : s.replace("#", "");
		return HEX.matcher(v).matches() ? v.toLowerCase(Locale.ROOT) : null;
	}
}
