package com.anvil;

import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Keeps {@link ChatFlair} at the site's version (capability "chat-flair-v"). A socket delta for the
 * version we hold is applied in place, no request; anything else — a missed message, a reconnect, a
 * "full" marker, nothing held yet — asks {@code chat-flair/v?since=} after a random 0–20 s (a change
 * reaches every plugin at once; this spreads their requests), which answers with the deltas when few,
 * else the whole versioned list (CDN-cached) is downloaded. Nothing about who the player sees is sent.
 */
final class ChatFlairSync
{
	interface Source
	{
		/** The site's version and the deltas from {@code v}; null on failure. Blocking. */
		ChatFlair.Since since(int v);

		/** The whole list at version {@code v}; null on failure. Blocking. */
		ChatFlair.Wire full(int v);
	}

	interface Scheduler
	{
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

	ChatFlairSync(ChatFlair flair, Source source, Scheduler scheduler, LongSupplier jitter, Runnable changed)
	{
		this.flair = flair;
		this.source = source;
		this.scheduler = scheduler;
		this.jitter = jitter;
		this.changed = changed;
	}

	/**
	 * Is the list wanted? Signed in, the setting on, and the site has it — from the config, or for a
	 * login in no clan (whose config is a 404) from /api/version. Never tied to the config answering.
	 */
	static boolean wanted(boolean on, boolean signedIn, PluginConfigResponse cfg, Predicate<String> siteAdvertises)
	{
		return on && signedIn && ((cfg != null && cfg.serverSupports("chat-flair-v")) || siteAdvertises.test("chat-flair-v"));
	}

	/** A {"t":"flair"} push: applied if it follows our version, else a jittered catch-up. */
	void pushed(ChatFlair.Wire d)
	{
		if (flair.apply(d))
		{
			changed.run();
		}
		else if (d == null || d.v != flair.version() || d.v == 0)
		{
			soon();
		}
	}

	/** Catch up after the jitter; several asks before it runs are one. */
	synchronized void soon()
	{
		if (pending)
		{
			return;
		}
		pending = true;
		scheduler.schedule(() ->
		{
			synchronized (this)
			{
				pending = false;
			}
			sync();
		}, jitter.getAsLong());
	}

	/** Bring the list to the site's version now. Blocking. */
	void sync()
	{
		ChatFlair.Since s = source.since(flair.version());
		if (s == null || s.v == flair.version())
		{
			return;
		}
		boolean moved = false;
		if (s.deltas != null)
		{
			for (ChatFlair.Wire d : s.deltas)
			{
				moved |= flair.apply(d);
			}
		}
		if (flair.version() != s.v)
		{
			moved |= flair.adopt(source.full(s.v));
		}
		if (moved)
		{
			changed.run();
		}
	}
}
