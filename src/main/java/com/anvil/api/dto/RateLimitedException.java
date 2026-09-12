package com.anvil.api.dto;

import java.io.IOException;

/**
 * A server that told us to wait, and for how long.
 *
 * The site limits a whole-log push to one a minute per member; a client that keeps firing into
 * that learns nothing and costs the clan's server a request every time. Carrying the wait means
 * the plugin can hold off instead of guessing, and tell the player a number.
 */
public class RateLimitedException extends IOException
{
	public final long retryAfterMs;

	public RateLimitedException(String message, long retryAfterMs)
	{
		super(message);
		this.retryAfterMs = retryAfterMs;
	}
}
