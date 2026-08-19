package com.anvil;

/**
 * The client-side half of the STARTING SHOT rule (site lib/startProof).
 *
 * <p>Two things the site can ask for beyond the screenshot itself, both of which only this client can
 * answer, and both of which are far more useful checked BEFORE the frame is grabbed than filed and
 * argued about afterwards:
 *
 * <ul>
 *   <li><b>Where you are.</b> A drawn spot the host pinned on the map comes down as coordinates, so
 *       "go to Edgeville bank" stops being an instruction nobody can verify.</li>
 *   <li><b>How old this session is.</b> Hiscores only flush on LOGOUT, so a player who has been
 *       logged in since before the event started has a stale start baseline — every gain they made
 *       beforehand lands in the event's first sweep. Making them log out and back in right before
 *       the shot is what actually stops that, and the screenshot is just the receipt.</li>
 * </ul>
 *
 * <p>Pure and static so it unit-tests without a client: the plugin supplies the numbers.
 */
final class StartProofRules
{
	/** {@code sessionLoginAtMs} when we never saw the login — a plugin enabled mid-session. */
	static final long UNKNOWN_LOGIN = 0L;

	private StartProofRules()
	{
	}

	/**
	 * Squares from the drawn spot, the way OSRS measures "within N squares" — the longer axis, not
	 * the diagonal. -1 when there is nothing to measure (no pin, or no position to compare).
	 */
	static int distance(PluginConfigResponse.StartProof proof, Integer x, Integer y)
	{
		if (proof == null || proof.spot == null || x == null || y == null)
		{
			return -1;
		}
		return Math.max(Math.abs(x - proof.spot.x), Math.abs(y - proof.spot.y));
	}

	/**
	 * Why this shot can't be filed yet, as a sentence for chat — or null when it can.
	 *
	 * <p>Every check fails OPEN: an older site sends no spot and no window, an unpinned location has
	 * no coordinates, and a client that can't read its own position skips the distance test. The
	 * rule only ever blocks on something we positively know is wrong.
	 */
	static String blockReason(
		PluginConfigResponse.StartProof proof,
		long sessionLoginAtMs,
		long nowMs,
		Integer x,
		Integer y)
	{
		if (proof == null)
		{
			return null;
		}

		if (proof.maxSessionMinutes > 0)
		{
			if (sessionLoginAtMs == UNKNOWN_LOGIN)
			{
				// The plugin was switched on (or the client reconnected) mid-session, so we can't
				// vouch for the logout that flushed the hiscores. One relog settles it.
				return "Log out and back in before your starting shot — that's what saves your hiscores"
					+ " so your starting totals are right.";
			}
			long minutes = Math.max(0, (nowMs - sessionLoginAtMs) / 60_000L);
			if (minutes > proof.maxSessionMinutes)
			{
				return "You've been logged in for " + describeMinutes(minutes) + " — log out and back in,"
					+ " then take your starting shot within " + proof.maxSessionMinutes + " min."
					+ " Hiscores only save on logout, so this is what sets your starting totals.";
			}
		}

		int away = distance(proof, x, y);
		if (away >= 0 && away > proof.spot.radius)
		{
			return "You're " + away + " squares from " + (proof.location != null ? proof.location : "the start spot")
				+ " — go there, then take your starting shot.";
		}

		return null;
	}

	/**
	 * How long is left before the site stops asking for a shot, as a sentence fragment — or null
	 * when there is no deadline to quote (an older site that doesn't send one, or an unreadable
	 * stamp). The window closing is not a failure state: the ask simply expires, so this reads as a
	 * countdown rather than a threat.
	 */
	static String describeWindow(PluginConfigResponse.StartProof proof, long nowMs)
	{
		if (proof == null || proof.windowEndsAt == null || proof.windowEndsAt.isEmpty())
		{
			return null;
		}
		long endsMs;
		try
		{
			endsMs = java.time.Instant.parse(proof.windowEndsAt).toEpochMilli();
		}
		catch (java.time.format.DateTimeParseException e)
		{
			return null;
		}
		long minutes = (endsMs - nowMs) / 60_000L;
		return minutes <= 0 ? null : describeMinutes(minutes);
	}

	/** "12 min" / "2h 05m" — a session age a player reads at a glance. */
	static String describeMinutes(long minutes)
	{
		if (minutes < 60)
		{
			return minutes + " min";
		}
		return (minutes / 60) + "h " + String.format("%02dm", minutes % 60);
	}
}
