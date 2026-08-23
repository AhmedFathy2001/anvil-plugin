package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What a clip's caption is allowed to say it caught.
 *
 * <p>The behaviour worth defending is that the window belongs to the FOOTAGE, not to the moment the
 * file turned up. OBS writes a clip whenever it gets round to it — on a busy machine that can be
 * minutes after the hotkey — and the replay buffer holds the seconds BEFORE the press. So a caption
 * built when the file lands describes a stretch of time the clip doesn't contain: the player has
 * killed other things, walked to another boss, taken other drops. Every test here is a version of
 * that.
 */
public class ClipMomentsTest
{
	private static final int WINDOW_SECONDS = 30;

	@Test
	public void namesWhatHappenedInsideTheWindow()
	{
		ClipMoments moments = new ClipMoments();
		moments.record("💀 Zulrah kill 1,204");
		String summary = moments.summarize(System.currentTimeMillis(), WINDOW_SECONDS, 3);
		assertEquals("💀 Zulrah kill 1,204", summary);
	}

	@Test
	public void ignoresWhatHappenedAfterTheClipEnded()
	{
		// The whole bug, in one case: the clip was asked for, OBS took its time, and the player
		// carried on playing. Nothing they did afterwards is in the footage.
		// The clip was asked for ten minutes ago and OBS is only now handing us the file, so "now" and
		// "when the footage ended" are ten minutes apart. That gap is the entire bug, and a test that
		// puts the later moments in the FUTURE doesn't reproduce it — a now-anchored window excludes
		// those for the wrong reason and passes by accident.
		ClipMoments moments = new ClipMoments();
		long now = System.currentTimeMillis();
		long clipRequestedAt = now - 600_000L;
		moments.record("🐉 Vorkath kill 500", clipRequestedAt - 5_000L);

		// …ten minutes of other content, all of it already in the past by the time the file lands.
		moments.record("⚔️ Fighting Zulrah", clipRequestedAt + 60_000L);
		moments.record("💰 Tanzanite fang", now - 2_000L);

		String summary = moments.summarize(clipRequestedAt, WINDOW_SECONDS, 3);
		assertEquals("the caption must describe the clip, not the wait", "🐉 Vorkath kill 500", summary);
	}

	@Test
	public void ignoresWhatHappenedBeforeTheWindowOpened()
	{
		ClipMoments moments = new ClipMoments();
		long now = System.currentTimeMillis();
		// A kill from well before the buffer's start edge isn't in the footage either.
		moments.record("🐉 Vorkath kill 499", now);
		String summary = moments.summarize(now + (WINDOW_SECONDS * 1000L) + 60_000L, WINDOW_SECONDS, 3);
		assertNull(summary);
	}

	@Test
	public void survivesABusyWaitWithoutLosingTheMomentItNeeds()
	{
		// Eviction used to be "keep the newest 40", which a raid fills in well under the time OBS can
		// take to write a file — so the moment the clip was FOR got pushed out by moments that came
		// after it. Retention is by age now, so a long wait doesn't cost the caption.
		ClipMoments moments = new ClipMoments();
		long clipRequestedAt = System.currentTimeMillis();
		moments.record("🏆 Twisted bow", clipRequestedAt - 5_000L);
		for (int i = 0; i < 200; i++)
		{
			moments.record("⚔️ Lizardman shaman kill " + i, clipRequestedAt + 10_000L + i);
		}
		String summary = moments.summarize(clipRequestedAt, WINDOW_SECONDS, 3);
		assertEquals("🏆 Twisted bow", summary);
	}

	@Test
	public void collapsesRepeatsAndSummarisesTheRest()
	{
		ClipMoments moments = new ClipMoments();
		moments.record("⚔️ Goblin kill 1");
		moments.record("⚔️ Goblin kill 1"); // the game announces things more than once
		moments.record("💰 Dragon claws");
		moments.record("📕 New clog slot: Dragon claws");
		moments.record("🐾 Pet");
		String summary = moments.summarize(System.currentTimeMillis(), WINDOW_SECONDS, 2);
		assertTrue("newest first", summary.startsWith("🐾 Pet"));
		assertTrue("names two then counts the rest", summary.endsWith("…and 2 more"));
	}

	@Test
	public void saysNothingWhenNothingHappened()
	{
		assertNull(new ClipMoments().summarize(System.currentTimeMillis(), WINDOW_SECONDS, 3));
	}

	@Test
	public void blankMomentsAreNotMoments()
	{
		ClipMoments moments = new ClipMoments();
		moments.record(null);
		moments.record("   ");
		assertNull(moments.summarize(System.currentTimeMillis(), WINDOW_SECONDS, 3));
	}
}
