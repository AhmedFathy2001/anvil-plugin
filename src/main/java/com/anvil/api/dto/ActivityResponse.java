package com.anvil.api.dto;

import com.google.gson.Gson;
import java.util.List;
import okhttp3.Response;

/** Response of GET /api/plugin/activity — Gson-mapped; see Anvil.Site/src/lib/pluginActivity.ts. */
public class ActivityResponse
{
	public String cursor;                       // send back as ?since= next poll
	public List<ActivityItem> activity; // ascending by id (oldest→newest); may be null
	public boolean truncated;                   // true = a gap; caller may want to refetch the board
	public boolean noActiveEvent;               // true = valid token, not enrolled (empty feed, not an error)
}
