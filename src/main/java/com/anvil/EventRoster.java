package com.anvil;

import java.util.List;

/**
 * The ranked teams of an event — {@code GET /api/plugin/event/<id>/roster} (site capability
 * "event-roster"), read for the panel's standings. The Teams page itself is a site view (UiView).
 */
final class EventRoster
{
	List<Team> teams;

	static final class Team
	{
		int teamId;
		String name;
		String color;
		/** Null before the board is revealed — nothing to rank yet. */
		Integer rank;
		/** "12 tiles", "340 pts (+20 bonus)"; null before the reveal. */
		String score;
		int members;
		boolean yours;
	}
}
