package com.renatus.model;

import java.util.Collections;
import java.util.List;
import lombok.Data;

/**
 * A split as returned by the plugin API. {@code runescapeName} is set for
 * clan-wide recent splits, {@code submitter} for pending confirmations.
 */
@Data
public class Split
{
	/** Shown in place of a participant who has since left the clan. */
	public static final String UNKNOWN_NAME = "Unknown";

	private String code;
	private Integer itemId;
	private String itemName;
	private long value;
	private String runescapeName;
	private String submitter;
	private String date;
	/** GE price at submission. 0 on older servers. */
	private long rawValue;
	/** What counts toward rank (raw value after the 300M cap). 0 on older servers. */
	private long splitValue;
	/** RSNs in the split: the submitter first, then tagged members in tag order. Empty on older servers. */
	private List<String> participants = Collections.emptyList();

	public List<String> getParticipants()
	{
		return participants != null ? participants : Collections.emptyList();
	}

	/** What counts toward rank, falling back to {@code value} on servers without split_value. */
	public long effectiveSplitValue()
	{
		return splitValue > 0 ? splitValue : value;
	}

	/** True when the raw value differs from the split value (the cap applied), as the Discord embed shows it. */
	public boolean showRawValue()
	{
		return rawValue > 0 && rawValue != effectiveSplitValue();
	}

	/** True when there's anything to show in the split's detail view. */
	public boolean hasDetails()
	{
		return !getParticipants().isEmpty() || splitValue > 0 || rawValue > 0;
	}

	/** True for a real member name that can be looked up (not blank or {@link #UNKNOWN_NAME}). */
	public static boolean isLookupable(String name)
	{
		return name != null && !name.trim().isEmpty() && !UNKNOWN_NAME.equals(name);
	}
}
