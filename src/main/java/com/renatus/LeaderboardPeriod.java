package com.renatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LeaderboardPeriod
{
	ALL_TIME("All time", 0, "Top"),
	THIRTY_DAYS("30 days", 30, "30 Days"),
	SEVEN_DAYS("7 days", 7, "7 Days");

	private final String name;
	/** Sent as the API's {@code days} parameter; 0 means it is left off (all time). */
	private final int days;
	private final String tabLabel;

	@Override
	public String toString()
	{
		return name;
	}
}
