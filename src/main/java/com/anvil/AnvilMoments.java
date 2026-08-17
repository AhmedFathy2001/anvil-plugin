package com.anvil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The queue behind the clan's highlight feed: pets, uniques, big hauls and deaths, held until the
 * next push.
 *
 * <p>WHAT THIS DOES NOT DO is decide whether any of it matters. The client cannot know which
 * competition is running, what counts as a unique at this clan, or which pets belong to which skill
 * — all of that lives on the site (src/lib/moments.ts), so a clan can change its mind without
 * waiting on a plugin release. This sends what it saw, generously, and expects most of it to be
 * thrown away.
 *
 * <p>EVERY MOMENT IS RECORDED AT THE EVENT, never inside a notification gate. A member with the
 * drops channel switched off still gets their pet on the site's feed — the two settings are about
 * different things, and tying them together is exactly the bug that once captioned every saved clip
 * "Clip saved".
 *
 * <p>Each entry carries its own idempotency key, because the game says everything twice: one pet
 * fires up to three chat lines, one kill fires two loot events, and a retry after a timeout is a
 * third copy on purpose. The key is derived from what happened rather than from a counter, so all
 * of those collapse into one row server-side.
 *
 * <p>Bounded and lossy by design. If a session outruns the pushes, the OLDEST entries go: a feed
 * that drops the pet you just got to keep a 200k loot pile would be worse than useless. Touched
 * from the client thread (game events) and drained on the executor, so every method is synchronized.
 */
class AnvilMoments
{
	/**
	 * A session's backlog, not a history. The site takes 25 per push and the pushes are debounced to
	 * seconds, so this only fills at all when the site is unreachable.
	 */
	static final int MAX_QUEUED = 50;

	/** How many go in one request — matches the site's own cap. */
	static final int BATCH = 25;

	/** Below this a drop isn't worth reporting on price alone. The site's floors are stricter. */
	static final long MIN_REPORTABLE_GP = 100_000L;

	static final class Moment
	{
		/** 'pet' | 'drop' | 'death'. */
		final String kind;
		final Integer itemId;
		final String itemName;
		final int quantity;
		final Long valueGp;
		final String source;
		final String sourceKind;
		final Integer kc;
		final long at;
		final String key;

		Moment(String kind, Integer itemId, String itemName, int quantity, Long valueGp,
			   String source, String sourceKind, Integer kc, long at, String key)
		{
			this.kind = kind;
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
			this.valueGp = valueGp;
			this.source = source;
			this.sourceKind = sourceKind;
			this.kc = kc;
			this.at = at;
			this.key = key;
		}
	}

	/** Keyed so a duplicate observation replaces rather than repeats; insertion-ordered for the batch. */
	private final Map<String, Moment> pending = new LinkedHashMap<>();

	/**
	 * Queue one. A repeat of a key already waiting REPLACES it — the second sighting of the same pet
	 * is the one that knows its name.
	 */
	synchronized void record(Moment moment)
	{
		if (moment == null || moment.key == null || moment.key.isEmpty())
		{
			return;
		}
		pending.remove(moment.key);
		pending.put(moment.key, moment);
		while (pending.size() > MAX_QUEUED)
		{
			pending.remove(pending.keySet().iterator().next());
		}
	}

	/**
	 * Fill in the name of something already queued — a pet is announced by one chat line and named
	 * by the next, and the feed wants the name.
	 *
	 * @return true when there was something to name
	 */
	synchronized boolean nameQueued(String key, String itemName, Integer itemId)
	{
		Moment existing = pending.get(key);
		if (existing == null || itemName == null || itemName.isEmpty())
		{
			return false;
		}
		pending.put(key, new Moment(existing.kind, itemId != null ? itemId : existing.itemId, itemName,
			existing.quantity, existing.valueGp, existing.source, existing.sourceKind, existing.kc,
			existing.at, existing.key));
		return true;
	}

	synchronized boolean isEmpty()
	{
		return pending.isEmpty();
	}

	synchronized int size()
	{
		return pending.size();
	}

	/** The next batch to send. Stays queued until {@link #onSent} confirms it — a failed push retries. */
	synchronized List<Moment> nextBatch()
	{
		List<Moment> batch = new ArrayList<>();
		for (Moment m : pending.values())
		{
			batch.add(m);
			if (batch.size() >= BATCH)
			{
				break;
			}
		}
		return batch;
	}

	/** Drop what the site has taken. */
	synchronized void onSent(List<Moment> batch)
	{
		if (batch == null)
		{
			return;
		}
		for (Moment m : batch)
		{
			pending.remove(m.key);
		}
	}

	/** Forget everything — a different account logging in doesn't inherit this one's moments. */
	synchronized void reset()
	{
		pending.clear();
	}

	/**
	 * A key for one thing that happened.
	 *
	 * <p>Built from WHAT happened rather than from a counter, so the two loot events and three chat
	 * lines the game fires for a single occurrence all produce the same string. Time is bucketed to
	 * ten seconds so the copies — which arrive within a tick or two of each other — agree, while a
	 * second genuine drop of the same item minutes later does not.
	 */
	static String keyFor(String kind, String source, Integer itemId, long at)
	{
		long bucket = at / 10_000L;
		return kind + "|" + (source == null ? "" : source.toLowerCase()) + "|"
			+ (itemId == null ? "" : itemId) + "|" + bucket;
	}
}
