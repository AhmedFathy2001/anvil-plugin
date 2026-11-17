package com.anvil;

import com.anvil.PluginConfigResponse.*;
import java.util.*;

/**
 * The extra progress lines a tile carries beyond its own count — "3/5 runs" on a multi-run timed tile
 * (Barracuda Trials ranks), and a COMBO's parts ("1 of 2 done", then one line per part) — plus the
 * virtual-id rule behind capped counters.
 *
 * <p>A capped counter tile's cap counters carry a VIRTUAL negative tileId, {@code -(capOf * 8 + slot)};
 * they are never rows, pins or chat subjects of their own.
 *
 * <p>A combo's parts ride the ordinary tracked lists with {@code partOf} set; they credit as usual and
 * fold under their combo, which is what pins.
 *
 * <p>No RuneLite dependencies, so it is unit-testable.
 */
final class TileNotes {


	/** Only real tiles can be pinned (the site serves no progress for a virtual id). */
	static boolean pinnable(int tileId) {
		return tileId > 0;
	}

	/** "3/5 runs" for a tile needing several qualifying runs; null for a plain one-clear timed tile. */
	static String runs(long done, int required) {
		if (required <= 1) return null;
		return fmt(Math.max(0, Math.min(done, required))) + "/" + fmt(required) + " runs";
	}


	/** Part tileId → its combo's tileId. */
	static Map<Integer, Integer> partCombos(List<TrackedCombo> combos) {
		if (combos == null || combos.isEmpty()) return Collections.emptyMap();
		Map<Integer, Integer> out = new HashMap<>();
		for (var c : combos) {
			if (c != null && c.parts != null) {
				for (var p : c.parts) {
					if (p != null) out.put(p.tileId, c.tileId);
				}
			}
		}
		return out;
	}

	/**
	 * A pinned combo's lines: "1 of 2 done", then each part — "Zulrah pet  done" once done, else its label
	 * with its count when it has one ("Searches 37/100"). {@code counts} maps a part's tileId to
	 * {current, required} at this client's newest count; a part missing from it shows just its label.
	 */
	static List<String> comboLines(TrackedCombo combo, Map<Integer, long[]> counts) {
		List<String> out = new ArrayList<>();
		int needed = Math.max(1, combo.needed);
		// A "mix" combo is weighted shares, so it reads as the server's percentage instead.
		out.add("mix".equals(combo.rule)
			? Math.max(0, Math.min(100, combo.percent)) + "%"
			: fmt(Math.min(combo.done, needed)) + " of " + fmt(needed) + " done");
		if (combo.parts != null) {
			for (var p : combo.parts) {
				if (p == null) continue;
				var label = p.label == null ? "Part" : p.label.trim();
				var c = counts == null ? null : counts.get(p.tileId);
				if (p.completed) {
					out.add(label + "  done");
				} else if (c != null && c[1] > 1) {
					out.add(label + "  " + fmt(Math.max(0, Math.min(c[0], c[1]))) + "/" + fmt(c[1]));
				} else out.add(label);
			}
		}
		return out;
	}

	/** A part named with its combo, for chat: "GOTR rare or searches — 100 searches". */
	static String partLabel(String comboLabel, String partLabel) {
		if (comboLabel == null || comboLabel.trim().isEmpty()) return partLabel;
		return comboLabel.trim() + " \u2014 " + (partLabel == null ? "" : partLabel.trim());
	}

	static String plural(String noun) {
		var n = noun.toLowerCase(Locale.ROOT);
		if (n.endsWith("s") || n.endsWith("x") || n.endsWith("z") || n.endsWith("sh") || n.endsWith("ch")) return noun + "es";
		if (n.length() > 1 && n.endsWith("y") && "aeiou".indexOf(n.charAt(n.length() - 2)) < 0) return noun.substring(0, noun.length() - 1) + "ies";
		return noun + "s";
	}

	static String fmt(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}
}
