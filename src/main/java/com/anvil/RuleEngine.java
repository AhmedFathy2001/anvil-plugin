package com.anvil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * The site's rule table (capability {@code rules}, GET /api/plugin/rules), run locally. It decides only
 * what can't wait for the site: whether a moment deserves a screenshot, and the chat line to print
 * right away. The site matches the reported facts to tiles (POST /api/plugin/facts) and its answer
 * corrects each rule's count. Pure and RuneLite-free (RuleEngineTest).
 */
final class RuleEngine {
	static final class Table {
		Map<String, List<DropRule>> drops = new HashMap<>();
		Map<String, List<KillRule>> kills = new HashMap<>();
		List<ValueRule> values = new ArrayList<>();
		List<StatRule> stats = new ArrayList<>();
		Proof proof = new Proof();
		Map<String, String> say = new HashMap<>();
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

	static final class Proof {
		int min = 25;
		double frac = 0.25;
	}

	/** What one event comes to: lines to print now, the fact for the site, and whether to screenshot it. */
	static final class Out {
		final List<String> say = new ArrayList<>();
		JsonObject fact;
		boolean shot;
		boolean coop;
		/** Tile id → its local count after this event, for the panel's optimistic rows. */
		final Map<Integer, Long> raised = new HashMap<>();
		/** Drop tiles this event moved, for the in-game "tile progress" popup. */
		final List<Rule> toasts = new ArrayList<>();
	}

	private static final long DEDUP_MS = 3_000;

	private volatile Table table;
	private final Map<Integer, Long> seenItems = new HashMap<>();
	private final Map<String, TreeSet<Long>> xpMinutes = new HashMap<>();

	void set(Table t) {
		table = t;
	}

	boolean active() {
		return table != null;
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
	Out kill(Map<String, Integer> moved, Collection<String> unverified, String inferred) {
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
		out.fact = f;
		return out;
	}

	/** One loot event ({@code items} as [id, qty]); a repeat of an item within the dedup window is the same loot fired twice. */
	Out loot(String source, String kind, List<int[]> items, Integer party, String inferred, long now) {
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
					out.toasts.add(r);
				} else if (said.add(r)) {
					out.say.add(fill(t, "dropPlain", r));
					out.toasts.add(r);
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
		out.fact = f;
		return out;
	}

	/** A priced haul, for loot-value tiles. */
	Out value(String source, String kind, long gp) {
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
		out.fact = f;
		return out;
	}

	/** Skill XP gained: a line per clean step crossed, only while the skill is actually being trained. */
	List<String> xp(String skill, long gained, long now) {
		TreeSet<Long> minutes = xpMinutes.computeIfAbsent(skill.toLowerCase(Locale.ROOT), k -> new TreeSet<>());
		long minute = now / 60_000;
		minutes.add(minute);
		minutes.headSet(minute - 60).clear();
		return stat("xp", skill, gained, minutes, minute);
	}

	/** A boss / activity count moved (a KC line). */
	List<String> kc(String name, long gained) {
		return stat("kc", name, gained, null, 0);
	}

	private List<String> stat(String on, String name, long gained, TreeSet<Long> minutes, long minute) {
		List<String> lines = new ArrayList<>();
		Table t = table;
		if (t == null || gained <= 0 || name == null) return lines;
		String key = name.toLowerCase(Locale.ROOT);
		for (StatRule r : t.stats) {
			if (!on.equals(r.on) || r.names == null || !r.names.contains(key) || r.have >= r.of) continue;
			long before = r.have;
			r.have = Math.min(r.of, r.have + gained);
			if (r.have >= r.of) {
				lines.add(fill(t, "done", r));
			} else if (r.step > 0 && r.have / r.step > before / r.step && busy(r.active, minutes, minute)) {
				lines.add(fill(t, on, r));
			}
		}
		return lines;
	}

	/** Gains in at least active[0] distinct minutes of the last active[1]; no gate when none is set. */
	static boolean busy(int[] active, TreeSet<Long> minutes, long minute) {
		if (active == null || active.length < 2 || minutes == null) return true;
		return minutes.subSet(minute - active[1] + 1, true, minute, true).size() >= active[0];
	}

	/** The site's answer: every touched tile's real count replaces the local guess. Returns lines to print. */
	List<String> adopt(JsonObject reply) {
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
					if (r.t == id && !(r instanceof StatRule)) {
						r.have = have;
						r.of = of;
					}
				}
			}
		}
		if (reply.has("notes")) reply.getAsJsonArray("notes").forEach(n -> lines.add(n.getAsString()));
		return lines;
	}

	private static List<Rule> all(Table t) {
		List<Rule> out = new ArrayList<>(t.values);
		t.drops.values().forEach(out::addAll);
		t.kills.values().forEach(out::addAll);
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
		for (int i = 0; i + 1 < extra.length; i += 2) s = s.replace(extra[i], extra[i + 1]);
		return s;
	}

	static String gp(long gp) {
		return gp >= 1_000_000 ? String.format(Locale.ROOT, "%.1fM gp", gp / 1_000_000.0)
			: gp >= 1_000 ? String.format(Locale.ROOT, "%.0fk gp", gp / 1_000.0) : gp + " gp";
	}
}
