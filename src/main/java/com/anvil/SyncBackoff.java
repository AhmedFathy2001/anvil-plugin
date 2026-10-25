package com.anvil;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import lombok.RequiredArgsConstructor;

/**
 * How long to wait before trying a failed push again.
 *
 * <p>The profile syncs run off a fixed 30s tick, so a push that keeps failing is a request every 30
 * seconds for as long as the player stays logged in — against a site that has already said no. That
 * is rude to a clan's server and pointless for the player: the two cases are "your site is down"
 * (which wants patience) and "your site doesn't accept this" (which wants stopping altogether).
 *
 * <p>This covers the first: doubling waits from 30s to a 10-minute ceiling ({@link RetryPolicy#backoffMs},
 * so a clan's clients that lost the site together don't all come back together), reset by any success.
 * The second is the caller's job — a permanent failure drops the batch instead of backing off.
 */
@RequiredArgsConstructor
final class SyncBackoff
{
	static final long FIRST_MS = 30_000L;
	static final long MAX_MS = 600_000L;

	private final long firstMs;
	private final long maxMs;
	/** A uniform draw in [0, 1) spreading each wait ±20%; 0.5 gives the exact doubling. */
	private final DoubleSupplier jitter;
	private long nextAttemptAt;
	private int failures;

	SyncBackoff()
	{
		this(FIRST_MS, MAX_MS);
	}

	SyncBackoff(long firstMs, long maxMs)
	{
		this(firstMs, maxMs, () -> ThreadLocalRandom.current().nextDouble());
	}

	/** Is a push allowed right now? True until something fails. */
	boolean ready(long now)
	{
		return now >= nextAttemptAt;
	}

	/** A push worked — back to normal cadence. */
	void onSuccess()
	{
		nextAttemptAt = 0;
		failures = 0;
	}

	/** A push failed for a reason that might clear on its own. Waits twice as long each time. */
	void onFailure(long now)
	{
		failures++;
		nextAttemptAt = now + RetryPolicy.backoffMs(failures, firstMs, maxMs, jitter.getAsDouble());
	}

	/** Seconds until the next attempt, for a log line that explains the silence. */
	long secondsUntilReady(long now)
	{
		return Math.max(0, (nextAttemptAt - now + 999) / 1000);
	}

	void reset()
	{
		onSuccess();
	}
}
