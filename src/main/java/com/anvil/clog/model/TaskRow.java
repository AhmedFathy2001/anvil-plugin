package com.anvil.clog.model;

import java.util.Set;

/**
 * A single renderable task. Immutable. {@code itemId < 0} means "no item icon" (stat tiles);
 * {@code points} is the tile's Leagues-style reward value (0 when the event isn't points-scored
 * or the value isn't known to the plugin yet).
 */
public final class TaskRow
{
	public final int tileId;
	public final String label;
	public final Type type;
	public final Kind kind;
	public final int current;
	public final int goal;
	public final int itemId;
	public final int points;
	public final String description;
	public final String category; // free-text grouping (boss/skill); "" = uncategorised
	public final String skillName; // hiscores skill for SKILL tiles ("mining"); null otherwise
	// Pre-formatted "current/goal" for tiles whose progress isn't a plain count — a cumulative
	// value tile is measured in gp, and "12500000/50000000" is a number nobody reads. Null on
	// every other kind, which means the renderer prints current/goal itself.
	public final String progressText;
	public final Status status;
	// Board position — the within-status-group sort key, so the in-game list mirrors the
	// site's tile order (difficulty sort, shuffle). Set after construction (0 on old
	// servers, where the sort falls through to the label tiebreak — the old behavior).
	public int position;

	public TaskRow(int tileId, String label, Type type, int current, int goal, int itemId)
	{
		this(tileId, label, type, current, goal, itemId, 0, null, null);
	}

	public TaskRow(int tileId, String label, Type type, int current, int goal, int itemId, int points)
	{
		this(tileId, label, type, current, goal, itemId, points, null, null);
	}

	public TaskRow(int tileId, String label, Type type, int current, int goal, int itemId, int points,
		String description)
	{
		this(tileId, label, type, current, goal, itemId, points, description, null);
	}

	public TaskRow(int tileId, String label, Type type, int current, int goal, int itemId, int points,
		String description, String category)
	{
		this(tileId, label, type, current, goal, itemId, points, description, category, false);
	}

	public TaskRow(int tileId, String label, Type type, int current, int goal, int itemId, int points,
		String description, String category, boolean forceCompleted)
	{
		// Legacy entry point (board previews / tests): infer the fine-grained kind from Type.
		this(tileId, label, kindOf(type), current, goal, itemId, points, description, category, forceCompleted);
	}

	public TaskRow(int tileId, String label, Kind kind, int current, int goal, int itemId, int points,
		String description, String category, boolean forceCompleted)
	{
		this(tileId, label, kind, current, goal, itemId, points, description, category, forceCompleted, null);
	}

	public TaskRow(int tileId, String label, Kind kind, int current, int goal, int itemId, int points,
		String description, String category, boolean forceCompleted, String skillName)
	{
		this(tileId, label, kind, current, goal, itemId, points, description, category, forceCompleted,
			skillName, null);
	}

	/** Canonical constructor — callers that know the precise {@link Kind} (e.g. {@link #build}) use this. */
	public TaskRow(int tileId, String label, Kind kind, int current, int goal, int itemId, int points,
		String description, String category, boolean forceCompleted, String skillName, String progressText)
	{
		this.progressText = progressText;
		this.tileId = tileId;
		this.label = label == null ? "" : label;
		this.kind = kind == null ? Kind.STANDARD : kind;
		this.type = typeOf(this.kind);
		this.current = current;
		this.goal = goal;
		this.itemId = itemId;
		this.points = points;
		this.description = description == null ? "" : description;
		this.category = category == null ? "" : category.trim();
		this.skillName = skillName;
		// A team-level completion (any member / manual) is authoritative even when this client's
		// own current < goal — e.g. an individual-mode tile a teammate finished first.
		this.status = forceCompleted ? Status.COMPLETED : statusOf(current, goal);
	}

	public boolean isCompleted()
	{
		return status == Status.COMPLETED;
	}

	/** The coarse {@link Type} implied by a {@link Kind} — DROP-family kinds show an item icon. */
	static Type typeOf(Kind kind)
	{
		return (kind == Kind.DROP || kind == Kind.COLLECTION || kind == Kind.VALUE || kind == Kind.GAIN) ? Type.DROP : Type.STAT;
	}

	/** Default {@link Kind} for the legacy constructors (board previews / tests) that only know Type. */
	static Kind kindOf(Type type)
	{
		return type == Type.DROP ? Kind.DROP : Kind.SKILL;
	}

	/**
	 * Derive completion status from progress. A goal of {@code <= 0} (an untargeted tile)
	 * counts as completed the moment there's any progress, otherwise not-started.
	 */
	public static Status statusOf(int current, int goal)
	{
		if (goal > 0)
		{
			if (current >= goal)
			{
				return Status.COMPLETED;
			}
			return current > 0 ? Status.IN_PROGRESS : Status.NOT_STARTED;
		}
		return current > 0 ? Status.COMPLETED : Status.NOT_STARTED;
	}
}
