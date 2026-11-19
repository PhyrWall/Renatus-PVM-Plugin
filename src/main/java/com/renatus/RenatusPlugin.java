package com.renatus;

import com.google.inject.Provides;
import com.renatus.model.PlayerProfile;
import com.renatus.model.Split;
import java.awt.image.BufferedImage;
import java.util.HashSet;
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
	private volatile boolean reminderDue;

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

		executor.execute(this::refreshAll);
		refreshTask = executor.scheduleWithFixedDelay(() ->
		{
			if (loginHandled)
			{
				refreshAll();
			}
		}, REFRESH_MINUTES, REFRESH_MINUTES, TimeUnit.MINUTES);
		ownProfileTask = executor.scheduleWithFixedDelay(() ->
		{
			if (loginHandled)
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
		panel = null;
		loginHandled = false;
		reminderDue = false;
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
			knownPendingCodes = null;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		// Treat the first tick with a local player as "just logged in", so the
		// pending-split reminder lands once the chatbox is ready.
		if (loginHandled)
		{
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null || player.getName() == null)
		{
			return;
		}
		loginHandled = true;
		reminderDue = true;
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

		RenatusApiClient.SetupState state = api.setupState();
		if (state != RenatusApiClient.SetupState.READY)
		{
			SwingUtilities.invokeLater(() -> p.showSetup(state));
			return;
		}
		SwingUtilities.invokeLater(p::clearMessage);

		refreshOwnProfile();

		api.fetchClan(
			clan -> SwingUtilities.invokeLater(() -> p.setClan(clan)),
			this::showError);
		api.fetchLeaderboard(config.leaderboardSize(),
			entries -> SwingUtilities.invokeLater(() -> p.setLeaderboard(entries)),
			this::showError);
		api.fetchRecentSplits(RECENT_SPLITS,
			splits -> SwingUtilities.invokeLater(() -> p.setRecentSplits(splits)),
			this::showError);
	}

	/**
	 * Confirms or denies a split from the panel's right-click menu. Discord is
	 * updated server-side (the bot rebuilds the embed), then the profile is
	 * re-fetched so the split drops out of the pending list.
	 */
	void respondToSplit(Split split, boolean approve)
	{
		RenatusPanel p = panel;
		if (p == null || split.getCode() == null || api.apiRoot() == null)
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
		if (p == null || name.isEmpty() || api.apiRoot() == null)
		{
			return;
		}
		api.fetchPlayer(name,
			profile -> SwingUtilities.invokeLater(() -> p.setLookupProfile(name, profile)),
			this::showError);
	}

	private void refreshOwnProfile()
	{
		RenatusPanel p = panel;
		if (p == null || api.apiRoot() == null)
		{
			return;
		}
		// /me is resolved from the API key, so it works for any account the
		// member plays (main or alt) and even while logged out.
		api.fetchMe(profile ->
		{
			SwingUtilities.invokeLater(() -> p.setOwnProfile(profile));
			maybeRemindPending(profile);
			pendingCount = profile != null ? profile.getPendingConfirmations().size() : -1;
			clientThread.invokeLater(() ->
			{
				announceNewPending(profile);
				updateInfoBox();
			});
		}, this::showError);
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
			String message = new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append("[Renatus] ")
				.append(ChatColorType.NORMAL)
				.append("New split to confirm: ")
				.append(ChatColorType.HIGHLIGHT)
				.append(split.getItemName())
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

	private void maybeRemindPending(PlayerProfile profile)
	{
		if (!reminderDue)
		{
			return;
		}
		reminderDue = false;
		if (profile == null || !config.pendingReminder())
		{
			return;
		}

		int pending = profile.getPendingConfirmations().size();
		if (pending == 0)
		{
			return;
		}

		String message = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[Renatus] ")
			.append(ChatColorType.NORMAL)
			.append("You have ")
			.append(ChatColorType.HIGHLIGHT)
			.append(Integer.toString(pending))
			.append(ChatColorType.NORMAL)
			.append(pending == 1 ? " split" : " splits")
			.append(" waiting for your confirmation in Discord.")
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	private void showError(String message)
	{
		RenatusPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.showMessage(message));
		}
	}
}
