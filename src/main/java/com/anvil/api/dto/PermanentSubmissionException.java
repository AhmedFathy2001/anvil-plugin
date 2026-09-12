package com.anvil.api.dto;

import java.io.IOException;

/**
 * POST /api/events/{eventId}/submissions — submits a drop with image proof.
 */
/**
 * A submission the server rejected for good — the tile's already complete, the event ended, the
 * data's invalid — so retrying it will never succeed. The retry loop drops these instead of
 * looping forever (the "Get 5M in PvP Loot already complete, keeps retrying" bug).
 */
public class PermanentSubmissionException extends IOException
{
	public PermanentSubmissionException(String message)
	{
		super(message);
	}
}
