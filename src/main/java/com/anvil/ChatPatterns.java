package com.anvil;

import lombok.RequiredArgsConstructor;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import lombok.extern.slf4j.Slf4j;

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
final class ChatPatterns
{
	private ChatPatterns() {}

	static final class Sample
	{
		String line;
		Map<String, String> expect;
	}

	static final class Entry
	{
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

	static final class Catalog
	{
		int version;
		List<Entry> patterns;
	}

	/** One site override. A null regex means "use the default". */
	static final class SiteOverride
	{
		String key;
		String regex;
		/** Optional literal the new wording always carries — the pre-filter for this override. */
		String hint;
	}

	/** Body of GET /api/plugin/chat-patterns. */
	static final class OverridesResponse
	{
		int version;
		List<SiteOverride> overrides;
	}

	static final int MAX_LENGTH = 500;
	/** charAt calls one test input may cost; a backtracking bomb blows through this in microseconds. */
	private static final long STEP_BUDGET = 2_000_000L;
	/**
	 * charAt calls an OVERRIDE may spend on one real chat line. Vetting can't foresee every line, and
	 * these run on the client thread — an override that overspends is dropped for the session.
	 */
	static final long LINE_BUDGET = 100_000L;
	private static final Pattern NEVER = Pattern.compile("(?!)");

	private static volatile Map<String, Entry> entries = Collections.emptyMap();
	private static volatile Map<String, Pattern> defaults = Collections.emptyMap();
	/** The accepted overrides and their hints, swapped as ONE object so a reader never pairs mismatched halves. */
	private static final class Live
	{
		final Map<String, Pattern> patterns;
		final Map<String, String> hints;

		Live(Map<String, Pattern> patterns, Map<String, String> hints)
		{
			this.patterns = Collections.unmodifiableMap(patterns);
			this.hints = Collections.unmodifiableMap(hints);
		}
	}

	private static volatile Live live = new Live(new HashMap<>(), new HashMap<>());

	/** Read the bundled catalog. Idempotent; a broken resource leaves every key matching nothing. */
	static synchronized void loadDefaults(Gson gson)
	{
		if (!defaults.isEmpty())
		{
			return;
		}
		try (InputStream in = ChatPatterns.class.getResourceAsStream("chat-patterns.json");
			Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			Catalog c = gson.fromJson(r, Catalog.class);
			Map<String, Entry> e = new HashMap<>();
			Map<String, Pattern> d = new HashMap<>();
			for (Entry p : c.patterns)
			{
				e.put(p.key, p);
				d.put(p.key, Pattern.compile(p.regex, flagsOf(p)));
			}
			entries = Collections.unmodifiableMap(e);
			defaults = Collections.unmodifiableMap(d);
		}
		catch (Exception ex)
		{
			log.warn("Anvil: chat pattern catalog failed to load", ex);
		}
	}

	/** The live pattern for a key: the site's override if one was accepted, else the default. */
	static Pattern get(String key)
	{
		Pattern p = live.patterns.get(key);
		if (p == null)
		{
			p = defaults.get(key);
		}
		return p == null ? NEVER : p;
	}

	/** A matcher that found {@code key} somewhere in {@code text}, or null. */
	static Matcher find(String key, String text)
	{
		return match(key, text, false);
	}

	/** A matcher for which {@code key} matched the whole of {@code text}, or null. */
	static Matcher full(String key, String text)
	{
		return match(key, text, true);
	}

	private static Matcher match(String key, String text, boolean whole)
	{
		Live l = live;
		Pattern o = l.patterns.get(key);
		if (o != null)
		{
			String hint = l.hints.get(key);
			if (hint != null && !text.contains(hint))
			{
				return null;
			}
			Matcher m = o.matcher(new Budgeted(text, LINE_BUDGET));
			try
			{
				return (whole ? m.matches() : m.find()) ? m : null;
			}
			catch (BudgetExceeded ex)
			{
				drop(key, o);
				// fall through to the default — this line still deserves a reading
			}
		}
		Pattern p = defaults.get(key);
		if (p == null)
		{
			return null;
		}
		Matcher m = p.matcher(text);
		return (whole ? m.matches() : m.find()) ? m : null;
	}

	/** Retire an override that overspent on a real line. Only if it is still the live one. */
	private static synchronized void drop(String key, Pattern bad)
	{
		Live l = live;
		if (l.patterns.get(key) != bad)
		{
			return;
		}
		Map<String, Pattern> next = new HashMap<>(l.patterns);
		next.remove(key);
		Map<String, String> hints = new HashMap<>(l.hints);
		hints.remove(key);
		live = new Live(next, hints);
		log.warn("Anvil: chat pattern override for {} was too slow on a real line — using the default this session", key);
	}

	/**
	 * Whether {@code text} could match {@code key} — the default's literal hint, a free pre-check on
	 * every chat line. An overridden key skips it: the new wording may not carry the old phrase.
	 */
	static boolean mayMatch(String key, String text)
	{
		Live l = live;
		if (l.patterns.containsKey(key))
		{
			String hint = l.hints.get(key);
			return hint == null || text.contains(hint);
		}
		Entry e = entries.get(key);
		return e == null || e.hint == null || text.contains(e.hint);
	}

	static boolean isOverridden(String key)
	{
		return live.patterns.containsKey(key);
	}

	/**
	 * Replace the override set with {@code list}, keeping only the overrides that pass {@link #vet}.
	 * Keys not named fall back to their defaults. Returns one "key: reason" per rejection.
	 */
	static synchronized List<String> applyOverrides(List<SiteOverride> list)
	{
		Map<String, Pattern> next = new HashMap<>();
		Map<String, String> hints = new HashMap<>();
		List<String> rejected = new ArrayList<>();
		if (list != null)
		{
			for (SiteOverride o : list)
			{
				if (o == null || o.key == null || o.regex == null)
				{
					continue;
				}
				Entry e = entries.get(o.key);
				if (e == null)
				{
					rejected.add(o.key + ": unknown key");
					continue;
				}
				try
				{
					next.put(o.key, vet(e, o.regex));
					if (o.hint != null && !o.hint.isEmpty())
					{
						hints.put(o.key, o.hint);
					}
				}
				catch (IllegalArgumentException ex)
				{
					rejected.add(o.key + ": " + ex.getMessage());
				}
			}
		}
		live = new Live(next, hints);
		return rejected;
	}

	/** Compile {@code regex} for {@code e}, or throw with the reason it can't stand in for the default. */
	static Pattern vet(Entry e, String regex)
	{
		if (regex.length() > MAX_LENGTH)
		{
			throw new IllegalArgumentException("longer than " + MAX_LENGTH + " chars");
		}
		int flags = flagsOf(e);
		Pattern p;
		Pattern probe;
		try
		{
			p = Pattern.compile(regex, flags);
			// Java 11 has no Pattern.namedGroups(). An empty alternative lets "" match, and group(name)
			// on a successful match throws only when the name doesn't exist.
			probe = Pattern.compile("(?:" + regex + ")|", flags);
		}
		catch (PatternSyntaxException ex)
		{
			throw new IllegalArgumentException("does not compile: " + ex.getDescription());
		}
		Matcher m = probe.matcher("");
		if (!m.matches())
		{
			throw new IllegalArgumentException("does not compile cleanly");
		}
		for (String g : e.groups)
		{
			try
			{
				m.group(g);
			}
			catch (IllegalArgumentException ex)
			{
				throw new IllegalArgumentException("missing named group (?<" + g + ">…)");
			}
		}
		for (String input : stressInputs(e))
		{
			try
			{
				p.matcher(new Budgeted(input)).find();
			}
			catch (BudgetExceeded ex)
			{
				throw new IllegalArgumentException("too slow (catastrophic backtracking)");
			}
		}
		return p;
	}

	private static List<String> stressInputs(Entry e)
	{
		List<String> out = new ArrayList<>();
		if (e.samples != null)
		{
			for (Sample s : e.samples)
			{
				out.add(s.line);
				out.add(s.line + s.line + s.line + s.line);
			}
		}
		char[] a = new char[MAX_LENGTH];
		Arrays.fill(a, 'a');
		out.add(new String(a));
		Arrays.fill(a, ' ');
		out.add(new String(a) + "!");
		return out;
	}

	private static int flagsOf(Entry e)
	{
		int f = 0;
		if (e.flags != null)
		{
			for (String s : e.flags)
			{
				if ("CASE_INSENSITIVE".equals(s))
				{
					f |= Pattern.CASE_INSENSITIVE;
				}
				else if ("DOTALL".equals(s))
				{
					f |= Pattern.DOTALL;
				}
			}
		}
		return f;
	}

	static Map<String, Entry> entries()
	{
		return entries;
	}

	private static final class BudgetExceeded extends RuntimeException
	{
		BudgetExceeded()
		{
			super(null, null, false, false);
		}
	}

	/** A CharSequence that gives up once the regex engine has read it too many times. */
	@RequiredArgsConstructor
	private static final class Budgeted implements CharSequence
	{
		private final String s;
		private final long[] left;

		Budgeted(String s)
		{
			this(s, STEP_BUDGET);
		}

		Budgeted(String s, long budget)
		{
			this(s, new long[]{budget});
		}


		@Override
		public char charAt(int i)
		{
			if (--left[0] < 0)
			{
				throw new BudgetExceeded();
			}
			return s.charAt(i);
		}

		@Override
		public int length()
		{
			return s.length();
		}

		@Override
		public CharSequence subSequence(int start, int end)
		{
			return new Budgeted(s.substring(start, end), left);
		}

		@Override
		public String toString()
		{
			return s;
		}
	}
}
