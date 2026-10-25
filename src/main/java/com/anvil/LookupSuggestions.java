package com.anvil;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

/**
 * Type-ahead for the Lookup box, the way RuneLite's hiscores panel does it: as you type, the rest of
 * the first name that fits is written into the box, selected, so Enter takes it and the next letter
 * either follows it or throws it away and looks again. The names are the ones you just looked up,
 * your friends, your clan and whoever is around you — all read off the client, nothing asked of the site.
 *
 * <p>The game's lists live on the client thread, so they're read there into a {@link Names} snapshot
 * that the EDT completes from. Every keystroke asks for a fresh one; when it lands and the box still
 * has nothing completed, the same letters are tried again — someone who has only just walked into
 * view is picked up without typing another letter.</p>
 */
final class LookupSuggestions extends KeyAdapter
{
	/** The game's name lists at one moment. Each list may be empty, never null. */
	static final class Names
	{
		static final Names EMPTY = new Names(null, null, null, null);

		final List<String> clanOnline;
		final List<String> friends;
		final List<String> nearby;
		final List<String> clan;

		Names(Collection<String> clanOnline, Collection<String> friends, Collection<String> nearby, Collection<String> clan)
		{
			this.clanOnline = copy(clanOnline);
			this.friends = copy(friends);
			this.nearby = copy(nearby);
			this.clan = copy(clan);
		}

		private static List<String> copy(Collection<String> names)
		{
			return names == null ? Collections.emptyList() : new ArrayList<>(names);
		}
	}

	/**
	 * The first name that starts with {@code typed} and is longer than it — recent lookups, then
	 * friends, clan members online, players nearby and the rest of the clan — or null. Never your own.
	 */
	static String complete(String typed, List<String> recent, Names names, String me)
	{
		String q = Rsn.normalize(typed);
		if (q.isEmpty())
		{
			return null;
		}
		String mine = Rsn.normalize(me);
		for (List<String> list : java.util.Arrays.asList(recent, names.friends, names.clanOnline, names.nearby, names.clan))
		{
			if (list == null)
			{
				continue;
			}
			for (String raw : list)
			{
				if (raw == null)
				{
					continue;
				}
				String name = raw.replace(' ', ' ').trim();
				String key = Rsn.normalize(name);
				if (key.length() > q.length() && key.startsWith(q) && !key.equals(mine))
				{
					return name;
				}
			}
		}
		return null;
	}

	private final JTextComponent field;
	private final BooleanSupplier enabled;
	private final Supplier<List<String>> recent;
	private final Supplier<String> me;
	private final Consumer<Consumer<Names>> fetch;
	private volatile Names names = Names.EMPTY;

	/**
	 * @param fetch reads the game's lists and hands them back on any thread (the client thread, live)
	 */
	LookupSuggestions(JTextComponent field, BooleanSupplier enabled, Supplier<List<String>> recent,
		Supplier<String> me, Consumer<Consumer<Names>> fetch)
	{
		this.field = field;
		this.enabled = enabled;
		this.recent = recent;
		this.me = me;
		this.fetch = fetch;
		field.addKeyListener(this);
	}

	/** Read the game's lists again, then retry the box if it is waiting on a name. */
	void refresh()
	{
		String asked = field.getText();
		fetch.accept(n -> SwingUtilities.invokeLater(() ->
		{
			names = n;
			// Only for the letters that asked, and only while nothing is filled in after them.
			if (asked.equals(field.getText()) && field.isFocusOwner() && field.getSelectionStart() == field.getSelectionEnd()
				&& field.getCaretPosition() == asked.length())
			{
				fill(asked);
			}
		}));
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		char c = e.getKeyChar();
		if (!enabled.getAsBoolean() || Character.isISOControl(c) || !(Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '-')
			|| field.getSelectionEnd() != field.getText().length())
		{
			return;
		}
		int start = field.getSelectionStart();
		int end = field.getSelectionEnd();
		// The letter typed is the next one of the name already filled in: keep the name, move past it.
		if (start < end && Rsn.normalize(field.getText().substring(start, start + 1)).equals(Rsn.normalize(String.valueOf(c))))
		{
			e.consume();
			field.select(start + 1, end);
			return;
		}
		// Otherwise the letter replaces the guess; once it's in, look for a name again.
		SwingUtilities.invokeLater(() ->
		{
			String typed = field.getText();
			if (field.getCaretPosition() == typed.length())
			{
				fill(typed);
				refresh();
			}
		});
	}

	private void fill(String typed)
	{
		String name = complete(typed, recent.get(), names, me.get());
		if (name == null || name.length() <= typed.length())
		{
			return;
		}
		try
		{
			field.getDocument().insertString(typed.length(), name.substring(typed.length()), null);
			field.select(typed.length(), name.length());
		}
		catch (BadLocationException ignored)
		{
			// The box changed under us; the next letter tries again.
		}
	}
}
