package com.anvil.ui;

import com.anvil.api.BingoApiClient;
import com.anvil.io.DeviceSignIn;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * "Sign in with Discord", and the wait that follows it.
 *
 * <p>The only part of the sidebar that talks to the site on its own rather than rendering what the
 * poll already fetched: it opens a device-code flow in the browser, then polls for the token while
 * the member approves it.</p>
 *
 * <p>It is also the only row that has to survive the member walking away, which is why the status
 * line says what is happening at every step — a button that silently did nothing for ninety seconds
 * reads as broken. And the destination is named BEFORE the click, not after: for somebody who has
 * typed no site, the click is what chooses the server, so naming it first makes the press the answer
 * to a question they have been asked.</p>
 */
@lombok.extern.slf4j.Slf4j
public class SignInRow
{
	private final BingoApiClient apiClient;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executor;

	/** Called once a token lands, so the panel can go and fetch with it. */
	private final Runnable onSignedIn;

	public SignInRow(BingoApiClient apiClient, ConfigManager configManager,
		ScheduledExecutorService executor, Runnable onSignedIn)
	{
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executor = executor;
		this.onSignedIn = onSignedIn;
		// Sign-in affordance — visible only in the "Site URL set, no token" state (see refreshSignInRow).
		SidebarChrome.styleFlatButton(signInButton, ColorScheme.BRAND_ORANGE);
		signInButton.addActionListener(e -> startSignIn());
		signInStatus.setFont(FontManager.getRunescapeSmallFont());
		signInStatus.setForeground(SidebarChrome.VALUE_COLOR);
		signInStatus.setVisible(false);
		signInRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		signInRow.add(signInButton, BorderLayout.NORTH);
		signInRow.add(signInStatus, BorderLayout.SOUTH);
		signInRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, signInRow.getPreferredSize().height));
		signInRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		signInRow.setVisible(false);
	}

	/** The row itself, for the panel to put in its header. */
	public JPanel component()
	{
		return signInRow;
	}

	private final JButton signInButton = new JButton("Sign in with Discord");
	private final JLabel signInStatus = new JLabel();
	private final JPanel signInRow = new JPanel(new BorderLayout(0, 2));
	private boolean signInInFlight;

	// ---- Device sign-in (DeviceSignIn) ------------------------------------------------------

	/**
	 * Show the Sign-in button whenever there is no Account Token — and say where it will connect.
	 *
	 * The destination is stated BEFORE the click, not after, because for somebody who has typed no
	 * site the click is what chooses the server. A button that quietly picked one and then contacted
	 * it would be the plugin making that decision; naming it first makes the press the answer to a
	 * question they have been asked.
	 */
	/** The credentials changed under us: whatever the last attempt said no longer applies. */
	public void clearStatus()
	{
		setStatus("");
	}

	public void refresh()
	{
		if (!signInInFlight)
		{
			boolean show = apiClient.needsSignIn();
			signInRow.setVisible(show);
			if (show)
			{
				setStatus(apiClient.getApiUrl().isEmpty()
					? "Connects to " + BingoApiClient.CANONICAL_SITE + ". Using your own Anvil? Put its address in Site URL first."
					: "Connects to " + apiClient.getApiUrl() + ".");
			}
			signInRow.revalidate();
			signInRow.repaint();
		}
	}

	/** Run the device sign-in (async, every step on the shared executor); on success store the token —
	 * onConfigChanged does the rest. Both callbacks arrive off the EDT, so they marshal via invokeLater. */
	private void startSignIn()
	{
		if (signInInFlight)
		{
			return;
		}
		// THE SITE, IF THEY HAVE NOT NAMED ONE. Only when empty — somebody who typed their own address
		// (a self-hosted Anvil, or an older per-clan one) has already answered this question, and
		// overwriting their answer because they pressed the obvious button would be its own bug.
		//
		// Writing it here rather than defaulting the config item is deliberate: the plugin reaches the
		// network only after this click, so an install nobody signs into contacts nothing at all. The
		// click IS the disclosure, which is why the button says where it goes.
		if (apiClient.getApiUrl().isEmpty())
		{
			configManager.setConfiguration("osrsbingo", "apiUrl", BingoApiClient.CANONICAL_SITE);
			// Straight onto the client too. The config write reaches it through onConfigChanged, and
			// the sign-in below starts on this thread — without this the first request would go out
			// against the empty URL it was holding a moment ago.
			apiClient.configure(
				BingoApiClient.CANONICAL_SITE, configManager.getConfiguration("osrsbingo", "playerToken"));
		}

		signInInFlight = true;
		signInButton.setEnabled(false);
		setStatus("Starting…");

		new DeviceSignIn(apiClient, executor).run(
			line -> SwingUtilities.invokeLater(() -> setStatus(line)),
			result -> SwingUtilities.invokeLater(() ->
			{
				signInInFlight = false;
				signInButton.setEnabled(true);
				if (result.outcome == DeviceSignIn.Outcome.SIGNED_IN)
				{
					// Storing the token fires the plugin's onConfigChanged → client reconfigure,
					// identity stamp + greet, and a sidebar refresh — the same path as a manual paste.
					configManager.setConfiguration("osrsbingo", "playerToken", result.token);

					// Read it straight back. A signed-in-but-empty config is the one failure mode a
					// member can't diagnose: the site says they approved, the panel keeps asking them
					// to sign in, and nothing explains why. It happens when the config write doesn't
					// take — a synced RuneLite profile clobbered by a second client, most often — and
					// it MUST NOT look like the sign-in itself failed. Say what happened and give them
					// the manual route, which always works.
					String stored = configManager.getConfiguration("osrsbingo", "playerToken");
					if (stored == null || stored.isEmpty())
					{
						log.warn("Anvil: signed in but the token did not persist to the RuneLite config");
						setStatus("Signed in, but RuneLite didn't save the token — paste it "
							+ "from Profile → RuneLite plugin on the site.");
					}
					else
					{
						setStatus("");
					}
				}
				refresh();
				onSignedIn.run();
			}));
	}

	/**
	 * The line under the sign-in button — most importantly the one carrying the approval code.
	 *
	 * <p>It used to be a bare setText on a label inside a row whose maximum height was capped at
	 * CONSTRUCTION time, while the label was still hidden. So the row could never grow to fit it: the
	 * status was set, the panel was told to show it, and the member saw an empty box where the code
	 * they had been sent to find was supposed to be. Re-cap on every change, like the site-connect
	 * row already does, and wrap as HTML so a long line breaks instead of being clipped at the edge
	 * of a narrow sidebar.
	 */
	private void setStatus(String text)
	{
		String plain = text == null ? "" : text;
		boolean show = !plain.isEmpty();
		String escaped = plain.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		signInStatus.setText(show ? "<html><body style='width:" + SidebarChrome.STATUS_WRAP_PX + "px'>" + escaped + "</body></html>" : "");
		signInStatus.setToolTipText(show ? plain : null);
		signInStatus.setVisible(show);
		signInRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, signInRow.getPreferredSize().height));
		signInRow.revalidate();
		signInRow.repaint();
	}

	// ---- Refresh flow -----------------------------------------------------------------------------

	/**
	 * Forget everything on screen, because it belongs to a site or an account we are no longer using.
	 *
	 * <p>Called the moment the Site URL or the token changes. Without it the panel kept rendering the
	 * previous clan's events, board and roster until a fetch against the NEW credentials succeeded —
	 * which is indefinitely when the new ones are wrong, so the member sits looking at a clan they
	 * just left and reasonably concludes the change didn't take.
	 */
}
