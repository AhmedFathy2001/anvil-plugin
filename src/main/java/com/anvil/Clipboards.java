package com.anvil;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import lombok.extern.slf4j.Slf4j;

/**
 * Putting a path on the clipboard, which is as far as a hub plugin may go toward "open this folder".
 *
 * <p>RuneLite's LinkBrowser::open — the sanctioned way to hand a local path to the OS — is a
 * restricted API for plugin-hub releases, so the folder buttons can't launch a file manager. Copying
 * the path is the honest substitute: one paste into a file manager or a terminal, and nothing about
 * the plugin reaches outside its own directory.
 */
@Slf4j
final class Clipboards
{
	private Clipboards()
	{
	}

	/** True when the text made it onto the clipboard, so a caller can say so rather than assume. */
	static boolean copy(String value)
	{
		if (value == null || value.isEmpty())
		{
			return false;
		}
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
			return true;
		}
		catch (Exception e)
		{
			// A headless or locked-down desktop has no clipboard; the path still gets said out loud.
			log.debug("Anvil: clipboard copy skipped: {}", e.getMessage());
			return false;
		}
	}
}
