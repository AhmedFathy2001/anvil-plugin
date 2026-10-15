package com.anvil;

/**
 * The two questions every retry path asks, answered once: is this refusal worth trying again, and how
 * long to wait before doing so.
 */
final class RetryPolicy
{
	private RetryPolicy()
	{
	}

	/**
	 * A refusal that will never succeed however often it is resent: a 4xx, except 401 (the token may
	 * refresh, and a push fired in the second after login, before X-RSN is stamped, is refused with one
	 * that the next attempt clears), 408 (timed out) and 429 (slow down). 5xx and network failures
	 * are transient; anything below 400 is not a refusal at all.
	 */
	static boolean isPermanent(int code)
	{
		return code >= 400 && code < 500 && code != 401 && code != 408 && code != 429;
	}

	/**
	 * The wait before attempt {@code failures + 1}: {@code firstMs} doubling per failure, capped at
	 * {@code capMs}, spread ±20% by {@code jitter} (a uniform draw in [0, 1)) so a clan's clients that
	 * all lost the site at the same moment don't all come back at the same moment. Never above the cap.
	 */
	static long backoffMs(int failures, long firstMs, long capMs, double jitter)
	{
		if (failures <= 0)
		{
			return 0;
		}
		long base = firstMs;
		for (int i = 1; i < failures && base < capMs; i++)
		{
			base *= 2;
		}
		base = Math.min(base, capMs);
		double j = Math.max(0, Math.min(1, jitter));
		return Math.min(capMs, Math.round(base * (0.8 + 0.4 * j)));
	}
}
