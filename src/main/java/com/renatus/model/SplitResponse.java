package com.renatus.model;

import lombok.Data;

/** Result of confirming/denying a split from the plugin. */
@Data
public class SplitResponse
{
	private boolean approve;
	/** True when this was the last response, so the split moved on to staff review. */
	private boolean finalized;
}
