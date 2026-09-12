package com.anvil.api.dto;

public class Decay
{
	public int targetPct;   // ramp target as a percent of face (50 = decays to half; 200 = grows to 2x)
	public int hours;       // hours over which the ramp reaches targetPct
}
