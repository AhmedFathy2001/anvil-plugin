package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.awt.Color;
import org.junit.Test;

public class TeamsViewTest
{
	@Test
	public void teamLineSaysWhatIsKnown()
	{
		EventRoster.Team t = new EventRoster.Team();
		t.members = 1;
		assertEquals("1 member", TeamsView.teamLine(t));
		t.rank = 2;
		t.score = "12 tiles";
		t.members = 1200;
		assertEquals("#2 · 12 tiles · 1,200 members", TeamsView.teamLine(t));
	}

	@Test
	public void teamColourFallsBackToGrey()
	{
		assertEquals(new Color(0xff0000), TeamsView.teamColor("#FF0000"));
		assertEquals(PlayerCardView.MUTED, TeamsView.teamColor("red"));
		assertEquals(PlayerCardView.MUTED, TeamsView.teamColor(null));
	}

	@Test
	public void parsesEachShapeTheServerSends()
	{
		Gson gson = new Gson();
		EventRoster teams = gson.fromJson("{\"mode\":\"teams\",\"page\":1,\"pageSize\":25,\"total\":40,\"pages\":2,"
			+ "\"teams\":[{\"teamId\":3,\"name\":\"Red\",\"color\":\"#f00\",\"rank\":1,\"score\":\"9 tiles\",\"members\":12,\"yours\":false}],"
			+ "\"yourTeam\":{\"teamId\":9,\"name\":\"Blue\",\"color\":\"#00f\",\"rank\":null,\"score\":null,\"members\":4,\"yours\":true},"
			+ "\"unplaced\":2}", EventRoster.class);
		assertTrue(teams.is("teams"));
		assertEquals(2, teams.pages);
		assertEquals(12, teams.teams.get(0).members);
		assertTrue(teams.yourTeam.yours);
		assertNull(teams.yourTeam.rank);

		EventRoster members = gson.fromJson("{\"mode\":\"members\",\"page\":2,\"pageSize\":25,\"total\":30,\"pages\":2,"
			+ "\"team\":{\"teamId\":3,\"name\":\"Red\",\"color\":\"#f00\",\"members\":30},"
			+ "\"players\":[{\"name\":\"Cap\",\"teamId\":3,\"teamName\":\"Red\",\"teamColor\":\"#f00\",\"rank\":null,\"score\":null,\"captain\":true,\"you\":false}]}",
			EventRoster.class);
		assertTrue(members.is("members"));
		assertTrue(members.players.get(0).captain);
		assertEquals(30, members.team.members);
	}
}
