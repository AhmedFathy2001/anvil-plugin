package com.anvil;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * A debounced push of ABSOLUTE counts — skill XP, boss kill counts, activity counters.
 *
 * <p>Values are queued per key and one request goes out once they stop moving, so a kill streak or
 * a training burst collapses into a single push of the newest numbers. Absolute values make a resend
 * harmless, which is what makes failure simple: the batch is folded back in (max-merged, so a newer
 * number queued meanwhile wins) and tried again after the same wait. The network send runs on the
 * plugin's executor; nothing here touches the client thread.
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
		queue(java.util.Collections.singletonMap(key, value));
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
		if (queued)
		{
			schedule(ex);
		}
	}

	/** Forget everything queued (and sent) — a new account, or the plugin stopping. */
	synchronized void reset()
	{
		pending.clear();
		if (sent != null)
		{
			sent.clear();
		}
		task = null;
	}

	private void schedule(ScheduledExecutorService ex)
	{
		if (task != null)
		{
			task.cancel(false);
		}
		task = ex.schedule(this::flush, delayMs, TimeUnit.MILLISECONDS);
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
		}
		if (!allowed.getAsBoolean())
		{
			return; // stopped being allowed between queue and flush — the hiscores side still has it
		}
		try
		{
			sender.send(batch);
		}
		catch (IOException e)
		{
			log.warn("{} push failed ({} key(s)) — requeueing: {}", what, batch.size(), e.getMessage());
			synchronized (this)
			{
				batch.forEach((k, v) -> pending.merge(k, v, Integer::max));
				ScheduledExecutorService ex = executor.get();
				if (ex != null && !ex.isShutdown())
				{
					schedule(ex);
				}
			}
			return;
		}
		if (sent != null)
		{
			synchronized (this)
			{
				batch.forEach((k, v) -> sent.merge(k, v, Integer::max));
			}
		}
		afterSend.run(); // pull back the updated progress / any completion the push triggered
	}
}
