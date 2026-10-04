<div align="center">

# Nuvio + IPTV

**Everything in Nuvio, plus everything IPTV: a TiviMate-style Live TV guide, catch-up, and your provider's movies and series, all in one app for Google TV, Android TV and Fire TV.**

[![Latest release](https://img.shields.io/github/v/release/homelessbrian/NuvioTV?include_prereleases&label=latest&style=for-the-badge)](https://github.com/homelessbrian/NuvioTV/releases)
[![Downloads](https://img.shields.io/github/downloads/homelessbrian/NuvioTV/total?style=for-the-badge)](https://github.com/homelessbrian/NuvioTV/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge)](LICENSE)
[![Based on Nuvio](https://img.shields.io/badge/based%20on-NuvioMedia%2FNuvioTV-8A2BE2?style=for-the-badge)](https://github.com/NuvioMedia/NuvioTV)

[What's new](#-whats-new) · [Download](#-installation) · [Features](#-features) · [Setup](#-getting-started) · [Remote controls](#-remote-controls) · [Report a bug](#-reporting-bugs-and-requesting-features)

</div>

---

## 📛 New name: Nuvio + IPTV

**Nuvio w/ Live TV is now Nuvio + IPTV.** The app has grown well beyond Live TV, with On Demand, catch-up, reminders and much more, so it now has a name that says what it does: **Nuvio, plus IPTV**.

**Nothing changes for you.** It's the same app, updated the usual way inside the app. Your playlists, favorites, settings and Google Drive sync all stay exactly as they are. You'll just see the new name under the app icon and on the loading screen.

---

## 🆕 What's new

**Now based on Nuvio 1.1.0-beta.4.**

- **Live TV row on the home screen:**
  - **What it shows:** your favorite channels with what's on now, in a slim row next to Continue watching.
  - **Make it your own:** put it above or below Continue watching, pick what it shows, rename it, change its order and size, or hide it completely.
- **Instant start:**
  - **Live TV opens straight away,** with your channels and Favorites.
  - **The TV guide is stored on the device like TiviMate's,** so it appears instantly however large it is, and uses much less memory.
- **On Demand:**
  - **Browse by genre** (filled in automatically from TMDB).
  - **A Sort by menu.**
  - **Duplicates merged:** with **a version picker** (4K, HD and so on) when you press Play.
  - **Cleaner titles and better posters:** provider tags like "EN - " or "4K-D+ - " are removed.
- **Catch-up:**
  - **It keeps playing into the next show,** so shows that run over aren't cut off.
  - **Watch from the beginning** works from the guide.
  - **Fixed replays starting an hour off** on some providers.
- **Fixes and polish:**
  - **"Playback unavailable"** on titles only your provider has
  - **A frozen picture** when going full screen
  - **Easier Assign EPG**
  - **Hold Back** works on all remotes

---

## ✨ Features

### 📺 A proper TV guide
- **TiviMate-style guide:** channels down the side, a timeline across, and a line marking "now" (which you can hide). It fits **8 or more channels** on screen.
- **Group list:**
  - **Opening it:** it slides out with a tap of Left.
  - **Grouping:** optionally grouped by playlist.
  - **Browse by channel name** (satellite-box style).
- **Info panel** with the show's poster (or just the channel logo, if you prefer), time left, description, the playlist it comes from, and a live preview window.
- **Make room for more channels:** compact rows, a smaller info panel, and hiding numbers, logos or names.
- **Badges:** catch-up icons on channels that support it, and 4K / FHD / HD / SD badges on channels you've watched.

### 🎬 Overlay mode (while watching)
- Press **Left** in full screen for a see-through guide over the video: the group's channels, the highlighted channel's schedule, and the show's details.
- **Left again** switches groups. **Right** opens the channel's full schedule, with a date picker.

### 📡 Your sources, your way
- **Multiple M3U playlists** and **multiple XMLTV guides,** merged into one guide.
- **Xtream Codes logins:**
  - **Pick what to include:** TV channels, movies & series, or both.
  - **Stream format:** Auto, TS or HLS.
- **Smart guide matching:** real listings always beat "Programming" placeholders. Fine-tune any channel with **Assign EPG**.
- **Catch-up:**
  - **Watch past shows** with a seek bar, or **restart what's on now**.
  - **Follow-on playback:** carries straight on into the next show.
- **Channel name editor** (like TiviMate): strip "USA:", "HD", "24/7" and similar from channel names.

### 🗂️ Channel management
- **Manage visibility** and **reorder** for channels *and* groups.
- **Copy, rename and renumber** channels, **create your own groups,** and **number channels 1, 2, 3…** in each group.
- **Favorites** and **Recently watched,** with sorting that works everywhere.

### 🎞️ On Demand
- **What it is:** movies and series from your Xtream providers, with **categories or genres**, **Recently added**, **Search** and **Sort by**.
- **Your posters, not the provider's:** posters and details come from your own addons. Matched titles open on Nuvio's normal details page.
- **📡 On Demand** in Nuvio's stream list, with quality, codec and audio badges. Play on a title opened from On Demand plays your provider's copy straight away; long-press Play for every stream.
- **Each title once:** duplicates are merged, and a picker lets you choose the version when there are several.

### 🔔 Watching
- **Zap** with Up/Down, type a channel number, or jump to the **last channel**.
- **Reminders:** "Remind me" on any upcoming show, with a pop-up and a Watch button.
- **Player menu:** audio, **closed captions**, screen size, stream info, **sleep timer**, find & stream, and watch from the beginning.
- **Smooth playback:**
  - **Match frame rate** and **audio passthrough** settings.
  - **Auto-reconnect,** plus automatic recovery from frozen or stuck streams.

### 🔒 Parental controls
- **PIN locks:** a PIN that locks any group or category. Adult content locks automatically.

### 🔎 Part of Nuvio, not bolted on
- **In the side menu:** Live TV and On Demand sit in Nuvio's side menu, with their settings inside Nuvio's own Settings.
- **Start page:** open the app on Home, Live TV, On Demand and more.
- **Live TV in Nuvio search:**
  - **From the menu:** search shows everything.
  - **From Live TV:** search shows only Live TV.
- **Find & stream in Nuvio:** see a movie in the guide, and stream it through your own addons and debrid.
- **Google Drive sync:** your setup on every TV, combined correctly when you use more than one.

---

## 📥 Installation

1. Open the **[latest release](https://github.com/homelessbrian/NuvioTV/releases)**.
2. Download the APK for your device:

   | Device | APK |
   |---|---|
   | Most Google TV / Android TV devices | `arm64-v8a` |
   | Fire TV (all models), older or budget boxes | `armeabi-v7a` |
   | Not sure (works everywhere) | `universal` |

3. Install it with a file manager or **Downloader**, allowing installs from unknown sources when asked.

Requires **Android 7.0 or newer** (Fire OS 6 or later). It installs **alongside** the official Nuvio app.

**Updating:** updates appear **inside the app**. Each new Nuvio release is followed by a matching Nuvio + IPTV version.

---

## 🚀 Getting started

1. Open **Settings → Live TV**.
2. Choose **Add M3U playlist** or **Add Xtream Codes login**. For Xtream, pick **Include TV channels** and/or **Include movies & series**.
3. Optionally **Add EPG source** for an extra TV guide.
4. Open **Live TV** or **On Demand** from the side menu. The first load can take a minute on big providers; a small progress bubble shows what's happening.
5. Optional, all in **Settings → Live TV**:
   - set a **Start page**
   - turn on the **home screen row**
   - set a parental **PIN**
   - turn on **Google Drive sync**

---

## 🎮 Remote controls

### In the guide
| Button | Action |
|---|---|
| **OK** on what's on now | Play in the preview · again for full screen |
| **OK** on a later show | Show info · **Remind me** |
| **OK** on an earlier show | Play from catch-up |
| **◀** (tap) on what's on now | Open the groups |
| **◀** (hold) | Scroll back through earlier shows (catch-up channels) |
| **▲ / ▼** · **CH+ / CH−** | Channels · page up / down |
| **0–9** | Jump to a channel number |
| **Long-press OK** | Channel menu: favorites, **Remind me**, **Watch from the beginning**, hide, **Manage visibility**, **Reorder**, **Copy**, rename, renumber, **Assign EPG** |
| **Hold Back** or **Play/Pause** | Back to full screen on what's playing |
| **Back** | Back to "now" → the group list → the side menu |

### Full screen
| Button | Action |
|---|---|
| **▲ / ▼**, **CH+ / CH−** | Next / previous channel |
| **OK** | Info bar (catch-up: pause / play) |
| **◀** | Overlay mode (catch-up: skip back) |
| **▶** | Catch-up: skip forward (hold to skip faster) |
| **Long-press OK** or **Menu** | Audio, subtitles, screen size, stream info, sleep timer, watch from the beginning, find & stream |
| **Back** | Back to the guide |

### On Demand
| Button | Action |
|---|---|
| **▲ / ▼** in the categories | Browse; each category opens as you highlight it |
| **▶** from the categories | Into the posters (the categories slide away) |
| **◀** from the first column, or **Back** | Categories again |
| **Long-press a category** | Manage visibility · Lock with PIN |

---

## 🐞 Reporting bugs and requesting features

Please search [existing issues](https://github.com/homelessbrian/NuvioTV/issues) first.

| | |
|---|---|
| 🐛 **Something isn't working** | [Report a bug](https://github.com/homelessbrian/NuvioTV/issues/new?template=live_tv_bug.yml) |
| 📺 **An idea for a new feature** | [Request a feature](https://github.com/homelessbrian/NuvioTV/issues/new?template=live_tv_feature.yml) |
| 🎨 **The look or layout could be better** | [Suggest a UI / UX improvement](https://github.com/homelessbrian/NuvioTV/issues/new?template=live_tv_ui.yml) |

**If the app crashes,** scan the QR code on the crash screen with your phone and paste the text into your report.

Problems with Nuvio's movies, shows, addons or accounts that also happen in the official app belong with [Nuvio](https://github.com/NuvioMedia/NuvioTV/issues).

---

## ❓ FAQ

<details>
<summary><b>Why the new name?</b></summary>

The app started as Nuvio with Live TV added. It now also has On Demand, catch-up, reminders, parental controls and more, so "Nuvio + IPTV" describes it better. Nothing about how it works or updates has changed.
</details>

<details>
<summary><b>Does this include any channels or movies?</b></summary>

No. It only plays what you add yourself: your own playlists, guides, Xtream logins, addons and debrid services.
</details>

<details>
<summary><b>I don't see On Demand.</b></summary>

Turn on movies & series for your Xtream login under **Settings → Live TV → On Demand**. On Demand appears in the side menu once the first import finishes.
</details>

<details>
<summary><b>Some channels show "Programming" or the wrong listings.</b></summary>

Long-press the channel and choose **Assign EPG**. Press Left to search, or pick a TV guide from the buttons at the top. If every channel is off by the same amount, use **Guide time shift**.
</details>

<details>
<summary><b>Catch-up plays the wrong show (an hour off).</b></summary>

Update to the latest version, then run **Update now** on that playlist once. Catch-up now uses your provider's time zone.
</details>

<details>
<summary><b>Sound cuts out or stops on Fire TV with Echo speakers.</b></summary>

Turn off **Audio passthrough** under **Settings → Live TV → Playback**.
</details>

<details>
<summary><b>What does Google Drive sync store, and where?</b></summary>

Your Live TV setup, in a private app folder in your own Google Drive that only this app can see. See the [privacy policy](https://homelessbrian.github.io/privacy.html).
</details>

---

## 🙏 Credits

- **[Nuvio](https://github.com/NuvioMedia/NuvioTV)** by the NuvioMedia team: Nuvio + IPTV is built on their work and follows their releases.
- Guide and overlay design inspired by **TiviMate**.
- Built with Kotlin, Jetpack Compose, TV Material 3 and Media3 / ExoPlayer.

Nuvio + IPTV is an unofficial fork and is **not affiliated with or endorsed by NuvioMedia or TiviMate**. Please report issues here, not to the Nuvio team.

## ⚖️ Legal

This app is a client-side player. It does not host, store or distribute any media, and it ships with no channels, playlists or content sources. Only use sources you own or are authorized to access. See Nuvio's [legal and DMCA information](https://github.com/NuvioMedia/NuvioTV#legal--dmca), which applies here as well.

Licensed under the **GNU General Public License v3.0**, like upstream Nuvio. See [LICENSE](LICENSE).
