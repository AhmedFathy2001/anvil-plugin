package com.anvil;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.util.Collections;
import java.util.List;
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
		return set(new StringSelection(value));
	}

	/**
	 * The file itself, the way copying it in a file manager does — so a paste into Discord ATTACHES
	 * it, where {@link #copy} would paste its path as text.
	 *
	 * <p>Nothing here opens the file. The clipboard carries a reference, and whichever app the user
	 * pastes into reads it — the plugin never does. That is the whole point: a file another program
	 * wrote, outside the plugin's directory, is not the plugin's to read.
	 *
	 * <p>File-list only, like a file manager's copy. Offering text as well lets the paste target pick
	 * the text, and a chat box that gets a path instead of a video is the thing this exists to fix.
	 */
	static boolean copyFile(String path)
	{
		if (path == null || path.isEmpty())
		{
			return false;
		}
		return set(new FileSelection(new File(path)));
	}

	private static boolean set(Transferable contents)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(contents, null);
			return true;
		}
		catch (Exception e)
		{
			// A headless or locked-down desktop has no clipboard; the path still gets said out loud.
			log.debug("Anvil: clipboard copy skipped: {}", e.getMessage());
			return false;
		}
	}

	private static final class FileSelection implements Transferable
	{
		private final List<File> files;

		FileSelection(File file)
		{
			this.files = Collections.singletonList(file);
		}

		@Override
		public DataFlavor[] getTransferDataFlavors()
		{
			return new DataFlavor[]{DataFlavor.javaFileListFlavor};
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor)
		{
			return DataFlavor.javaFileListFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException
		{
			if (!isDataFlavorSupported(flavor))
			{
				throw new UnsupportedFlavorException(flavor);
			}
			return files;
		}
	}
}
