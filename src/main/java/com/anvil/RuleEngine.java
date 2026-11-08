package com.anvil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.IntPredicate;
import java.util.function.IntToLongFunction;
import java.util.function.Predicate;

/**
 * The site's rule table (GET /api/plugin/rules), run locally. It decides only
 * what can't wait for the site: whether a moment deserves a screenshot, and the chat line to print
 * right away. The site matches the reported facts to tiles (POST /api/plugin/facts) and its answer
 * corrects each rule's count. Pure and RuneLite-free (RuleEngineTest).
 *
 * Counts are touched from two threads: the client thread raises them per event, the executor overwrites
 * them with the site's answer. Every method that reads or writes a count holds the engine's lock; each
 * runs in microseconds, so the client thread never waits on anything slow.
 */
final class RuleEngine {
	static final class Table {
		Map<String, List<DropRule>> drops = new HashMap<>();
		Map<String, List<KillRule>> kills = new HashMap<>();
		List<ValueRule> values = new ArrayList<>();
		List<StatRule> stats = new ArrayList<>();
		List<EventRule> events = new ArrayList<>();
		Proof proof = new Proof();
		Map<String, String> say = new HashMap<>();
		/** Source key ("pp:" for pickpocketing) -> item ids rare enough to maybe post: screenshot and let the site judge. */
		Map<String, Set<Integer>> rare = new HashMap<>();
		/** Every drop tile is done: item gains stop counting (the site's rule). */
		boolean blackout;
	}

	static class Rule {
		int t;
		String label;
		long have;
		long of;
		List<String> src;
	}

	static final class DropRule extends Rule {
		List<String> npcs;
		boolean simple;
	}

	static final class KillRule extends Rule {
		String unit;
		boolean quiet;
		boolean coop;
	}

	static final class ValueRule extends Rule {
		long min;
	}

	static final class StatRule extends Rule {
		String on;
		List<String> names;
		long step;
		int[] active;
	}

	/**
	 * A tile judged on the spot (timed, deathless, lms, pvp, pvpLoot, diary, ca, clog, page, gain): the
	 * event must name one of {@code keys} ("*" or none = any), as the site expanded them; for timed and
	 * deathless the key is the activity the clear parser matches. {@code of} 0 = never counted.
	 */
	static final class EventRule extends Rule {
		String on;
		Set<String> keys;
		/** The event's value must be at most this (null = no cap) and at least {@code min}. */
		Long max;
		long min;
		int party;
		int lvl;
		boolean team;
		boolean coop;
		Set<Integer> need;
		String say;

		String key() {
			return keys == null || keys.isEmpty() ? "" : keys.iterator().next();
		}
	}

	static final class Proof {
		int min = 25;
		double frac = 0.25;
	}

	/** What one event comes to: lines to print now, the fact for the site, and whether to screenshot it. */
	static final class Out {
		final List<String> say = new ArrayList<>();
		final List<JsonObject> facts = new ArrayList<>();
		boolean shot;
		boolean coop;
		/** A gain was held for its tile's batch: flushGains sends it. */
		boolean held;
		/** A stat tile reached its goal: the stat push goes now. */
		boolean done;
		/** Tile id → its local count after this event, for the panel's optimistic rows. */
		final Map<Integer, Long> raised = new HashMap<>();
		/** Drop tiles this event moved, as they stood, for the in-game "tile progress" popup. */
		final List<Rule> toasts = new ArrayList<>();
	}

	private static final long DEDUP_MS = 3_000;

	private volatile Table table;
	private final Map<Integer, Long> seenItems = new HashMap<>();
	private final Map<String, TreeSet<Long>> xpMinutes = new HashMap<>();
	/** Gains held per tile until the batch goes or the tile completes. */
	private final Map<Integer, Long> held = new HashMap<>();
	/** This client's unconfirmed count for a tile: event rules build on it when the table is older. */
	volatile IntToLongFunction floor = t -> 0;

	synchronized void set(Table t) {
		table = t;
	}

	/** Could this item be rare enough to post? An npc or pickpocket drop on the site's rare table. */
	boolean rare(String kind, String source, int itemId) {
		Table t = table;
		String p = "npc".equals(kind) ? "" : "pickpocket".equals(kind) ? "pp:" : null;
		Set<Integer> ids = t == null || p == null || source == null ? null : t.rare.get(p + source.toLowerCase());
		return ids != null && ids.contains(itemId);
	}

	boolean active() {
		return table != null;
	}

	boolean blackout() {
		Table t = table;
		return t != null && t.blackout;
	}

	/** Drop or value tiles to credit: those also credit off the game's drop line. */
	boolean dropLines() {
		Table t = table;
		return t != null && !(t.drops.isEmpty() && t.values.isEmpty());
	}

	boolean tracksKill(String name) {
		Table t = table;
		return t != null && name != null && t.kills.containsKey(name.toLowerCase(Locale.ROOT));
	}

	/** Item ids some drop tile tracks (clog lines and inferred drops look items up by name). */
	Set<Integer> dropItemIds() {
		Table t = table;
		Set<Integer> ids = new HashSet<>();
		if (t != null) t.drops.keySet().forEach(k -> ids.add(Integer.valueOf(k)));
		return ids;
	}

	/** One kill/open/counter event: each moved name and its amount; a tile reached by several takes the largest, once. */
	synchronized Out kill(Map<String, Integer> moved, Collection<String> unverified, String inferred) {
		Out out = new Out();
		Table t = table;
		if (t == null) return out;
		Map<KillRule, Integer> byRule = new IdentityHashMap<>();
		for (Map.Entry<String, Integer> e : moved.entrySet()) {
			for (KillRule r : t.kills.getOrDefault(e.getKey().toLowerCase(Locale.ROOT), Collections.emptyList())) byRule.merge(r, e.getValue(), Math::max);
		}
		boolean any = false;
		for (Map.Entry<KillRule, Integer> e : byRule.entrySet()) {
			KillRule r = e.getKey();
			if (r.have >= r.of) continue;
			any = true;
			long n = Math.min(e.getValue(), r.of - r.have);
			r.have += n;
			out.raised.put(r.t, r.have);
			out.coop |= r.coop;
			out.shot |= r.have >= r.of || milestone(n, r.have, r.of, t.proof);
			if (!r.quiet) {
				out.say.add(fill(t, "kill", r, "{unit}", r.unit == null ? "kill" : r.unit));
			}
		}
		if (!any) return out;
		JsonObject f = fact("kill");
		JsonObject n = new JsonObject();
		moved.forEach(n::addProperty);
		f.add("n", n);
		if (unverified != null && !unverified.isEmpty()) {
			JsonArray u = new JsonArray();
			unverified.forEach(u::add);
			f.add("unv", u);
		}
		if (inferred != null) f.addProperty("inf", inferred);
		out.facts.add(f);
		return out;
	}

	/** One loot event ({@code items} as [id, qty]); a repeat of an item within the dedup window is the same loot fired twice. */
	synchronized Out loot(String source, String kind, List<int[]> items, Integer party, String inferred, long now) {
		Out out = new Out();
		Table t = table;
		if (t == null || items == null) return out;
		JsonArray sent = new JsonArray();
		Set<DropRule> said = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int[] it : items) {
			List<DropRule> rules = t.drops.get(String.valueOf(it[0]));
			if (rules == null) continue;
			Long last = seenItems.put(it[0], now);
			if (last != null && now - last < DEDUP_MS) continue;
			boolean any = false;
			for (DropRule r : rules) {
				if (!sourceOk(r, source, kind) || (r.simple && r.have >= r.of)) continue;
				any = true;
				if (r.simple) {
					r.have = Math.min(r.of, r.have + Math.max(1, it[1]));
					out.raised.put(r.t, r.have);
					out.say.add(fill(t, "drop", r));
					out.toasts.add(snap(r));
				} else if (said.add(r)) {
					out.say.add(fill(t, "dropPlain", r));
					out.toasts.add(snap(r));
				}
			}
			if (any) {
				JsonArray pair = new JsonArray();
				pair.add(it[0]);
				pair.add(it[1]);
				sent.add(pair);
			}
		}
		if (sent.size() == 0) return out;
		out.shot = true;
		JsonObject f = fact("loot");
		f.addProperty("src", source);
		f.addProperty("kind", kind);
		f.add("items", sent);
		if (party != null) f.addProperty("party", party);
		if (inferred != null) f.addProperty("inf", inferred);
		out.facts.add(f);
		return out;
	}

	/** A priced haul, for loot-value tiles. */
	synchronized Out value(String source, String kind, long gp) {
		Out out = new Out();
		Table t = table;
		if (t == null || gp <= 0) return out;
		for (ValueRule r : t.values) {
			boolean total = r.min <= 0;
			if ((total ? r.have >= r.of : gp < r.min) || !valueSourceOk(r.src, source, kind)) continue;
			r.have = total ? Math.min(r.of, r.have + gp) : r.of;
			out.raised.put(r.t, r.have);
			out.say.add(fill(t, "value", r, "{gp}", gp(gp)));
		}
		if (out.say.isEmpty()) return out;
		out.shot = true;
		JsonObject f = fact("value");
		f.addProperty("src", source);
		f.addProperty("kind", kind);
		f.addProperty("gp", gp);
		out.facts.add(f);
		return out;
	}

	/** Is any event rule of this kind open? */
	boolean has(String on) {
		Table t = table;
		return t != null && t.events.stream().anyMatch(r -> on.equals(r.on));
	}

	/** Hits a rule taking any of these keys, or any key ("*", or none listed). */
	static Predicate<EventRule> named(String... ks) {
		return r -> r.keys == null || r.keys.contains("*") || Arrays.stream(ks).anyMatch(r.keys::contains);
	}

	/**
	 * Open rules of kind {@code on} that {@code hit} names, {@code claim}ed, whose gates pass: the value
	 * against max/min, the exact party, the whole party from the team ({@code teamWhy} says why not).
	 * A refusal with a template ({@code on.max}, {@code on.party}, ...) is said into {@code no}. Counts nothing.
	 */
	synchronized List<EventRule> match(String on, Predicate<EventRule> hit, long value, String x, int party, String teamWhy, IntPredicate claim, List<String> no) {
		List<EventRule> out = new ArrayList<>();
		Table t = table;
		if (t == null) return out;
		for (EventRule r : t.events) {
			if (!on.equals(r.on) || r.of > 0 && Math.max(r.have, floor.applyAsLong(r.t)) >= r.of || !hit.test(r) || claim != null && !claim.test(r.t)) continue;
			String why = r.max != null && value > r.max ? "max" : value < r.min ? "min" : r.party > 0 && party != r.party ? "party" : r.team && teamWhy != null ? "team" : null;
			if (why == null) out.add(r);
			else if (t.say.containsKey(on + "." + why)) no.add(fill(t, on + "." + why, r, "{x}", x, "{seen}", String.valueOf(party), "{party}", String.valueOf(r.party), "{why}", teamWhy));
		}
		return out;
	}

	/**
	 * Count a matched rule by {@code n} (no more than it still needs), say its line ({x} filled) and add
	 * its tile fact with the extras {@code kv}. A gain is held for its tile's batch until it completes the tile.
	 */
	synchronized Out credit(Out o, EventRule r, long n, String x, Object... kv) {
		Table t = table;
		if (t == null) return o;
		if (r.of > 0) {
			r.have = Math.max(r.have, floor.applyAsLong(r.t));
			if (r.have >= r.of) return o;
			n = Math.min(n, r.of - r.have);
			r.have += n;
			o.raised.put(r.t, r.have);
		}
		boolean gain = "gain".equals(r.on);
		String key = r.say != null ? r.say : r.on;
		if (t.say.containsKey(key) && !(gain && held.containsKey(r.t))) o.say.add(fill(t, key, r, "{x}", x));
		if (gain) {
			n = held.merge(r.t, n, Long::sum);
			if (r.have < r.of) {
				o.held = true;
				return o;
			}
			held.remove(r.t);
		}
		o.shot = true;
		o.coop |= r.coop;
		o.facts.add(tile(r.t, n, kv));
		return o;
	}

	/** One event against the rules of kind {@code on}: each one it names and passes, credited (a gain by {@code value}, else 1). */
	synchronized Out event(String on, Predicate<EventRule> hit, long value, String x, IntPredicate claim, Object... kv) {
		Out o = new Out();
		for (EventRule r : match(on, hit, value, x, 0, null, claim, o.say)) credit(o, r, "gain".equals(on) ? value : 1, x, kv);
		return o;
	}

	/** Every held gain, as count-only facts (the batch timer, logout, hop). */
	synchronized Out flushGains() {
		Out o = new Out();
		held.forEach((t, n) -> o.facts.add(tile(t, n)));
		held.clear();
		return o;
	}

	/** A multi-run timed tile's {have, of}; null for anything else. */
	synchronized long[] runs(int id) {
		Table t = table;
		if (t != null) {
			for (EventRule r : t.events) {
				if (r.t == id && "timed".equals(r.on) && r.of > 1) return new long[]{r.have, r.of};
			}
		}
		return null;
	}

	/** A `tile` fact: the tile, the amount, and extras in pairs (a null value is left out). */
	static JsonObject tile(int id, long n, Object... kv) {
		JsonObject f = fact("tile");
		f.addProperty("t", id);
		f.addProperty("n", n);
		for (int i = 0; i + 1 < kv.length; i += 2) {
			Object v = kv[i + 1];
			if (v instanceof Number) f.addProperty((String) kv[i], (Number) v);
			else if (v instanceof Boolean) f.addProperty((String) kv[i], (Boolean) v);
			else if (v != null) f.addProperty((String) kv[i], v.toString());
		}
		return f;
	}

	/** Skill XP gained: a line per clean step crossed, only while the skill is actually being trained. */
	synchronized Out xp(String skill, long gained, long now) {
		TreeSet<Long> minutes = xpMinutes.computeIfAbsent(key(skill), k -> new TreeSet<>());
		long minute = now / 60_000;
		minutes.add(minute);
		minutes.headSet(minute - 60).clear();
		return stat("xp", skill, gained, minutes, minute);
	}

	/** A boss / activity count moved (a KC line). */
	synchronized Out kc(String name, long gained) {
		return stat("kc", name, gained, null, 0);
	}

	/** A stat name as the site spells rule names: letters and digits, so "Kree'Arra" and "kree arra" meet. */
	static String key(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	private Out stat(String on, String name, long gained, TreeSet<Long> minutes, long minute) {
		Out out = new Out();
		Table t = table;
		if (t == null || gained <= 0 || name == null) return out;
		String key = key(name);
		for (StatRule r : t.stats) {
			if (!on.equals(r.on) || r.names == null || !r.names.contains(key) || r.have >= r.of) continue;
			long before = r.have;
			r.have = Math.min(r.of, r.have + gained);
			out.raised.put(r.t, r.have);
			if (r.have >= r.of) {
				out.done = true;
				out.say.add(fill(t, "done", r));
			} else if (r.step > 0 && r.have / r.step > before / r.step && busy(r.active, minutes, minute)) {
				out.say.add(fill(t, on, r));
			}
		}
		return out;
	}

	/** Gains in at least active[0] distinct minutes of the last active[1]; no gate when none is set. */
	static boolean busy(int[] active, TreeSet<Long> minutes, long minute) {
		if (active == null || active.length < 2 || minutes == null) return true;
		return minutes.subSet(minute - active[1] + 1, true, minute, true).size() >= active[0];
	}

	/** The site's answer: every touched tile's real count replaces the local guess. Returns lines to print. */
	synchronized List<String> adopt(JsonObject reply) {
		List<String> lines = new ArrayList<>();
		Table t = table;
		if (t == null || reply == null) return lines;
		if (reply.has("credits")) {
			for (JsonElement el : reply.getAsJsonArray("credits")) {
				JsonObject c = el.getAsJsonObject();
				int id = c.get("t").getAsInt();
				long have = c.get("have").getAsLong();
				long of = c.get("of").getAsLong();
				for (Rule r : all(t)) {
					if (r.t == id && r.of > 0) {
						r.have = have;
						r.of = of;
					}
				}
			}
		}
		if (reply.has("notes")) reply.getAsJsonArray("notes").forEach(n -> lines.add(n.getAsString()));
		return lines;
	}

	/** A live push's counts (tile id → the team's count): every rule on that tile, stats too, rises to it. */
	synchronized void raise(Map<Integer, Long> have) {
		Table t = table;
		if (t == null) return;
		List<Rule> rules = all(t);
		rules.addAll(t.stats);
		for (Rule r : rules) {
			Long n = have.get(r.t);
			if (n != null && r.of > 0) r.have = Math.max(r.have, n);
		}
	}

	/** The rule as it stands now, for the popup: read outside the lock, it must not see the next answer land. */
	private static Rule snap(Rule r) {
		Rule s = new Rule();
		s.t = r.t;
		s.label = r.label;
		s.have = r.have;
		s.of = r.of;
		return s;
	}

	private static List<Rule> all(Table t) {
		List<Rule> out = new ArrayList<>(t.values);
		t.drops.values().forEach(out::addAll);
		t.kills.values().forEach(out::addAll);
		out.addAll(t.events);
		return out;
	}

	/** A grindy kill tile screenshots only on proof steps (and completion); a small one on every credit. */
	static boolean milestone(long added, long current, long required, Proof p) {
		if (required < p.min) return true;
		double step = Math.max(1.0, required * p.frac);
		return Math.floor(current / step) > Math.floor((current - added) / step);
	}

	private static boolean sourceOk(DropRule r, String source, String kind) {
		if (r.src != null && !r.src.isEmpty() ? !r.src.contains(kind) : "pvp".equals(kind)) return false;
		return r.npcs == null || r.npcs.isEmpty() || DropSource.sourceMatches(r.npcs, source);
	}

	static boolean valueSourceOk(List<String> sources, String source, String kind) {
		if (sources == null || sources.isEmpty()) return true;
		for (String s : sources) {
			if (s != null && (s.equalsIgnoreCase("PvP") ? "pvp".equals(kind) : s.equalsIgnoreCase(source))) return true;
		}
		return false;
	}

	private static JsonObject fact(String k) {
		JsonObject f = new JsonObject();
		f.addProperty("k", k);
		return f;
	}

	private static String fill(Table t, String key, Rule r, String... extra) {
		String s = t.say.getOrDefault(key, "{label} ({have}/{of})");
		boolean big = r.of >= 10_000;
		s = s.replace("{label}", r.label == null ? "" : r.label)
			.replace("{have}", big ? String.format(Locale.ROOT, "%,d", r.have) : String.valueOf(r.have))
			.replace("{of}", big ? String.format(Locale.ROOT, "%,d", r.of) : String.valueOf(r.of))
			.replace("{pct}", String.valueOf(r.of > 0 ? r.have * 100 / r.of : 0));
		for (int i = 0; i + 1 < extra.length; i += 2) s = s.replace(extra[i], extra[i + 1] == null ? "" : extra[i + 1]);
		return s;
	}

	static String gp(long gp) {
		return gp >= 1_000_000 ? String.format(Locale.ROOT, "%.1fM gp", gp / 1_000_000.0)
			: gp >= 1_000 ? String.format(Locale.ROOT, "%.0fk gp", gp / 1_000.0) : gp + " gp";
	}
}
