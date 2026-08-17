package com.anvil;

/**
 * How long to wait before trying a failed push again.
 *
 * <p>The profile syncs run off a fixed 30s tick, so a push that keeps failing is a request every 30
 * seconds for as long as the player stays logged in — against a site that has already said no. That
 * is rude to a clan's server and pointless for the player: the two cases are "your site is down"
 * (which wants patience) and "your site doesn't accept this" (which wants stopping altogether).
 *
 * <p>This covers the first: doubling waits from 30s to a 10-minute ceiling, reset by any success.
 * The second is the caller's job — a permanent failure drops the batch instead of backing off.
 */
final class SyncBackoff
{
	static final long FIRST_MS = 30_000L;
	static final long MAX_MS = 600_000L;

	private long nextAttemptAt;
	private long waitMs;

	/** Is a push allowed right now? True until something fails. */
	boolean ready(long now)
	{
		return now >= nextAttemptAt;
	}

	/** A push worked — back to normal cadence. */
	void onSuccess()
	{
		nextAttemptAt = 0;
		waitMs = 0;
	}

	/** A push failed for a reason that might clear on its own. Waits twice as long each time. */
	void onFailure(long now)
	{
		waitMs = waitMs == 0 ? FIRST_MS : Math.min(MAX_MS, waitMs * 2);
		nextAttemptAt = now + waitMs;
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
