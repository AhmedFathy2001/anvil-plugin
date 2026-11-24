package com.anvil;

import com.google.gson.JsonObject;
import java.awt.Color;
import java.util.ArrayList;
import javax.swing.JTextField;

/** Test seams for FeedList: what the list holds, its box and error line, and presses settled at once. */
final class FeedListKit
{
	private FeedListKit()
	{
	}

	static int rowCount(FeedList f)
	{
		return f.model.getSize();
	}

	static String rowId(FeedList f, int i)
	{
		return UiView.str(f.model.getElementAt(i), "id");
	}

	static JsonObject reactionChip(FeedList f, int i, String kind)
	{
		JsonObject row = f.model.getElementAt(i);
		if (row.has("reacts") && row.get("reacts").isJsonArray())
		{
			for (var e : row.getAsJsonArray("reacts"))
			{
				if (kind.equals(UiView.str(e.getAsJsonObject(), "kind")))
				{
					return e.getAsJsonObject();
				}
			}
		}
		return "gz".equals(kind) && row.has("gz") ? row.getAsJsonObject("gz") : null;
	}

	static JTextField box(FeedList f)
	{
		return f.input;
	}

	static String errorLine(FeedList f)
	{
		return f.error.isVisible() ? f.error.getText() : null;
	}

	/** Press row {@code i}'s chip of this kind, as a click on it would. */
	static void pressChip(FeedList f, int i, String kind)
	{
		JsonObject chip = reactionChip(f, i, kind);
		if (chip != null)
		{
			f.press(i, chip);
		}
	}

	/** Every press whose post has answered settles now, without waiting out the timer. */
	static void settleNow(FeedList f)
	{
		for (String rowId : new ArrayList<>(f.held.keySet()))
		{
			FeedList.Held h = f.held.get(rowId);
			if (h.posts == 0)
			{
				f.settle(rowId, h.gen);
			}
		}
	}

	/** The name line as panel HTML: the runs WhoLine paints, as font tags. */
	static String whoHtml(JsonObject r, String who, String tag, Color whoInk, Color tagInk)
	{
		StringBuilder sb = new StringBuilder("<html>");
		FeedList.whoRuns(r, who, tag, whoInk, tagInk, (text, ink) -> sb.append(String.format("<font color='#%06x'>%s</font>", ink.getRGB() & 0xffffff, Ui.esc(text))));
		return sb.toString();
	}
}
