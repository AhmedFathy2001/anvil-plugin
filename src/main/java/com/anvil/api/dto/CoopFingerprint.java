package com.anvil.api.dto;

import java.util.List;

/**
 * POST /api/plugin/stats — real-time boss KC push (no screenshot). Body is
 * {@code {"stats":[{"name":"<in-game boss name>","kc":<absolute count>}]}}. The event, team, and
 * player are resolved server-side from the account-token auth (Bearer + X-RSN + X-Account-Hash),
 * so a caller can only ever report its own KC. Counts are ABSOLUTE (idempotent): the server takes
 * max(hiscores, pushed) per boss, so a debounced "latest value" is all that's needed and the
 * hourly hiscores cron reconciles it. Used to complete boss-KC tiles instantly instead of waiting
 * on the ~1h hiscores lag.
 */
/** What a client could see of its company at kill time. Both halves are best-effort. */
public final class CoopFingerprint
{
	/** Lowercased RSNs of ROSTER teammates seen in the instance — empty when none could be named. */
	public final List<String> teammates;
	/** Instance/raid party headcount, which is reliable exactly where names aren't. 0 = unknown. */
	public final int partySize;

	public CoopFingerprint(List<String> teammates, int partySize)
	{
		this.teammates = teammates;
		this.partySize = partySize;
	}

	public boolean isEmpty()
	{
		return (teammates == null || teammates.isEmpty()) && partySize <= 1;
	}
}
