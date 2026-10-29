package com.anvil;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import lombok.extern.slf4j.Slf4j;

/**
 * A debounced push of ABSOLUTE counts — skill XP, boss kill counts, activity counters.
 *
 * <p>Values are queued per key and one request goes out once they stop moving, so a kill streak or
 * a training burst collapses into a single push of the newest numbers. Absolute values make a resend
 * harmless, which is what makes failure simple: the batch is folded back in (max-merged, so a newer
 * number queued meanwhile wins) and tried again — after a wait that doubles per failure up to
 * {@link #RETRY_CAP_MS}, so a site that is down isn't asked every 15 seconds. A permanent refusal
 * (a 4xx other than 401/408/429) drops the batch instead: resending it would never work. The network
 * send runs on the plugin's executor; nothing here touches the client thread.
 */
@Slf4j
final class CoalescedPush
{
	interface Sender
	{
		void send(Map<String, Integer> batch) throws IOException;
	}

	private final String what;
	private final long delayMs;
	private final Supplier<ScheduledExecutorService> executor;
	private final BooleanSupplier allowed;
	private final Sender sender;
	private final Runnable afterSend;
	/** What the site last accepted, when only a RISE is worth sending; null to send every value. */
	private final Map<String, Integer> sent;
	private final Map<String, Integer> pending = new HashMap<>();
	private ScheduledFuture<?> task;
	/** Failed sends since the last success; 0 = healthy. Drives the retry wait. */
	private int failures;
	/** When the oldest unsent value was queued; 0 while nothing is waiting. */
	private long holdingSince;
	/**
	 * Longest a value waits for the streak to settle. The settle timer restarts on every new value,
	 * so without a cap a non-stop skilling session (an XP drop every few seconds) never paused long
	 * enough to push at all: the site, the tracker and teammates saw nothing until the member stopped.
	 */
	static final long MAX_HOLD_MS = 60_000L;
	/** Longest wait between retries while the site keeps failing. */
	static final long RETRY_CAP_MS = 300_000L;

	CoalescedPush(String what, long delayMs, boolean onlyRises, Supplier<ScheduledExecutorService> executor,
		BooleanSupplier allowed, Sender sender, Runnable afterSend)
	{
		this.what = what;
		this.delayMs = delayMs;
		this.sent = onlyRises ? new HashMap<>() : null;
		this.executor = executor;
		this.allowed = allowed;
		this.sender = sender;
		this.afterSend = afterSend;
	}

	void queue(String key, int value)
	{
		queue(Collections.singletonMap(key, value));
	}

	/** Buffer {@code values} and restart the settle timer — unless nothing in them is new. */
	synchronized void queue(Map<String, Integer> values)
	{
		ScheduledExecutorService ex = executor.get();
		if (ex == null || ex.isShutdown())
		{
			return;
		}
		boolean queued = false;
		for (Map.Entry<String, Integer> e : values.entrySet())
		{
			Integer last = sent == null ? null : sent.get(e.getKey());
			if (last == null || last < e.getValue())
			{
				pending.put(e.getKey(), e.getValue());
				queued = true;
			}
		}
		// While backing off, new values ride the retry that's already waiting rather than pulling it
		// forward to the settle delay — a training session would otherwise retry every 15s regardless.
		if (queued && holdingSince == 0)
		{
			holdingSince = System.currentTimeMillis();
		}
		if (queued && !(failures > 0 && task != null && !task.isDone()))
		{
			schedule(ex, settleWait(System.currentTimeMillis(), holdingSince, delayMs));
		}
	}

	/** What's waiting to go — for saving across a logout or restart. */
	synchronized Map<String, Integer> snapshot()
	{
		return new HashMap<>(pending);
	}

	/**
	 * Fold saved values back in. They're absolute, so the larger of saved and queued wins per key —
	 * the same merge a failed send uses.
	 */
	synchronized void restore(Map<String, Integer> values)
	{
		if (values == null || values.isEmpty())
		{
			return;
		}
		values.forEach((k, v) ->
		{
			if (k != null && v != null)
			{
				pending.merge(k, v, Integer::max);
			}
		});
		ScheduledExecutorService ex = executor.get();
		if (ex != null && !ex.isShutdown())
		{
			schedule(ex, delayMs);
		}
	}

	/** Send what's waiting now (a value that completes a tile) — unless backing off a failing site. */
	synchronized void sendNow()
	{
		ScheduledExecutorService ex = executor.get();
		if (pending.isEmpty() || failures > 0 || ex == null || ex.isShutdown())
		{
			return;
		}
		schedule(ex, 0);
	}

	/** The site is reachable again: send what's waiting now instead of sitting out the backoff. */
	synchronized void kick()
	{
		if (failures == 0 || pending.isEmpty())
		{
			return;
		}
		failures = 0;
		ScheduledExecutorService ex = executor.get();
		if (ex != null && !ex.isShutdown())
		{
			schedule(ex, 0);
		}
	}

	/** Forget everything queued (and sent) — a new account, or the plugin stopping. */
	synchronized void reset()
	{
		pending.clear();
		holdingSince = 0;
		failures = 0;
		if (sent != null)
		{
			sent.clear();
		}
		if (task != null)
		{
			task.cancel(false);
			task = null;
		}
	}

	private void schedule(ScheduledExecutorService ex, long waitMs)
	{
		if (task != null)
		{
			task.cancel(false);
		}
		task = ex.schedule(this::flush, waitMs, TimeUnit.MILLISECONDS);
	}

	/** The settle delay, cut short so nothing waits past {@link #MAX_HOLD_MS} from {@code holdingSince}. */
	static long settleWait(long now, long holdingSince, long delayMs)
	{
		return Math.max(0, Math.min(delayMs, holdingSince + MAX_HOLD_MS - now));
	}

	/** The wait before the next retry after {@code failures} straight failures. First retry = the settle delay. */
	static long retryDelayMs(int failures, long settleMs, double jitter)
	{
		return RetryPolicy.backoffMs(failures, settleMs, Math.max(settleMs, RETRY_CAP_MS), jitter);
	}

	/** Send the buffer (no screenshot). Requeues on failure. */
	void flush()
	{
		Map<String, Integer> batch;
		synchronized (this)
		{
			if (pending.isEmpty())
			{
				return;
			}
			batch = new HashMap<>(pending);
			pending.clear();
			holdingSince = 0;
		}
		if (!allowed.getAsBoolean())
		{
			return; // stopped being allowed between queue and flush — the hiscores side still has it
		}
		try
		{
			sender.send(batch);
		}
		catch (BingoApiClient.PermanentSubmissionException e)
		{
			// The site will never take this batch (a 4xx that isn't 401/408/429). Drop it rather than
			// resend it forever; newer values queued meanwhile still go on their own.
			log.warn("{} push refused ({} key(s)) — dropping: {}", what, batch.size(), e.getMessage());
			synchronized (this)
			{
				failures = 0;
			}
			return;
		}
		catch (IOException e)
		{
			synchronized (this)
			{
				failures++;
				batch.forEach((k, v) -> pending.merge(k, v, Integer::max));
				long wait = retryDelayMs(failures, delayMs, Math.random());
				log.warn("{} push failed ({} key(s)) — retrying in {}s: {}", what, batch.size(), wait / 1000, e.getMessage());
				ScheduledExecutorService ex = executor.get();
				if (ex != null && !ex.isShutdown())
				{
					schedule(ex, wait);
				}
			}
			return;
		}
		synchronized (this)
		{
			failures = 0;
			if (sent != null)
			{
				batch.forEach((k, v) -> sent.merge(k, v, Integer::max));
			}
		}
		afterSend.run(); // pull back the updated progress / any completion the push triggered
	}
}
