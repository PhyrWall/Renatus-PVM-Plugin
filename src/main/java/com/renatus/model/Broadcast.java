package com.renatus.model;

import lombok.Data;

/** A clan-wide notification from /broadcast, shown at the top of the panel. */
@Data
public class Broadcast
{
	private String title;
	private String message;
	private String author;

	private String date;
}
