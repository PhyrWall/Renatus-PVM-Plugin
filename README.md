# Renatus PvM

RuneLite companion plugin for the Renatus PVM OSRS clan. It reads from the clan's
website and shows:

- **Clan profile**:Rank, Days in clan, Total Approve Split GP and Progress to next rank
- **Splits waiting for your confirmation**: splits you've been tagged in but
  haven't confirmed yet in Discord, plus a chat reminder on login
- **Confirm or deny splits from RuneLite**: right-click a split in the panel's
  "Waiting for confirmation" box. It's recorded the same way as the Discord
  buttons, and the bot rebuilds the split's embed in Discord (forwarding it to
  staff once everyone has responded)
- **New split alerts**: while you're logged in, the plugin checks every 15 minutes and
  posts a chat message (plus a notification) when someone tags you in a new split
- **Pending split info box**: an on-screen counter of splits waiting for your confirmation
- **Player lookup**: search any clan member, or click a leaderboard or recent-split row
- **Leaderboard, recent clan splits, and the rank ladder**

Submitting splits and staff approval still happen in Discord.

## Setup

1. In the Renatus Discord, run `/get-api-key`. Only you can see the reply.
2. In RuneLite, open the **Renatus PvM** config and:
   - turn on **Connect to clan server** (off by default because it sends requests to the clan's server)
   - set **Clan server URL** to the clan website's address (e.g. `https://clan.xxx.com`)
   - paste your key into **API key**

The key only works while you're an active clan member. If you forget your API Key or need to reset it run `/get-api-key`
again to issue a new key.