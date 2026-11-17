package com.renatus.model;

import java.util.Collections;
import java.util.List;
import lombok.Data;

@Data
public class PlayerProfile
{
	private String runescapeName;
	private String runescapeNameAlt;
	private boolean active;
	private String joinDate;
	private int daysInClan;
	private long totalGp;
	private int splitCount;
	private Rank rank;
	private Rank nextRank;
	private List<Split> recentSplits = Collections.emptyList();
	private List<Split> pendingConfirmations = Collections.emptyList();
	private int pendingSubmissions;
}
