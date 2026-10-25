package com.anvil;

import lombok.RequiredArgsConstructor;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Clan notifications (POST /api/plugin/notify) the site didn't take, kept and resent.
 *
 * <p>The happy path never comes here: a notification is sent straight away, exactly as before, and
 * only lands in the outbox when that attempt failed for a reason that may clear (no network, a 5xx,
 * 401/408/429). A permanent refusal is dropped where it happened. Each entry keeps the URL and the
 * identity headers (X-RSN, X-Account-Hash) it was first sent with, so a retry after a character switch
 * still speaks for the character the 99 or the pet belonged to.
 *
 * <p>On disk under {@code <plugin dir>/outbox/notify/} as {@code <id>.json} plus {@code <id>.png} when
 * there's a screenshot, so a client restart mid-outage loses nothing. Bounded: at most
 * {@link #MAX_ITEMS} entries, {@link #MAX_BYTES} of screenshots and {@link #MAX_AGE_MS} old — the
 * oldest go first; a notification a day late is news nobody wants.
 *
 * <p>Retries back off from {@link #FIRST_RETRY_MS} to {@link #MAX_RETRY_MS} with jitter, and one
 * failed send ends a pass: with the site down there is no point dialling it once per queued entry.
 * Sends run on the OkHttp pool, never the client thread; disk writes on the thread that enqueued
 * (an OkHttp callback) or that pool.
 */
@RequiredArgsConstructor
@Slf4j
final class NotifyOutbox
{
	static final int MAX_ITEMS = 100;
	static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
	static final long MAX_BYTES = 20L * 1024 * 1024;
	static final long FIRST_RETRY_MS = 15_000L;
	static final long MAX_RETRY_MS = 300_000L;

	enum Result
	{
		SENT, PERMANENT, RETRY
	}

	interface Sender
	{
		Result send(Item item, byte[] png);
	}

	/** One queued notification. Serialized as-is; the PNG lives beside it. */
	static final class Item
	{
		String id;
		String url;
		String channel;
		/** The payload_json exactly as first sent. */
		String payload;
		String filename;
		String rsn;
		String accountHash;
		/** Fingerprint of the token it was sent with: another account's sign-in doesn't send it. */
		String account;
		int pngBytes;
		long createdAt;
		int attempts;
		long nextAttemptAt;
		/** The screenshot, held in memory only when there is no directory to write it to. */
		transient byte[] png;
	}

	private final Gson gson;
	private final Supplier<ScheduledExecutorService> scheduler;
	private final Supplier<ExecutorService> sendPool;
	private volatile Filepath dir;
	private volatile Sender sender;
	private final List<Item> items = new ArrayList<>();
	private ScheduledFuture<?> task;
	private boolean pumping;


	void setDir(Filepath dir)
	{
		this.dir = dir;
	}

	void setSender(Sender sender)
	{
		this.sender = sender;
	}

	synchronized int size()
	{
		return items.size();
	}

	/** A fresh entry: first retry after {@link #FIRST_RETRY_MS} (jittered). */
	static Item newItem(long now)
	{
		Item it = new Item();
		it.id = now + "-" + UUID.randomUUID().toString().substring(0, 8);
		it.createdAt = now;
		it.attempts = 1;
		it.nextAttemptAt = now + RetryPolicy.backoffMs(1, FIRST_RETRY_MS, MAX_RETRY_MS, Math.random());
		return it;
	}

	/**
	 * The entries to evict, given the queue (oldest first): everything past {@code maxAge}, then the
	 * oldest until at most {@code maxItems} remain and their screenshots fit in {@code maxBytes}.
	 */
	static List<Item> overflow(List<Item> queue, long now, int maxItems, long maxAge, long maxBytes)
	{
		List<Item> evict = new ArrayList<>();
		List<Item> kept = new ArrayList<>();
		long bytes = 0;
		for (Item it : queue)
		{
			if (now - it.createdAt > maxAge)
			{
				evict.add(it);
			}
			else
			{
				kept.add(it);
				bytes += Math.max(0, it.pngBytes);
			}
		}
		int i = 0;
		while (i < kept.size() && (kept.size() - i > maxItems || bytes > maxBytes))
		{
			Item oldest = kept.get(i++);
			bytes -= Math.max(0, oldest.pngBytes);
			evict.add(oldest);
		}
		return evict;
	}

	/** Read what a previous session left. Blocking (disk); call off the client thread. */
	void load()
	{
		Filepath d = DiskFiles.ensureDir(dir);
		if (d == null)
		{
			return;
		}
		List<Item> found = new ArrayList<>();
		List<Filepath> pngs = new ArrayList<>();
		try (Stream<Filepath> walk = d.walk(1))
		{
			List<Filepath> files = walk.filter(Filepath::isFile).collect(Collectors.toList());
			for (Filepath f : files)
			{
				if (f.getFileName().endsWith(".png"))
				{
					pngs.add(f);
				}
				if (!f.getFileName().endsWith(".json"))
				{
					continue;
				}
				try
				{
					Item it = gson.fromJson(DiskFiles.readText(f), Item.class);
					if (it != null && it.id != null && it.url != null && it.payload != null && f.getFileName().equals(it.id + ".json"))
					{
						found.add(it);
					}
					else
					{
						DiskFiles.deleteQuietly(f);
					}
				}
				catch (JsonParseException e)
				{
					DiskFiles.deleteQuietly(f);
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Notification outbox unreadable: {}", e.getMessage());
			return;
		}
		// A screenshot whose entry never made it to disk is an orphan nothing will read.
		for (Filepath png : pngs)
		{
			String id = png.getFileName().substring(0, png.getFileName().length() - 4);
			if (found.stream().noneMatch(it -> it.id.equals(id)))
			{
				DiskFiles.deleteQuietly(png);
			}
		}
		if (found.isEmpty())
		{
			return;
		}
		synchronized (this)
		{
			for (Item it : found)
			{
				if (items.stream().noneMatch(x -> x.id.equals(it.id)))
				{
					items.add(it);
				}
			}
			items.sort(Comparator.comparingLong(x -> x.createdAt));
			prune(System.currentTimeMillis());
			log.info("Anvil: {} notification(s) waiting from last session", items.size());
			schedule(FIRST_RETRY_MS);
		}
	}

	/** Keep a notification that just failed to send. Blocking (disk); never on the client thread. */
	void enqueue(Item item, byte[] png)
	{
		Filepath d = DiskFiles.ensureDir(dir);
		item.pngBytes = png == null ? 0 : png.length;
		if (d != null)
		{
			try
			{
				if (png != null && png.length > 0)
				{
					DiskFiles.writeAtomic(d.joinSegment(item.id + ".png"), png);
				}
				DiskFiles.writeAtomic(d.joinSegment(item.id + ".json"), gson.toJson(item));
			}
			catch (IOException | RuntimeException e)
			{
				log.debug("Couldn't save notification for retry: {}", e.getMessage());
				item.png = png; // keep it for this session at least
			}
		}
		else
		{
			item.png = png;
		}
		synchronized (this)
		{
			items.add(item);
			prune(System.currentTimeMillis());
			if (task == null || task.isDone())
			{
				schedule(Math.max(0, item.nextAttemptAt - System.currentTimeMillis()));
			}
		}
	}

	/** The site is back: try everything now. */
	synchronized void kick()
	{
		if (items.isEmpty())
		{
			return;
		}
		for (Item it : items)
		{
			it.nextAttemptAt = 0;
		}
		schedule(0);
	}

	/** Signed out: nothing queued may go out under whoever signs in next. */
	void clear()
	{
		List<Item> all;
		synchronized (this)
		{
			all = new ArrayList<>(items);
			items.clear();
			cancel();
		}
		all.forEach(this::deleteFiles);
	}

	/** Plugin stopping: cancel the timer; the files stay for next time. */
	synchronized void stop()
	{
		cancel();
	}

	private void cancel()
	{
		if (task != null)
		{
			task.cancel(false);
			task = null;
		}
	}

	/** Call with the monitor held. */
	private void prune(long now)
	{
		List<Item> evict = overflow(items, now, MAX_ITEMS, MAX_AGE_MS, MAX_BYTES);
		if (evict.isEmpty())
		{
			return;
		}
		items.removeAll(evict);
		log.info("Anvil: dropped {} undelivered notification(s) (too old, or too many waiting)", evict.size());
		ExecutorService pool = sendPool.get();
		if (pool != null && !pool.isShutdown())
		{
			pool.submit(() -> evict.forEach(this::deleteFiles));
		}
	}

	/** Call with the monitor held. */
	private void schedule(long waitMs)
	{
		ScheduledExecutorService ex = scheduler.get();
		if (ex == null || ex.isShutdown())
		{
			return;
		}
		cancel();
		task = ex.schedule(this::handOff, Math.max(0, waitMs), TimeUnit.MILLISECONDS);
	}

	/** On the timer: run the pass on the HTTP pool, never the plugin's one thread. */
	private void handOff()
	{
		ExecutorService pool = sendPool.get();
		if (pool != null && !pool.isShutdown())
		{
			pool.submit(this::pump);
		}
	}

	/** One pass over what's due. Blocking HTTP; runs on the OkHttp pool. */
	void pump()
	{
		Sender s = sender;
		List<Item> due;
		synchronized (this)
		{
			if (pumping || s == null)
			{
				return;
			}
			pumping = true;
			long now = System.currentTimeMillis();
			prune(now);
			due = items.stream().filter(it -> it.nextAttemptAt <= now).collect(Collectors.toList());
		}
		try
		{
			for (int i = 0; i < due.size(); i++)
			{
				Item it = due.get(i);
				byte[] png = it.pngBytes > 0 ? screenshot(it) : null;
				if (it.pngBytes > 0 && png == null)
				{
					remove(it); // its screenshot is gone; nothing sensible left to send
					continue;
				}
				Result r = s.send(it, png);
				if (r != Result.RETRY)
				{
					if (r == Result.PERMANENT)
					{
						log.warn("Anvil: the site refused a queued '{}' notification — dropping it", it.channel);
					}
					remove(it);
					continue;
				}
				// Still down. Push this one back, and don't dial again for the rest — same wait for all.
				long now = System.currentTimeMillis();
				long next;
				synchronized (this)
				{
					it.attempts++;
					next = now + RetryPolicy.backoffMs(it.attempts, FIRST_RETRY_MS, MAX_RETRY_MS, Math.random());
					for (int j = i; j < due.size(); j++)
					{
						due.get(j).nextAttemptAt = Math.max(due.get(j).nextAttemptAt, next);
					}
				}
				rewrite(it);
				break;
			}
		}
		finally
		{
			synchronized (this)
			{
				pumping = false;
				long soonest = items.stream().mapToLong(it -> it.nextAttemptAt).min().orElse(-1);
				if (soonest >= 0)
				{
					schedule(soonest - System.currentTimeMillis());
				}
			}
		}
	}

	private byte[] screenshot(Item it)
	{
		if (it.png != null)
		{
			return it.png;
		}
		Filepath d = dir;
		return d == null ? null : DiskFiles.read(d.joinSegment(it.id + ".png"));
	}

	private void remove(Item it)
	{
		synchronized (this)
		{
			items.remove(it);
		}
		deleteFiles(it);
	}

	private void rewrite(Item it)
	{
		Filepath d = dir;
		if (d == null)
		{
			return;
		}
		try
		{
			DiskFiles.writeAtomic(d.joinSegment(it.id + ".json"), gson.toJson(it));
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Couldn't update queued notification: {}", e.getMessage());
		}
	}

	private void deleteFiles(Item it)
	{
		Filepath d = dir;
		if (d == null || it.id == null)
		{
			return;
		}
		try
		{
			DiskFiles.deleteQuietly(d.joinSegment(it.id + ".json"));
			DiskFiles.deleteQuietly(d.joinSegment(it.id + ".png"));
		}
		catch (RuntimeException ignored)
		{
			// joinSegment refusing a name read back from disk: nothing of ours to delete.
		}
	}
}
