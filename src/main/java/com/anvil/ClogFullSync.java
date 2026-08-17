package com.anvil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects a WHOLE collection log, as the server transmits it.
 *
 * <p>The client normally holds one page — whichever the player last drew — which is why the page
 * reader ({@link ClogPageReader}) can only ever report what someone has clicked through. But opening
 * the log and toggling its Search makes the server transmit EVERY entry, one script fire per item.
 * That's the whole log in one go, with no paging and nothing for the player to do but open the
 * interface. (The technique is WikiSync's; RuneProfile uses it too.)
 *
 * <p>This class is the accumulator and the "have they stopped arriving yet" policy. It is
 * RuneLite-free on purpose: the awkward parts are the quiet period and the guards against half a
 * transmit being sent as if it were a log, and both are worth testing without a client.
 *
 * <p>Item ids only — no page names. The site owns the catalogue (src/data/clog.json) and maps ids to
 * pages on the way in, so a game update that shuffles pages is a dataset rebuild rather than a
 * plugin release.
 */
final class ClogFullSync
{
	/**
	 * Items land over several ticks; wait this long after the last one before calling it complete.
	 * Two ticks and change — long enough that a transmit arriving in bursts isn't cut in half, short
	 * enough that a player who pressed a button isn't left watching an ellipsis.
	 */
	static final long QUIET_MS = 1_200;

	/**
	 * A transmit that yields fewer items than this is treated as noise rather than a log. The point
	 * is not to validate someone's collection — a genuinely empty log is possible — but to refuse to
	 * REPLACE a stored log with the tail end of an interrupted transmit (they closed the interface
	 * mid-flight, or another plugin's own search toggle fired one item).
	 */
	static final int MIN_ITEMS = 5;

	/** itemId -> quantity, in arrival order so a batch reads like the log does. */
	private final Map<Integer, Integer> items = new LinkedHashMap<>();

	private long lastItemAt;
	private boolean receiving;
	/** Set when the player asked for this explicitly, so a no-change sync still gets sent + reported. */
	private boolean manual;

	/** A transmit is starting (the log was opened / the button was pressed). Clears anything stale. */
	void begin(boolean manualRequest)
	{
		items.clear();
		receiving = true;
		manual = manual || manualRequest;
		lastItemAt = 0;
	}

	/** One item, straight off the transmit script. Ignored unless a transmit is actually in flight. */
	void onItem(int itemId, int quantity, long now)
	{
		if (!receiving || itemId <= 0)
		{
			return;
		}
		items.put(itemId, Math.max(1, quantity));
		lastItemAt = now;
	}

	/**
	 * Has a complete-looking transmit settled? True once nothing has arrived for {@link #QUIET_MS}
	 * and enough items landed to be a log rather than a fragment.
	 */
	boolean isDue(long now)
	{
		return receiving
			&& lastItemAt > 0
			&& items.size() >= MIN_ITEMS
			&& now - lastItemAt >= QUIET_MS;
	}

	/** True when the player pressed the button, so the caller can report the result in chat. */
	boolean isManual()
	{
		return manual;
	}

	/** Everything received, as an unmodifiable snapshot. Does not clear — see {@link #onSent}. */
	Map<Integer, Integer> snapshot()
	{
		return Collections.unmodifiableMap(new LinkedHashMap<>(items));
	}

	int size()
	{
		return items.size();
	}

	/** The push landed: drop the batch and stand down until the next transmit. */
	void onSent()
	{
		items.clear();
		receiving = false;
		manual = false;
		lastItemAt = 0;
	}

	/**
	 * The push failed. Keeps the items so the next tick can retry, but stops the clock restarting —
	 * a site that is down must not cost the player their transmit.
	 */
	void onSendFailed(long now)
	{
		lastItemAt = now;
	}

	/** Account switch / logout: a half-received log belongs to nobody. */
	void reset()
	{
		items.clear();
		receiving = false;
		manual = false;
		lastItemAt = 0;
	}
}
