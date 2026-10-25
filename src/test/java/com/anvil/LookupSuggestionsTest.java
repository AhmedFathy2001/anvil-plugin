package com.anvil;

import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Lookup box's type-ahead: which name it fills in, and what the next letter does to it. */
public class LookupSuggestionsTest
{
	private static final LookupSuggestions.Names NAMES = new LookupSuggestions.Names(
		Collections.singletonList("Zezima"),
		Collections.singletonList("Zulrah Fan"),
		Collections.singletonList("Zammy Pker"),
		Arrays.asList("Zezima", "Zaros Kid"));

	@Test
	public void recentsComeFirstThenFriendsClanNearbyRoster()
	{
		assertEquals("Zebra", LookupSuggestions.complete("z", Collections.singletonList("Zebra"), NAMES, null));
		assertEquals("Zulrah Fan", LookupSuggestions.complete("z", null, NAMES, null));
		assertEquals("Zezima", LookupSuggestions.complete("ze", null, NAMES, null));
		assertEquals("Zammy Pker", LookupSuggestions.complete("zam", null, NAMES, null));
		assertEquals("Zaros Kid", LookupSuggestions.complete("zar", null, NAMES, null));
	}

	@Test
	public void spacesAndUnderscoresMatchTheSame()
	{
		assertEquals("Zulrah Fan", LookupSuggestions.complete("zulrah_f", null, NAMES, null));
	}

	@Test
	public void nothingForBlankFullOrOwnNames()
	{
		assertNull(LookupSuggestions.complete("", null, NAMES, null));
		assertNull(LookupSuggestions.complete("zezima", null, NAMES, null));
		assertNull(LookupSuggestions.complete("zul", null, NAMES, "Zulrah Fan"));
		assertNull(LookupSuggestions.complete("q", null, NAMES, null));
	}

	@Test
	public void aLetterFillsTheRestSelectedAndTheNextOneFollowsOrReplacesIt() throws Exception
	{
		JTextField f = new JTextField();
		List<String> recent = Collections.emptyList();
		LookupSuggestions s = new LookupSuggestions(f, () -> true, () -> recent, () -> null, then -> then.accept(NAMES));
		SwingUtilities.invokeAndWait(s::refresh);
		flush();

		type(f, s, 'z');
		assertEquals("zulrah Fan", f.getText());
		assertEquals("ulrah Fan", f.getSelectedText());

		// The next letter of the guess: kept, one letter further in.
		type(f, s, 'u');
		assertEquals("zulrah Fan", f.getText());
		assertEquals("lrah Fan", f.getSelectedText());

		// A letter off the guess: it goes, and the box looks again.
		f.setText("z");
		f.setCaretPosition(1);
		type(f, s, 'e');
		assertEquals("zezima", f.getText());
		assertEquals("zima", f.getSelectedText());
	}

	@Test
	public void offWhileLookingUpClans() throws Exception
	{
		JTextField f = new JTextField();
		LookupSuggestions s = new LookupSuggestions(f, () -> false, Collections::emptyList, () -> null, then -> then.accept(NAMES));
		type(f, s, 'z');
		assertEquals("z", f.getText());
		assertTrue(f.getSelectedText() == null);
	}

	/** What Swing does with a typed key: our listener first, then the insert unless it was consumed. */
	private static void type(JTextField f, LookupSuggestions s, char c) throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			KeyEvent e = new KeyEvent(f, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, c);
			s.keyTyped(e);
			if (!e.isConsumed())
			{
				f.replaceSelection(String.valueOf(c));
			}
		});
		flush();
	}

	private static void flush() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> { });
		SwingUtilities.invokeAndWait(() -> { });
	}
}
