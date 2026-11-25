package com.renatus;

import com.renatus.model.Broadcast;
import com.renatus.model.ClanSummary;
import com.renatus.model.LeaderboardEntry;
import com.renatus.model.PlayerProfile;
import com.renatus.model.Rank;
import com.renatus.model.Split;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.ProgressBar;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.QuantityFormatter;

/**
 * Side panel. Every public method must be called on the Swing EDT.
 */
class RenatusPanel extends PluginPanel
{
	static final Color GOLD = new Color(255, 200, 60);
	private static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR;
	static final Color PENDING_RED = new Color(230, 70, 70);

	private final ItemManager itemManager;

	/** Minimum gap between manual refreshes, so the button can't be spammed. */
	private static final int REFRESH_COOLDOWN_SECONDS = 30;

	private final JButton refreshButton = new JButton(refreshLabel(0));
	private Timer cooldownTimer;
	private int cooldownLeft;
	private final JLabel messageLabel = new JLabel();
	private final IconTextField searchField = new IconTextField();
	private final JPanel profilePanel = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
	private final JPanel clanPanel = new JPanel(new GridLayout(2, 2, 4, 4));
	private final JPanel leaderboardList = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
	private final JPanel recentList = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
	private final JPanel ranksList = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
	private final JPanel broadcastList = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
	/** The "Notifications" box; only in the panel while there are broadcasts to show. */
	private JComponent broadcastSection;
	private JComponent header;

	private static final int MAX_SUGGESTIONS = 8;
	private final JPanel suggestionsPanel = new JPanel(new DynamicGridLayout(0, 1, 0, 1));
	private final List<JLabel> suggestionLabels = new ArrayList<>();
	private final List<String> suggestions = new ArrayList<>();
	private List<String> members = Collections.emptyList();
	private int selectedSuggestion = -1;
	// Set while the panel itself changes the search text, so that doesn't reopen the dropdown.
	private boolean updatingSearch;

	private RenatusPlugin plugin;

	// What the profile card shows: our own profile unless a lookup is open.
	private PlayerProfile ownProfile;
	private boolean ownLoaded;
	private String lookupName;
	private PlayerProfile lookupProfile;
	private List<Rank> ranks = Collections.emptyList();
	// Profile splits whose participant list is open; kept across refreshes.
	private final Set<String> expandedSplits = new HashSet<>();
	// Set when loading failed; drops late results until the next load starts.
	private boolean loadFailed;

	@Inject
	RenatusPanel(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	void init(RenatusPlugin plugin)
	{
		this.plugin = plugin;

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new DynamicGridLayout(0, 1, 0, 8));

		JPanel header = new JPanel(new BorderLayout());
		this.header = header;
		header.setOpaque(false);
		JLabel title = new JLabel("Renatus PvM");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(GOLD);
		header.add(title, BorderLayout.WEST);
		refreshButton.setFocusable(false);
		refreshButton.addActionListener(e ->
		{
			plugin.manualRefresh();
			startRefreshCooldown();
		});
		header.add(refreshButton, BorderLayout.EAST);
		add(header);

		broadcastList.setOpaque(false);
		broadcastSection = section("Notifications", broadcastList);

		messageLabel.setForeground(MUTED);
		messageLabel.setFont(FontManager.getRunescapeSmallFont());

		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(PANEL_WIDTH - 20, 30));
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchField.setToolTipText("Look up a clan member");
		searchField.addActionListener(e -> submitSearch());
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				updateSuggestions();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				updateSuggestions();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				updateSuggestions();
			}
		});
		searchField.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				switch (e.getKeyCode())
				{
					case KeyEvent.VK_DOWN:
						selectSuggestion(selectedSuggestion + 1);
						e.consume();
						break;
					case KeyEvent.VK_UP:
						selectSuggestion(selectedSuggestion - 1);
						e.consume();
						break;
					case KeyEvent.VK_ESCAPE:
						hideSuggestions();
						break;
				}
			}
		});
		add(searchField);

		suggestionsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		suggestionsPanel.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));

		profilePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		profilePanel.setBorder(new EmptyBorder(8, 8, 8, 8));
		add(profilePanel);

		clanPanel.setOpaque(false);
		add(section("Clan Details", clanPanel));

		JPanel tabDisplay = new JPanel(new BorderLayout());
		tabDisplay.setOpaque(false);
		MaterialTabGroup tabs = new MaterialTabGroup(tabDisplay);
		tabs.setLayout(new GridLayout(1, 3, 4, 4));
		MaterialTab leaderboardTab = new MaterialTab("Top", tabs, leaderboardList);
		tabs.addTab(leaderboardTab);
		tabs.addTab(new MaterialTab("Recent", tabs, recentList));
		tabs.addTab(new MaterialTab("Ranks", tabs, ranksList));
		tabs.select(leaderboardTab);
		add(section("Clan Activity", tabs, tabDisplay));

		for (JPanel list : new JPanel[]{leaderboardList, recentList, ranksList})
		{
			list.setOpaque(false);
		}

		renderProfile();
		showSetup(RenatusApiClient.SetupState.DISABLED);
	}

	// -- status messages ------------------------------------------------------

	void showSetup(RenatusApiClient.SetupState state)
	{
		if (state != RenatusApiClient.SetupState.READY)
		{
			setBroadcasts(null);
			clearData();
		}
		switch (state)
		{
			case DISABLED:
				showMessage("Enable \"Connect to clan server\" in the plugin config.");
				break;
			case NO_KEY:
				showMessage("Run /get-api-key in the Renatus Discord and paste the key into the plugin config.");
				break;
			default:
				clearMessage();
		}
	}

	void showMessage(String message)
	{
		messageLabel.setText("<html>" + escape(message) + "</html>");
		if (messageLabel.getParent() != this)
		{
			add(messageLabel, 1); // directly under the header
		}
		revalidate();
		repaint();
	}

	void clearMessage()
	{
		if (messageLabel.getParent() == this)
		{
			remove(messageLabel);
			revalidate();
			repaint();
		}
	}

	/** Disables the Refresh button for {@link #REFRESH_COOLDOWN_SECONDS}, counting down on it. */
	private void startRefreshCooldown()
	{
		stopRefreshCooldown();
		cooldownLeft = REFRESH_COOLDOWN_SECONDS;
		refreshButton.setEnabled(false);
		refreshButton.setText(refreshLabel(cooldownLeft));
		cooldownTimer = new Timer(1000, e ->
		{
			cooldownLeft--;
			if (cooldownLeft <= 0)
			{
				stopRefreshCooldown();
			}
			else
			{
				refreshButton.setText(refreshLabel(cooldownLeft));
			}
		});
		cooldownTimer.start();
	}

	/** Clears any running cooldown and re-enables the Refresh button. */
	void stopRefreshCooldown()
	{
		if (cooldownTimer != null)
		{
			cooldownTimer.stop();
			cooldownTimer = null;
		}
		cooldownLeft = 0;
		refreshButton.setEnabled(true);
		refreshButton.setText(refreshLabel(0));
	}

	private static String refreshLabel(int secondsLeft)
	{
		return secondsLeft > 0 ? String.valueOf(secondsLeft) : "Refresh";
	}

	// -- load state -----------------------------------------------------------

	/** Starts a fresh load: data from the server is accepted again. */
	void beginLoad()
	{
		loadFailed = false;
	}

	/**
	 * Loading data failed (bad API key, server down...): empty every section
	 * and show why. Results from the same load that arrive later are dropped
	 * so the panel doesn't half-fill.
	 */
	void loadFailed(String message)
	{
		loadFailed = true;
		clearData();
		showMessage(message);
	}

	/** Empties the profile, clan stats, and the leaderboard, recent and ranks tabs. */
	void clearData()
	{
		ownProfile = null;
		ownLoaded = false;
		lookupName = null;
		lookupProfile = null;
		ranks = Collections.emptyList();
		expandedSplits.clear();
		members = Collections.emptyList();
		searchField.setText("");
		hideSuggestions();

		clanPanel.removeAll();
		leaderboardList.removeAll();
		recentList.removeAll();
		renderProfile();
		renderRanks();
		revalidate();
		repaint();
	}

	// -- data setters ---------------------------------------------------------

	/**
	 * Shows the clan notifications at the top of the panel (under the header
	 * and any status message), or removes the section when there are none.
	 */
	void setBroadcasts(@Nullable List<Broadcast> broadcasts)
	{
		broadcastList.removeAll();
		boolean any = false;
		if (broadcasts != null)
		{
			for (Broadcast broadcast : broadcasts)
			{
				if (broadcast != null && broadcast.getMessage() != null && !broadcast.getMessage().trim().isEmpty())
				{
					broadcastList.add(broadcastRow(broadcast));
					any = true;
				}
			}
		}

		if (!any)
		{
			if (broadcastSection.getParent() == this)
			{
				remove(broadcastSection);
			}
		}
		else if (broadcastSection.getParent() != this)
		{
			// Right after the header, or after the status message when one is showing.
			int index = getComponentZOrder(header) + 1;
			if (messageLabel.getParent() == this)
			{
				index++;
			}
			add(broadcastSection, index);
		}
		revalidate();
		repaint();
	}

	private JComponent broadcastRow(Broadcast broadcast)
	{
		JPanel row = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, GOLD),
			new EmptyBorder(4, 6, 4, 6)));

		if (broadcast.getTitle() != null && !broadcast.getTitle().trim().isEmpty())
		{
			row.add(wrappedText(broadcast.getTitle(), FontManager.getRunescapeBoldFont()));
		}
		row.add(wrappedText(broadcast.getMessage(), FontManager.getRunescapeSmallFont()));

		String footer = date(broadcast.getDate());
		if (!footer.isEmpty())
		{
			row.add(muted(footer));
		}
		return row;
	}

	/**
	 * Read-only text that word-wraps to whatever width the layout gives it.
	 * HTML labels with a CSS width overflow the panel, so use a JTextArea.
	 */
	private static JTextArea wrappedText(String text, Font font)
	{
		JTextArea area = new JTextArea(text.trim().replace("\r\n", "\n"));
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setBorder(null);
		area.setFont(font);
		area.setForeground(Color.WHITE);
		// Prime the width so the first preferred height already accounts for wrapping.
		area.setSize(PANEL_WIDTH - 48, Short.MAX_VALUE);
		return area;
	}

	void setOwnProfile(@Nullable PlayerProfile profile)
	{
		if (loadFailed)
		{
			return;
		}
		ownProfile = profile;
		ownLoaded = true;
		if (lookupName == null)
		{
			renderProfile();
		}
	}

	void setLookupProfile(String name, @Nullable PlayerProfile profile)
	{
		lookupName = name;
		lookupProfile = profile;
		renderProfile();
	}

	void setClan(@Nullable ClanSummary clan)
	{
		if (loadFailed)
		{
			return;
		}
		clanPanel.removeAll();
		if (clan != null)
		{
			clanPanel.add(statBox("Members", Integer.toString(clan.getActiveMembers())));
			clanPanel.add(statBox("Total split", gp(clan.getTotalGp())));
			clanPanel.add(statBox("Splits this month", Integer.toString(clan.getSplitsThisMonth())));
			clanPanel.add(statBox("Last month", Integer.toString(clan.getSplitsLastMonth())));
			ranks = clan.getRanks() != null ? clan.getRanks() : Collections.emptyList();
			renderRanks();
		}
		revalidate();
		repaint();
	}

	void setLeaderboard(@Nullable List<LeaderboardEntry> entries)
	{
		if (loadFailed)
		{
			return;
		}
		leaderboardList.removeAll();
		if (entries != null)
		{
			int position = 1;
			for (LeaderboardEntry entry : entries)
			{
				String rank = entry.getRank() != null ? entry.getRank() + " · " : "";
				JPanel row = row(null,
					"#" + position++ + " " + entry.getRunescapeName(),
					rank + entry.getSplitCount() + " splits",
					gp(entry.getTotalGp()));
				clickToLookup(row, entry.getRunescapeName());
				leaderboardList.add(row);
			}
		}
		revalidate();
		repaint();
	}

	void setRecentSplits(@Nullable List<Split> splits)
	{
		if (loadFailed)
		{
			return;
		}
		recentList.removeAll();
		if (splits != null)
		{
			for (Split split : splits)
			{
				JPanel row = row(split.getItemId(), split.getItemName(), split.getRunescapeName(), gp(split.getValue()));
				clickToLookup(row, split.getRunescapeName());
				if (split.hasDetails())
				{
					row.setComponentPopupMenu(participantsMenu(split));
					for (Component child : row.getComponents())
					{
						((JComponent) child).setInheritsPopupMenu(true);
					}
					String lookupHint = Split.isLookupable(split.getRunescapeName())
						? "Look up " + split.getRunescapeName() + " - " : "";
					row.setToolTipText(lookupHint + "right-click for split details");
				}
				recentList.add(row);
			}
		}
		revalidate();
		repaint();
	}

	/** Clan member names offered as search suggestions. Null leaves the current list alone. */
	void setMembers(@Nullable List<String> names)
	{
		if (loadFailed || names == null)
		{
			return;
		}
		List<String> sorted = new ArrayList<>();
		for (String name : names)
		{
			if (name != null && !name.trim().isEmpty())
			{
				sorted.add(name);
			}
		}
		sorted.sort(String.CASE_INSENSITIVE_ORDER);
		members = sorted;
		updateSuggestions();
	}

	// -- search suggestions ---------------------------------------------------

	/** Enter in the search field: look up the highlighted suggestion, else what was typed. */
	private void submitSearch()
	{
		if (selectedSuggestion >= 0 && selectedSuggestion < suggestions.size())
		{
			lookup(suggestions.get(selectedSuggestion));
		}
		else
		{
			hideSuggestions();
			plugin.lookupPlayer(searchField.getText());
		}
	}

	/** Rebuilds the dropdown from the search text: names starting with it first, then names containing it. */
	private void updateSuggestions()
	{
		if (updatingSearch)
		{
			return;
		}
		String query = normalizeName(searchField.getText());
		suggestions.clear();
		if (!query.isEmpty())
		{
			List<String> contains = new ArrayList<>();
			for (String name : members)
			{
				String normalized = normalizeName(name);
				if (normalized.startsWith(query))
				{
					suggestions.add(name);
				}
				else if (normalized.contains(query))
				{
					contains.add(name);
				}
			}
			suggestions.addAll(contains);
			if (suggestions.size() > MAX_SUGGESTIONS)
			{
				suggestions.subList(MAX_SUGGESTIONS, suggestions.size()).clear();
			}
		}
		renderSuggestions();
	}

	private void hideSuggestions()
	{
		suggestions.clear();
		renderSuggestions();
	}

	private void renderSuggestions()
	{
		selectedSuggestion = -1;
		suggestionsPanel.removeAll();
		suggestionLabels.clear();

		if (suggestions.isEmpty())
		{
			if (suggestionsPanel.getParent() == this)
			{
				remove(suggestionsPanel);
			}
		}
		else
		{
			for (int i = 0; i < suggestions.size(); i++)
			{
				suggestionsPanel.add(suggestionLabel(suggestions.get(i), i));
			}
			if (suggestionsPanel.getParent() != this)
			{
				add(suggestionsPanel, getComponentZOrder(searchField) + 1); // directly under the search field
			}
		}
		revalidate();
		repaint();
	}

	private JLabel suggestionLabel(String name, int index)
	{
		JLabel label = new JLabel(name);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.WHITE);
		label.setOpaque(true);
		label.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		label.setBorder(new EmptyBorder(4, 8, 4, 8));
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					lookup(name);
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				selectSuggestion(index);
			}
		});
		suggestionLabels.add(label);
		return label;
	}

	/** Highlights one suggestion (arrow keys / hover); out of range clears the highlight. */
	private void selectSuggestion(int index)
	{
		if (suggestions.isEmpty())
		{
			return;
		}
		selectedSuggestion = Math.max(-1, Math.min(index, suggestions.size() - 1));
		for (int i = 0; i < suggestionLabels.size(); i++)
		{
			boolean selected = i == selectedSuggestion;
			JLabel label = suggestionLabels.get(i);
			label.setBackground(selected ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
			label.setForeground(selected ? GOLD : Color.WHITE);
		}
	}

	/** Lower-case, with _, - and non-breaking spaces treated as plain spaces, the way RSNs are written interchangeably. */
	private static String normalizeName(String name)
	{
		return name.replace('_', ' ').replace('-', ' ').replace(' ', ' ').trim().toLowerCase(Locale.ROOT);
	}

	// -- rendering ------------------------------------------------------------

	private void renderProfile()
	{
		profilePanel.removeAll();

		boolean lookup = lookupName != null;
		PlayerProfile profile = lookup ? lookupProfile : ownProfile;

		if (lookup)
		{
			JButton back = new JButton("Back to my profile");
			back.setFocusable(false);
			back.addActionListener(e ->
			{
				lookupName = null;
				lookupProfile = null;
				searchField.setText("");
				renderProfile();
			});
			profilePanel.add(back);
		}

		if (profile == null && !lookup)
		{
			profilePanel.add(muted(ownLoaded ? "Couldn't load your clan profile." : "Your clan profile will show here."));
		}
		else if (profile == null)
		{
			profilePanel.add(bold(lookupName, Color.WHITE));
			profilePanel.add(muted("Not on the Renatus roster."));
		}
		else
		{
			renderProfileBody(profile);
		}

		renderRanks();
		profilePanel.revalidate();
		profilePanel.repaint();
	}

	private void renderProfileBody(PlayerProfile profile)
	{
		String displayName = profile.getRunescapeName();
		if (profile.getRunescapeNameAlt() != null)
		{
			displayName += " | " + profile.getRunescapeNameAlt();
		}
		profilePanel.add(bold(displayName, Color.WHITE));

		if (!profile.isActive())
		{
			profilePanel.add(muted("Not an active clan member."));
			return;
		}

		Rank rank = profile.getRank();
		profilePanel.add(bold(rank != null ? rank.getName() : "Unranked", GOLD));

		JPanel stats = new JPanel(new GridLayout(0, 2, 4, 2));
		stats.setOpaque(false);
		stats.add(muted("Days in clan"));
		stats.add(value(Integer.toString(profile.getDaysInClan())));
		stats.add(muted("Total split"));
		stats.add(value(gp(profile.getTotalGp())));
		stats.add(muted("Approved splits"));
		stats.add(value(Integer.toString(profile.getSplitCount())));
		if (profile.getPendingSubmissions() > 0)
		{
			stats.add(muted("Awaiting approval"));
			stats.add(value(Integer.toString(profile.getPendingSubmissions())));
		}
		profilePanel.add(stats);

		Rank next = profile.getNextRank();
		if (next != null)
		{
			profilePanel.add(muted("Next rank: " + next.getName()));
			profilePanel.add(progress(profile.getTotalGp(), next.getGpRequired(),
				gp(profile.getTotalGp()), gp(next.getGpRequired()) + " gp"));
			if (next.getDaysRequired() > 0)
			{
				profilePanel.add(progress(profile.getDaysInClan(), next.getDaysRequired(),
					Integer.toString(profile.getDaysInClan()), next.getDaysRequired() + " days"));
			}
		}
		else
		{
			profilePanel.add(muted("Highest rank reached."));
		}

		if (!profile.getPendingConfirmations().isEmpty())
		{
			profilePanel.add(pendingBox(profile.getPendingConfirmations()));
		}

		if (!profile.getRecentSplits().isEmpty())
		{
			profilePanel.add(bold("Recent Splits", Color.WHITE));
			for (Split split : profile.getRecentSplits())
			{
				profilePanel.add(recentSplitEntry(split));
			}
		}
	}

	private void renderRanks()
	{
		ranksList.removeAll();
		PlayerProfile shown = lookupName != null ? lookupProfile : ownProfile;
		Rank currentRank = shown != null ? shown.getRank() : null;
		String current = currentRank != null ? currentRank.getName() : null;
		for (Rank rank : ranks)
		{
			// Ranks below the one shown are already behind them: list only the current rank and up.
			if (currentRank != null && isBelow(rank, currentRank))
			{
				continue;
			}
			String detail = gp(rank.getGpRequired()) + " gp · " + rank.getDaysRequired() + " days";
			JPanel row = row(null, rank.getName(), detail, "");
			if (rank.getName().equals(current))
			{
				row.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(0, 3, 0, 0, GOLD),
					new EmptyBorder(4, 5, 4, 6)));
			}
			ranksList.add(row);
		}
		ranksList.revalidate();
		ranksList.repaint();
	}

	/** Whether {@code rank} sits below {@code current}: lower gp requirement, or equal gp and fewer days. */
	private static boolean isBelow(Rank rank, Rank current)
	{
		if (rank.getName().equals(current.getName()))
		{
			return false;
		}
		if (rank.getGpRequired() != current.getGpRequired())
		{
			return rank.getGpRequired() < current.getGpRequired();
		}
		return rank.getDaysRequired() < current.getDaysRequired();
	}

	// -- component helpers ----------------------------------------------------

	/** Right-click menu on a pending split: confirm/deny or copy its code. */
	private JPopupMenu responseMenu(Split split, String code)
	{
		JPopupMenu menu = new JPopupMenu();

		JMenuItem confirm = new JMenuItem("Confirm split");
		confirm.addActionListener(e -> plugin.respondToSplit(split, true));
		menu.add(confirm);

		JMenuItem deny = new JMenuItem("Deny split");
		deny.addActionListener(e ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"Deny the " + split.getItemName() + " split from " + split.getSubmitter() + "?\n"
					+ "You can't change your answer afterwards.",
				"Deny split " + code, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
			{
				plugin.respondToSplit(split, false);
			}
		});
		menu.add(deny);

		menu.addSeparator();
		JMenuItem copy = new JMenuItem("Copy code");
		copy.addActionListener(e -> copyCode(code));
		menu.add(copy);
		return menu;
	}

	private void copyCode(String code)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
		showMessage("Copied split code " + code);
	}

	private JPanel pendingBox(List<Split> splits)
	{
		JPanel box = new JPanel(new DynamicGridLayout(0, 1, 0, 3));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(PENDING_RED, 2),
			new EmptyBorder(5, 5, 5, 5)));
		box.add(bold("Waiting for confirmation (" + splits.size() + ")", PENDING_RED));

		for (Split split : splits)
		{
			String code = split.getCode() != null ? split.getCode() : "";
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			row.setBorder(new EmptyBorder(4, 6, 4, 6));

			if (split.getItemId() != null && split.getItemId() > 1)
			{
				JLabel icon = new JLabel();
				icon.setPreferredSize(new Dimension(36, 32));
				itemManager.getImage(split.getItemId()).addTo(icon);
				row.add(icon, BorderLayout.WEST);
			}

			// participants is submitter first, then the tagged members
			List<String> participants = split.getParticipants();
			StringBuilder with = new StringBuilder();
			if (participants.size() > 1)
			{
				with.append("<br><font color='#a5a5a5'>with:");
				for (String name : participants.subList(1, participants.size()))
				{
					with.append("<br>&nbsp;&nbsp;- ").append(escape(name));
				}
				with.append("</font>");
			}
			long splitValue = split.effectiveSplitValue();
			String values = (splitValue > 0 ? "<br>Split Value: " + gp(splitValue) + " gp" : "")
				+ (split.showRawValue() ? "<br><font color='#a5a5a5'>Raw Value: " + gp(split.getRawValue()) + " gp</font>" : "");
			JLabel text = new JLabel("<html><body style='width:130px'>" + escape(split.getItemName())
				+ "<br><font color='#a5a5a5'>from " + escape(split.getSubmitter()) + "</font>"
				+ with
				+ values
				+ "<br><font color='#ffc83c'>Code: " + escape(code) + "</font></body></html>");
			text.setFont(FontManager.getRunescapeSmallFont());
			text.setForeground(Color.WHITE);
			row.add(text, BorderLayout.CENTER);

			if (!code.isEmpty())
			{
				row.setToolTipText("Right-click to confirm or deny - left-click to copy code " + code);
				row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				row.setComponentPopupMenu(responseMenu(split, code));
				for (Component child : row.getComponents())
				{
					((JComponent) child).setInheritsPopupMenu(true);
				}
				row.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseClicked(MouseEvent e)
					{
						if (!SwingUtilities.isLeftMouseButton(e))
						{
							return;
						}
						copyCode(code);
					}

					@Override
					public void mouseEntered(MouseEvent e)
					{
						row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
					}

					@Override
					public void mouseExited(MouseEvent e)
					{
						row.setBackground(ColorScheme.DARK_GRAY_COLOR);
					}
				});
			}
			box.add(row);
		}
		return box;
	}

	private JPanel row(@Nullable Integer itemId, String title, String subtitle, String right)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(4, 8, 4, 6));

		if (itemId != null && itemId > 1)
		{
			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(36, 32));
			itemManager.getImage(itemId).addTo(icon);
			row.add(icon, BorderLayout.WEST);
		}

		JLabel text = new JLabel("<html>" + escape(title) + "<br><font color='#a5a5a5'>" + escape(subtitle) + "</font></html>");
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setForeground(Color.WHITE);
		row.add(text, BorderLayout.CENTER);

		if (!right.isEmpty())
		{
			JLabel value = new JLabel(right, SwingConstants.RIGHT);
			value.setFont(FontManager.getRunescapeSmallFont());
			value.setForeground(GOLD);
			row.add(value, BorderLayout.EAST);
		}
		return row;
	}

	/**
	 * "Your Recent Splits" row. When the server sends details, left-click
	 * toggles its values and participant list under the row.
	 */
	private JComponent recentSplitEntry(Split split)
	{
		JPanel row = row(split.getItemId(), split.getItemName(), date(split.getDate()), gp(split.getValue()));
		List<String> participants = split.getParticipants();
		if (!split.hasDetails())
		{
			return row;
		}

		String key = split.getCode() != null ? split.getCode() : split.getItemName() + "|" + split.getDate();
		boolean expanded = expandedSplits.contains(key);

		row.setToolTipText((expanded ? "Hide" : "Show") + " split details");
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!SwingUtilities.isLeftMouseButton(e))
				{
					return;
				}
				if (!expandedSplits.remove(key))
				{
					expandedSplits.add(key);
				}
				renderProfile();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		if (!expanded)
		{
			return row;
		}

		JPanel entry = new JPanel(new DynamicGridLayout(0, 1, 0, 0));
		entry.setOpaque(false);
		entry.add(row);

		JPanel list = new JPanel(new DynamicGridLayout(0, 1, 0, 1));
		list.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		list.setBorder(new EmptyBorder(2, 16, 4, 6));

		JPanel values = new JPanel(new GridLayout(0, 2, 4, 1));
		values.setOpaque(false);
		long splitValue = split.effectiveSplitValue();
		if (splitValue > 0)
		{
			values.add(muted("Split Value"));
			values.add(value(gp(splitValue) + " gp"));
		}
		if (split.showRawValue())
		{
			values.add(muted("Raw Value"));
			values.add(value(gp(split.getRawValue()) + " gp"));
		}
		if (values.getComponentCount() > 0)
		{
			list.add(values);
		}

		if (!participants.isEmpty())
		{
			list.add(muted("In this split (" + participants.size() + ")"));
			for (int i = 0; i < participants.size(); i++)
			{
				list.add(participantLabel(participants.get(i), i == 0));
			}
		}
		entry.add(list);
		return entry;
	}

	/** clickable participant name to look them up unless they've left the clan. */
	private JLabel participantLabel(String name, boolean submitter)
	{
		JLabel label = new JLabel(displayName(name) + (submitter ? " (submitter)" : ""));
		label.setFont(FontManager.getRunescapeSmallFont());
		if (!Split.isLookupable(name))
		{
			label.setForeground(MUTED);
			label.setToolTipText("No longer in the clan");
			return label;
		}
		label.setForeground(Color.WHITE);
		label.setToolTipText("Look up " + name);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					lookup(name);
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setForeground(GOLD);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setForeground(Color.WHITE);
			}
		});
		return label;
	}

	/**
	 * Right-click menu on a clan recent split: its values, then its participants;
	 * picking a participant looks them up.
	 */
	private JPopupMenu participantsMenu(Split split)
	{
		JPopupMenu menu = new JPopupMenu();
		long splitValue = split.effectiveSplitValue();
		if (splitValue > 0)
		{
			menu.add(infoItem("Split Value: " + gp(splitValue) + " gp"));
		}
		if (split.showRawValue())
		{
			menu.add(infoItem("Raw Value: " + gp(split.getRawValue()) + " gp"));
		}

		List<String> participants = split.getParticipants();
		if (participants.isEmpty())
		{
			return menu;
		}
		if (menu.getComponentCount() > 0)
		{
			menu.addSeparator();
		}
		menu.add(infoItem("In this split:"));
		for (int i = 0; i < participants.size(); i++)
		{
			String name = participants.get(i);
			JMenuItem item = new JMenuItem(displayName(name) + (i == 0 ? " (submitter)" : ""));
			if (Split.isLookupable(name))
			{
				item.addActionListener(e -> lookup(name));
			}
			else
			{
				item.setEnabled(false);
			}
			menu.add(item);
		}
		return menu;
	}

	/** A non-clickable menu line. */
	private static JMenuItem infoItem(String text)
	{
		JMenuItem item = new JMenuItem(text);
		item.setEnabled(false);
		return item;
	}

	private void lookup(String name)
	{
		updatingSearch = true;
		try
		{
			searchField.setText(name);
		}
		finally
		{
			updatingSearch = false;
		}
		hideSuggestions();
		plugin.lookupPlayer(name);
	}

	private static String displayName(String name)
	{
		return name == null || name.trim().isEmpty() ? Split.UNKNOWN_NAME : name;
	}

	private void clickToLookup(JPanel row, String name)
	{
		if (!Split.isLookupable(name))
		{
			return;
		}
		row.setToolTipText("Look up " + name);
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// left-click only: right-click opens the participants menu
				if (SwingUtilities.isLeftMouseButton(e))
				{
					lookup(name);
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
	}

	/** An outlined box with a gold title bar on top, holding the given components stacked vertically. */
	private static JComponent section(String title, JComponent... contents)
	{
		JPanel box = new JPanel(new DynamicGridLayout(0, 1, 0, 6));
		box.setOpaque(false);
		box.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(4, 6, 6, 6)));
		box.add(sectionDivider(title));
		for (JComponent content : contents)
		{
			box.add(content);
		}
		return box;
	}

	/** A titled gold bar heading a section. */
	private static JComponent sectionDivider(String text)
	{
		JPanel divider = new JPanel(new BorderLayout());
		divider.setOpaque(false);
		divider.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 2, 0, GOLD),
			new EmptyBorder(4, 2, 3, 2)));
		divider.add(bold(text, GOLD), BorderLayout.WEST);
		return divider;
	}

	private JComponent statBox(String label, String value)
	{
		JPanel box = new JPanel(new BorderLayout());
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(new EmptyBorder(4, 6, 4, 6));
		box.add(muted(label), BorderLayout.NORTH);
		box.add(value(value), BorderLayout.CENTER);
		return box;
	}

	/**
	 * ProgressBar lays its left/center/right labels out in three equal columns,
	 * so each label only gets a third of the width. Keep each one short:
	 * current on the left, percent in the middle, target on the right.
	 */
	private static ProgressBar progress(long current, long required, String left, String right)
	{
		ProgressBar bar = new ProgressBar();
		int percent = required <= 0 ? 100 : (int) Math.min(100, current * 100 / required);
		bar.setMaximumValue(100);
		bar.setValue(percent);
		bar.setLeftLabel(left);
		bar.setCenterLabel(percent + "%");
		bar.setRightLabel(right);
		bar.setForeground(percent >= 100 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR);
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bar.setPreferredSize(new Dimension(PANEL_WIDTH - 36, 16));
		return bar;
	}

	private static JLabel bold(String text, Color color)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(color);
		return label;
	}

	private static JLabel muted(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(MUTED);
		return label;
	}

	private static JLabel value(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.WHITE);
		return label;
	}

	private static String gp(long amount)
	{
		return QuantityFormatter.quantityToStackSize(amount);
	}

	private static String date(String iso)
	{
		return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : "";
	}

	private static String escape(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
