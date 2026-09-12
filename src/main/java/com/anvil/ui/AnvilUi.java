package com.anvil.ui;

import com.anvil.AnvilConfig;
import com.anvil.clip.ObsClipService;
import com.anvil.io.BannerSoundService;
import com.anvil.io.DebugSupportLog;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;

/**
 * Everything the plugin puts on screen, and takes back off.
 *
 * <p>Two overlays, the always-on sidebar in RuneLite's toolbar, the two buttons in the game's own
 * title bars, and two hotkeys. All of it is mounted when the plugin starts and removed when it
 * stops — a control for a plugin that is no longer running does nothing when pressed, which is worse
 * than no control at all.</p>
 */
@Singleton
public class AnvilUi
{
	private final AnvilConfig config;
	private final OverlayManager overlayManager;
	private final ClientToolbar clientToolbar;
	private final KeyManager keyManager;
	private final AnvilSidebarPanel sidebarPanel;
	private final AnvilOverlay overlay;
	private final BingoClogBannerOverlay clogBanner;
	private final GameTabButtons tabButtons;
	private final BannerSoundService bannerSound;
	private final ObsClipService clips;
	private final DebugSupportLog supportLog;

	@Inject
	AnvilUi(AnvilConfig config, OverlayManager overlayManager, ClientToolbar clientToolbar,
		KeyManager keyManager, AnvilSidebarPanel sidebarPanel, AnvilOverlay overlay,
		BingoClogBannerOverlay clogBanner, GameTabButtons tabButtons,
		BannerSoundService bannerSound, ObsClipService clips, DebugSupportLog supportLog)
	{
		this.config = config;
		this.overlayManager = overlayManager;
		this.clientToolbar = clientToolbar;
		this.keyManager = keyManager;
		this.sidebarPanel = sidebarPanel;
		this.overlay = overlay;
		this.clogBanner = clogBanner;
		this.tabButtons = tabButtons;
		this.bannerSound = bannerSound;
		this.clips = clips;
		this.supportLog = supportLog;

		// Save a clip of what just happened, from anywhere.
		this.clipHotkey = new HotkeyListener(config::clipHotkey)
		{
			@Override
			public void hotkeyPressed()
			{
				clips.capture();
			}
		};
		// Write a support log to attach to a bug report. Also ::anvillog in chat.
		this.supportLogHotkey = new HotkeyListener(config::exportDebugLogHotkey)
		{
			@Override
			public void hotkeyPressed()
			{
				supportLog.export();
			}
		};
	}

	/**
	 * The two hotkeys, built at the END of the constructor and not in a field initialiser: an
	 * initialiser runs before the fields above are assigned, so {@code config} would be null.
	 */
	private final HotkeyListener clipHotkey;
	private final HotkeyListener supportLogHotkey;

	private NavigationButton sidebarNavButton;

	/** {@code owner} is the Plugin the overlays hang off — RuneLite wants it for their lifecycle. */
	public void mount(Plugin owner)
	{
		overlayManager.add(overlay);
		overlayManager.add(clogBanner);

		BufferedImage icon = ImageUtil.loadImageResource(owner.getClass(), "/com/anvil/sidebar_icon.png");
		sidebarNavButton = NavigationButton.builder()
			.tooltip("Anvil progress")
			.icon(icon)
			.priority(7)
			.panel(sidebarPanel)
			.build();
		clientToolbar.addNavigation(sidebarNavButton);

		tabButtons.onStartUp();
		bannerSound.ensureUserDir();
		keyManager.registerKeyListener(clipHotkey);
		keyManager.registerKeyListener(supportLogHotkey);
	}

	public void unmount()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(clogBanner);
		tabButtons.onShutDown();
		if (sidebarNavButton != null)
		{
			clientToolbar.removeNavigation(sidebarNavButton);
			sidebarNavButton = null;
		}
		bannerSound.shutdown();
		keyManager.unregisterKeyListener(clipHotkey);
		keyManager.unregisterKeyListener(supportLogHotkey);
	}
}
