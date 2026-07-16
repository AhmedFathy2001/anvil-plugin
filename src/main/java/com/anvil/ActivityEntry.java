package com.anvil;

/**
 * One entry in the always-on sidebar's live team feed — the plugin-side mirror of the Site's
 * {@code /api/plugin/activity} JSON (see {@code Anvil.Site/src/lib/pluginActivity.ts}). RuneLite-free
 * and immutable, in the value-object style of {@link ClogTaskModel.TaskRow} / {@link ConnectionView}.
 */
public final class ActivityEntry
{
	/** Kind of feed event. Mirrors the server's {@code "progress" | "complete"}. */
	public enum Kind
	{
		PROGRESS, COMPLETE;

		/** Map the lowercase wire value to a {@link Kind}; unknown/blank → {@link #PROGRESS} (Gson bypasses this). */
		public static Kind fromWire(String s)
		{
			return "complete".equalsIgnoreCase(s == null ? null : s.trim()) ? COMPLETE : PROGRESS;
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

	public ActivityEntry(String id, String ts, String player, int tileId, String tileLabel,
		Kind kind, int amount, boolean self)
	{
		this.id = id == null ? "" : id;
		this.ts = ts == null ? "" : ts;
		this.player = player;
		this.tileId = tileId;
		this.tileLabel = tileLabel == null ? "" : tileLabel;
		this.kind = kind == null ? Kind.PROGRESS : kind;
		this.amount = amount;
		this.self = self;
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
		String who = self ? "You" : (player == null || player.isEmpty() ? null : player);
		if (kind == Kind.COMPLETE)
		{
			return (who == null ? "Team" : who) + " completed " + tileLabel;
		}
		String amt = amount > 0 ? "+" + amount : "+1";
		if (who == null)
		{
			return tileLabel + " " + amt; // teammate partial with no attribution — lead with the tile
		}
		return who + " " + amt + " · " + tileLabel;
	}
}
