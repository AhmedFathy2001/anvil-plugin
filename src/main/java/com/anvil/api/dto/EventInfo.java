package com.anvil.api.dto;

import com.anvil.ui.AnvilSidebarDataSource;
import com.anvil.ui.view.Ladder;
import java.util.List;

public class EventInfo
{
	public int id;
	public String name;
	public String startDate;
	public String endDate;
	public String forceEndedAt;
	// Drives which in-game Anvil view opens for the player's own active event:
	//   format="tilerace" -> race track; format="bingo" + scoringMode="points" -> accordion;
	//   format="bingo" + scoringMode="tiles" -> square grid. May be null on older servers.
	public String format;         // "tilerace" | "bingo" | "ladder" | null
	public String scoringMode;
	// Reveal-policy events (showdown / lucky draw / bounty / ladder rotation) only — null/0 on
	// classic events and older servers. The tracked* lists already contain ONLY revealed, still-open
	// tiles.
	public String revealPolicy;   // "scheduled" | "interval" | "bounty" | "rotating" | null
	public int hiddenTileCount;   // tiles not yet revealed
	public String nextRevealAt;   // ISO time of the next reveal; null when none is scheduled
	// Points ramp for reveal-mode events: a mission's live value scales from 100% at reveal toward
	// targetPct% over `hours` (<100 decays, >100 grows). Null when off. Lets the ladder board show a
	// live grow/decay value per mission (see AnvilSidebarDataSource.liveValue).
	public Decay decay;
	// Open missions on a reveal-mode board (revealed + still-open), with face points and reveal time
	// so the plugin can render the "active missions" list + per-second countdown. Absent on classic.
	public List<Mission> missions;
	// Ladder events only: the individual leaderboard both all-time and for the current month, each
	// with the caller's own rank. Drives the in-game missions board's standings + "You: #N".
	public Standings standings;
	public Standings monthlyStandings;
	// Lock-out (bounty / lockout) events: the most recent EVENT-WIDE claims, so the plugin can
	// announce "X claimed <mission>" to other players. Absent on non-lockout events.
	public List<Claim> recentClaims;
}
