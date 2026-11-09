package com.anvil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The highlighted game messages seen on a game-mode world (Leagues, Deadman, ...), batched for
 * POST /api/plugin/mode-lines. The site decides which are relics, unlocks or tasks; this only keeps
 * the lines worth asking about (the game colours its announcements) and sends a few seconds at once.
 */
final class ModeLines {
	static final int MAX_BATCH = 20;
	static final long FLUSH_MS = 5_000;

	private final List<String> pending = new ArrayList<>();
	private long firstAt;
	private String last;

	/** Keep one line if it looks like an announcement. True when kept. */
	synchronized boolean offer(String raw, long nowMs) {
		if (raw == null || raw.isEmpty() || raw.length() > 400
			|| (!raw.contains("<col=") && !raw.toLowerCase(Locale.ROOT).startsWith("congratulations"))) {
			return false;
		}
		// The game prints some lines on two channels; one copy is enough.
		if (raw.equals(last) || pending.size() >= MAX_BATCH) return false;
		last = raw;
		if (pending.isEmpty()) firstAt = nowMs;
		pending.add(raw);
		return true;
	}

	/** The batch once it has waited FLUSH_MS, else null. */
	synchronized List<String> drain(long nowMs) {
		if (pending.isEmpty() || nowMs - firstAt < FLUSH_MS) return null;
		List<String> out = new ArrayList<>(pending);
		pending.clear();
		return out;
	}
}
