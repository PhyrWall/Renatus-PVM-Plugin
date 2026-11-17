package com.renatus.model;

import java.util.Collections;
import java.util.List;
import lombok.Data;

@Data
public class ClanSummary
{
	private int activeMembers;
	private long totalGp;
	private int splitsThisMonth;
	private int splitsLastMonth;
	private List<Rank> ranks = Collections.emptyList();
}
