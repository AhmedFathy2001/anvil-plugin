package com.anvil.track;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.notify.AnvilEmbeds;
import com.anvil.notify.MomentsService;
import com.anvil.notify.RareDropNotifier;
import com.anvil.util.ClipMoments;
import com.anvil.util.CombatTarget;
import com.anvil.util.DeathAttribution;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.Player;

/**
 * Who is fighting whom, and who died.
 *
 * <p>Three client-thread events, kept together because they are one story told in order: something
 * targets us, something hits us, something dies. Each handler is a reference check and a field
 * write — encoding and network sends happen off-thread, elsewhere.</p>
 *
 * <h2>Why damage is tracked at all</h2>
 *
 * <p>A hitsplat says how much and what type, never who. Keeping the set of things currently on us is
 * the only attribution the client offers for incoming damage, and it is what lets a death name the
 * thing that killed it rather than the thing it was killing. In the other direction, damage WE deal
 * to a player is what credits a PvP kill: loot-key kills produce no reliable chat or loot signal —
 * the kill message is a random taunt and PlayerLootReceived never fires, since the loot goes into a
 * key rather than onto the ground — so damage-then-death is the signal.</p>
 */
@Singleton
public class CombatRouter
{
	private final Client client;
	private final AnvilConfig config;
	private final BingoApiClient apiClient;
	private final PvpTracker pvp;
	private final PartyTracker party;
	private final TimedClearTracker timed;
	private final LmsTracker lms;
	private final RecapCounters counters;
	private final MomentsService moments;
	private final RareDropNotifier rareDrops;
	private final AnvilEmbeds embeds;
	private final ClipMoments clipMoments;
	private final CombatTarget combatTarget;

	@Inject
	CombatRouter(Client client, AnvilConfig config, BingoApiClient apiClient, PvpTracker pvp,
		PartyTracker party, TimedClearTracker timed, LmsTracker lms, RecapCounters counters,
		MomentsService moments, RareDropNotifier rareDrops, AnvilEmbeds embeds,
		ClipMoments clipMoments, CombatTarget combatTarget)
	{
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.pvp = pvp;
		this.party = party;
		this.timed = timed;
		this.lms = lms;
		this.counters = counters;
		this.moments = moments;
		this.rareDrops = rareDrops;
		this.embeds = embeds;
		this.clipMoments = clipMoments;
		this.combatTarget = combatTarget;
	}

	/**
	 * Two things the plugin owns and this cannot be injected: who is logged in, and the one
	 * DeathAttribution instance the session lifecycle also clears on logout.
	 */
	public void bind(Supplier<String> localPlayerName, DeathAttribution deathAttribution)
	{
		this.localPlayerName = localPlayerName;
		this.deathAttribution = deathAttribution;
	}

	private Supplier<String> localPlayerName = () -> null;
	private DeathAttribution deathAttribution = new DeathAttribution();

	/** Something acquired or dropped us as its target. */
	public void onInteractingChanged(Actor source, Actor target)
	{
		if (source == null || source == client.getLocalPlayer())
		{
			return; // our own target changing is the other question entirely
		}
		String name = source.getName();
		if (name == null || name.isEmpty())
		{
			return;
		}
		if (target == client.getLocalPlayer())
		{
			deathAttribution.targetedUs(name, System.currentTimeMillis());
		}
		else
		{
			deathAttribution.stoppedTargetingUs(name);
		}
	}

	public void onHitsplatApplied(Actor hitActor, Hitsplat ourHit)
	{
		// Damage TAKEN — the clock a death is attributed against. Anything not ours landing on us
		// counts, including a mechanic with nobody behind it: the point is when we were last hurt,
		// and whether anything had us targeted at the time is decided at death.
		if (ourHit != null && ourHit.isOthers() && ourHit.getAmount() > 0
			&& hitActor == client.getLocalPlayer())
		{
			deathAttribution.tookDamage(System.currentTimeMillis());
		}
		// Biggest hit of the event — a recap superlative, so it counts every hit we land on anything,
		// player or NPC, and is independent of the PvP gates below. One int compare per hitsplat.
		if (ourHit != null && ourHit.isMine() && ourHit.getAmount() > 0)
		{
			counters.recordEventHit(ourHit.getAmount());
			// Remember WHAT we're fighting, so a clip of a fight that didn't end in a kill still has
			// something true to say. Most clips are of the fight, not the loot — a wipe, a lucky
			// spec, a tick-perfect prayer — and none of those fire any of the events a clip moment
			// is normally built from. One field write per landed hit.
			if (hitActor != null && hitActor != client.getLocalPlayer())
			{
				String tname = hitActor.getName();
				if (tname != null && !tname.isEmpty())
				{
					combatTarget.note(tname);
				}
			}
		}

		// Track damage WE deal to other players so a subsequent death can be attributed to us —
		// this drives BOTH the PvP kill notification AND PvP-kill tile credit. Cheap: a couple of
		// reference checks on the client thread.
		if (!config.notifyPvpKills() && !pvp.boardHasPvpTiles() && !pvp.pvpCounterActive())
		{
			return;
		}
		if (ourHit == null || !ourHit.isMine())
		{
			return;
		}
		if (!(hitActor instanceof Player) || hitActor == client.getLocalPlayer())
		{
			return;
		}
		String name = hitActor.getName();
		if (name == null || name.isEmpty())
		{
			return;
		}
		pvp.noteDamagedPlayer(name.toLowerCase());
	}

	/**
	 * Something died.
	 *
	 * <p>Runs on the client thread — keep this cheap: a reference check and (optionally) a frame
	 * request. Encoding and the network send happen off-thread.</p>
	 */
	public void onActorDeath(Actor actor)
	{
		// Remember the last NPC that died near us so a timed "Duration:" line can attribute itself
		// to the boss we just killed without a hardcoded activity table.
		if (actor instanceof NPC)
		{
			String npcName = actor.getName();
			if (npcName != null && !npcName.isEmpty())
			{
				timed.noteNpcDeath(npcName);
			}
		}

		// Deathless raids: any player dying while we're inside an instance counts against the
		// current run (raid instances are private, so any player here is a party member).
		if (actor instanceof Player && party.inInstance())
		{
			party.noteDeathInInstance();
		}

		if (actor == client.getLocalPlayer())
		{
			onOurOwnDeath();
			return;
		}

		// A player we damaged dying → our PvP kill. ActorDeath fires on the tick the death animation
		// starts (target at 0 HP) — the moment we want the screenshot, and a reliable signal even for
		// loot-key kills (which produce no ground loot and only a random taunt message). Damage
		// attribution (a hitsplat we dealt within the window) says the kill is ours; the Player gives
		// the victim name for the roster / RSN-bounty match. Consumed once so a single death credits
		// once. Caveat: if two attackers both damaged the victim, both credit — the baked screenshot
		// is the audit trail.
		if (actor instanceof Player)
		{
			String vname = actor.getName();
			if (vname != null && !vname.isEmpty() && pvp.claimKill(vname.toLowerCase()))
			{
				creditPvpKill(vname);
			}
		}
	}

	private void onOurOwnDeath()
	{
		// LMS: dying while the BR HUD reads N survivors means we placed Nth. Record before the
		// notification gates — placement tracking is independent of death notifications.
		lms.recordDeathPlacement();
		// Recap counter — count the death for the "Wipe Magnet" superlative even if death
		// notifications are off (still gated by auto-submit + an active event inside).
		counters.recordEventDeath();
		// Clan feed — WHAT killed us, which is the half the recap counter throws away. Dying to the
		// boss everyone is racing that week is the story; dying in general is a number. Recorded here
		// rather than beside the post below, so the drops channel being off can't erase it.
		moments.recordDeathMoment();
		clipMoments.record("💀 Died");
		if (!config.notifyDeaths() || !embeds.notifyEnabled("deaths"))
		{
			return;
		}
		String message = rareDrops.buildDeathMessage(localPlayerName.get());
		embeds.captureFrameAsync(png ->
			apiClient.postNotification("deaths", message, null, png, "anvil-death.png"));
	}

	private void creditPvpKill(String victim)
	{
		// Recap counter first: ANY dangerous-PvP kill feeds the PKer superlative, pvp tiles on the
		// board or not. Tile credit + notify keep their own gates.
		if (pvp.inDangerousPvp())
		{
			counters.recordEventPvpKill();
		}
		// Clip trail gets the same treatment for the same reason: the kill is what the clip CAUGHT,
		// whether or not the clan broadcasts PKs and whether or not the board has a pvp tile.
		// Recording it inside notifyPvpKill (where it used to live) meant a player with that channel
		// off saved clips captioned "Clipped during <event>" — describing nothing.
		clipMoments.record("⚔️ Killed " + victim);
		pvp.creditPvpKillTiles(victim);
		if (config.notifyPvpKills())
		{
			pvp.notifyPvpKill(victim);
		}
	}
}
