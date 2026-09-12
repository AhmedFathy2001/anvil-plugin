package com.anvil.api.dto;

/** Outcome of a clip relay attempt, so the caller can tell the player something true. */
public enum ClipRelayResult
{
	/** Posted to the clan's clips channel. */
	POSTED,
	/** This site is too old for the relay, or isn't configured — fall back to a user webhook. */
	UNSUPPORTED,
	/** The clan has no clips channel set up (server said 501). */
	NO_CHANNEL,
	/** Too big for the server to post anywhere (413). */
	TOO_LARGE,
	/** Rate-limited, Discord refused, or the upload failed. */
	FAILED
}
