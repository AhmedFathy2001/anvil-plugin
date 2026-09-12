package com.anvil.notify;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The repeated pieces of a Discord embed, held to the byte.
 *
 * <p>Every expectation here is the literal output of the hand-written block it replaced — the
 * author object, the thumbnail object, the attachment reference, the wiki link, the "A clan member"
 * fallback. Between them they appeared thirty-odd times across ten posts, and Discord renders what
 * we send: a key renamed here is a post that arrives without its picture, and nothing in the plugin
 * would notice.</p>
 */
public class EmbedPartsTest
{
	@Test
	public void theAuthorLineNamesThePlayer()
	{
		JsonObject embed = new JsonObject();
		AnvilEmbeds.addAuthor(embed, "Zezima");
		assertEquals("{\"author\":{\"name\":\"Zezima\"}}", embed.toString());
	}

	/** No name read means no author block at all, not an author block saying nothing. */
	@Test
	public void anUnreadableNameLeavesTheAuthorLineOff()
	{
		JsonObject embed = new JsonObject();
		AnvilEmbeds.addAuthor(embed, null);
		AnvilEmbeds.addAuthor(embed, "");
		assertEquals("{}", embed.toString());
		assertFalse(embed.has("author"));
	}

	@Test
	public void theThumbnailIsAUrlObject()
	{
		JsonObject embed = new JsonObject();
		AnvilEmbeds.addThumbnail(embed, "https://example.invalid/icon.png");
		assertEquals("{\"thumbnail\":{\"url\":\"https://example.invalid/icon.png\"}}", embed.toString());
	}

	/** RuneLite's static cache export — the exact sprite the client draws. */
	@Test
	public void anItemThumbnailPointsAtTheRuneLiteCache()
	{
		JsonObject embed = new JsonObject();
		AnvilEmbeds.addItemThumbnail(embed, 20997);
		assertEquals("{\"thumbnail\":{\"url\":\"https://static.runelite.net/cache/item/icon/20997.png\"}}",
			embed.toString());
	}

	@Test
	public void anUnresolvedItemGetsNoThumbnailRatherThanABrokenOne()
	{
		JsonObject embed = new JsonObject();
		AnvilEmbeds.addItemThumbnail(embed, null);
		AnvilEmbeds.addItemThumbnail(embed, 0);
		AnvilEmbeds.addItemThumbnail(embed, -1);
		assertEquals("{}", embed.toString());
	}

	/**
	 * The screenshot rides in the same multipart request and Discord resolves this against it.
	 * The scheme is {@code attachment://}, not a URL — get it wrong and the post renders a broken
	 * frame rather than failing loudly.
	 */
	@Test
	public void theImageNamesTheAttachmentTravellingWithIt()
	{
		JsonObject embed = new JsonObject();
		AnvilEmbeds.addAttachment(embed, "anvil-drop.png");
		assertEquals("{\"image\":{\"url\":\"attachment://anvil-drop.png\"}}", embed.toString());
	}

	@Test
	public void theWikiLinkTurnsSpacesIntoUnderscores()
	{
		JsonObject embed = new JsonObject();
		AnvilEmbeds.addWikiUrl(embed, "Twisted bow");
		assertEquals("{\"url\":\"https://oldschool.runescape.wiki/w/Twisted_bow\"}", embed.toString());
	}

	@Test
	public void aNameWeCouldNotReadStillReadsAsASentence()
	{
		assertEquals("Zezima", AnvilEmbeds.who("Zezima"));
		assertEquals("A clan member", AnvilEmbeds.who(null));
		assertEquals("A clan member", AnvilEmbeds.who(""));
	}

	/**
	 * Stat fields are backticked so Discord renders them as a monospace box, and any backtick in the
	 * value is stripped rather than escaped — one would break out of the box and mangle the rest.
	 */
	@Test
	public void aStatFieldIsInlineAndBackticked()
	{
		assertEquals("{\"name\":\"KC\",\"value\":\"`1,204`\",\"inline\":true}",
			AnvilEmbeds.statField("KC", "1,204").toString());
		assertEquals("{\"name\":\"Item\",\"value\":\"`odd name`\",\"inline\":true}",
			AnvilEmbeds.statField("Item", "odd `name`").toString());
	}
}
