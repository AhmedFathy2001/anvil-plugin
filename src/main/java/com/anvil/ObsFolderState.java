package com.anvil;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * What THIS MACHINE knows about the OBS recording folder we borrowed — kept on disk, never in config.
 *
 * <p>Two things live here, and both went wrong when they lived elsewhere:
 *
 * <ul>
 *   <li><b>The player's own folder</b> (the value we hand back when we stop managing OBS). It used to be
 *   a RuneLite config key, and RuneLite syncs config across machines on the same account — so a folder
 *   saved on one computer was "restored" into OBS on another. A Mac's
 *   {@code /Users/<name>/.runelite/plugin-data/anvil/clips} landed in a Linux OBS, where it doesn't
 *   exist, and every clip after that failed to save where we could read it.
 *   <li><b>Which clients are running.</b> Two RuneLite clients share one OBS. When one closed it handed
 *   OBS back to the player's folder while the other was still playing, and the survivor's clips went
 *   out of reach mid-session. Each client keeps a heartbeat file here; the one that closes LAST is the
 *   one that restores.
 * </ul>
 *
 * <p>Everything sits under the plugin's own directory, which RuneLite gives every plugin and which is
 * per machine. All failures are soft: no state means "no backup" and "no other clients".
 */
@Slf4j
@Singleton
class ObsFolderState
{
	/** A client whose heartbeat is older than this has crashed or closed without saying so. */
	private static final long LIVE_MS = 3 * 60_000L;

	private final String clientId = UUID.randomUUID().toString();
	private volatile Filepath root;

	void setRoot(Filepath pluginDir)
	{
		this.root = pluginDir.join("obs");
	}

	/** The player's own OBS recording folder, as last seen on this machine. Null when unknown. */
	String backup()
	{
		Filepath file = file("record-dir-backup.txt");
		if (file == null || !file.isFile())
		{
			return null;
		}
		try (BufferedReader reader = file.openBufferedReader())
		{
			String line = reader.readLine();
			return line == null || line.trim().isEmpty() ? null : line.trim();
		}
		catch (Exception e)
		{
			log.debug("Anvil OBS: couldn't read the folder backup: {}", e.getMessage());
			return null;
		}
	}

	void saveBackup(String dir)
	{
		Filepath file = file("record-dir-backup.txt");
		if (file == null || dir == null || dir.isEmpty())
		{
			return;
		}
		try
		{
			root.createDirectories();
			file.write(dir.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			log.debug("Anvil OBS: couldn't save the folder backup: {}", e.getMessage());
		}
	}

	void clearBackup()
	{
		Filepath file = file("record-dir-backup.txt");
		try
		{
			if (file != null)
			{
				file.deleteIfExists();
			}
		}
		catch (Exception e)
		{
			log.debug("Anvil OBS: couldn't clear the folder backup: {}", e.getMessage());
		}
	}

	/** Say this client is alive. Called at startup and on the plugin's regular tick. */
	void heartbeat()
	{
		Filepath dir = clientsDir();
		if (dir == null)
		{
			return;
		}
		try
		{
			dir.createDirectories();
			dir.joinSegment(clientId).write(Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			log.debug("Anvil OBS: heartbeat skipped: {}", e.getMessage());
		}
	}

	/** This client is going away. */
	void leave()
	{
		Filepath dir = clientsDir();
		try
		{
			if (dir != null)
			{
				dir.joinSegment(clientId).deleteIfExists();
			}
		}
		catch (Exception e)
		{
			log.debug("Anvil OBS: couldn't remove the heartbeat: {}", e.getMessage());
		}
	}

	/** Is another Anvil client on this machine still running (and so still relying on our folder)? */
	boolean othersAlive()
	{
		Filepath dir = clientsDir();
		if (dir == null || !dir.isDirectory())
		{
			return false;
		}
		long cutoff = System.currentTimeMillis() - LIVE_MS;
		try (Stream<Filepath> walk = dir.walk(1))
		{
			return walk
				.filter(Filepath::isFile)
				.filter(f -> !clientId.equals(f.getFileName()))
				.anyMatch(f -> lastModified(f) >= cutoff);
		}
		catch (Exception e)
		{
			log.debug("Anvil OBS: couldn't list clients: {}", e.getMessage());
			return false;
		}
	}

	private Filepath clientsDir()
	{
		Filepath r = root;
		return r == null ? null : r.join("clients");
	}

	private Filepath file(String name)
	{
		Filepath r = root;
		return r == null ? null : r.joinSegment(name);
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
}
