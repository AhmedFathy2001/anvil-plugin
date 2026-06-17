package com.osrsbingo;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.audio.AudioPlayer;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plays a clip when the bingo banner fires. Clips are WAV/PCM (the only format stock Java decodes)
 * and come from two places: bundled clips under {@code resources/com/osrsbingo/sounds/} and user
 * clips in {@code <RuneLite dir>/anvil-bingo-sounds/}. Playback goes through RuneLite's
 * {@link AudioPlayer} (plugin-hub policy requires this over the raw Java Sound API).
 */
@Slf4j
@Singleton
public class BannerSoundService
{
	static final String USER_DIR_NAME = "anvil-bingo-sounds";
	private static final String BUNDLED_PREFIX = "/com/osrsbingo/sounds/";

	private static final String[] BUNDLED_CLIPS = {
		"odablock-we-won.wav",
		"c-engineer-completed.wav",
	};

	private final OsrsBingoConfig config;
	private final AudioPlayer audioPlayer;
	private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "anvil-banner-sound");
		t.setDaemon(true);
		return t;
	});

	@Inject
	public BannerSoundService(OsrsBingoConfig config, AudioPlayer audioPlayer)
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
			List<String> userClips = new ArrayList<>();
			File[] files = userDir().listFiles((d, name) -> name.toLowerCase().endsWith(".wav"));
			if (files != null)
			{
				for (File f : files)
				{
					userClips.add(f.getAbsolutePath());
				}
			}

			List<String> bundled = new ArrayList<>();
			for (String name : BUNDLED_CLIPS)
			{
				if (BannerSoundService.class.getResource(BUNDLED_PREFIX + name) != null)
				{
					bundled.add(BUNDLED_PREFIX + name);
				}
			}

			// A named clip overrides random selection; user files win over bundled on a name clash.
			String wanted = config.bannerSoundClip();
			wanted = wanted == null ? "" : wanted.trim();
			if (!wanted.isEmpty())
			{
				if (!wanted.toLowerCase().endsWith(".wav"))
				{
					wanted += ".wav";
				}
				for (String p : userClips)
				{
					if (new File(p).getName().equalsIgnoreCase(wanted))
					{
						playFile(new File(p));
						return;
					}
				}
				for (String r : bundled)
				{
					if (r.substring(r.lastIndexOf('/') + 1).equalsIgnoreCase(wanted))
					{
						playResource(r);
						return;
					}
				}
				return;
			}

			int total = userClips.size() + bundled.size();
			if (total == 0)
			{
				return;
			}

			int pick = ThreadLocalRandom.current().nextInt(total);
			if (pick < userClips.size())
			{
				playFile(new File(userClips.get(pick)));
			}
			else
			{
				playResource(bundled.get(pick - userClips.size()));
			}
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

	private void playResource(String resourcePath) throws Exception
	{
		audioPlayer.play(BannerSoundService.class, resourcePath, gainDb());
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

	/** Opens a file picker and copies the chosen WAV(s) into the user sounds folder. */
	public void importSounds()
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
			for (File src : chooser.getSelectedFiles())
			{
				try
				{
					Files.copy(src.toPath(), new File(userDir(), src.getName()).toPath(),
						StandardCopyOption.REPLACE_EXISTING);
				}
				catch (Exception e)
				{
					log.warn("Could not import sound {}: {}", src.getName(), e.getMessage());
				}
			}
		});
	}

	/** Opens the user sounds folder in the OS file manager. */
	public void openFolder()
	{
		ensureUserDir();
		if (!Desktop.isDesktopSupported())
		{
			return;
		}
		audioExecutor.submit(() ->
		{
			try
			{
				Desktop.getDesktop().open(userDir());
			}
			catch (Exception e)
			{
				log.warn("Could not open sounds folder: {}", e.getMessage());
			}
		});
	}

	public void shutdown()
	{
		audioExecutor.shutdownNow();
	}
}
