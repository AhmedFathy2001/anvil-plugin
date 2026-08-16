package com.anvil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides what of the collection log is worth sending, and when.
 *
 * <p>The game redraws a log page on every scroll, filter and tab click, so the naive "push what's on
 * screen" would be hundreds of identical requests per session. This keeps a hash per page and only
 * queues one that actually changed, then batches the queue behind a quiet period.
 *
 * <p>The hashes of pages we've already sent are persisted (see {@link #serializeState}), so logging
 * back in and opening the log doesn't re-send a log that hasn't moved. That's the difference between
 * a sync that costs one request a week and one that costs a request a minute forever.
 *
 * <p>RuneLite-free on purpose: the flush policy is the part with edge cases, and this way it's
 * unit-testable without a client. The plugin owns the widget hook, the scheduler and the HTTP.
 */
final class ClogSync
{
	/** Wait this long after the last change before sending — a player paging through settles first. */
	static final long QUIET_MS = 2_500;
	/** Send at most this many pages per request; a full first sync becomes a handful of them. */
	static final int BATCH_PAGES = 25;
	/** Queue this many and go early — a bulk sync shouldn't sit waiting on the quiet timer. */
	private static final int EAGER_AT = BATCH_PAGES;

	/** Last hash we successfully sent, per page name. The persisted half of the state. */
	private final Map<String, Long> sent = new HashMap<>();
	/** Changed pages awaiting a push, newest write wins. Insertion-ordered so batches are stable. */
	private final Map<String, ClogPage> pending = new LinkedHashMap<>();

	private long lastChangeAt;
	private boolean stateLoaded;

	/**
	 * Offer the page currently on screen. Cheap and idempotent — the common case is a hash compare
	 * against what we already sent, and no allocation at all.
	 *
	 * @return true when the page was queued (i.e. it's new or changed)
	 */
	boolean offer(ClogPage page, long now)
	{
		if (page == null || page.name == null || page.name.isEmpty())
		{
			return false;
		}
		Long alreadySent = sent.get(page.name);
		if (alreadySent != null && alreadySent == page.hash)
		{
			return false;
		}
		ClogPage queued = pending.get(page.name);
		if (queued != null && queued.hash == page.hash)
		{
			return false;
		}
		pending.put(page.name, page);
		lastChangeAt = now;
		return true;
	}

	/** Whether a flush should run now: the queue has settled, or it's big enough to go early. */
	boolean isDue(long now)
	{
		if (pending.isEmpty())
		{
			return false;
		}
		return pending.size() >= EAGER_AT || now - lastChangeAt >= QUIET_MS;
	}

	/** Pages to send in the next request, oldest first. Does NOT clear them — see {@link #onSent}. */
	List<ClogPage> nextBatch()
	{
		List<ClogPage> batch = new ArrayList<>(Math.min(BATCH_PAGES, pending.size()));
		for (ClogPage page : pending.values())
		{
			batch.add(page);
			if (batch.size() >= BATCH_PAGES)
			{
				break;
			}
		}
		return batch;
	}

	/**
	 * Mark a batch delivered. Only called after the server has accepted it, so a failed push leaves
	 * the pages queued and the next flush retries them — no bookkeeping, no lost pages.
	 */
	void onSent(Collection<ClogPage> batch)
	{
		for (ClogPage page : batch)
		{
			ClogPage queued = pending.get(page.name);
			// Left queued if it changed again while the request was in flight.
			if (queued != null && queued.hash == page.hash)
			{
				pending.remove(page.name);
			}
			sent.put(page.name, page.hash);
		}
	}

	int pendingCount()
	{
		return pending.size();
	}

	/** How many distinct pages we've delivered — the numerator of the "68% synced" nudge. */
	int syncedPages()
	{
		return sent.size();
	}

	boolean hasState()
	{
		return stateLoaded;
	}

	/** Forget everything. Called when the account changes — one player's log is not another's. */
	void reset()
	{
		sent.clear();
		pending.clear();
		lastChangeAt = 0;
		stateLoaded = false;
	}

	/**
	 * The sent-hash map as one config string: {@code <hex hash>|<page name>} per line.
	 *
	 * <p>Hash first, split on the first pipe: page names contain colons ("Chambers of Xeric:
	 * Challenge Mode") and spaces, so a name-first format would need escaping. A hex hash can't
	 * contain a pipe, which makes the parse total.
	 */
	String serializeState()
	{
		StringBuilder out = new StringBuilder(sent.size() * 24);
		for (Map.Entry<String, Long> e : sent.entrySet())
		{
			if (e.getKey() == null || e.getKey().indexOf('\n') >= 0)
			{
				continue;
			}
			if (out.length() > 0)
			{
				out.append('\n');
			}
			out.append(Long.toHexString(e.getValue())).append('|').append(e.getKey());
		}
		return out.toString();
	}

	/** Restore from {@link #serializeState}. Junk lines are skipped, never fatal. */
	void restoreState(String raw)
	{
		sent.clear();
		stateLoaded = true;
		if (raw == null || raw.isEmpty())
		{
			return;
		}
		for (String line : raw.split("\n"))
		{
			int pipe = line.indexOf('|');
			if (pipe <= 0 || pipe == line.length() - 1)
			{
				continue;
			}
			try
			{
				sent.put(line.substring(pipe + 1), Long.parseUnsignedLong(line.substring(0, pipe), 16));
			}
			catch (NumberFormatException e)
			{
				// A truncated or hand-edited config line: drop it and re-sync that page.
			}
		}
	}
}
