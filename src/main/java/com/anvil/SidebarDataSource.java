package com.anvil;

/**
 * The Guice key the always-on progress sidebar ({@link AnvilSidebarPanel}) is wired through, bound to
 * {@link AnvilSidebarDataSource} — a view over the config the plugin already polls, and the only
 * implementation. It holds the row types the plugin and the panel share.
 */
public interface SidebarDataSource
{
	// ── The logged-in character: whose it's tracked as, and the way out ──────────────────────────
	//
	// The Account Token names whoever set this RuneLite up, not whoever is playing. Every character
	// links to it by default (the one-click path); siblings and visitors use this row to say "not
	// mine", and to claim back a character that's linked to somebody else.

	/** One line about the logged-in character. */
	final class AccountRow
	{
		public enum State
		{
			/** Linked to (or linking to) the signed-in Anvil account. Offers Not my account. */
			TRACKING,
			/** Marked Not my account on this computer; nothing is sent. Offers Track it again. */
			PAUSED,
			/** Claimed by another Anvil account; nothing tracks for this token. Offers It's mine. */
			OTHER,
		}

		public enum Action
		{
			NOT_MINE,
			TRACK_AGAIN,
			DISPUTE,
		}

		public final String rsn;
		public final State state;

		public AccountRow(String rsn, State state)
		{
			this.rsn = rsn;
			this.state = state;
		}
	}

	/**
	 * What the panel may DO for the addressed clan.
	 *
	 * <p>A roster sync scrapes the clan channel you are standing in, so it needs an admin with that
	 * channel readable. A profile sync goes to the site the plugin is authenticated against.
	 */
	class PanelActions
	{
		public static final PanelActions NONE = new PanelActions(false, false, null);

		/** Show "Sync clan roster": admin, at home, with a readable clan channel. */
		public final boolean canSyncRoster;
		/** Show "Sync profile": the site takes profile data and it's the one we're signed in to. */
		public final boolean canSyncProfile;
		/** Why the roster button is absent, when the reason is worth saying. Null when it isn't. */
		public final String rosterNote;

		public PanelActions(boolean canSyncRoster, boolean canSyncProfile, String rosterNote)
		{
			this.canSyncRoster = canSyncRoster;
			this.canSyncProfile = canSyncProfile;
			this.rosterNote = rosterNote;
		}
	}
}
