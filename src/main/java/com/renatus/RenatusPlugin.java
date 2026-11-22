package com.renatus;

import com.google.inject.Provides;
import com.renatus.model.PlayerProfile;
import com.renatus.model.Split;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.externalplugins.PluginHubManifest;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Renatus PvM",
	description = "Renatus clan companion: rank progress, splits and leaderboard",
	tags = {"renatus", "clan", "splits", "rank"}
)
public class RenatusPlugin extends Plugin
{
	private static final long REFRESH_MINUTES = 15;
	/** How often /me is polled while logged in, to catch newly tagged splits. Matches the server's cache TTL. */
	private static final long OWN_PROFILE_POLL = 5;
	private static final int RECENT_SPLITS = 5;

	private static final int REMINDER_DELAY_TICKS = 10;
	private static final int CHAT_ICON_SIZE = 13;
	private static final int REMINDER_MAX_NAMES = 3;
	private static final String LOGGED_OUT_MESSAGE = "Log in to view Renatus PvM.";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private RenatusConfig config;

	@Inject
	private RenatusApiClient api;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Notifier notifier;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ChatIconManager chatIconManager;
	private int chatIconId = -1;

	private RenatusPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> refreshTask;
	private ScheduledFuture<?> ownProfileTask;

	private Set<String> knownPendingCodes;
	private BufferedImage infoBoxImage;
	/** Only touched on the client thread. */
	private PendingSplitsInfoBox pendingInfoBox;
	/** Last pending-confirmation count from /me API; -1 until loaded. */
	private volatile int pendingCount = -1;

	private volatile boolean loginHandled;
	// The last data load failed and the panel was emptied; the next poll reloads everything.
	private volatile boolean loadFailed;
	private volatile boolean reminderDue;

	private int ticksSinceLogin;
	private PlayerProfile reminderProfile;

	@Override
	protected void startUp()
	{
		panel = injector.getInstance(RenatusPanel.class);
		panel.init(this);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		infoBoxImage = ImageUtil.loadImageResource(getClass(), "infobox.png");
		navButton = NavigationButton.builder()
			.tooltip("Renatus PvM")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		if (chatIconId == -1)
		{
			chatIconId = chatIconManager.registerChatIcon(
				ImageUtil.resizeImage(infoBoxImage, CHAT_ICON_SIZE, CHAT_ICON_SIZE));
		}

		// Nothing is fetched until the first game tick while logged in.
		showLoggedOut();
		refreshTask = executor.scheduleWithFixedDelay(() ->
		{
			if (loginHandled)
			{
				refreshAll();
			}
		}, REFRESH_MINUTES, REFRESH_MINUTES, TimeUnit.MINUTES);
		ownProfileTask = executor.scheduleWithFixedDelay(() ->
		{
			if (!loginHandled)
			{
				return;
			}
			if (loadFailed)
			{
				refreshAll();
			}
			else
			{
				refreshOwnProfile();
			}
		}, OWN_PROFILE_POLL, OWN_PROFILE_POLL, TimeUnit.MINUTES);
	}

	@Override
	protected void shutDown()
	{
		if (refreshTask != null)
		{
			refreshTask.cancel(false);
			refreshTask = null;
		}
		if (ownProfileTask != null)
		{
			ownProfileTask.cancel(false);
			ownProfileTask = null;
		}
		knownPendingCodes = null;
		clientToolbar.removeNavigation(navButton);
		infoBoxManager.removeIf(PendingSplitsInfoBox.class::isInstance);
		pendingInfoBox = null;
		pendingCount = -1;
		navButton = null;
		RenatusPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(p::stopRefreshCooldown);
		}
		panel = null;
		loginHandled = false;
		reminderDue = false;
		reminderProfile = null;
	}

	@Provides
	RenatusConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RenatusConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			loginHandled = false;
			reminderDue = false;
			reminderProfile = null;
			knownPendingCodes = null;
			pendingCount = -1;
			updateInfoBox();
			showLoggedOut();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		// Treat the first tick with a local player as "just logged in", so the
		// pending-split reminder lands once the chatbox is ready.
		if (loginHandled)
		{
			ticksSinceLogin++;
			maybeRemindPending();
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null || player.getName() == null)
		{
			return;
		}
		loginHandled = true;
		reminderDue = true;
		reminderProfile = null;
		ticksSinceLogin = 0;
		refreshAll();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!RenatusConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		clientThread.invokeLater(this::updateInfoBox);
		refreshAll();
	}

	/** Re-fetches everything. Safe to call from any thread. */
	void refreshAll()
	{
		RenatusPanel p = panel;
		if (p == null)
		{
			return;
		}
		if (!loginHandled)
		{
			showLoggedOut();
			return;
		}

		RenatusApiClient.SetupState state = api.setupState();
		if (state != RenatusApiClient.SetupState.READY)
		{
			SwingUtilities.invokeLater(() -> p.showSetup(state));
			return;
		}
		loadFailed = false;
		SwingUtilities.invokeLater(() ->
		{
			p.beginLoad();
			p.clearMessage();
		});

		refreshOwnProfile();

		api.fetchClan(
			clan -> SwingUtilities.invokeLater(() -> p.setClan(clan)),
			this::loadError);
		api.fetchLeaderboard(config.leaderboardSize(),
			entries -> SwingUtilities.invokeLater(() -> p.setLeaderboard(entries)),
			this::loadError);
		api.fetchRecentSplits(RECENT_SPLITS,
			splits -> SwingUtilities.invokeLater(() -> p.setRecentSplits(splits)),
			this::loadError);
		// A failure won't  empty the panel.
		api.fetchMembers(
			names -> SwingUtilities.invokeLater(() -> p.setMembers(names)),
			error -> log.debug("Couldn't load clan member names: {}", error));
	}

	/**
	 * Confirms or denies a split from the panel's right-click menu. Discord is
	 * updated server-side (the bot rebuilds the embed), then the profile is
	 * re-fetched so the split drops out of the pending list.
	 */
	void respondToSplit(Split split, boolean approve)
	{
		RenatusPanel p = panel;
		if (p == null || !loginHandled || split.getCode() == null || api.apiRoot() == null)
		{
			return;
		}
		String verb = approve ? "Confirmed" : "Denied";
		SwingUtilities.invokeLater(() -> p.showMessage((approve ? "Confirming " : "Denying ") + split.getItemName() + "..."));
		api.respondToSplit(split.getCode(), approve, result ->
		{
			String message = verb + " " + split.getItemName() + " from " + split.getSubmitter() + " (" + split.getCode() + ")"
				+ (result != null && result.isFinalized() ? ". Sent to staff for approval." : ".");
			SwingUtilities.invokeLater(() -> p.showMessage(message));
			refreshOwnProfile();
		}, this::showError);
	}

	/** Looks up any clan member by name and shows them in the panel. */
	void lookupPlayer(String rsn)
	{
		RenatusPanel p = panel;
		String name = Text.toJagexName(rsn);
		if (p == null || !loginHandled || name.isEmpty() || api.apiRoot() == null)
		{
			return;
		}
		api.fetchPlayer(name, profile ->
		{
			if (loginHandled)
			{
				SwingUtilities.invokeLater(() -> p.setLookupProfile(name, profile));
			}
		}, this::showError);
	}

	private void refreshOwnProfile()
	{
		RenatusPanel p = panel;
		if (p == null || !loginHandled || api.apiRoot() == null)
		{
			return;
		}
		// /me is resolved from the API key, so it works for any account the
		// member plays (main or alt).
		api.fetchMe(profile ->
		{
			if (!loginHandled)
			{
				return; // logged out while the request was in flight
			}
			SwingUtilities.invokeLater(() -> p.setOwnProfile(profile));
			pendingCount = profile != null ? profile.getPendingConfirmations().size() : -1;
			clientThread.invokeLater(() ->
			{
				reminderProfile = profile;
				maybeRemindPending();
				announceNewPending(profile);
				updateInfoBox();
			});
		}, this::loadError);
	}

	/**
	 * Posts a chat message for each split that started waiting on the player
	 * since the last /me result. The first result of a login only records
	 * what's already there (the login reminder covers those). Client thread only.
	 */
	private void announceNewPending(PlayerProfile profile)
	{
		if (profile == null)
		{
			return;
		}
		if (!loginHandled)
		{
			knownPendingCodes = null;
			return;
		}

		Set<String> current = new HashSet<>();
		for (Split split : profile.getPendingConfirmations())
		{
			if (split.getCode() != null)
			{
				current.add(split.getCode());
			}
		}

		Set<String> known = knownPendingCodes;
		knownPendingCodes = current;
		if (known == null || !config.newSplitAlert())
		{
			return;
		}

		for (Split split : profile.getPendingConfirmations())
		{
			if (split.getCode() == null || known.contains(split.getCode()))
			{
				continue;
			}
			String message = chatPrefix()
				.append(ChatColorType.NORMAL)
				.append("New split to confirm: ")
				.append(RenatusPanel.PENDING_RED, split.getItemName())
				.append(ChatColorType.NORMAL)
				.append(" from " + split.getSubmitter() + " (code " + split.getCode() + "). Confirm it in Discord.")
				.build();
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(message)
				.build());
			notifier.notify("Renatus: new split to confirm - " + split.getItemName() + " from " + split.getSubmitter());
		}
	}

	/** Adds, updates or removes the pending-splits info box. Client thread only. */
	private void updateInfoBox()
	{
		int count = pendingCount;
		boolean show = config.pendingInfoBox() && count > 0 && api.apiRoot() != null;
		if (!show)
		{
			if (pendingInfoBox != null)
			{
				infoBoxManager.removeInfoBox(pendingInfoBox);
				pendingInfoBox = null;
			}
			return;
		}
		if (pendingInfoBox == null)
		{
			pendingInfoBox = new PendingSplitsInfoBox(infoBoxImage, this, count);
			infoBoxManager.addInfoBox(pendingInfoBox);
		}
		else
		{
			pendingInfoBox.setCount(count);
		}
	}

	/**
	 * Sends the login reminder once {@link #REMINDER_DELAY_TICKS} have passed
	 * and /me has loaded, whichever comes last. Client thread only.
	 */
	private void maybeRemindPending()
	{
		PlayerProfile profile = reminderProfile;
		if (!reminderDue || !loginHandled || ticksSinceLogin < REMINDER_DELAY_TICKS || profile == null)
		{
			return;
		}
		reminderDue = false;
		if (!config.pendingReminder())
		{
			return;
		}

		int pending = profile.getPendingConfirmations().size();
		if (pending == 0)
		{
			return;
		}

		Set<String> submitters = new LinkedHashSet<>();
		for (Split split : profile.getPendingConfirmations())
		{
			if (split.getSubmitter() != null && !split.getSubmitter().isEmpty())
			{
				submitters.add(split.getSubmitter());
			}
		}

		ChatMessageBuilder builder = chatPrefix()
			.append(ChatColorType.NORMAL)
			.append("You have ")
			.append(RenatusPanel.PENDING_RED, Integer.toString(pending))
			.append(ChatColorType.NORMAL)
			.append(pending == 1 ? " split" : " splits");
		if (!submitters.isEmpty())
		{
			builder.append(" from ");
			appendNames(builder, new ArrayList<>(submitters));
		}
		String message = builder
			.append(ChatColorType.NORMAL)
			.append(" waiting for your confirmation.")
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	/**
	 * Appends "A", "A and B", "A, B and C" or "A, B, C and N others",
	 * with the names highlighted.
	 */
	private static void appendNames(ChatMessageBuilder builder, List<String> names)
	{
		int shown = Math.min(names.size(), REMINDER_MAX_NAMES);
		int others = names.size() - shown;
		for (int i = 0; i < shown; i++)
		{
			if (i > 0)
			{
				boolean last = i == shown - 1 && others == 0;
				builder.append(ChatColorType.NORMAL).append(last ? " and " : ", ");
			}
			builder.append(ChatColorType.HIGHLIGHT).append(names.get(i));
		}
		if (others > 0)
		{
			builder.append(ChatColorType.NORMAL)
				.append(" and " + others + (others == 1 ? " other" : " others"));
		}
	}

	/** Starts a chat message with the Renatus icon (once loaded) and a gold [Renatus] tag. */
	private ChatMessageBuilder chatPrefix()
	{
		ChatMessageBuilder builder = new ChatMessageBuilder();
		int iconIndex = chatIconId != -1 ? chatIconManager.chatIconIndex(chatIconId) : -1;
		if (iconIndex != -1)
		{
			builder.img(iconIndex).append(" ");
		}
		return builder.append(RenatusPanel.GOLD, "[Renatus] ");
	}

	/** A one-off action (split response, lookup) failed: just show why. */
	private void showError(String message)
	{
		RenatusPanel p = panel;
		if (p != null && loginHandled)
		{
			SwingUtilities.invokeLater(() -> p.showMessage(message));
		}
	}

	/**
	 * Loading the panel's data failed (bad API key, server down...): empty
	 * the panel and pending info box so nothing stale is shown.
	 */
	private void loadError(String message)
	{
		if (!loginHandled)
		{
			return; // logged out while the request was in flight
		}
		if (api.isRateLimited())
		{
			showError(message);
			return;
		}
		loadFailed = true;
		pendingCount = -1;
		clientThread.invokeLater(this::updateInfoBox);
		RenatusPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.loadFailed(message));
		}
	}

	/** Empties the panel and asks the player to log in. Late results are dropped. */
	private void showLoggedOut()
	{
		RenatusPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.loadFailed(LOGGED_OUT_MESSAGE));
		}
	}

	static String getPluginVersion()
	{
		PluginHubManifest.DisplayData displayData = ExternalPluginManager.getDisplayData(RenatusPlugin.class);
		if (displayData != null && displayData.getVersion() != null)
		{
			return displayData.getVersion();
		}
		return "DEV";
	}
}
