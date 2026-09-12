package com.anvil.ui.view;

import com.anvil.ui.ConnectionView;
import com.anvil.util.Lists;
import java.util.List;
import java.util.Locale;

/**
 * One live weekly competition (Skill / Boss of the Week) as the sidebar shows it: the comp itself,
 * the caller's standing, and the head of the leaderboard. Every display string is derived here so
 * the panel just renders — and so the shaping is unit-testable without Swing.
 */
public final class WeeklyView
{
	public final int id;
	public final String title;
	/** {@code "skill"} | {@code "boss"} — picks the kind label and the gain's unit. */
	public final String type;
	/** Raw metric key as the site stores it ({@code "mining"}, {@code "chambers_of_xeric"}). */
	public final String metric;
	/** What the site calls that metric, when it sends one. Read through {@link #metricLabel()}. */
	private final String sentLabel;
	public final String startDate;
	public final String endDate;
	/** True for a comp that hasn't started yet — it has no standings, just a start time. */
	public final boolean upcoming;
	/** Caller's rank, or 0 when they aren't on the board (not enrolled / no gain yet / unknown). */
	public final int yourRank;
	public final long yourGained;
	/** Ranked participants, or 0 when the standings couldn't be read. */
	public final int participants;
	/** Head of the leaderboard, rank order, capped by the source. Never {@code null}. */
	public final List<Standing> top;
	/** The comp's page on the Anvil site ({@code <baseUrl>/weekly/<id>}), or {@code null} when unknown. */
	public final String url;

	public WeeklyView(int id, String title, String type, String metric, String startDate, String endDate,
		int yourRank, long yourGained, int participants, List<Standing> top, String url)
	{
		this(id, title, type, metric, startDate, endDate, false, yourRank, yourGained, participants, top, url);
	}

	public WeeklyView(int id, String title, String type, String metric, String startDate, String endDate,
		boolean upcoming, int yourRank, long yourGained, int participants, List<Standing> top, String url)
	{
		this(id, title, type, metric, null, startDate, endDate, upcoming, yourRank, yourGained,
			participants, top, url);
	}

	public WeeklyView(int id, String title, String type, String metric, String sentLabel, String startDate,
		String endDate, boolean upcoming, int yourRank, long yourGained, int participants,
		List<Standing> top, String url)
	{
		this.sentLabel = sentLabel;
		this.id = id;
		this.title = title == null || title.isEmpty() ? kindLabel(type) : title;
		this.type = type == null ? "" : type;
		this.metric = metric == null ? "" : metric;
		this.startDate = startDate;
		this.endDate = endDate;
		this.upcoming = upcoming;
		this.yourRank = Math.max(0, yourRank);
		this.yourGained = Math.max(0, yourGained);
		this.participants = Math.max(0, participants);
		this.top = Lists.copyOrEmpty(top);
		this.url = url;
	}

	/** True for an EHP/EHB comp — ranked by efficient hours, not one skill's XP or one boss's KC. */
	public boolean isEfficiency()
	{
		return "efficiency".equalsIgnoreCase(type);
	}

	/**
	 * "Skill of the Week" / "Boss of the Week" / "Efficiency of the Week" — the card's kind line and
	 * the list row's subtitle. Public so the callers that hold only a raw type string (the sidebar's
	 * leaderboard + schedule, the login greeting) name a comp the same way this card does.
	 */
	public String kindLabel()
	{
		return kindLabel(type);
	}

	public static String kindLabel(String type)
	{
		if ("skill".equalsIgnoreCase(type))
		{
			return "Skill of the Week";
		}
		// Efficiency comps are a THIRD type, not a fallback: matching only "skill" and letting
		// everything else read as boss labelled every EHP/EHB week "Boss of the Week".
		return "efficiency".equalsIgnoreCase(type) ? "Efficiency of the Week" : "Boss of the Week";
	}

	/** The tracked metric, spelled for a person: {@code "phosanisNightmare"} → "Phosani's Nightmare". */
	public String metricLabel()
	{
		return metricLabel(type, metric, sentLabel);
	}

	/**
	 * The site's own name for the metric, falling back to what the key can be made to look like.
	 *
	 * <p>Prefer what was sent, always. Boss keys are hiscores keys, and no amount of splitting
	 * "phosanisNightmare" recovers the apostrophe in "Phosani's Nightmare" or turns
	 * "chambersOfXericChallengeMode" into the clan's "CoX: CM". The fallback exists for sites
	 * older than the field, not as an equal option.
	 */
	public static String metricLabel(String type, String metric, String sentLabel)
	{
		if (sentLabel != null && !sentLabel.trim().isEmpty())
		{
			return sentLabel.trim();
		}
		return metricLabel(type, metric);
	}

	public static String metricLabel(String type, String metric)
	{
		// "ehp"/"ehb" are initialisms — humanise would title-case them to "Ehp".
		return "efficiency".equalsIgnoreCase(type)
			? (metric == null ? "" : metric.toUpperCase(Locale.ROOT))
			: humanise(metric);
	}

	/** What a gain counts in: XP for a skill comp, kills for a boss one, hours for EHP/EHB. */
	public String unitNoun()
	{
		return unitNoun(type);
	}

	public static String unitNoun(String type)
	{
		if ("efficiency".equalsIgnoreCase(type))
		{
			return "hrs";
		}
		return "skill".equalsIgnoreCase(type) ? "xp" : "kc";
	}

	/** A gain as the UI shows it — "1.2M xp", "184 kc", "12.40 hrs". */
	public String formatGain(long gained)
	{
		return formatGain(type, gained);
	}

	public static String formatGain(String type, long gained)
	{
		long safe = Math.max(0, gained);
		return formatGainValue(type, safe) + " " + unitNoun(type);
	}

	/** The number alone, for callers that place the unit themselves. */
	public static String formatGainValue(String type, long gained)
	{
		long safe = Math.max(0, gained);
		// Efficiency comps travel in MILLI-hours (the site's ConnectionView.EFFICIENCY_SCALE): the weekly's
		// integer columns would round 12.4 EHB down to 12 and throw away most of a week's gain.
		// Rendered raw that arrives as "+12,400 kc".
		return "efficiency".equalsIgnoreCase(type)
			? String.format(Locale.ROOT, "%.2f", safe / ConnectionView.EFFICIENCY_SCALE)
			: ConnectionView.formatCount(safe);
	}

	/** Underscored/hyphenated metric keys → title case, keeping the small joining words lowercase. */
	static String humanise(String key)
	{
		if (key == null || key.isEmpty())
		{
			return "";
		}
		// Boss keys arrive camel-cased ("phosanisNightmare"), so a split on separators alone left
		// them as one word and title-case made it "PhosanisNightmare" — which is what the in-game
		// banner printed. A word boundary is also lower→upper (and letter→digit, for "theatreOfBlood2").
		String spaced = key.replace('_', ' ').replace('-', ' ')
			.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
			.replaceAll("(?<=[A-Za-z])(?=[0-9])", " ");
		String[] words = spaced.trim().split("\\s+");
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < words.length; i++)
		{
			String w = words[i];
			if (w.isEmpty())
			{
				continue;
			}
			if (out.length() > 0)
			{
				out.append(' ');
			}
			// Case-insensitively, because the camel split hands these over capitalised ("Of").
			boolean small = i > 0 && ("of".equalsIgnoreCase(w) || "the".equalsIgnoreCase(w)
				|| "and".equalsIgnoreCase(w) || "at".equalsIgnoreCase(w));
			out.append(small ? w.toLowerCase(Locale.ROOT)
				: Character.toUpperCase(w.charAt(0)) + w.substring(1));
		}
		return out.toString();
	}
}
