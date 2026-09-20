package com.anvil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Singleton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.util.Filepath;

/**
 * WHERE A CLIP IS, AND WHETHER WE MAY READ IT.
 *
 * <p>OBS writes the replay buffer wherever OBS is configured to, and tells us the absolute path over
 * the websocket. That path is a string, and a string cannot become a {@link Filepath}: the only ways
 * a plugin gets one are its own directory and a folder the player picks in a chooser. So the upload
 * needs the clip to be in one of exactly two places:
 *
 * <ul>
 *   <li>OUR OWN clips folder, when the player let the plugin point OBS at it. Nothing to ask, ever.
 *   <li>A FOLDER THE PLAYER GRANTED this session, by picking their OBS recordings folder once.
 * </ul>
 *
 * <p>The grant lasts one session and cannot be remembered: there is no public way to rebuild a
 * Filepath from a stored path, and RuneLite's config cannot serialize one. That is the reason the
 * managed-folder option exists at all — it is the only way to never be asked again.
 *
 * <p>A clip we may not read is not lost. The caller queues it, and a grant later in the session
 * posts it retroactively: the file is still sitting where OBS left it.
 */
@Slf4j
@Singleton
class ClipFolder
{
	/** Unsent clips in OUR folder are swept on startup — a failed upload must not fill someone's disk. */
	private static final long PRUNE_AFTER_MS = 7L * 24 * 60 * 60 * 1000;
	/** Keep the sweep honest even if the clock is odd: never leave more than this many behind. */
	private static final int MAX_KEPT = 30;

	/** Our own clips directory, inside the plugin directory. Always readable. */
	private volatile Filepath clipsDir;
	/** The OBS recordings folder the player picked. Null until they do; forgotten on shutdown. */
	private volatile Filepath granted;
	/** One ask per session. Dismissing it means dismissed, not "ask again on the next clip". */
	private volatile boolean asked;

	void setRoot(Filepath dir)
	{
		this.clipsDir = dir;
	}

	/** Our own clips folder as an OS path, for handing to OBS. Null when it can't be created. */
	String managedPath()
	{
		Filepath dir = ensureClipsDir();
		return dir == null ? null : dir.toString();
	}

	/** True once the player has been asked this session, whatever they answered. */
	boolean asked()
	{
		return asked;
	}

	/**
	 * The clip OBS just named, as something we are allowed to open — or null when it landed somewhere
	 * we may not read.
	 *
	 * <p>Matched by FILE NAME inside a folder we hold, never by the path OBS gave us: the path is the
	 * thing we are not allowed to act on. Our own folder is tried first, so a managed setup never
	 * depends on a grant.
	 */
	Filepath locate(String obsPath)
	{
		String name = fileName(obsPath);
		if (name == null)
		{
			return null;
		}
		for (Filepath dir : new Filepath[]{clipsDir, granted})
		{
			if (dir == null)
			{
				continue;
			}
			try
			{
				Filepath candidate = dir.joinSegment(name);
				if (candidate.isFile())
				{
					return candidate;
				}
			}
			catch (Exception e)
			{
				// A name this directory won't accept (traversal, a reserved device name) is simply not
				// a clip we can reach — the next folder still gets its turn.
				log.debug("Anvil clips: {} not resolvable in {}: {}", name, dir, e.getMessage());
			}
		}
		return null;
	}

	/** True when this clip is ours to delete after posting — i.e. it is in our own folder. */
	boolean isOurs(Filepath clip)
	{
		Filepath dir = clipsDir;
		return dir != null && clip != null && clip.startsWith(dir);
	}

	/**
	 * The last segment of a path OBS reported. Handles both separators because OBS reports Windows
	 * paths with backslashes and everything else with forward slashes, and the plugin may be running
	 * on a different OS than the string implies is only true for a remote OBS — which is exactly the
	 * case where the file is unreachable anyway.
	 */
	static String fileName(String path)
	{
		if (path == null)
		{
			return null;
		}
		String trimmed = path.trim();
		while (trimmed.endsWith("/") || trimmed.endsWith("\\"))
		{
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		int cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
		String name = cut >= 0 ? trimmed.substring(cut + 1) : trimmed;
		return name.isEmpty() ? null : name;
	}

	/** What the player chose when asked how clips should reach Discord. */
	enum Ask
	{
		/** Let the plugin point OBS at its own folder, for good. Never asked again. */
		MANAGE,
		/** They picked their OBS recordings folder. Lasts until RuneLite closes. */
		GRANTED,
		/** Neither. Clips go on the clipboard and they paste them. */
		DISMISSED
	}

	/**
	 * Ask how clips should reach Discord. Once per session, whatever they answer.
	 *
	 * <p>BOTH ANSWERS ARE OFFERED HERE, because the difference between them is not a preference — it
	 * is "be asked every session" versus "never again", and nobody goes looking in a settings panel
	 * for a choice they don't know exists. The permanent option costs them OBS's recording folder
	 * while RuneLite runs, so it is stated plainly rather than made the quiet default.
	 *
	 * <p>MUST NOT be called on the client thread — it blocks on a modal dialog. The caller picks the
	 * moment: the login screen, never mid-clip.
	 *
	 * @param obsDir where OBS says it records. We may name it; we may not open it.
	 */
	Ask ask(Client client, String obsDir)
	{
		if (asked)
		{
			return granted != null ? Ask.GRANTED : Ask.DISMISSED;
		}
		asked = true;
		Ask choice = chooseHow(obsDir);
		if (choice != Ask.GRANTED)
		{
			return choice;
		}
		return pickFolder(client, obsDir) ? Ask.GRANTED : Ask.DISMISSED;
	}

	/** The three-way question, on the EDT, on top of the client. */
	private Ask chooseHow(String obsDir)
	{
		String[] options = {"Let Anvil handle it", "Pick my OBS folder", "Not now"};
		String where = obsDir == null || obsDir.isEmpty() ? "your OBS recordings folder" : obsDir;
		String message = "<html><body style='width:380px'>"
			+ "<b>Post your clips to Discord automatically?</b><br><br>"
			+ "Anvil can post a clip the moment OBS saves it, but it has to read the file first — and "
			+ "RuneLite only lets a plugin read its own folder, or one you pick each time you play.<br><br>"
			+ "<b>Let Anvil handle it</b> — Anvil points OBS at its own clips folder while RuneLite is "
			+ "running, and puts your setting back when it closes. Your ordinary OBS recordings will "
			+ "save there too. You won't be asked again.<br><br>"
			+ "<b>Pick my OBS folder</b> — choose " + escape(where) + " yourself. Nothing of yours changes, "
			+ "but you'll be asked again next time you start RuneLite.<br><br>"
			+ "<b>Not now</b> — each clip goes on your clipboard instead, ready to paste into Discord."
			+ "</body></html>";
		final int[] picked = {2};
		try
		{
			Runnable show = () ->
			{
				JOptionPane pane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE,
					JOptionPane.DEFAULT_OPTION, null, options, options[0]);
				JDialog dialog = pane.createDialog("Anvil — clips");
				// The client is full-screen for a lot of people; a dialog behind it is a hang as far as
				// anyone can tell.
				dialog.setAlwaysOnTop(true);
				dialog.setVisible(true);
				dialog.dispose();
				Object value = pane.getValue();
				for (int i = 0; i < options.length; i++)
				{
					if (options[i].equals(value))
					{
						picked[0] = i;
					}
				}
			};
			if (SwingUtilities.isEventDispatchThread())
			{
				show.run();
			}
			else
			{
				SwingUtilities.invokeAndWait(show);
			}
		}
		catch (Exception e)
		{
			log.debug("Anvil clips: prompt failed: {}", e.getMessage());
			return Ask.DISMISSED;
		}
		switch (picked[0])
		{
			case 0:
				return Ask.MANAGE;
			case 1:
				return Ask.GRANTED;
			default:
				return Ask.DISMISSED;
		}
	}

	/** The folder chooser itself. True when they picked one. */
	private boolean pickFolder(Client client, String obsDir)
	{
		try
		{
			String title = obsDir == null || obsDir.isEmpty()
				? "Anvil — pick the folder OBS saves clips to"
				: "Anvil — pick the folder OBS saves clips to:  " + obsDir;
			List<Filepath> picked = new Filepath.Chooser()
				.setIsOpen()
				.setAcceptsDirectories()
				.setDialogTitle(title)
				.showDialog(client);
			if (picked == null || picked.isEmpty())
			{
				return false;
			}
			granted = picked.get(0);
			log.debug("Anvil clips: folder granted");
			return true;
		}
		catch (Exception e)
		{
			log.debug("Anvil clips: folder chooser failed: {}", e.getMessage());
			return false;
		}
	}

	/** The OBS path lands inside HTML, and Windows paths are full of characters that is fussy about. */
	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/**
	 * Delete clips we kept but never sent. Only ever our OWN folder — a granted folder is the
	 * player's recordings, and nothing of theirs is ours to remove.
	 */
	void prune()
	{
		Filepath dir = clipsDir;
		if (dir == null || !dir.isDirectory())
		{
			return;
		}
		try (java.util.stream.Stream<Filepath> walk = dir.walk(1))
		{
			List<Filepath> files = walk.filter(Filepath::isFile).collect(Collectors.toList());
			long cutoff = System.currentTimeMillis() - PRUNE_AFTER_MS;
			List<Filepath> doomed = new ArrayList<>();
			for (Filepath f : files)
			{
				if (lastModified(f) < cutoff)
				{
					doomed.add(f);
				}
			}
			if (files.size() - doomed.size() > MAX_KEPT)
			{
				files.stream()
					.filter(f -> !doomed.contains(f))
					.sorted(Comparator.comparingLong(ClipFolder::lastModified))
					.limit(files.size() - doomed.size() - MAX_KEPT)
					.forEach(doomed::add);
			}
			for (Filepath f : doomed)
			{
				f.deleteIfExists();
			}
			if (!doomed.isEmpty())
			{
				log.debug("Anvil clips: swept {} unsent clip(s)", doomed.size());
			}
		}
		catch (Exception e)
		{
			log.debug("Anvil clips: sweep skipped: {}", e.getMessage());
		}
	}

	private static long lastModified(Filepath f)
	{
		try
		{
			return f.getLastModifiedTime().toMillis();
		}
		catch (Exception e)
		{
			return 0L;
		}
	}

	/** Our clips directory, created on first use. Null when it can't be made. */
	private Filepath ensureClipsDir()
	{
		Filepath dir = clipsDir;
		if (dir == null)
		{
			return null;
		}
		try
		{
			if (!dir.isDirectory())
			{
				dir.createDirectories();
			}
			return dir;
		}
		catch (Exception e)
		{
			log.debug("Anvil clips: can't create {}: {}", dir, e.getMessage());
			return null;
		}
	}
}
