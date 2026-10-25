package com.anvil;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.*;
import javax.swing.*;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;

import static com.anvil.Ui.*;

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
	/** Opens a name in the Lookup tab; null when this site has no player cards. */
	private final Consumer<String> lookup;

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

	TeamsView(Source source, Executor executor, int eventId, IntSupplier eventIdLookup, Consumer<String> lookup)
	{
		this.source = source;
		this.executor = executor;
		this.eventId = eventId;
		this.eventIdLookup = eventIdLookup;
		this.lookup = lookup;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setAlignmentX(LEFT_ALIGNMENT);

		styleSearch(search);
		search.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		search.setToolTipText("Find a player on any team");
		JLabel hint = small("Search players", MUTED);
		JPanel searchRow = left(new JPanel(new BorderLayout(0, 2)));
		searchRow.setOpaque(false);
		searchRow.add(hint, BorderLayout.NORTH);
		searchRow.add(search, BorderLayout.CENTER);
		add(capH(searchRow));
		add(gap(8));

		debounce = new Timer(SEARCH_DELAY_MS, e ->
		{
			query = search.getText().trim();
			page = 1;
			load();
		});
		debounce.setRepeats(false);
		onEdit(search, () ->
		{
			if (!settingSearch)
			{
				debounce.restart();
			}
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
		add(left(results));

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
				results.add(small(plural(data.unplaced, "player") + " signed up.", MUTED));
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
		results.add(header(plural(data.total, "team"), data.unplaced > 0 ? data.unplaced + " not placed" : null));
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
		results.add(header(plural(data.total, "player"), null));
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
		JLabel back = small("‹ All teams", ColorScheme.LIGHT_GRAY_COLOR);
		hoverable(back, ColorScheme.DARK_GRAY_COLOR, this::backToTeams);
		results.add(back);
		results.add(gap(6));
		if (t != null)
		{
			JPanel card = block();
			JPanel title = row(6);
			title.add(new JLabel(swatchIcon(t.color, 10)), BorderLayout.WEST);
			title.add(title(t.name), BorderLayout.CENTER);
			card.add(capH(title));
			card.add(gap(3));
			card.add(small(teamLine(t), MUTED));
			results.add(capH(card));
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
			// A scored board sends each member's share (best first): show it like the ranked lists.
			results.add(personRow(p, p.rank != null, false));
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
		results.add(header(plural(data.total, "match", "matches"), null));
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
		JLabel where = small(data.page + " / " + data.pages, MUTED);
		where.setHorizontalAlignment(SwingConstants.CENTER);
		JPanel row = row(6);
		row.add(prev, BorderLayout.WEST);
		row.add(where, BorderLayout.CENTER);
		row.add(next, BorderLayout.EAST);
		results.add(capH(row));
	}

	// ---- Rows -------------------------------------------------------------------------------------

	private JPanel teamRow(EventRoster.Team t)
	{
		JPanel row = row(6);
		row.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
		row.add(new JLabel(swatchIcon(t.color, 8)), BorderLayout.WEST);
		row.add(twoLines(small(t.name, t.yours ? YOU : Color.WHITE), small(teamLine(t), MUTED)), BorderLayout.CENTER);

		JLabel arrow = new JLabel("›");
		arrow.setForeground(MUTED);
		row.add(arrow, BorderLayout.EAST);
		row.setToolTipText(plainText(t.name) + " — see who's on it");
		hoverable(capH(row), ColorScheme.DARKER_GRAY_COLOR, () -> openTeam(t.teamId));
		return row;
	}

	/**
	 * A person: rank (when ranked), name, and a second line — their score in a ranking, their team in
	 * a search. Clicking the person looks them up; a search result's team line opens that team.
	 * Without player cards there is no Lookup, so a search result opens its team as before.
	 */
	private JPanel personRow(EventRoster.Person p, boolean ranked, boolean showTeam)
	{
		JPanel row = row(6);
		row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

		if (ranked)
		{
			JLabel rank = small(p.rank == null ? "–" : "#" + p.rank, p.rank != null && p.rank <= 3 ? ColorScheme.BRAND_ORANGE : MUTED);
			rank.setPreferredSize(new Dimension(34, rank.getPreferredSize().height));
			row.add(rank, BorderLayout.WEST);
		}

		JPanel text = twoLines(small(p.name, p.you ? YOU : Color.WHITE), null);
		String second = showTeam
			? (p.teamName != null ? p.teamName : "Not on a team yet")
			: ranked ? p.score : null;
		boolean canLookUp = lookup != null && p.name != null && !p.name.isEmpty();
		if (second != null)
		{
			JLabel line = small(second, MUTED);
			if (showTeam && p.teamColor != null)
			{
				line.setIcon(swatchIcon(p.teamColor, 6));
				line.setIconTextGap(4);
			}
			if (showTeam && p.teamId != null && canLookUp)
			{
				// The row looks the person up, so their team gets its own way in.
				line.setText(plainText(second + " ›"));
				line.setToolTipText("Open " + plainText(p.teamName));
				onClick(line, () -> openTeam(p.teamId));
			}
			text.add(line, BorderLayout.SOUTH);
		}
		row.add(text, BorderLayout.CENTER);

		String tag = p.captain && p.you ? "Captain · you" : p.captain ? "Captain" : p.you ? "You" : null;
		if (tag != null)
		{
			row.add(small(tag, p.captain ? ColorScheme.BRAND_ORANGE : YOU), BorderLayout.EAST);
		}
		capH(row);
		if (canLookUp)
		{
			row.setToolTipText("Look up " + plainText(p.name));
			hoverable(row, ColorScheme.DARKER_GRAY_COLOR, () -> lookup.accept(p.name));
		}
		else if (showTeam && p.teamId != null)
		{
			row.setToolTipText("Open " + plainText(p.teamName));
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

	/** A name over its second line ({@code below} may be null, to be added later). */
	private static JPanel twoLines(JLabel top, JLabel below)
	{
		JPanel text = new JPanel(new BorderLayout());
		text.setOpaque(false);
		text.add(top, BorderLayout.NORTH);
		if (below != null)
		{
			text.add(below, BorderLayout.SOUTH);
		}
		return text;
	}

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
		return s.append(plural(t.members, "member")).toString();
	}

	static Icon swatchIcon(String hex, int size)
	{
		Color c = teamColor(hex, MUTED);
		return new Icon()
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
