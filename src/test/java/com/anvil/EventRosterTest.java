package com.anvil;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import org.junit.Test;

public class EventRosterTest
{
	@Test
	public void parsesTheRankedTeams()
	{
		Gson gson = new Gson();
		EventRoster teams = gson.fromJson("{\"mode\":\"teams\",\"page\":1,\"pageSize\":25,\"total\":40,\"pages\":2,"
			+ "\"teams\":[{\"teamId\":3,\"name\":\"Red\",\"color\":\"#f00\",\"rank\":1,\"score\":\"9 tiles\",\"members\":12,\"yours\":false}],"
			+ "\"yourTeam\":{\"teamId\":9,\"name\":\"Blue\",\"color\":\"#00f\",\"rank\":null,\"score\":null,\"members\":4,\"yours\":true},"
			+ "\"unplaced\":2}", EventRoster.class);
		assertEquals(12, teams.teams.get(0).members);
		assertEquals(Integer.valueOf(1), teams.teams.get(0).rank);
		assertEquals("9 tiles", teams.teams.get(0).score);
	}
}
