package com.anvil.ui;

import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * What a proof screenshot says about itself.
 *
 * <p>A proof is looked at later, by somebody who was not there, possibly after the event has ended.
 * Everything it claims has to be in the pixels — so these tests are about the lines that get burned
 * in, and about the one line that is never allowed to be missing.</p>
 */
public class ProofBannerTest
{
	private static List<String> meta(String rsn, String team, String event)
	{
		return ProofBanner.metaLines(new ProofBanner.Context(rsn, team, event));
	}

	@Test
	public void aFullyKnownProofNamesThePlayerTheirTeamAndTheEvent()
	{
		List<String> lines = meta("Zezima", "Team Gold", "July Bingo");
		assertEquals(4, lines.size());
		assertEquals("RSN: Zezima", lines.get(0));
		assertEquals("Team Gold", lines.get(1).substring("Team: ".length()));
		assertEquals("Event: July Bingo", lines.get(2));
	}

	/**
	 * Omitted, not printed empty. A proof that says "Team:" and nothing after it is worse than one
	 * that does not mention a team at all — the first looks like a bug in the evidence.
	 */
	@Test
	public void whatWeCouldNotReadIsLeftOutRatherThanLeftBlank()
	{
		assertEquals(1, meta(null, null, null).size());
		assertEquals(1, meta("", null, null).size());
		assertEquals(2, meta("Zezima", null, null).size());
	}

	/**
	 * The timestamp is the one line that is never unavailable, and the one a dispute turns on.
	 */
	@Test
	public void theUtcStampIsAlwaysThereEvenWithNothingElseToSay()
	{
		List<String> lines = meta(null, null, null);
		assertEquals(1, lines.size());
		assertTrue(lines.get(0).startsWith("UTC: "));
		// yyyy-MM-dd HH:mm
		assertEquals("UTC: ".length() + 16, lines.get(0).length());
	}

	@Test
	public void theUtcStampIsLastSoItReadsAsTheFooter()
	{
		List<String> lines = meta("Zezima", "Team Gold", "July Bingo");
		assertTrue(lines.get(lines.size() - 1).startsWith("UTC: "));
	}

	/**
	 * Dual frames are a setting and the first capture can fail, so the caller hands over whatever it
	 * has. With nothing to stack, the settled frame passes straight through — not a copy of it, so
	 * nothing is spent on the common case.
	 */
	@Test
	public void oneFrameIsNotStackedAtAll()
	{
		BufferedImage flush = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		assertSame(flush, ProofBanner.stack(null, flush));
	}

	@Test
	public void twoFramesBecomeOneTallImageWithADividerBetweenThem()
	{
		BufferedImage trigger = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		BufferedImage flush = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		BufferedImage out = ProofBanner.stack(trigger, flush);
		assertEquals(400, out.getWidth());
		assertEquals(300 + 4 + 300, out.getHeight());
	}

	/** Frames can differ in size across a resize mid-burst; the wider one sets the width. */
	@Test
	public void theWiderFrameSetsTheWidth()
	{
		BufferedImage trigger = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		BufferedImage flush = new BufferedImage(520, 280, BufferedImage.TYPE_INT_RGB);
		BufferedImage out = ProofBanner.stack(trigger, flush);
		assertEquals(520, out.getWidth());
		assertEquals(300 + 4 + 280, out.getHeight());
	}

	/** Drawing must not throw on the shapes the plugin actually hands it. */
	@Test
	public void drawingSurvivesAnAbsentIconAndAnEmptyDetail()
	{
		BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		ProofBanner.draw(img, "BINGO DROP", "", new ProofBanner.Context(null, null, null), null);
		ProofBanner.drawDrop(img, "Twisted bow", 1, 1, 1,
			new ProofBanner.Context("Zezima", "Team Gold", "July Bingo"), null);
	}
}
