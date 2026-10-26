package com.anvil;

import com.google.gson.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class PushMergeTest
{
	private static JsonObject json(String s)
	{
		return new Gson().fromJson(s.replace('\'', '"'), JsonObject.class);
	}

	private static final JsonObject HELD = json("{"
		+ "'codeword':'ABC123','player':{'id':7},"
		+ "'event':{'id':5,'name':'Old name','standings':{'rank':3}},"
		+ "'trackedKills':[{'tileId':1,'currentAmount':9}],"
		+ "'panel':{'tiles':[],'activeNow':[],'scheduled':[{'id':2}]}}");

	private static final JsonObject TEAM = json("{"
		+ "'event':{'id':5,'name':'New name'},"
		+ "'trackedKills':[{'tileId':1,'currentAmount':0},{'tileId':2,'currentAmount':6}],"
		+ "'trackedStats':[{'tileId':3,'currentAmount':500,'yourGained':0}],"
		+ "'panel':{'tiles':[{'tileId':1,'current':0,'goal':5,'complete':false},{'tileId':2,'current':6,'goal':10,'complete':false}],"
		+ "'activeNow':[{'tileId':2,'workers':['Mate','redman'],'self':false,'gains':{'redman':40,'Mate':10}}]}}");

	@Test
	public void teamFieldsReplaceOursAndCallerOnlyFieldsSurvive()
	{
		JsonObject out = PushMerge.merge(HELD, TEAM, null, "Redman");
		assertEquals("ABC123", out.get("codeword").getAsString());
		assertEquals("New name", out.getAsJsonObject("event").get("name").getAsString());
		assertEquals("a ladder rank is ours, not the team's", 3, out.getAsJsonObject("event").getAsJsonObject("standings").get("rank").getAsInt());
		assertEquals(2, out.getAsJsonArray("trackedKills").size());
		assertEquals("the schedule is ours", 1, out.getAsJsonObject("panel").getAsJsonArray("scheduled").size());
		assertEquals("Old name", HELD.getAsJsonObject("event").get("name").getAsString());
	}

	@Test
	public void ourOwnNumbersGoOnPerMemberTilesAndRows()
	{
		JsonObject mine = json("{'c':{'1':5},'y':{'3':120}}");
		JsonObject out = PushMerge.merge(HELD, TEAM, mine, "Redman");
		assertEquals(5, out.getAsJsonArray("trackedKills").get(0).getAsJsonObject().get("currentAmount").getAsInt());
		assertEquals("a team tile keeps the team's count", 6, out.getAsJsonArray("trackedKills").get(1).getAsJsonObject().get("currentAmount").getAsInt());
		assertEquals(120, out.getAsJsonArray("trackedStats").get(0).getAsJsonObject().get("yourGained").getAsInt());
		JsonObject row = out.getAsJsonObject("panel").getAsJsonArray("tiles").get(0).getAsJsonObject();
		assertEquals(5, row.get("current").getAsInt());
		assertTrue("5 of 5 is done", row.get("complete").getAsBoolean());
	}

	@Test
	public void weAreYouFirstInActiveNow()
	{
		JsonObject out = PushMerge.merge(HELD, TEAM, null, "Redman");
		JsonObject row = out.getAsJsonObject("panel").getAsJsonArray("activeNow").get(0).getAsJsonObject();
		assertEquals("[\"You\",\"Mate\"]", row.get("workers").toString());
		assertTrue(row.get("self").getAsBoolean());
		assertEquals(40, row.getAsJsonObject("gains").get("You").getAsInt());
		assertFalse(row.getAsJsonObject("gains").has("redman"));

		JsonObject other = PushMerge.merge(HELD, TEAM, null, "Somebody");
		assertFalse(other.getAsJsonObject("panel").getAsJsonArray("activeNow").get(0).getAsJsonObject().get("self").getAsBoolean());
	}
}
