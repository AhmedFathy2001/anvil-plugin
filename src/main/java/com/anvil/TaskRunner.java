package com.anvil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The plugin's own background thread, and the one place that knows whether it is still there.
 *
 * <p>Work handed here is network work: config polls, submissions, uploads, retries. It runs off the
 * client thread because it blocks, and it runs off the Swing EDT because it blocks.</p>
 *
 * <p><b>Why the plugin owns a thread instead of using RuneLite's.</b> RuneLite injects a shared
 * {@code ScheduledExecutorService}, and it is tempting: no lifecycle to manage, and {@code @Schedule}
 * on top of it. But it is {@code Executors.newSingleThreadScheduledExecutor()} and it is shared by
 * the whole client — every plugin's scheduled work is behind the same one thread. Anvil's HTTP reads
 * are configured to wait up to ninety seconds, because some of them do several round-trips
 * server-side. Parking that thread for a minute and a half would stall every other plugin on it.
 * {@code @Schedule(asynchronous = true)} does not help: the scheduler submits to that same executor.
 * So the blocking half stays here, on a thread of our own that we shut down with the plugin. The
 * non-blocking pollers that RuneLite's scheduler suits — the device sign-in loop — still use it,
 * via {@link PollScheduler}.</p>
 *
 * <p><b>What this actually replaces.</b> {@code executor} was a nullable field read at thirty-eight
 * sites, each re-deciding what "shutting down" means — some {@code == null || isShutdown()}, some
 * {@code != null && !isShutdown()}, one copying it to a local first to avoid a race with
 * {@code shutDown()} nulling it. That race is real: {@code shutDown} runs on the client thread while
 * work is in flight. Holding the reference in one final field and reading it once per call removes
 * the race everywhere rather than at the one site that remembered it.</p>
 *
 * <p>Every method is null-safe after {@link #stop()}: {@link #run} returns false and
 * {@link #runLater} returns null rather than throwing, so a caller's early-out stays a plain
 * {@code if}.</p>
 */
final class TaskRunner
{
	/** Null before start() and after stop(). Volatile: written on the client thread, read on ours. */
	private volatile ScheduledExecutorService executor;

	/** Fresh single thread. Single on purpose — submissions must not race each other onto the site. */
	void start()
	{
		executor = Executors.newSingleThreadScheduledExecutor();
	}

	/**
	 * Drop the thread and everything queued on it.
	 *
	 * <p>{@code shutdownNow} rather than a graceful drain: this runs while RuneLite waits for the
	 * plugin to stop, and a queued upload holding it up would look like a hung client. Anything
	 * mid-flight that mattered is on disk in the pending-submission store and retried next start.</p>
	 */
	void stop()
	{
		ScheduledExecutorService ex = executor;
		executor = null;
		if (ex != null)
		{
			ex.shutdownNow();
		}
	}

	/** Is there still a thread to run on? Only ask when you are not also submitting. */
	boolean isLive()
	{
		ScheduledExecutorService ex = executor;
		return ex != null && !ex.isShutdown();
	}

	/**
	 * Run it on the background thread as soon as there is room.
	 *
	 * @return false when the plugin is stopping and the task will never run, so the caller can take
	 *         whatever other path it has (most have none, and simply return).
	 */
	boolean run(Runnable task)
	{
		ScheduledExecutorService ex = executor;
		if (ex == null || ex.isShutdown())
		{
			return false;
		}
		ex.submit(task);
		return true;
	}

	/**
	 * Run it once, later.
	 *
	 * @return the handle, so a caller that re-schedules on every new event can cancel the previous
	 *         one (that is the coalescing pattern all over this plugin) — or null when the plugin is
	 *         stopping.
	 */
	ScheduledFuture<?> runLater(Runnable task, long delayMs)
	{
		ScheduledExecutorService ex = executor;
		if (ex == null || ex.isShutdown())
		{
			return null;
		}
		return ex.schedule(task, delayMs, TimeUnit.MILLISECONDS);
	}

	/**
	 * Run it forever, every {@code periodMs}, starting one period from now.
	 *
	 * <p>Note for whoever writes the next one of these: an exception thrown out of a task scheduled
	 * this way cancels the repeat permanently and silently. The caller is responsible for catching —
	 * see {@code AnvilPlugin.safely}.</p>
	 */
	ScheduledFuture<?> runEvery(Runnable task, long periodMs)
	{
		ScheduledExecutorService ex = executor;
		if (ex == null || ex.isShutdown())
		{
			return null;
		}
		return ex.scheduleAtFixedRate(task, periodMs, periodMs, TimeUnit.MILLISECONDS);
	}
}
