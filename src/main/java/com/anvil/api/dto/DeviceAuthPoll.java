package com.anvil.api.dto;

/** POST /api/plugin/auth/poll response — status: pending | slow_down | expired | denied | complete. */
public class DeviceAuthPoll
{
	public String status;
	public String token;
	public int interval;
}
