package com.anvil.ui;

import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import com.anvil.api.dto.ActivityItem;
import com.anvil.clog.model.Kind;
import com.anvil.clog.model.TaskRow;
import com.google.gson.Gson;
import java.util.Map;

/**
 * One entry in the always-on sidebar's live team feed — the plugin-side mirror of the Site's
 * {@code /api/plugin/activity} JSON (see {@code Anvil.Site/src/lib/pluginActivity.ts}). RuneLite-free
 * and immutable, in the value-object style of {@link TaskRow} / {@link ConnectionView}.
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
		if (kind == Kind.REVEAL)
		{
			return "New tile revealed: " + tileLabel;
		}
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

	/** The feed as the site sends it, in the shape the panel draws. Nulls are skipped, not guessed. */
	public static List<ActivityEntry> toEntries(List<ActivityItem> items)
	{
		if (items == null || items.isEmpty())
		{
			return Collections.emptyList();
		}
		List<ActivityEntry> out = new ArrayList<>(items.size());
		for (ActivityItem it : items)
		{
			if (it == null)
			{
				continue;
			}
			out.add(new ActivityEntry(it.id, it.ts, it.player, it.tileId, it.tileLabel,
				ActivityEntry.Kind.fromWire(it.kind), it.amount, it.isSelf));
		}
		return out;
	}
}
