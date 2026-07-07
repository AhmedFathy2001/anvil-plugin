package com.anvil;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * One-click support-log export. Members who hit a problem (a drop that never submits, login trouble)
 * shouldn't have to hunt for {@code .runelite/logs/client.log} by hand. This bundles a caller-supplied
 * diagnostic header with the Anvil-relevant slice of RuneLite's client.log into a single shareable
 * .txt in {@code .runelite/anvil-debug/}, copies its path to the clipboard, and opens the folder so
 * they can just drag the file into Discord. All file work runs off the client thread by the caller.
 */
@Slf4j
@Singleton
public class DebugLogExporter
{
	private static final File OUT_DIR = new File(RuneLite.RUNELITE_DIR, "anvil-debug");
	private static final File LOG_FILE = new File(new File(RuneLite.RUNELITE_DIR, "logs"), "client.log");

	// Read at most the tail of client.log — the file can be many MB across a long session and only the
	// recent end is relevant to a just-now problem.
	private static final long MAX_LOG_TAIL_BYTES = 512 * 1024;
	// Keep the raw tail bounded so the bundle stays small enough to share easily.
	private static final int RAW_TAIL_LINES = 200;

	private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	@Inject
	public DebugLogExporter()
	{
	}

	public static class Result
	{
		public final File file;
		public final int anvilLineCount;
		public final boolean clientLogFound;

		Result(File file, int anvilLineCount, boolean clientLogFound)
		{
			this.file = file;
			this.anvilLineCount = anvilLineCount;
			this.clientLogFound = clientLogFound;
		}
	}

	/**
	 * Write the bundle, copy its path to the clipboard, and open the folder. Never throws — on failure
	 * it returns null and logs, so the caller can show a friendly fallback message.
	 */
	public Result export(String header)
	{
		try
		{
			if (!OUT_DIR.exists() && !OUT_DIR.mkdirs())
			{
				log.warn("Anvil: could not create debug-export dir {}", OUT_DIR);
			}

			List<String> anvilLines = new ArrayList<>();
			List<String> rawTail = new ArrayList<>();
			boolean logFound = readLogTail(anvilLines, rawTail);

			String nl = System.lineSeparator();
			StringBuilder sb = new StringBuilder();
			sb.append(header).append(nl).append(nl);
			sb.append("=== Anvil log lines (").append(anvilLines.size()).append(") ===").append(nl);
			if (anvilLines.isEmpty())
			{
				sb.append(logFound
					? "(no Anvil lines found in the recent log — the action may not have run, or logs rotated)"
					: "(client.log not found at " + LOG_FILE + ")").append(nl);
			}
			else
			{
				for (String l : anvilLines)
				{
					sb.append(l).append(nl);
				}
			}
			sb.append(nl).append("=== Recent client.log tail (last ").append(rawTail.size())
				.append(" lines) ===").append(nl);
			for (String l : rawTail)
			{
				sb.append(l).append(nl);
			}

			File out = new File(OUT_DIR, "anvil-debug-" + LocalDateTime.now().format(FILE_TS) + ".txt");
			Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));

			copyToClipboard(out.getAbsolutePath());
			openFolder();

			log.info("Anvil: exported debug log to {} ({} Anvil lines)", out, anvilLines.size());
			return new Result(out, anvilLines.size(), logFound);
		}
		catch (Exception e)
		{
			log.warn("Anvil: debug log export failed: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Reads the tail of client.log into {@code rawTail} (last {@link #RAW_TAIL_LINES}) and every
	 * Anvil-related line into {@code anvilLines}. Matching on "anvil" catches the logger name
	 * (com.anvil.*) that logback prefixes onto every plugin log line. Returns false if no log file.
	 */
	private boolean readLogTail(List<String> anvilLines, List<String> rawTail)
	{
		if (!LOG_FILE.isFile())
		{
			return false;
		}
		try (RandomAccessFile raf = new RandomAccessFile(LOG_FILE, "r"))
		{
			long len = raf.length();
			long from = Math.max(0, len - MAX_LOG_TAIL_BYTES);
			raf.seek(from);
			byte[] buf = new byte[(int) (len - from)];
			raf.readFully(buf);
			String text = new String(buf, StandardCharsets.UTF_8);
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
			log.warn("Anvil: could not read client.log: {}", e.getMessage());
			return false;
		}
	}

	private void copyToClipboard(String value)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
		}
		catch (Exception e)
		{
			log.debug("Anvil: clipboard copy skipped: {}", e.getMessage());
		}
	}

	private void openFolder()
	{
		if (!Desktop.isDesktopSupported())
		{
			return;
		}
		new Thread(() ->
		{
			try
			{
				Desktop.getDesktop().open(OUT_DIR);
			}
			catch (Exception e)
			{
				log.debug("Anvil: could not open debug folder: {}", e.getMessage());
			}
		}, "anvil-open-debug").start();
	}
}
