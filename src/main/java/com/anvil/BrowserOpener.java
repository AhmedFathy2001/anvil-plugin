package com.anvil;

/**
 * Opens a URL in the member's system browser.
 *
 * An interface rather than a direct {@link net.runelite.client.util.LinkBrowser} call so the
 * sign-in flow can be unit-tested without launching anything: production binds
 * {@code LinkBrowser::browse}, tests bind a recorder that just remembers the URL it was handed.
 *
 * <p>Lived inside the federation data source until federation was removed. It is hoisted rather
 * than deleted with it because opening a browser is what DEVICE SIGN-IN does, and that is the
 * plugin's live login path — the one thing in that file that was never about federation at all.</p>
 */
@FunctionalInterface
public interface BrowserOpener
{
	/** @return true when a browser was actually opened. */
	boolean open(String url);
}
