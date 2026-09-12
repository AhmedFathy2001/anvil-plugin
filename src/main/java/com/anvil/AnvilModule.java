package com.anvil;

import com.anvil.api.BingoApiClient;
import com.anvil.api.BoardRefresh;
import com.anvil.api.EventConfigStore;
import com.anvil.clan.ClanRosterService;
import com.anvil.clog.ProfileSync;
import com.anvil.io.BannerSoundActions;
import com.anvil.api.PluginConfigResponse;
import com.anvil.session.LocalPlayer;
import com.anvil.session.SessionIdentity;
import com.anvil.track.LocalProgress;
import com.anvil.track.ProofPipeline;
import com.anvil.ui.AnvilSidebarDataSource;
import com.anvil.ui.SidebarDataSource;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import java.util.function.Supplier;
import javax.inject.Provider;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * The three things Guice cannot work out on its own.
 *
 * <p>They live here rather than on {@link AnvilPlugin} for one reason: a {@code @Provides} method on
 * the plugin can be invoked BEFORE the plugin's own {@code @Inject} fields are populated, so reading
 * one is an NPE that makes the whole plugin fail to load. It happened once. A module has no such
 * fields to read, which turns a rule somebody has to remember into one the code cannot break.</p>
 *
 * <p>Everything reaching back into the graph does so through a {@link Provider}: almost every
 * collaborator named below also wants the board, so asking for one directly would be a dependency
 * cycle. A Provider resolves when it is first used — by which time the graph is built.</p>
 */
public class AnvilModule extends AbstractModule
{
	@Override
	protected void configure()
	{
		// Everything else has an @Inject constructor.
	}

	@Provides
	AnvilConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AnvilConfig.class);
	}

	/**
	 * The board, as a view rather than a value.
	 *
	 * <p>{@code /config} is re-fetched every thirty seconds and the response object is replaced
	 * wholesale — so a collaborator handed the object at construction would go on crediting tiles
	 * from a board that has since ended, and would never see a tile somebody added this morning.
	 * Every read through this supplier is of the current one.</p>
	 */
	@Provides
	@Singleton
	Supplier<PluginConfigResponse> provideBoard(Provider<EventConfigStore> store)
	{
		return () -> store.get().current();
	}

	/** Pull the board back after a credit, so the panel shows the tile's new total. */
	@Provides
	@Singleton
	BoardRefresh provideBoardRefresh(Provider<EventConfigStore> store)
	{
		return () -> store.get().refreshConfig();
	}

	/**
	 * Data source for the progress sidebar — the single wiring seam between the panel and its data.
	 * The panel only knows the {@link SidebarDataSource} interface.
	 *
	 * <p>One {@link AnvilSidebarDataSource} over the config the plugin already polls, so rendering
	 * the board costs no extra request. It used to sit under a federation layer that fanned several
	 * sites out; one Anvil now serves every clan, so the clans a member can switch between arrive in
	 * that same config response ({@code clans[]}) and the switch is an address, not a second data
	 * source. Offline (no Site URL or token) it resolves to the empty state.</p>
	 */
	@Provides
	@Singleton
	SidebarDataSource provideSidebarDataSource(BingoApiClient apiClient,
		Provider<EventConfigStore> board, Provider<LocalProgress> progress,
		Provider<ProofPipeline> proofs, Provider<SessionIdentity> session,
		Provider<ClanRosterService> roster, Provider<ProfileSync> profileSync,
		Provider<BannerSoundActions> sounds, Provider<LocalPlayer> localPlayer)
	{
		AnvilSidebarDataSource delegate = new AnvilSidebarDataSource(
			() -> board.get().current(), apiClient,
			() -> progress.get().snapshot(), () -> localPlayer.get().name(),
			() -> session.get().homeMembership());
		// The starting-shot button's action, bound after construction: the capture only ever fires
		// from a click, long after everything here has resolved.
		delegate.setStartProofCapture(() -> proofs.get().captureStartProof());
		// The panel's buttons: roster sync, profile sync, the clan picker, and the local banner clips
		// (which live in a folder on this machine, not on any account).
		delegate.setHost(roster::get, profileSync::get, sounds::get, board::get);
		return delegate;
	}
}
