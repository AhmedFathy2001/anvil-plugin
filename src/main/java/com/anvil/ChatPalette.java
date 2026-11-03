package com.anvil;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
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

	/**
	 * The Anvil tones on the OPAQUE chatbox: its parchment washes the bright set out, so the same tones
	 * in the dark inks the game itself uses there. A player's own (custom) palette is theirs, kept as is.
	 */
	private static final Map<Tone, String> OPAQUE = new EnumMap<>(Tone.class);

	static
	{
		OPAQUE.put(Tone.DONE, "006a00");
		OPAQUE.put(Tone.NEAR, "7a4a00");
		OPAQUE.put(Tone.DROP, "a03c00");
		OPAQUE.put(Tone.FEED, "4d4d4d");
		OPAQUE.put(Tone.WARN, "9e0000");
		OPAQUE.put(Tone.INFO, "000000");
	}

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
	String text(Tone tone, String ownText, boolean opaque)
	{
		if (colors == null || (tone == Tone.INFO && !custom))
		{
			return ownText;
		}
		String c = opaque && !custom ? OPAQUE.get(tone) : colors.get(tone);
		return c != null ? c : ownText;
	}

	/**
	 * A name drawn letter by letter across 2–4 gradient stops ("#rrggbb"), or null when fewer than two
	 * stops are valid. {@code safeName} must already be free of chat tags; spaces carry no colour.
	 */
	static String gradientName(String safeName, List<String> stops)
	{
		List<int[]> rgb = new ArrayList<>();
		for (String s : stops == null ? new ArrayList<String>() : stops)
		{
			String hex = nameColor(s);
			if (hex != null && rgb.size() < 4)
			{
				int v = Integer.parseInt(hex, 16);
				rgb.add(new int[]{(v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff});
			}
		}
		if (rgb.size() < 2 || safeName == null || safeName.isEmpty())
		{
			return null;
		}
		int n = safeName.codePointCount(0, safeName.length());
		StringBuilder out = new StringBuilder();
		String last = null;
		int i = 0;
		for (int off = 0; off < safeName.length(); )
		{
			int cp = safeName.codePointAt(off);
			off += Character.charCount(cp);
			if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp))
			{
				double t = n == 1 ? 0 : (double) i / (n - 1) * (rgb.size() - 1);
				int seg = Math.min((int) t, rgb.size() - 2);
				double f = t - seg;
				int[] a = rgb.get(seg);
				int[] b = rgb.get(seg + 1);
				String c = String.format(Locale.ROOT, "%02x%02x%02x",
					(int) Math.round(a[0] + (b[0] - a[0]) * f),
					(int) Math.round(a[1] + (b[1] - a[1]) * f),
					(int) Math.round(a[2] + (b[2] - a[2]) * f));
				if (!c.equals(last))
				{
					out.append(last == null ? "" : "</col>").append("<col=").append(c).append('>');
					last = c;
				}
			}
			out.appendCodePoint(cp);
			i++;
		}
		return out.append("</col>").toString();
	}

	/** A name colour from the site ("#rrggbb"), or null. */
	static String nameColor(String s)
	{
		String v = s == null ? "" : s.replace("#", "");
		return HEX.matcher(v).matches() ? v.toLowerCase(Locale.ROOT) : null;
	}
}
