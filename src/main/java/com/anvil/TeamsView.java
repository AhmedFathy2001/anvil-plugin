package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import static com.anvil.PlayerCardView.MUTED;
import static com.anvil.PlayerCardView.block;
import static com.anvil.PlayerCardView.gap;
import static com.anvil.PlayerCardView.header;
import static com.anvil.PlayerCardView.hoverable;
import static com.anvil.PlayerCardView.safe;
import static com.anvil.PlayerCardView.small;

/**
 * An event's Teams page: who is on which team, a page at a time ({@link EventRoster}).
 *
 * <p>Stateful on purpose, unlike the card views: the search field has to keep its caret and focus
 * while results arrive, so the panel mounts ONE of these per open page and leaves it alone, and only
 * the results area under the field is ever rebuilt. Every fetch runs on the interactive executor;
 * an answer that arrives after a newer request was made is dropped.</p>
 */
final class TeamsView extends JPanel
{
	interface Source
	{
		EventRoster fetch(int eventId, int teamId, String query, int page);
	}

	private static final Color YOU = ColorScheme.BRAND_ORANGE;
	private static final int SEARCH_DELAY_MS = 350;

	private final Source source;
	private final Executor executor;
	/** Resolves the event id off the EDT when the caller did not have it (the active board). */
	private final IntSupplier eventIdLookup;

	private int eventId;
	private int teamId;
	private int page = 1;
	/** The overview page to return to from a team. */
	private int overviewPage = 1;
	private String query = "";

	private EventRoster data;
	private boolean loading;
	private boolean failed;
	private int generation;
	private boolean settingSearch;

	private final JTextField search = new JTextField();
	private final JPanel results = new JPanel();
	private final Timer debounce;

	TeamsView(Source source, Executor executor, int eventId, IntSupplier eventIdLookup)
	{
		this.source = source;
		this.executor = executor;
		this.eventId = eventId;
		this.eventIdLookup = eventIdLookup;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setAlignmentX(LEFT_ALIGNMENT);

		search.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		search.setForeground(Color.WHITE);
		search.setCaretColor(Color.WHITE);
		search.setSelectionColor(ColorScheme.BRAND_ORANGE);
		search.setSelectedTextColor(Color.BLACK);
		search.setFont(FontManager.getRunescapeFont());
		search.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		search.setToolTipText("Find a player on any team");
		JLabel hint = small("Search players", MUTED);
		JPanel searchRow = new JPanel(new BorderLayout(0, 2));
		searchRow.setOpaque(false);
		searchRow.setAlignmentX(LEFT_ALIGNMENT);
		searchRow.add(hint, BorderLayout.NORTH);
		searchRow.add(search, BorderLayout.CENTER);
		searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, searchRow.getPreferredSize().height));
		add(searchRow);
		add(gap(8));

		debounce = new Timer(SEARCH_DELAY_MS, e ->
		{
			query = search.getText().trim();
			page = 1;
			load();
		});
		debounce.setRepeats(false);
		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			private void changed()
			{
				if (!settingSearch)
				{
					debounce.restart();
				}
			}
			@Override public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
			@Override public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
			@Override public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
		});
		search.addActionListener(e ->
		{
			debounce.stop();
			query = search.getText().trim();
			page = 1;
			load();
		});

		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		results.setBackground(ColorScheme.DARK_GRAY_COLOR);
		results.setAlignmentX(LEFT_ALIGNMENT);
		add(results);

		load();
	}

	/** Leaving the page: nothing that arrives later may touch it. */
	void dispose()
	{
		debounce.stop();
		generation++;
	}

	// ---- Fetching ---------------------------------------------------------------------------------

	private void load()
	{
		final int mine = ++generation;
		final int wantTeam = teamId;
		final String wantQuery = query;
		final int wantPage = page;
		final int knownEvent = eventId;
		loading = true;
		renderResults();
		executor.execute(() ->
		{
			int id = knownEvent > 0 ? knownEvent : eventIdLookup.getAsInt();
			EventRoster r = id > 0 ? source.fetch(id, wantTeam, wantQuery, wantPage) : null;
			SwingUtilities.invokeLater(() ->
			{
				if (mine != generation)
				{
					return;
				}
				eventId = id;
				loading = false;
				failed = r == null;
				if (r != null)
				{
					data = r;
					page = Math.max(1, r.page);
				}
				renderResults();
			});
		});
	}

	private void openTeam(int id)
	{
		if (!query.isEmpty())
		{
			setSearch("");
		}
		else
		{
			overviewPage = page;
		}
		teamId = id;
		page = 1;
		load();
	}

	private void backToTeams()
	{
		teamId = 0;
		page = overviewPage;
		load();
	}

	private void goToPage(int p)
	{
		page = p;
		load();
	}

	private void setSearch(String text)
	{
		settingSearch = true;
		try
		{
			search.setText(text);
		}
		finally
		{
			settingSearch = false;
		}
		debounce.stop();
		query = text;
	}

	// ---- Rendering --------------------------------------------------------------------------------

	private void renderResults()
	{
		results.removeAll();
		if (data == null)
		{
			if (loading)
			{
				results.add(small("Loading teams…", MUTED));
			}
			else
			{
				results.add(small("Couldn't load the teams.", ColorScheme.PROGRESS_ERROR_COLOR));
				results.add(gap(6));
				results.add(AnvilSidebarPanel.fullWidthButton("Try again", "Load the teams again", this::load));
			}
			finish();
			return;
		}
		if (loading || failed)
		{
			results.add(small(loading ? "Updating…" : "Couldn't refresh — showing the last answer.",
				failed ? ColorScheme.PROGRESS_ERROR_COLOR : MUTED));
			results.add(gap(4));
		}

		if (data.is("members"))
		{
			renderMembers();
		}
		else if (data.is("search"))
		{
			renderSearch();
		}
		else if (data.is("players"))
		{
			renderPlayers();
		}
		else
		{
			renderTeams();
		}
		renderPager();
		finish();
	}

	private void finish()
	{
		results.revalidate();
		results.repaint();
	}

	private void renderTeams()
	{
		List<EventRoster.Team> teams = data.teams;
		if (teams == null || teams.isEmpty())
		{
			results.add(small(data.unplaced > 0 ? "Teams haven't been set yet." : "No teams yet.", MUTED));
			if (data.unplaced > 0)
			{
				results.add(small(count(data.unplaced, "player") + " signed up.", MUTED));
			}
			return;
		}
		EventRoster.Team yours = data.yourTeam;
		if (yours != null && teams.stream().noneMatch(t -> t.teamId == yours.teamId))
		{
			results.add(header("Your team", null));
			results.add(gap(3));
			results.add(teamRow(yours));
			results.add(gap(8));
		}
		results.add(header(count(data.total, "team"), data.unplaced > 0 ? data.unplaced + " not placed" : null));
		results.add(gap(3));
		for (EventRoster.Team t : teams)
		{
			results.add(teamRow(t));
			results.add(gap(2));
		}
	}

	private void renderPlayers()
	{
		List<EventRoster.Person> people = data.players;
		if (people == null || people.isEmpty())
		{
			results.add(small("Nobody has signed up yet.", MUTED));
			return;
		}
		EventRoster.Person you = data.you;
		if (you != null && people.stream().noneMatch(p -> p.you))
		{
			results.add(header("You", null));
			results.add(gap(3));
			results.add(personRow(you, true, false));
			results.add(gap(8));
		}
		results.add(header(count(data.total, "player"), null));
		results.add(gap(3));
		for (EventRoster.Person p : people)
		{
			results.add(personRow(p, true, false));
			results.add(gap(2));
		}
	}

	private void renderMembers()
	{
		EventRoster.Team t = data.team;
		JLabel back = new JLabel("‹ All teams");
		back.setFont(FontManager.getRunescapeSmallFont());
		back.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		back.setAlignmentX(LEFT_ALIGNMENT);
		hoverable(back, ColorScheme.DARK_GRAY_COLOR, this::backToTeams);
		results.add(back);
		results.add(gap(6));
		if (t != null)
		{
			JPanel card = block();
			JPanel title = new JPanel(new BorderLayout(6, 0));
			title.setOpaque(false);
			title.setAlignmentX(LEFT_ALIGNMENT);
			title.add(swatch(t.color, 10), BorderLayout.WEST);
			JLabel name = new JLabel(safe(t.name));
			name.setFont(FontManager.getRunescapeBoldFont());
			name.setForeground(Color.WHITE);
			title.add(name, BorderLayout.CENTER);
			title.setMaximumSize(new Dimension(Integer.MAX_VALUE, title.getPreferredSize().height));
			card.add(title);
			card.add(gap(3));
			card.add(small(teamLine(t), MUTED));
			card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
			results.add(card);
			results.add(gap(8));
		}
		if (data.players == null || data.players.isEmpty())
		{
			results.add(small("Nobody on this team yet.", MUTED));
			return;
		}
		results.add(header("Members", null));
		results.add(gap(3));
		for (EventRoster.Person p : data.players)
		{
			results.add(personRow(p, false, false));
			results.add(gap(2));
		}
	}

	private void renderSearch()
	{
		List<EventRoster.Person> hits = data.players;
		if (hits == null || hits.isEmpty())
		{
			results.add(small("No players match \"" + data.query + "\".", MUTED));
			return;
		}
		results.add(header(count(data.total, "match", "matches"), null));
		results.add(gap(3));
		for (EventRoster.Person p : hits)
		{
			results.add(personRow(p, false, true));
			results.add(gap(2));
		}
	}

	private void renderPager()
	{
		if (data.pages <= 1)
		{
			return;
		}
		results.add(gap(6));
		JButton prev = AnvilSidebarPanel.flatButton("‹ Prev", "The previous page", () -> goToPage(data.page - 1));
		JButton next = AnvilSidebarPanel.flatButton("Next ›", "The next page", () -> goToPage(data.page + 1));
		prev.setEnabled(data.page > 1 && !loading);
		next.setEnabled(data.page < data.pages && !loading);
		JLabel where = new JLabel(data.page + " / " + data.pages, SwingConstants.CENTER);
		where.setFont(FontManager.getRunescapeSmallFont());
		where.setForeground(MUTED);
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(prev, BorderLayout.WEST);
		row.add(where, BorderLayout.CENTER);
		row.add(next, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		results.add(row);
	}

	// ---- Rows -------------------------------------------------------------------------------------

	private JPanel teamRow(EventRoster.Team t)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(swatch(t.color, 8), BorderLayout.WEST);

		JPanel text = new JPanel(new BorderLayout());
		text.setOpaque(false);
		JLabel name = new JLabel(safe(t.name));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(t.yours ? YOU : Color.WHITE);
		text.add(name, BorderLayout.NORTH);
		text.add(small(teamLine(t), MUTED), BorderLayout.SOUTH);
		row.add(text, BorderLayout.CENTER);

		JLabel arrow = new JLabel("›");
		arrow.setForeground(MUTED);
		row.add(arrow, BorderLayout.EAST);
		row.setToolTipText(safe(t.name) + " — see who's on it");
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		hoverable(row, ColorScheme.DARKER_GRAY_COLOR, () -> openTeam(t.teamId));
		return row;
	}

	/**
	 * A person: rank (when ranked), name, and a second line — their score in a ranking, their team in
	 * a search. A search result opens the team it names.
	 */
	private JPanel personRow(EventRoster.Person p, boolean ranked, boolean showTeam)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		row.setAlignmentX(LEFT_ALIGNMENT);

		if (ranked)
		{
			JLabel rank = new JLabel(p.rank == null ? "–" : "#" + p.rank);
			rank.setFont(FontManager.getRunescapeSmallFont());
			rank.setForeground(p.rank != null && p.rank <= 3 ? ColorScheme.BRAND_ORANGE : MUTED);
			rank.setPreferredSize(new Dimension(34, rank.getPreferredSize().height));
			row.add(rank, BorderLayout.WEST);
		}

		JPanel text = new JPanel(new BorderLayout());
		text.setOpaque(false);
		JLabel name = new JLabel(safe(p.name));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(p.you ? YOU : Color.WHITE);
		text.add(name, BorderLayout.NORTH);
		String second = showTeam
			? (p.teamName != null ? p.teamName : "Not on a team yet")
			: ranked ? p.score : null;
		if (second != null)
		{
			JLabel line = small(second, MUTED);
			if (showTeam && p.teamColor != null)
			{
				line.setIcon(swatchIcon(p.teamColor, 6));
				line.setIconTextGap(4);
			}
			text.add(line, BorderLayout.SOUTH);
		}
		row.add(text, BorderLayout.CENTER);

		String tag = p.captain && p.you ? "Captain · you" : p.captain ? "Captain" : p.you ? "You" : null;
		if (tag != null)
		{
			row.add(small(tag, p.captain ? ColorScheme.BRAND_ORANGE : YOU), BorderLayout.EAST);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		if (showTeam && p.teamId != null)
		{
			row.setToolTipText("Open " + safe(p.teamName));
			hoverable(row, ColorScheme.DARKER_GRAY_COLOR, () -> openTeam(p.teamId));
		}
		else
		{
			row.setOpaque(true);
			row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		}
		return row;
	}

	// ---- Bits -------------------------------------------------------------------------------------

	/** "#2 · 12 tiles · 8 members" — what is known; the rank and score wait for the reveal. */
	static String teamLine(EventRoster.Team t)
	{
		StringBuilder s = new StringBuilder();
		if (t.rank != null)
		{
			s.append('#').append(t.rank).append(" · ");
		}
		if (t.score != null)
		{
			s.append(t.score).append(" · ");
		}
		return s.append(count(t.members, "member")).toString();
	}

	static String count(int n, String one)
	{
		return count(n, one, one + "s");
	}

	static String count(int n, String one, String many)
	{
		return String.format(Locale.ROOT, "%,d %s", n, n == 1 ? one : many);
	}

	/** A team's colour; the server stores "#rrggbb", and anything else reads as grey. */
	static Color teamColor(String hex)
	{
		if (hex != null && hex.matches("#[0-9a-fA-F]{6}"))
		{
			return new Color(Integer.parseInt(hex.substring(1), 16));
		}
		return MUTED;
	}

	private static Component swatch(String hex, int size)
	{
		Color c = teamColor(hex);
		JComponent dot = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				g.setColor(c);
				int y = (getHeight() - size) / 2;
				g.fillRect(0, Math.max(0, y), size, size);
			}
		};
		dot.setPreferredSize(new Dimension(size, size));
		return dot;
	}

	private static javax.swing.Icon swatchIcon(String hex, int size)
	{
		Color c = teamColor(hex);
		return new javax.swing.Icon()
		{
			@Override
			public void paintIcon(Component comp, Graphics g, int x, int y)
			{
				g.setColor(c);
				g.fillRect(x, y, size, size);
			}

			@Override
			public int getIconWidth()
			{
				return size;
			}

			@Override
			public int getIconHeight()
			{
				return size;
			}
		};
	}
}
