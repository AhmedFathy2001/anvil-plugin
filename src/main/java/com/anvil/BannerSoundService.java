package com.anvil;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.audio.AudioPlayer;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Plays a clip when the bingo banner fires. No clips ship with the plugin — users supply their own
 * WAV/PCM files (the only format stock Java decodes) by dropping them into
 * {@code <RuneLite dir>/anvil-bingo-sounds/} (or via {@link #importSounds()}). Nothing plays until a
 * file is added. Playback goes through RuneLite's {@link AudioPlayer} (plugin-hub policy requires
 * this over the raw Java Sound API).
 */
@Slf4j
@Singleton
public class BannerSoundService
{
	static final String USER_DIR_NAME = "anvil-bingo-sounds";

	private final AnvilConfig config;
	private final AudioPlayer audioPlayer;
	private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "anvil-banner-sound");
		t.setDaemon(true);
		return t;
	});

	@Inject
	public BannerSoundService(AnvilConfig config, AudioPlayer audioPlayer)
	{
		this.config = config;
		this.audioPlayer = audioPlayer;
	}

	public static File userDir()
	{
		return new File(RuneLite.RUNELITE_DIR, USER_DIR_NAME);
	}

	public void ensureUserDir()
	{
		File dir = userDir();
		if (!dir.exists())
		{
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
	}

	/** True if the user has at least one .wav in their sounds folder (i.e. a banner could play). */
	public boolean hasClips()
	{
		File[] files = userDir().listFiles((d, name) -> name.toLowerCase().endsWith(".wav"));
		return files != null && files.length > 0;
	}

	/** All .wav filenames in the user folder, case-insensitively sorted (for the in-tab manager). */
	public List<String> listClips()
	{
		File[] files = userDir().listFiles((d, name) -> name.toLowerCase().endsWith(".wav"));
		List<String> out = new ArrayList<>();
		if (files != null)
		{
			for (File f : files)
			{
				out.add(f.getName());
			}
			out.sort(String.CASE_INSENSITIVE_ORDER);
		}
		return out;
	}

	/**
	 * A clip filename as something to read: "odablock-we-won.wav" becomes "Odablock we won".
	 *
	 * <p>Clips arrive as downloaded files, so their names are slugs. Printed verbatim they made both
	 * the in-game list and the side panel look like a directory listing that had leaked into the
	 * interface. Lives here rather than in either panel because both of them show the same files.
	 */
	public static String displayName(String file)
	{
		String s = file == null ? "" : file.trim();
		int dot = s.lastIndexOf('.');
		if (dot > 0)
		{
			s = s.substring(0, dot);
		}
		s = s.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
		if (s.isEmpty())
		{
			return file == null ? "" : file;
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/**
	 * True if {@code name} is in the play cycle. The cycle is the comma-separated allowlist in
	 * {@link AnvilConfig#bannerSoundClip()}; an empty allowlist means "every clip plays".
	 */
	public boolean isSelected(String name)
	{
		Set<String> sel = parseSelected(config.bannerSoundClip());
		return sel.isEmpty() || sel.contains(name.toLowerCase());
	}

	/** Parse the comma-separated clip allowlist into lowercased ".wav" filenames. Empty = play all. */
	static Set<String> parseSelected(String csv)
	{
		Set<String> out = new LinkedHashSet<>();
		if (csv == null)
		{
			return out;
		}
		for (String part : csv.split(","))
		{
			String s = part.trim().toLowerCase();
			if (s.isEmpty())
			{
				continue;
			}
			if (!s.endsWith(".wav"))
			{
				s += ".wav";
			}
			out.add(s);
		}
		return out;
	}

	public void play()
	{
		if (!config.bannerSound())
		{
			return;
		}
		audioExecutor.submit(this::playBlocking);
	}

	private void playBlocking()
	{
		try
		{
			File[] files = userDir().listFiles((d, name) -> name.toLowerCase().endsWith(".wav"));
			if (files == null || files.length == 0)
			{
				return;
			}

			// The cycle is the allowlist (comma-separated filenames); empty = every clip is eligible.
			// Each banner plays one at random from the eligible set, so a multi-clip cycle varies.
			Set<String> selected = parseSelected(config.bannerSoundClip());
			List<File> candidates = new ArrayList<>();
			for (File f : files)
			{
				if (selected.isEmpty() || selected.contains(f.getName().toLowerCase()))
				{
					candidates.add(f);
				}
			}
			if (candidates.isEmpty())
			{
				return;
			}

			playFile(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
		}
		catch (Exception e)
		{
			log.debug("Banner sound skipped: {}", e.getMessage());
		}
	}

	private void playFile(File file) throws Exception
	{
		audioPlayer.play(file, gainDb());
	}

	/**
	 * Convert the 0–100 volume config to a MASTER_GAIN decibel value AudioPlayer applies. 100 → 0 dB
	 * (unattenuated); lower volumes attenuate logarithmically; 0 → effectively silent. AudioPlayer
	 * clamps to the line's supported range.
	 */
	private float gainDb()
	{
		int vol = Math.max(0, Math.min(100, config.bannerSoundVolume()));
		return vol <= 0 ? -80f : (float) (20.0 * Math.log10(vol / 100.0));
	}

	/**
	 * Opens a file picker and copies the chosen WAV(s) into the user sounds folder. {@code onImported}
	 * (may be null) is invoked with the successfully-copied filenames so the caller can confirm in chat;
	 * it's skipped when the user cancels or nothing copied.
	 */
	public void importSounds(Consumer<List<String>> onImported)
	{
		SwingUtilities.invokeLater(() ->
		{
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle("Add banner sounds");
			chooser.setMultiSelectionEnabled(true);
			chooser.setFileFilter(new FileNameExtensionFilter("WAV audio (*.wav)", "wav"));
			if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION)
			{
				return;
			}
			ensureUserDir();
			List<String> imported = new ArrayList<>();
			for (File src : chooser.getSelectedFiles())
			{
				try
				{
					Files.copy(src.toPath(), new File(userDir(), src.getName()).toPath(),
						StandardCopyOption.REPLACE_EXISTING);
					imported.add(src.getName());
				}
				catch (Exception e)
				{
					log.warn("Could not import sound {}: {}", src.getName(), e.getMessage());
				}
			}
			if (onImported != null && !imported.isEmpty())
			{
				onImported.accept(imported);
			}
		});
	}

	/** Copies the user sounds folder's path — see Clipboards for why it can't be opened directly. */
	public void copyFolderPath()
	{
		ensureUserDir();
		// LinkBrowser::open is restricted for hub releases, so the path goes on the clipboard and the
		// player pastes it wherever they were going to open it.
		Clipboards.copy(userDir().getAbsolutePath());
	}

	public void shutdown()
	{
		audioExecutor.shutdownNow();
	}
}
