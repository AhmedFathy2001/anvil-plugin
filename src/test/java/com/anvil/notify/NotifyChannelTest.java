package com.anvil.notify;

import com.anvil.api.dto.NotifyChannels;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which notifications a clan's channel setup lets through.
 *
 * <p>Pets, levels, quests, diaries and collection-log slots were each announced under the name of a
 * channel they shared. They have their own names now, which puts every clan in one of two states:
 * either the site knows the new names and answered for them, or it predates the split and said
 * nothing. Only the first is a preference. The second must keep behaving as it always did, because a
 * clan that never asked for a change should not lose its 99 posts to one.</p>
 */
public class NotifyChannelTest
{
	private static NotifyChannels legacySite()
	{
		NotifyChannels n = new NotifyChannels();
		n.rareDrops = true;
		n.combatAchievements = true;
		return n; // pets/levels/quests/diaries/collectionLog absent, as an older site leaves them
	}

	@Test
	public void aSiteThatPredatesTheSplitKeepsPostingWhereItDid()
	{
		NotifyChannels n = legacySite();
		assertTrue(AnvilEmbeds.channelEnabled(n, "levels"));
		assertTrue(AnvilEmbeds.channelEnabled(n, "quests"));
		assertTrue(AnvilEmbeds.channelEnabled(n, "diaries"));
		assertTrue(AnvilEmbeds.channelEnabled(n, "collectionLog"));
		assertTrue(AnvilEmbeds.channelEnabled(n, "pets"));
	}

	@Test
	public void inheritanceFollowsTheChannelEachOneUsedToShare()
	{
		NotifyChannels n = new NotifyChannels();
		n.rareDrops = true; // drops on, CAs off
		assertTrue("pets rode with drops", AnvilEmbeds.channelEnabled(n, "pets"));
		assertFalse("levels rode with CAs", AnvilEmbeds.channelEnabled(n, "levels"));
	}

	@Test
	public void anExplicitAnswerBeatsTheChannelItSplitFrom()
	{
		NotifyChannels n = legacySite();
		n.levels = false; // clan pointed levels nowhere, though CAs still have a home
		assertFalse(AnvilEmbeds.channelEnabled(n, "levels"));
		assertTrue(AnvilEmbeds.channelEnabled(n, "combatAchievements"));

		NotifyChannels off = new NotifyChannels();
		off.levels = true; // levels split out; the channel it came from is unset
		assertTrue(AnvilEmbeds.channelEnabled(off, "levels"));
	}

	/**
	 * The old default arm answered `rareDrops` for anything it didn't recognise, so every channel
	 * added after it would have silently ridden the drop flag — on for clans that never configured it
	 * and off for clans that had.
	 */
	@Test
	public void anUnknownChannelIsOffRatherThanTreatedAsDrops()
	{
		NotifyChannels n = new NotifyChannels();
		n.rareDrops = true;
		assertFalse(AnvilEmbeds.channelEnabled(n, "somethingLater"));
	}

	@Test
	public void noConfigAtAllPostsNothing()
	{
		assertFalse(AnvilEmbeds.channelEnabled(null, "levels"));
	}
}
