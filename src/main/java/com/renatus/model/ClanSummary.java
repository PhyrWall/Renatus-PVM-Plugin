package com.renatus.model;

import lombok.Data;

@Data
public class ClanSummary
{
	private int activeMembers;
	private long totalGp;
	private int splitsThisMonth;
	private int splitsLastMonth;
}
