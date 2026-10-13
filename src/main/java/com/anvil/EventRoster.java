package com.anvil;

import java.util.List;

/**
 * One page of an event's roster — {@code GET /api/plugin/event/<id>/roster} (site capability
 * "event-roster"). The server does the ranking and the paging, so an event of 1,200 one-person teams
 * costs the same to open as two teams of five.
 *
 * <p>{@link #mode} says which of the shapes arrived:</p>
 * <ul>
 *   <li>{@code teams} — the teams ranked, with member counts; {@link #yourTeam} rides on every page.</li>
 *   <li>{@code players} — every team is one person (or the event ranks individuals), so the people
 *       are ranked instead; {@link #you} rides on every page.</li>
 *   <li>{@code members} — one team's members, captain first.</li>
 *   <li>{@code search} — players by name across every team.</li>
 * </ul>
 */
final class EventRoster
{
	String mode;
	int page;
	int pageSize;
	int total;
	int pages;

	List<Team> teams;
	Team yourTeam;
	/** Signed up but not on a team yet ({@code teams} mode). */
	int unplaced;

	List<Person> players;
	Person you;

	/** {@code members} mode: the team being listed. */
	Team team;
	/** {@code search} mode: what was searched for, as the server read it. */
	String query;

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

	static final class Person
	{
		String name;
		Integer teamId;
		String teamName;
		String teamColor;
		Integer rank;
		String score;
		boolean captain;
		boolean you;
	}

	boolean is(String m)
	{
		return m.equals(mode);
	}
}
