package com.anvil;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Team-private tile claims as the sidebar shows them (server capability {@code tile-claims}). */
public class TileClaimsTest
{
	private static BingoApiClient.BoardClaim claim(String name, String note, boolean mine)
	{
		BingoApiClient.BoardClaim c = new BingoApiClient.BoardClaim();
		c.name = name;
		c.note = note;
		c.mine = mine;
		return c;
	}

	@Test
	public void boardTileParsesClaimsAndToleratesTheirAbsence()
	{
		Gson gson = new Gson();
		BingoApiClient.BoardTile with = gson.fromJson(
			"{\"tileId\":7,\"label\":\"Zulrah pet\",\"claims\":[{\"name\":\"AliceRSN\",\"note\":\"tonight\",\"mine\":false},"
				+ "{\"name\":\"Me\",\"note\":null,\"mine\":true}]}",
			BingoApiClient.BoardTile.class);
		assertEquals(2, with.claims.size());
		assertEquals("tonight", with.claims.get(0).note);
		assertTrue(with.claims.get(1).mine);

		// Older sites and unclaimed tiles send nothing at all.
		BingoApiClient.BoardTile without = gson.fromJson("{\"tileId\":8,\"label\":\"Fire cape\"}", BingoApiClient.BoardTile.class);
		assertNull(without.claims);
		assertNull(AnvilSidebarPanel.claimLine(without.claims));
	}

	@Test
	public void claimLinePutsYouFirstAndCollapsesTheRest()
	{
		assertEquals("Planning: AliceRSN", AnvilSidebarPanel.claimLine(Collections.singletonList(claim("AliceRSN", null, false))));
		assertEquals("Planning: You, AliceRSN",
			AnvilSidebarPanel.claimLine(Arrays.asList(claim("AliceRSN", null, false), claim("Me", null, true))));
		assertEquals("Planning: A, B +2", AnvilSidebarPanel.claimLine(Arrays.asList(
			claim("A", null, false), claim("B", null, false), claim("C", null, false), claim("D", null, false))));
		assertNull(AnvilSidebarPanel.claimLine(Collections.emptyList()));
	}

	@Test
	public void tooltipNamesEveryoneWithTheirNote()
	{
		assertEquals("AliceRSN (tonight) · You",
			AnvilSidebarPanel.claimTooltip(Arrays.asList(claim("AliceRSN", "tonight", false), claim("Me", " ", true))));
	}

	@Test
	public void claimedByMeOnlyWhenOneIsYours()
	{
		assertTrue(AnvilSidebarPanel.claimedByMe(Arrays.asList(claim("A", null, false), claim("Me", null, true))));
		assertFalse(AnvilSidebarPanel.claimedByMe(Collections.singletonList(claim("A", null, false))));
		assertFalse(AnvilSidebarPanel.claimedByMe(null));
	}

	@Test
	public void claimErrorsShowTheServersOwnSentence()
	{
		Gson gson = new Gson();
		assertEquals("Your team already completed this tile.",
			BingoApiClient.claimErrorMessage(gson, 409, "{\"error\":\"Your team already completed this tile.\"}"));
		assertEquals("Couldn't update your claim (HTTP 502).", BingoApiClient.claimErrorMessage(gson, 502, "<html>Bad gateway</html>"));
		assertEquals("Couldn't update your claim (HTTP 404).", BingoApiClient.claimErrorMessage(gson, 404, ""));
	}
}
