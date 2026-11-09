package com.anvil;

import lombok.RequiredArgsConstructor;
import java.util.function.LongSupplier;


/**
 * Keeps {@link ChatFlair} at the site's version. A socket delta for the
 * version we hold is applied in place, no request; anything else — a missed message, a reconnect, a
 * "full" marker, nothing held yet — asks {@code chat-flair/v?since=} after a random 0–20 s (a change
 * reaches every plugin at once; this spreads their requests), which answers with the deltas when few,
 * else the whole versioned list (CDN-cached) is downloaded. Nothing about who the player sees is sent.
 */
@RequiredArgsConstructor
final class ChatFlairSync {
	interface Source {
		/** The site's version and the deltas from {@code v}; null on failure. Blocking. */
		ChatFlair.Since since(int v);

		/** The whole list at version {@code v}; null on failure. Blocking. */
		ChatFlair.Wire full(int v);
	}

	interface Scheduler {
		/** Run {@code r} after {@code ms}, off the client thread, where blocking HTTP is fine. */
		void schedule(Runnable r, long ms);
	}

	static final long JITTER_MS = 20_000;

	private final ChatFlair flair;
	private final Source source;
	private final Scheduler scheduler;
	private final LongSupplier jitter;
	/** After the list changed: repaint, save. */
	private final Runnable changed;
	private boolean pending;


	/** A {"t":"flair"} push: applied if it follows our version, else a jittered catch-up. */
	void pushed(ChatFlair.Wire d) {
		if (flair.apply(d)) {
			changed.run();
		} else if (d == null || d.v != flair.version() || d.v == 0) {
			soon();
		}
	}

	/** Catch up after the jitter; several asks before it runs are one. */
	synchronized void soon() {
		if (pending) return;
		pending = true;
		scheduler.schedule(() -> {
			synchronized (this) {
				pending = false;
			}
			sync();
		}, jitter.getAsLong());
	}

	/** Bring the list to the site's version now. Blocking. */
	void sync() {
		var s = source.since(flair.version());
		if (s == null) return;
		if (s.v == flair.version()) {
			// A dev client can retain a valid version number with an empty/corrupt disk snapshot. The
			// version endpoint then quite correctly says "current", but none of that client's names ever
			// recover. An empty current generation is cheap and safe to verify from the CDN-cached full list.
			var wrongSize = s.n != null && s.n >= 0 && flair.size() != s.n;
			if (s.v > 0 && (flair.isEmpty() || wrongSize) && flair.adopt(source.full(s.v))) changed.run();
			return;
		}
		var moved = false;
		if (s.deltas != null) {
			for (ChatFlair.Wire d : s.deltas) moved |= flair.apply(d);
		}
		if (flair.version() != s.v || s.n != null && s.n >= 0 && flair.size() != s.n) moved |= flair.adopt(source.full(s.v));
		if (moved) changed.run();
	}
}
