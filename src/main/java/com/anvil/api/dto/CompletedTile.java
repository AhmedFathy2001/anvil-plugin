package com.anvil.api.dto;

public class CompletedTile
{
	public int tileId;
	public String label;
	public int points;   // tile difficulty/reward value — used to pick the "hardest" to banner
	public String completedBy; // crediting player of the finishing submission; null for stat/manual tiles
}
