package com.anvil;

import java.util.*;

/**
 * One entry in the always-on sidebar's live team feed — the plugin-side mirror of the Site's
 * {@code /api/plugin/activity} JSON (see {@code Anvil.Site/src/lib/pluginActivity.ts}). RuneLite-free
 * and immutable, in the value-object style of {@link ClogTaskModel.TaskRow} / {@link ConnectionView}.
 */
public final class ActivityEntry
{
	/** Kind of feed event. Mirrors the server's {@code "progress" | "complete" | "reveal"}. */
	public enum Kind
	{
		PROGRESS, COMPLETE, REVEAL;

		/** Map the lowercase wire value to a {@link Kind}; unknown/blank → {@link #PROGRESS} (Gson bypasses this). */
		public static Kind fromWire(String s)
		{
			String v = s == null ? "" : s.trim();
			if ("complete".equalsIgnoreCase(v))
			{
				return COMPLETE;
			}
			// Reveal-policy events (showdown / lucky draw / bounty): a hidden tile just went live.
			if ("reveal".equalsIgnoreCase(v))
			{
				return REVEAL;
			}
			return PROGRESS;
		}
	}

	/** Namespaced id ({@code s<submissionId>}/{@code c<completionId>}) — globally unique, the dedup key. */
	public final String id;

	/** ISO timestamp of the underlying event (submission created / tile completed). */
	public final String ts;

	/** Crediting RSN, or {@code null} for an unattributed (stat/manual) completion. */
	public final String player;

	public final int tileId;
	public final String tileLabel;
	public final Kind kind;

	/** Units credited by this event (drops/kills/gains); 0 for a completion. */
	public final int amount;

	/** True when the caller themselves credited this — the panel styles/labels its own actions ("You"). */
	public final boolean self;

	/**
	 * A completion's other contributors, biggest share first ({@link #player} excluded) — how a team
	 * KC/XP tile, which has no single finisher, still names who did it. Empty otherwise / older sites.
	 */
	public final List<String> participants;

	public ActivityEntry(String id, String ts, String player, int tileId, String tileLabel,
		Kind kind, int amount, boolean self)
	{
		this(id, ts, player, tileId, tileLabel, kind, amount, self, null);
	}

	public ActivityEntry(String id, String ts, String player, int tileId, String tileLabel,
		Kind kind, int amount, boolean self, List<String> participants)
	{
		this.id = id == null ? "" : id;
		this.ts = ts == null ? "" : ts;
		this.player = player;
		this.tileId = tileId;
		this.tileLabel = tileLabel == null ? "" : tileLabel;
		this.kind = kind == null ? Kind.PROGRESS : kind;
		this.amount = amount;
		this.self = self;
		List<String> named = new ArrayList<>();
		if (participants != null)
		{
			for (String p : participants)
			{
				if (p != null && !p.isEmpty() && !p.equals(player))
				{
					named.add(p);
				}
			}
		}
		this.participants = Collections.unmodifiableList(named);
	}

	public boolean isCompletion()
	{
		return kind == Kind.COMPLETE;
	}

	/**
	 * A short, member-facing one-line feed summary. Kept here (not the renderer) so it's unit-testable:
	 * {@code "Kayle completed …"}/{@code "Team completed …"} for completions, {@code "You +3 · …"} for progress.
	 */
	public String summary()
	{
		if (kind == Kind.REVEAL)
		{
			return "New tile revealed: " + tileLabel;
		}
		String who = self ? "You" : (player == null || player.isEmpty() ? null : player);
		if (kind == Kind.COMPLETE)
		{
			// Whoever finished it leads; with nobody credited (a team KC/XP tile), its biggest contributor.
			String lead = who != null ? who : participants.isEmpty() ? null : participants.get(0);
			int more = participants.size() - (who != null || participants.isEmpty() ? 0 : 1);
			if (lead == null)
			{
				return "Team completed " + tileLabel;
			}
			return lead + (more > 0 ? " & " + more + " more" : "") + " completed " + tileLabel;
		}
		String amt = amount > 0 ? "+" + amount : "+1";
		if (who == null)
		{
			return tileLabel + " " + amt; // teammate partial with no attribution — lead with the tile
		}
		return who + " " + amt + " · " + tileLabel;
	}

	/** {@link #summary()} plus, for a completion, everyone else who worked the tile — the hover text. */
	public String detail()
	{
		if (kind != Kind.COMPLETE || participants.isEmpty())
		{
			return summary();
		}
		boolean credited = self || (player != null && !player.isEmpty());
		String who = self ? "You" : credited ? player : participants.get(0);
		List<String> rest = credited ? participants : participants.subList(1, participants.size());
		return who + " completed " + tileLabel + (rest.isEmpty() ? "" : " — with " + String.join(", ", rest));
	}
}
