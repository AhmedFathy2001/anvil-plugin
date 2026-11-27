package com.anvil;

import static java.util.Collections.*;
import lombok.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import lombok.extern.slf4j.*;

/**
 * Every chat line the plugin reads, by a stable key, so the site can re-word one without a release.
 *
 * <p>The defaults live in {@code chat-patterns.json} beside this class — the same file the site's
 * pattern editor is built from, with each key's required named groups and real sample lines. When
 * Jagex rewords a line, the site serves an override for that key (GET /api/plugin/chat-patterns);
 * {@link #applyOverrides} vets each one and swaps the whole map at once, so a parser always reads one
 * consistent snapshot. A rejected override changes nothing: that key keeps its default.</p>
 *
 * <p>Parsers read groups BY NAME — an override may order its groups differently.</p>
 */
@Slf4j
class ChatPatterns {
	static class Sample {
		String line;
		Map<String, String> expect;
	}

	static class Entry {
		String key;
		String description;
		List<String> types;
		String input;
		String match;
		String regex;
		List<String> groups;
		List<String> flags;
		/** Cheap substring the DEFAULT line always carries — checked before the regex runs. */
		String hint;
		List<Sample> samples;
	}

	static class Catalog {
		int version;
		List<Entry> patterns;
	}

	/** One site override. A null regex means "use the default". */
	static class SiteOverride {
		String key;
		String regex;
		/** Optional literal the new wording always carries — the pre-filter for this override. */
		String hint;
	}

	/** Body of GET /api/plugin/chat-patterns. */
	static class OverridesResponse {
		int version;
		List<SiteOverride> overrides;
	}

	static final int MAX_LENGTH = 500;
	/**
	 * charAt calls an OVERRIDE may spend on one real chat line. Vetting can't foresee every line, and
	 * these run on the client thread — an override that overspends is dropped for the session.
	 */
	static final Pattern NEVER = Pattern.compile("(?!)");

	static volatile Map<String, Entry> entries = emptyMap();
	static volatile Map<String, Pattern> defaults = emptyMap();
	/** The accepted overrides and their hints, swapped as ONE object so a reader never pairs mismatched halves. */
	static class Live {
		final Map<String, Pattern> patterns;
		final Map<String, String> hints;

		Live(Map<String, Pattern> patterns, Map<String, String> hints) {
			this.patterns = unmodifiableMap(patterns);
			this.hints = unmodifiableMap(hints);
		}
	}

	static volatile Live live = new Live(new HashMap<>(), new HashMap<>());

	/** Read the bundled catalog. Idempotent; a broken resource leaves every key matching nothing. */
	static synchronized void loadDefaults(Gson gson) {
		if (!defaults.isEmpty()) return;
		try (var in = ChatPatterns.class.getResourceAsStream("chat-patterns.json");
			var r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			var c = gson.fromJson(r, Catalog.class);
			var e = new HashMap<String, Entry>();
			var d = new HashMap<String, Pattern>();
			for (var p : c.patterns) {
				e.put(p.key, p);
				d.put(p.key, Pattern.compile(p.regex, flagsOf(p)));
			}
			entries = unmodifiableMap(e);
			defaults = unmodifiableMap(d);
		} catch (Exception ex) {
			log.warn("Chat patterns failed to load", ex);
		}
	}

	/** The live pattern for a key: the site's override if one was accepted, else the default. */
	static Pattern get(String key) {
		var p = live.patterns.get(key);
		if (p == null) p = defaults.get(key);
		return p == null ? NEVER : p;
	}

	/** A matcher that found {@code key} somewhere in {@code text}, or null. */
	static Matcher find(String key, String text) {
		return match(key, text, false);
	}

	/** A matcher for which {@code key} matched the whole of {@code text}, or null. */
	static Matcher full(String key, String text) {
		return match(key, text, true);
	}

	static Matcher match(String key, String text, boolean whole) {
		var l = live;
		var o = l.patterns.get(key);
		if (o != null) {
			var hint = l.hints.get(key);
			if (hint != null && !text.contains(hint)) return null;
			try {
				return hit(o.matcher(new Budgeted(text, 100_000L /* LINE_BUDGET */)), whole);
			} catch (BudgetExceeded ex) {
				drop(key, o);
				// fall through to the default — this line still deserves a reading
			}
		}
		var p = defaults.get(key);
		return p == null ? null : hit(p.matcher(text), whole);
	}

	static Matcher hit(Matcher m, boolean whole) {
		return (whole ? m.matches() : m.find()) ? m : null;
	}

	/** Retire an override that overspent on a real line. Only if it is still the live one. */
	static synchronized void drop(String key, Pattern bad) {
		var l = live;
		if (l.patterns.get(key) != bad) return;
		var next = new HashMap<>(l.patterns);
		next.remove(key);
		var hints = new HashMap<>(l.hints);
		hints.remove(key);
		live = new Live(next, hints);
		log.warn("Chat pattern {} too slow; default this session", key);
	}

	/**
	 * Whether {@code text} could match {@code key} — the default's literal hint, a free pre-check on
	 * every chat line. An overridden key skips it: the new wording may not carry the old phrase.
	 */
	static boolean mayMatch(String key, String text) {
		var l = live;
		if (l.patterns.containsKey(key)) {
			var hint = l.hints.get(key);
			return hint == null || text.contains(hint);
		}
		var e = entries.get(key);
		return e == null || e.hint == null || text.contains(e.hint);
	}

	/**
	 * Replace the override set with {@code list}, keeping only the overrides that pass {@link #vet}.
	 * Keys not named fall back to their defaults. Returns one "key: reason" per rejection.
	 */
	static synchronized List<String> applyOverrides(List<SiteOverride> list) {
		var next = new HashMap<String, Pattern>();
		var hints = new HashMap<String, String>();
		var rejected = new ArrayList<String>();
		if (list != null) {
			for (var o : list) {
				if (o == null || o.key == null || o.regex == null) continue;
				var e = entries.get(o.key);
				if (e == null) {
					rejected.add(o.key + ": unknown key");
					continue;
				}
				try {
					next.put(o.key, vet(e, o.regex));
					if (o.hint != null && !o.hint.isEmpty()) hints.put(o.key, o.hint);
				} catch (IllegalArgumentException ex) {
					rejected.add(o.key + ": " + ex.getMessage());
				}
			}
		}
		live = new Live(next, hints);
		return rejected;
	}

	/** Compile {@code regex} for {@code e}, or throw with the reason it can't stand in for the default. */
	static Pattern vet(Entry e, String regex) {
		if (regex.length() > MAX_LENGTH) throw new IllegalArgumentException("longer than " + MAX_LENGTH + " chars");
		int flags = flagsOf(e);
		Pattern p, probe;
		try {
			p = Pattern.compile(regex, flags);
			// Java 11 has no Pattern.namedGroups(). An empty alternative lets "" match, and group(name)
			// on a successful match throws only when the name doesn't exist.
			probe = Pattern.compile("(?:" + regex + ")|", flags);
		} catch (PatternSyntaxException ex) {
			throw new IllegalArgumentException("does not compile: " + ex.getDescription());
		}
		var m = probe.matcher("");
		if (!m.matches()) throw new IllegalArgumentException("does not compile cleanly");
		for (var g : e.groups) {
			try {
				m.group(g);
			} catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("missing named group (?<" + g + ">…)");
			}
		}
		// Stress inputs: every sample, each also four times over, then 500 a's and 500 spaces and a "!".
		var inputs = new ArrayList<String>();
		if (e.samples != null) {
			for (var s : e.samples) {
				inputs.add(s.line);
				inputs.add(s.line.repeat(4));
			}
		}
		inputs.add("a".repeat(MAX_LENGTH));
		inputs.add(" ".repeat(MAX_LENGTH) + "!");
		for (var input : inputs) {
			try {
				p.matcher(new Budgeted(input, 2_000_000L /* STEP_BUDGET */)).find();
			} catch (BudgetExceeded ex) {
				throw new IllegalArgumentException("too slow (catastrophic backtracking)");
			}
		}
		return p;
	}

	static int flagsOf(Entry e) {
		int f = 0;
		if (e.flags != null) {
			for (var s : e.flags) {
				if ("CASE_INSENSITIVE".equals(s)) f |= Pattern.CASE_INSENSITIVE;
				else if ("DOTALL".equals(s)) f |= Pattern.DOTALL;
			}
		}
		return f;
	}

	static class BudgetExceeded extends RuntimeException {
		BudgetExceeded() {
			super(null, null, false, false);
		}
	}

	/** A CharSequence that gives up once the regex engine has read it too many times. */
	@RequiredArgsConstructor
	static class Budgeted implements CharSequence {
		final String s;
		final long[] left;

		Budgeted(String s, long budget) {
			this(s, new long[]{budget});
		}

		public char charAt(int i) {
			if (--left[0] < 0) throw new BudgetExceeded();
			return s.charAt(i);
		}

		public int length() {
			return s.length();
		}

		public CharSequence subSequence(int start, int end) {
			return new Budgeted(s.substring(start, end), left);
		}

		public String toString() {
			return s;
		}
	}
}
