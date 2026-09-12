package com.anvil.ui.view;

import com.anvil.api.dto.Decay;
import com.anvil.api.dto.Mission;
import com.anvil.detect.LadderMissions;
import com.anvil.util.Lists;
import java.util.List;

/**
 * The ladder missions-board view-model for the sidebar card: the countdown target, the caller's
 * rank (this month + all-time), and the currently-open missions. Display strings (live value,
 * m:ss countdown) are computed at tick time from these raw values via {@link LadderMissions}.
 */
public final class Ladder
{
	/** ISO time of the next reveal — the per-second countdown target. Null when none is scheduled. */
	public final String nextRevealAtIso;
	public final int monthRank;      // caller's rank this month; 0 when unranked
	public final long monthPoints;   // caller's points this month
	public final int allTimeRank;    // caller's all-time rank; 0 when unranked
	/** The points ramp (may be null) — lets each mission show a live grow/decay value. */
	public final Decay decay;
	/** Currently-open missions (revealed, not yet claimed/expired), board order. Never null. */
	public final List<Mission> missions;
	/**
	 * True for a real ladder event, false for an ordinary bingo that merely carries missions.
	 *
	 * Both surface a mission strip — a bingo can drop hidden missions mid-event too, and those
	 * deserve the same countdown and live values rather than being left to show up as "New tile
	 * revealed" lines in the activity feed. Only a ladder REPLACES the board summary with this
	 * card and carries a personal rank; a bingo keeps its board summary and gets the strip
	 * underneath.
	 */
	public final boolean ladderFormat;

	public Ladder(String nextRevealAtIso, int monthRank, long monthPoints, int allTimeRank,
		Decay decay, List<Mission> missions, boolean ladderFormat)
	{
		this.nextRevealAtIso = nextRevealAtIso;
		this.monthRank = Math.max(0, monthRank);
		this.monthPoints = Math.max(0, monthPoints);
		this.allTimeRank = Math.max(0, allTimeRank);
		this.decay = decay;
		this.missions = Lists.copyOrEmpty(missions);
		this.ladderFormat = ladderFormat;
	}

	/** One open mission: its label, face value, and reveal time (for the live grow/decay value). */
	public static final class Mission
	{
		public final int tileId;
		public final String label;
		public final int face;
		public final String revealedAtIso;

		public Mission(int tileId, String label, int face, String revealedAtIso)
		{
			this.tileId = tileId;
			this.label = label == null ? "" : label;
			this.face = Math.max(0, face);
			this.revealedAtIso = revealedAtIso;
		}
	}
}
