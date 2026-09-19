package com.anvil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
import net.runelite.client.util.Filepath;

/**
 * One-click support export: a diagnostic header plus the Anvil-relevant slice of RuneLite's
 * client.log, written to a shareable .txt in the plugin's own {@code debug/} folder with its path on
 * the clipboard, so "my drop never submitted" arrives with the stack trace already attached.
 *
 * <p>THE USER HANDS US THE LOG. {@code .runelite/logs/client.log} belongs to RuneLite, and a
 * {@link Filepath} cannot escape the directory it is rooted at — so the plugin cannot open it on its
 * own, and does not try. It asks: a file picker, and the file the user chooses is the one we read.
 * That is what {@link Filepath.Chooser} exists for, and it was confirmed acceptable on the hub
 * review for exactly this.
 *
 * <p>PRE-POINTED AT {@code logs/}, which takes the one {@code Filepath.Unchecked} call in the plugin.
 * {@code setCurrentDirectory} only accepts a Filepath, and the plugin can only name its own folder
 * without it. It is used for nothing but the dialog's starting folder: the plugin still reads only
 * the file the user picks. Pre-pointing was agreed on the hub review, and the packager's
 * disallowed-API list does not cover Filepath at all — the file rule is a review rule, and this is
 * what the reviewer approved.
 *
 * <p>Cancelling is fine: the header still exports, and says the log was not included so whoever
 * reads it knows to ask. All file work runs off the client thread by the caller; the dialog itself
 * is marshalled onto the Swing thread here.
 */
@Slf4j
@Singleton
public class DebugLogExporter
{
	private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	// Read at most the tail of client.log — the file can be many MB across a long session and only the
	// recent end is relevant to a just-now problem.
	private static final int MAX_LOG_TAIL_BYTES = 512 * 1024;
	// Keep the raw tail bounded so the bundle stays small enough to share easily.
	private static final int RAW_TAIL_LINES = 200;

	private final Client client;

	/** The plugin's own `debug/` folder, handed over by AnvilPlugin at startUp. Null = no export. */
	private volatile Filepath root;

	/** Our own directory — where the picker opens only if RuneLite's logs folder is somehow missing. */
	private volatile Filepath pickerFallback;

	@Inject
	public DebugLogExporter(Client client)
	{
		this.client = client;
	}

	public static class Result
	{
		/** Where it landed, as text — the same string that went on the clipboard. */
		public final String path;
		/** Whether the user picked a log, so the chat message can say what the file contains. */
		public final boolean logIncluded;

		Result(String path, boolean logIncluded)
		{
			this.path = path;
			this.logIncluded = logIncluded;
		}
	}

	public void setRoot(Filepath debugDir, Filepath pluginDir)
	{
		this.root = debugDir;
		this.pickerFallback = pluginDir;
	}

	/**
	 * Ask for the log, write the bundle, copy its path. Never throws — on failure it returns null and
	 * logs, so the caller can show a friendly fallback message. Must NOT be called on the Swing thread:
	 * it blocks there until the user answers the picker.
	 */
	public Result export(String header)
	{
		Filepath dir = root;
		if (dir == null)
		{
			log.warn("Anvil: no plugin directory, cannot export a debug log");
			return null;
		}
		try
		{
			if (!dir.exists())
			{
				dir.createDirectories();
			}

			List<String> anvilLines = new ArrayList<>();
			List<String> rawTail = new ArrayList<>();
			Filepath chosen = pickClientLog();
			boolean logRead = chosen != null && readLogTail(chosen, anvilLines, rawTail);

			String nl = System.lineSeparator();
			StringBuilder sb = new StringBuilder();
			sb.append(header).append(nl).append(nl);

			if (!logRead)
			{
				// Said rather than left as an absence, because the person reading this file is a clan
				// admin wondering where the stack trace went.
				sb.append("=== Client log ===").append(nl);
				sb.append(chosen == null
					? "Not included — no log was picked. Attach .runelite/logs/client.log yourself if this is a crash."
					: "Not included — the chosen file could not be read.").append(nl);
			}
			else
			{
				sb.append("=== Anvil log lines (").append(anvilLines.size()).append(") ===").append(nl);
				if (anvilLines.isEmpty())
				{
					sb.append("(no Anvil lines in the recent log — the action may not have run, or logs rotated)")
						.append(nl);
				}
				for (String l : anvilLines)
				{
					sb.append(l).append(nl);
				}
				sb.append(nl).append("=== Recent client.log tail (last ").append(rawTail.size())
					.append(" lines) ===").append(nl);
				for (String l : rawTail)
				{
					sb.append(l).append(nl);
				}
			}

			Filepath out = dir.joinSegment("anvil-debug-" + LocalDateTime.now().format(FILE_TS) + ".txt");
			out.write(sb.toString());

			String path = out.toString();
			Clipboards.copy(path);

			log.info("Anvil: exported debug log to {} (client log {})", path, logRead ? "included" : "omitted");
			return new Result(path, logRead);
		}
		catch (Exception e)
		{
			log.warn("Anvil: debug log export failed: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Show the picker on the Swing thread and wait for an answer. Null when the user cancels, or when
	 * the dialog could not be shown — either way the export carries on without the log.
	 */
	private Filepath pickClientLog()
	{
		AtomicReference<Filepath> picked = new AtomicReference<>();
		Runnable show = () ->
		{
			Filepath.Chooser chooser = new Filepath.Chooser()
				.setIsOpen()
				.setAcceptsFiles()
				.setDialogTitle("Pick client.log to include with your debug export")
				.addExtensionFilter("RuneLite log", "log");
			Filepath start = logsFolder();
			if (start != null)
			{
				chooser.setCurrentDirectory(start);
			}
			List<Filepath> result = chooser.showDialog(client);
			if (result != null && !result.isEmpty())
			{
				picked.set(result.get(0));
			}
		};
		try
		{
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
			log.warn("Anvil: could not show the log picker: {}", e.getMessage());
			return null;
		}
		return picked.get();
	}

	/**
	 * RuneLite's logs folder, so the picker opens with client.log already in front of the user.
	 *
	 * The only Unchecked use in the plugin, and deliberately the narrowest one possible: a starting
	 * folder for a dialog. Nothing is read or written through it — whatever the user picks comes
	 * back from the Chooser as its own Filepath. Falls back to our own directory if the folder is
	 * missing, and to the dialog's default if even that is unavailable.
	 */
	private Filepath logsFolder()
	{
		try
		{
			if (RuneLite.LOGS_DIR.isDirectory())
			{
				return Filepath.Unchecked.getRooted(RuneLite.LOGS_DIR.toPath());
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Anvil: could not point the picker at the logs folder: {}", e.getMessage());
		}
		return pickerFallback;
	}

	/**
	 * Reads the tail of the chosen log into {@code rawTail} (last {@link #RAW_TAIL_LINES}) and every
	 * Anvil-related line into {@code anvilLines}. Matching on "anvil" catches the logger name
	 * (com.anvil.*) that logback prefixes onto every plugin log line. False if it could not be read.
	 *
	 * <p>A FileChannel rather than reading the whole file: a long session's log runs to many MB, and
	 * only the recent end is about a just-now problem.
	 */
	private boolean readLogTail(Filepath logFile, List<String> anvilLines, List<String> rawTail)
	{
		try (FileChannel ch = logFile.openFileChannel())
		{
			long len = ch.size();
			long from = Math.max(0, len - MAX_LOG_TAIL_BYTES);
			ByteBuffer buf = ByteBuffer.allocate((int) (len - from));
			ch.position(from);
			while (buf.hasRemaining() && ch.read(buf) > 0)
			{
				// read until the tail is full or the file ends
			}
			String text = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
			// If we started mid-file, drop the first partial line.
			if (from > 0)
			{
				int firstNl = text.indexOf('\n');
				if (firstNl >= 0)
				{
					text = text.substring(firstNl + 1);
				}
			}
			String[] lines = text.split("\r?\n");
			for (String line : lines)
			{
				if (line.toLowerCase().contains("anvil"))
				{
					anvilLines.add(line);
				}
			}
			int start = Math.max(0, lines.length - RAW_TAIL_LINES);
			for (int i = start; i < lines.length; i++)
			{
				rawTail.add(lines[i]);
			}
			return true;
		}
		catch (IOException e)
		{
			log.warn("Anvil: could not read the chosen log: {}", e.getMessage());
			return false;
		}
	}
}
