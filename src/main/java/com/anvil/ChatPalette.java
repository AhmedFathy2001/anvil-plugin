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

	/**
	 * A name in a supporter's gradient: each letter its own colour, from the first stop to the last.
	 * {@code safeName} is already escaped for chat. Null when fewer than two stops are usable, so the
	 * caller falls back to the solid colour.
	 */
	static String gradientName(String safeName, java.util.List<String> stops)
	{
		java.util.List<int[]> rgb = new java.util.ArrayList<>();
		if (stops != null)
		{
			for (String st : stops)
			{
				String hex = nameColor(st);
				if (hex != null && rgb.size() < 4)
				{
					int v = Integer.parseInt(hex, 16);
					rgb.add(new int[]{v >> 16 & 0xff, v >> 8 & 0xff, v & 0xff});
				}
			}
		}
		if (rgb.size() < 2 || safeName == null || safeName.isEmpty())
		{
			return null;
		}
		StringBuilder out = new StringBuilder();
		int n = safeName.length();
		for (int i = 0; i < n; i++)
		{
			char ch = safeName.charAt(i);
			if (ch == ' ')
			{
				out.append(ch);
				continue;
			}
			double t = n == 1 ? 0 : (double) i / (n - 1) * (rgb.size() - 1);
			int seg = Math.min(rgb.size() - 2, (int) t);
			double f = t - seg;
			int[] a = rgb.get(seg);
			int[] b = rgb.get(seg + 1);
			out.append(String.format("<col=%02x%02x%02x>", (int) Math.round(a[0] + (b[0] - a[0]) * f),
				(int) Math.round(a[1] + (b[1] - a[1]) * f), (int) Math.round(a[2] + (b[2] - a[2]) * f)))
				.append(ch);
		}
		return out.append("</col>").toString();
	}
}
