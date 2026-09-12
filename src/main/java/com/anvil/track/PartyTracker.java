package com.anvil.track;

import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.CoopFingerprint;
import com.anvil.api.dto.RosterEntry;
import com.anvil.util.Rsn;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.VarbitID;

/**
 * Who is in here with us, and whether anybody has died.
 *
 * <p>Party size gates a whole class of tile — "solo Chambers", "trio ToA" — and the client will not
 * simply tell you. Raid teams share the entry room, so every member renders at least once, and
 * counting distinct players seen since entering the instance is the closest thing to an answer.
 * ToA and ToB additionally publish per-slot party varbits, which are exact where they exist.</p>
 *
 * <p>Deaths are counted the same way and for the same reason: a deathless tile has to know whether
 * ANY member died, not just this one, and the only signal for that is watching players die in the
 * instance we are in.</p>
 *
 * <p>Counted per instance and reset on entry, because a previous raid's party is not this one's.</p>
 */
@Slf4j
@Singleton
public class PartyTracker
{
    private final Client client;

    private Supplier<PluginConfigResponse> pluginConfig = () -> null;
    private final javax.inject.Provider<PvpTracker> pvp;

    @Inject
    PartyTracker(Client client,
        javax.inject.Provider<PvpTracker> pvp) {
        this.client = client;
        this.pvp = pvp;
    }


    /** The party size we can actually vouch for: the exact varbit count when there is one, else seen. */
    public int observedSize() {
        return lastRaidPartySize > 0 ? lastRaidPartySize : instancePlayersSeen.size();
    }

    public int deathsThisInstance() {
        return instancePlayerDeaths;
    }

    public boolean inInstance() {
        return wasInInstance;
    }

    public void noteDeathInInstance() {
        instancePlayerDeaths++;
    }

    public void setRaidPartySize(int n) {
        lastRaidPartySize = n;
    }

    /** Entering an instance: a previous raid's party and deaths are not this one's. */
    /**
     * Am I in an instance, who else is in it, and how big is the raid party?
     *
     * <p>Deathless raids reset the party-death counter and roster on every instance ENTRY (CoX/ToB/
     * ToA runs are instanced, and each attempt is a fresh entry). While inside, the distinct players
     * seen are the party size for tiles that pin one.</p>
     *
     * <p>Raid party size comes from client varbits instead, each raid scoped by its own "am I in
     * this raid" signal so a stale value from a prior raid cannot bleed into another's gating. Only
     * one raid's varbits are ever read — you cannot be in two raids at once.</p>
     */
    public void onGameTick() {
        // Off the top-level view rather than the deprecated Client.getPlayers() — same players, and
        // it is the view we already asked whether we are inside an instance of.
        WorldView topView = client.getTopLevelWorldView();
        boolean inInstance = topView != null && topView.isInstance();
        if (inInstance && !inInstance()) {
            onInstanceEntered();
        }
        setInInstance(inInstance);
        if (inInstance) {
            for (Player p : topView.players()) {
                if (p != null && p.getName() != null) {
                    seePlayer(p.getName().toLowerCase());
                }
            }
        }
        setRaidPartySize(readRaidPartySize());
    }

    private int readRaidPartySize() {
        int raidParty = 0;
        if (client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL) > 0) {
            // ToA: scoped by a non-zero raid level. Count occupied party slots.
            for (int slot : TOA_PARTY_SLOTS) {
                if (client.getVarbitValue(slot) > 0) {
                    raidParty++;
                }
            }
        } else if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1) {
            // CoX: the client exposes the party size directly while inside the dungeon.
            raidParty = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE);
        } else if (client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS) > 0) {
            // ToB: scoped by an active party status. Count occupied party slots.
            for (int slot : TOB_PARTY_SLOTS) {
                if (client.getVarbitValue(slot) > 0) {
                    raidParty++;
                }
            }
        }
        return raidParty;
    }

    public void onInstanceEntered() {
        instancePlayerDeaths = 0;
        instancePlayersSeen.clear();
    }

    public void setInInstance(boolean in) {
        wasInInstance = in;
    }

    public void seePlayer(String name) {
        instancePlayersSeen.add(name);
    }

    // ---- Deathless-raid tiles --------------------------------------------------------------
    // Player deaths (anyone — raid instances are private, so any player is a party member)
    // observed since the local player last entered an instance. Consulted when a raid
    // completion line correlates to a deathless tile; reset on every instance entry.
    public int instancePlayerDeaths = 0;

    public boolean wasInInstance = false;

    // Distinct players seen in the current instance (party size for tiles that require one).
    // Raid teams share the entry room, so everyone renders at least once.
    public final Set<String> instancePlayersSeen = new HashSet<>();

    // Raids expose the real party roster in client varbits, which we read for party-size tile gates.
    // The scene headcount (instancePlayersSeen) is unreliable inside raids: raiders split across
    // separate rooms — and even when the whole team is co-located (e.g. the CoX Olm room),
    // client.getPlayers() may not return them — so it reads solo even in a group. ToA and ToB track
    // each occupied party slot in a run of per-slot varbits (count the non-empty ones); CoX exposes
    // the count directly.
    public static final int[] TOA_PARTY_SLOTS = {
            VarbitID.TOA_CLIENT_P0, VarbitID.TOA_CLIENT_P1, VarbitID.TOA_CLIENT_P2, VarbitID.TOA_CLIENT_P3,
            VarbitID.TOA_CLIENT_P4, VarbitID.TOA_CLIENT_P5, VarbitID.TOA_CLIENT_P6, VarbitID.TOA_CLIENT_P7,
    };

    public static final int[] TOB_PARTY_SLOTS = {
            VarbitID.TOB_CLIENT_P0, VarbitID.TOB_CLIENT_P1, VarbitID.TOB_CLIENT_P2,
            VarbitID.TOB_CLIENT_P3, VarbitID.TOB_CLIENT_P4,
    };

    // Captured on the client thread (onGameTick) so the party-size tile gates — which can run off the
    // client thread — read it safely. 0 = not in a recognised raid (gates then fall back to the scene
    // count, which still covers instanced content without a party varbit).
    public volatile int lastRaidPartySize = 0;

    /**
     * What this client can see of its company right now: roster teammates in the instance, and the
     * party headcount. Deliberately two signals — names are reliable for a single-arena boss and
     * useless inside a raid (the party splits across rooms), while the raid party varbits are
     * reliable exactly there. The server decides what to do with them; a client never suppresses
     * its own submission, because two clients that can't see each other would both stay quiet.
     */
    public CoopFingerprint coopFingerprint() {
        List<String> teammates = new ArrayList<>();
        if (pluginConfig.get() != null && pluginConfig.get().pvpRoster != null && !pluginConfig.get().pvpRoster.isEmpty()
                && pluginConfig.get().team != null) {
            String me = Rsn.normalize(client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName());
            Set<String> mine = new HashSet<>();
            for (RosterEntry e : pluginConfig.get().pvpRoster) {
                if (e != null && e.name != null && e.teamId == pluginConfig.get().team.id) {
                    mine.add(Rsn.normalize(e.name));
                }
            }
            // Copy before iterating: the set is written from the game tick, and this runs off a
            // kill credit on the same thread today — but a snapshot costs nothing and can't throw.
            for (String seen : new ArrayList<>(instancePlayersSeen)) {
                String n = Rsn.normalize(seen);
                if (!n.isEmpty() && !n.equals(me) && mine.contains(n)) {
                    teammates.add(n);
                }
            }
        }
        int party = lastRaidPartySize > 0 ? lastRaidPartySize : instancePlayersSeen.size();
        CoopFingerprint fp = new CoopFingerprint(teammates, party);
        return fp.isEmpty() ? null : fp;
    }
}
