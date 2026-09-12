package com.anvil.api.dto;

import com.anvil.detect.StartProofRules;

public class StartProof
{
	/** Does this event ask for a starting shot at all? */
	public boolean required;
	/** Has the location been drawn (i.e. has the event gone live)? Nothing exists before this. */
	public boolean drawn;
	/** Where the player must be standing. Null until the draw. */
	public String location;
	/**
	 * The same place as game coordinates, when the host pinned it on the site's map. Null when
	 * they only named it (and on sites that predate the pin), in which case position isn't
	 * checked at all — see {@link StartProofRules}.
	 */
	public Spot spot;
	/**
	 * How long this game session may have been running when the shot is taken, in minutes.
	 * 0 = the host isn't asking. The point is the LOGOUT before it: hiscores only flush then,
	 * so a session older than the event start means a stale baseline for every stat tile.
	 */
	public int maxSessionMinutes;
	/** This player's own keyword, derived server-side from the draw stamp. Null until the draw. */
	public String keyword;
	/** True while this player still owes a shot (never filed one, or theirs was rejected). */
	public boolean needsUpload;
	/**
	 * Is a shot still being ASKED for? The site stops asking six hours after the start: OSRS
	 * force-logs everyone by then, so nobody is still sitting on the stack the shot exists to
	 * catch. `needsUpload` already folds this in — this is here so the panel can say how long
	 * is left rather than having the card vanish without explanation.
	 */
	public boolean windowOpen;
	/** ISO instant when the ask lapses. Null before the draw, and on sites too old to send it. */
	public String windowEndsAt;
	/** "pending" | "accepted" | "rejected", or null when nothing is on file. */
	public String status;
	public String imageUrl;

	/** The drawn spot on the world map: where, and how many squares from it still counts. */
	public static class Spot
	{
		public int x;
		public int y;
		public int radius;
	}
}
