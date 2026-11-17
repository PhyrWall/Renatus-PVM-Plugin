package com.renatus.model;

import lombok.Data;

@Data
public class LeaderboardEntry
{
	private String runescapeName;
	private long totalGp;
	private int splitCount;
	private String rank;
}
