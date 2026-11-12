package com.anvil;

import java.util.*;
import java.util.regex.Pattern;
import java.awt.Color;

/**
 * What colour an Anvil chat line is (site lib/chatLook). Every line has a {@link Tone}; the site hands
 * the palette over with the login brief. Until then, and against a site without one, the Anvil
 * colours apply. "Plain" means the plugin's own text colour for everything (its existing setting).
 */

final class ChatPalette {
	enum Tone {
		DONE, NEAR, DROP, FEED, WARN, INFO;

		static Tone of(String s) {
			if (s != null) {
				for (var t : values()) {
					if (t.name().equalsIgnoreCase(s)) return t;
				}
			}
			return INFO;
		}
	}

	static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{6}");
	static final String HEX_DIGITS = "0123456789abcdef";

	static final ChatPalette ANVIL = anvil();

	/**
	 * The Anvil tones on the OPAQUE chatbox: its parchment washes the bright set out, so the same tones
	 * in the dark inks the game itself uses there. A player's own (custom) palette is theirs, kept as is.
	 */
	static final Map<Tone, String> OPAQUE = tones("006a00", "7a4a00", "a03c00", "4d4d4d", "9e0000", "000000");

	/** One colour per tone, in Tone order. */
	static Map<Tone, String> tones(String... hex) {
		Map<Tone, String> m = new EnumMap<>(Tone.class);
		for (var t : Tone.values()) m.put(t, hex[t.ordinal()]);
		return m;
	}

	/** Null: plain. */
	final Map<Tone, String> colors;
	/** Custom palettes colour INFO too; the Anvil one leaves it to the player's own text colour. */
	final boolean custom;

	ChatPalette(Map<Tone, String> colors, boolean custom) {
		this.colors = colors;
		this.custom = custom;
	}

	static ChatPalette anvil() {
		return new ChatPalette(tones("16c60c", "ffb000", "ff7f27", "9a9a9a", "ff3b3b", "ffffff"), false);
	}

	/** The site's look ({palette, colors}); anything unreadable keeps the Anvil colour for that tone. */
	static ChatPalette from(String palette, Map<String, String> colors) {
		if ("plain".equals(palette)) return new ChatPalette(null, false);
		Map<Tone, String> m = new EnumMap<>(ANVIL.colors);
		if (colors != null) {
			for (var e : colors.entrySet()) {
				var v = nameColor(e.getValue());
				if (v != null) m.put(Tone.of(e.getKey()), v);
			}
		}
		return new ChatPalette(m, "custom".equals(palette));
	}

	/** The text colour for {@code tone}, {@code ownText} being the player's configured text colour. */
	String text(Tone tone, String ownText, boolean opaque) {
		if (colors == null || (tone == Tone.INFO && !custom)) return ownText;
		var c = opaque && !custom ? OPAQUE.get(tone) : colors.get(tone);
		return c != null ? c : ownText;
	}

	/**
	 * A name drawn letter by letter across 2–4 gradient stops ("#rrggbb"), or null when fewer than two
	 * stops are valid. {@code safeName} must already be free of chat tags; spaces carry no colour.
	 */
	static String gradientName(String safeName, List<String> stops) {
		return gradientName(safeName, stops, 0);
	}

	/**
	 * The one chat-name renderer: callers choose the source or surface-derived palette, then this
	 * handles static versus gradient markup and animation sampling.
	 */
	static String paintName(String safeName, String color, List<String> stops, double shift) {
		var gradient = stops != null && stops.size() >= 2 ? gradientName(safeName, stops, shift) : null;
		var solid = nameColor(color);
		return gradient != null ? gradient
			: solid != null && safeName != null && !safeName.isEmpty() ? "<col=" + solid + ">" + safeName + "</col>" : null;
	}

	/**
	 * A gradient name sampled {@code shift} name-widths farther along a reflecting gradient. A shift
	 * from 0 to 2 is one seamless there-and-back animation cycle; zero is the ordinary static look.
	 */
	static List<int[]> rgbOf(List<String> stops) {
		List<int[]> rgb = new ArrayList<>();
		for (var s : stops == null ? new ArrayList<String>() : stops) {
			var hex = nameColor(s);
			if (hex != null && rgb.size() < 4) {
				int v = Integer.parseInt(hex, 16);
				rgb.add(new int[]{(v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff});
			}
		}
		return rgb;
	}

	/** The reflecting gradient's colour at {@code p} (0-1 across a name, +shift for the motion). */
	static int sample(List<int[]> rgb, double p) {
		p %= 2;
		if (p < 0) p += 2;
		p = p > 1 ? 2 - p : p;
		var t = p * (rgb.size() - 1);
		int seg = Math.min((int) t, rgb.size() - 2);
		var f = t - seg;
		int[] a = rgb.get(seg);
		int[] b = rgb.get(seg + 1);
		return (int) Math.round(a[0] + (b[0] - a[0]) * f) << 16
			| (int) Math.round(a[1] + (b[1] - a[1]) * f) << 8
			| (int) Math.round(a[2] + (b[2] - a[2]) * f);
	}

	static String gradientName(String safeName, List<String> stops, double shift) {
		var rgb = rgbOf(stops);
		if (rgb.size() < 2 || safeName == null || safeName.isEmpty()) return null;
		int n = safeName.codePointCount(0, safeName.length());
		var out = new StringBuilder(safeName.length() * 20);
		int last = -1;
		int i = 0;
		for (int off = 0; off < safeName.length(); ) {
			int cp = safeName.codePointAt(off);
			off += Character.charCount(cp);
			if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
				int c = sample(rgb, n == 1 ? shift : (double) i / (n - 1) + shift);
				if (c != last) {
					if (last >= 0) out.append("</col>");
					out.append("<col=");
					appendHex(out, c);
					out.append('>');
					last = c;
				}
			}
			out.appendCodePoint(cp);
			i++;
		}
		return out.append("</col>").toString();
	}

	/** Six lower-case hex digits without the allocation-heavy Formatter used by the static path. */
	static void appendHex(StringBuilder out, int rgb) {
		for (int shift = 20; shift >= 0; shift -= 4) out.append(HEX_DIGITS.charAt(rgb >> shift & 0xf));
	}

	// What the chatbox's text sits on: the opaque box's parchment, and a dark game scene behind the
	// transparent one (the worst case: night, a cave, the Wilderness). Approximations, not exact pixels.
	static final int OPAQUE_BG = 0xC4B48C;
	static final int TRANSPARENT_BG = 0x262626;
	/** WCAG's floor for large or bold text; chat text is small but heavy and shadowed on the dark box. */
	static final double MIN_CONTRAST = 3.0;
	static final Pattern COL = Pattern.compile("<col=([0-9a-fA-F]{6})>");

	/**
	 * Every {@code <col=…>} in a chat line made readable on this chatbox: a colour that already
	 * clears {@link #MIN_CONTRAST} is left exactly as it is; a fainter one is mixed towards black (on
	 * the parchment) or white (on the dark box) just until it does. Covers the tag, the tones, team
	 * and Supporter names and every letter of a gradient, whoever chose the colour.
	 */
	static String readable(String line, boolean opaque) {
		var m = COL.matcher(line);
		var out = new StringBuffer();
		while (m.find()) m.appendReplacement(out, "<col=" + readableHex(m.group(1), opaque) + ">");
		m.appendTail(out);
		return out.toString();
	}

	/**
	 * {@code hex} (with or without "#") as a Swing colour adapted like {@link #readableHex}; {@code fallback}
	 * when it isn't a colour. readableHex answers bare "rrggbb", which Ui.teamColor refuses, so every
	 * panel stop used to silently become its fallback (a white Tracking name, a one-colour Lookup name).
	 */
	static Color readableColor(String hex, boolean opaque, Color fallback) {
		var v = nameColor(hex);
		return v == null ? fallback : new Color(Integer.parseInt(readableHex(v, opaque), 16));
	}

	static String readableHex(String hex, boolean opaque) {
		int source = Integer.parseInt(hex, 16);
		int bg = opaque ? OPAQUE_BG : TRANSPARENT_BG;
		int toward = opaque ? 0x000000 : 0xFFFFFF;
		if (contrast(source, bg) >= MIN_CONTRAST) return hex.toLowerCase(Locale.ROOT);
		// The old 10% staircase made a moving gradient visibly snap whenever a glyph crossed into the
		// next darker/lighter band. Find the exact boundary instead; contrast is monotonic toward black
		// on parchment and toward white on the transparent dark box.
		double lo = 0;
		double hi = 1;
		int readable = toward;
		for (int i = 0; i < 12; i++) {
			double mid = (lo + hi) / 2;
			int candidate = mix(source, toward, mid);
			if (contrast(candidate, bg) >= MIN_CONTRAST) {
				hi = mid;
				readable = candidate;
			} else lo = mid;
		}
		var out = new StringBuilder(6);
		appendHex(out, readable);
		return out.toString();

	}

	static double contrast(int a, int b) {
		var la = luminance(a);
		var lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	static double luminance(int rgb) {
		return 0.2126 * channel(rgb >> 16) + 0.7152 * channel(rgb >> 8) + 0.0722 * channel(rgb);
	}

	static double channel(int v) {
		var c = (v & 0xff) / 255.0;
		return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	static int mix(int a, int b, double t) {
		int r = (int) Math.round(((a >> 16) & 0xff) * (1 - t) + ((b >> 16) & 0xff) * t);
		int g = (int) Math.round(((a >> 8) & 0xff) * (1 - t) + ((b >> 8) & 0xff) * t);
		int bl = (int) Math.round((a & 0xff) * (1 - t) + (b & 0xff) * t);
		return (r << 16) | (g << 8) | bl;
	}

	/** A name colour from the site ("#rrggbb"), or null. */
	static String nameColor(String s) {
		var v = s == null ? "" : s.replace("#", "");
		return HEX.matcher(v).matches() ? v.toLowerCase(Locale.ROOT) : null;
	}
}
