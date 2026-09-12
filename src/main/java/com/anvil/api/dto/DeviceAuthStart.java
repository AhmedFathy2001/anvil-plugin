package com.anvil.api.dto;

/** POST /api/plugin/auth/start response. */
public class DeviceAuthStart
{
	public String device_code;
	public String user_code;
	public String verification_url;
	public String verification_url_complete;
	public int interval;
	public int expires_in;
}
