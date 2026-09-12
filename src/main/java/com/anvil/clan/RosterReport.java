package com.anvil.clan;

import com.anvil.AnvilConfig;
import com.anvil.api.dto.ClanChange;
import com.anvil.api.dto.ClanSyncResponse;
import com.anvil.util.AnvilChat;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * What a roster sync says afterwards, and to whom.
 *
 * <h2>Two different audiences</h2>
 *
 * <p>A sync the admin PRESSED reports in chat, because they are looking at it and want to know it
 * worked. A sync that ran automatically at login says nothing at all unless something actually
 * changed — an automatic push that announced itself every login would be worse than the roster drift
 * it exists to prevent.</p>
 *
 * <p>Per-member changes are named rather than counted where there are few of them: "3 changes" tells
 * an admin nothing they can act on, while "Kayle joined, Nisbro left" is the whole point of having
 * run it.</p>
 */
@Slf4j
@Singleton
public class RosterReport
{
    private final AnvilConfig config;
    private final AnvilChat chat;

    /**
     * Has an automatic sync already reported itself this login?
     *
     * <p>The first of a session always speaks, even to say nothing moved: that line is how you know
     * the plugin is talking to your site at all, and its absence is what made a working sync look
     * broken. Every one after it speaks only for news.</p>
     */
    private volatile boolean reportedThisLogin;

    /** One "it ran and agreed with the site" line per session, and only once. */
    public void onLogout() {
        reportedThisLogin = false;
    }

    @Inject
    RosterReport(AnvilConfig config, AnvilChat chat) {
        this.config = config;
        this.chat = chat;
    }

	/**
	 * A sync nobody asked for reports itself only when the roster actually MOVED — except for the
	 * first of a login, which speaks either way so you know the plugin is talking to your site.
	 */
	public void reportAutomatic(ClanSyncResponse r)
	{
		List<String> parts = new ArrayList<>();
		if (r.added > 0)
		{
			parts.add(r.added + " joined");
		}
		if (r.returned > 0)
		{
			parts.add(r.returned + " returned");
		}
		if (r.markedLeft > 0)
		{
			parts.add(r.markedLeft + " left");
		}
		if (r.renamed > 0)
		{
			parts.add(r.renamed + " renamed");
		}
		boolean moved = !parts.isEmpty();
		boolean firstThisLogin = !reportedThisLogin;
		reportedThisLogin = true;
		if (moved)
		{
			chat.send("Clan roster updated: " + String.join(", ", parts) + ".");
		}
		else if (firstThisLogin)
		{
			chat.send("Clan roster checked — nothing changed.");
		}
	}

	/**
	 * One chat line per member change, capped so a busy sync doesn't flood the chatbox. Only for a
	 * sync somebody asked for: the automatic one has said its piece.
	 */
	public void reportPerMemberChanges(ClanSyncResponse r)
	{
		if (r.changes == null || r.changes.isEmpty())
		{
			return;
		}
		int cap = 12;
		int shown = 0;
		for (ClanChange ch : r.changes)
		{
			if (shown >= cap)
			{
				break;
			}
			String line = changeLine(ch);
			if (line == null)
			{
				continue;
			}
			chat.send(line);
			shown++;
		}
		if (r.changes.size() > cap)
		{
			chat.send("...and " + (r.changes.size() - cap) + " more changes (see Discord audit feed).");
		}
	}

	/** One member change as a sentence, or null for a kind we have nothing to say about. */
	private static String changeLine(ClanChange ch)
	{
		switch (ch.type == null ? "" : ch.type)
		{
			case "joined":
				return ch.rsn + " joined the clan.";
			case "left":
				return ch.rsn + " left the clan.";
			case "returned":
				return ch.rsn + " returned to the clan.";
			case "renamed":
				return (ch.oldRsn == null ? "?" : ch.oldRsn) + " is now known as " + ch.rsn + ".";
			case "rank_changed":
				return ch.rsn + " is now " + (ch.newRank == null ? "ranked" : ch.newRank)
					+ (ch.oldRank != null ? " (was " + ch.oldRank + ")" : "") + ".";
			default:
				return null;
		}
	}

	/**
	 * Plan limit. The admin running the sync is the one person who can act on this and they are right
	 * here, so say it in-game rather than leaving it to a banner they would have to open the site to
	 * see. Names first, because "6 members were not added" is only useful if you know WHICH six.
	 */
	public void reportPlanLimit(ClanSyncResponse r)
	{
		if (r.refusedNewMembers != null && !r.refusedNewMembers.isEmpty())
		{
			int cap = 6;
			String names = String.join(", ",
				r.refusedNewMembers.subList(0, Math.min(cap, r.refusedNewMembers.size())));
			String more = r.refusedNewMembers.size() > cap
				? " and " + (r.refusedNewMembers.size() - cap) + " more"
				: "";
			chat.send("Not added (plan limit): " + names + more + ".");
		}
		// Whatever the server wants to say about the cap, in its own words.
		if (r.capNotice != null && !r.capNotice.isEmpty())
		{
			chat.send(r.capNotice);
		}
	}
}
