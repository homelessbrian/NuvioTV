<div align="center">

# Nuvio Live TV

**Nuvio for Google TV, Android TV and Fire TV, with a full TiviMate-style Live TV guide and your provider's movies and series built in.**

Everything you love about Nuvio, plus your IPTV channels, a real TV guide, On Demand, and one-press streaming of anything in the guide through your own addons and debrid.

[![Latest release](https://img.shields.io/github/v/release/homelessbrian/NuvioTV?include_prereleases&label=latest&style=for-the-badge)](https://github.com/homelessbrian/NuvioTV/releases)
[![Downloads](https://img.shields.io/github/downloads/homelessbrian/NuvioTV/total?style=for-the-badge)](https://github.com/homelessbrian/NuvioTV/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge)](LICENSE)
[![Based on Nuvio](https://img.shields.io/badge/based%20on-NuvioMedia%2FNuvioTV-8A2BE2?style=for-the-badge)](https://github.com/NuvioMedia/NuvioTV)

[What's new](#-whats-new) · [Download](#-installation) · [Features](#-features) · [Setup](#-getting-started) · [Remote controls](#-remote-controls) · [Report a bug](#-reporting-bugs-and-requesting-features)

</div>

---

## 🆕 What's new

**Now based on Nuvio 1.1.0-beta.3.**

- **On Demand:** your Xtream provider's movies and series get their own section in the side menu. Browse by category, search, see what was recently added, and hide categories you don't want.
- **Watch On Demand:** when your provider has a movie or episode you open anywhere in Nuvio, it shows up in the stream list with quality, codec, audio and size badges, just like your addon streams. It always comes last, and auto-play never picks it.
- **Your posters, not the provider's:** On Demand uses your own addons' posters and details (Cinemeta, BingeCat, whatever you use) and falls back to the provider's images.
- **Choose what to include:** each Xtream login has **Include TV channels** and **Include movies & series** switches, both when adding it and later under **Edit**.
- **Parental controls:** set a PIN, lock any group or category, and adult content locks automatically.
- **Reminders:** long-press any upcoming show and choose **Remind me**. You get a pop-up with a Watch button a minute before it starts, anywhere in Nuvio.
- **Stream info and quality badges:** resolution, frame rate, codecs and bitrate in the player menu, plus 4K / FHD / HD / SD badges in the guide.
- **Match frame rate:** switches your TV to the video's refresh rate for smoother motion.
- **Sleep timer:** stops playback after 15 minutes to 2 hours.
- **Start page:** open Nuvio on Home, Live TV, On Demand, Discover, Search or Library.
- **Loading progress:** a small pill shows what's downloading and how far along it is, like "Importing movies… 12,500".
- **Fixes:**
  - Channel sorting now works in Favorites and your own groups.
  - Hidden On Demand categories stay hidden everywhere.
  - Channels no longer get stuck showing "Programming".

> **Updating from an earlier version?** Existing Xtream logins start with movies & series switched **off**, so nothing downloads unexpectedly. Turn them on under **Settings → Live TV → On Demand**.

---

## ✨ Features

### 📺 A proper TV guide
- **TiviMate-style guide:** channels down the side, a timeline across, and a line marking "now". It fits **8 or more channels** on screen.
- **Group list:** slides out when you press Left and tucks away when you pick a group. With several playlists, each playlist's groups sit under their own foldable heading.
- **Info panel** with the highlighted show's poster, title, time left and description, plus a live preview window.
- **Make room for more channels:** hide or shrink the preview and info panel, use compact rows, or hide channel numbers, logos or names.
- **Opens where you left off:** on the group you last watched, with your last channel playing in the preview.
- **Catch-up icon** on channels that support catch-up, and quality badges on channels you've watched.

### 🎬 Overlay mode (while watching)
- Press **Left** in full screen for a see-through guide over the video: the channels in the group, the highlighted channel's schedule, and the show's details.
- **Left again** switches groups. **Right** opens the channel's full schedule, with a date picker to jump between days.
- You can turn it off so Left goes straight back to the guide.

### 📡 Your sources, your way
- **Multiple M3U playlists** and **multiple XMLTV guides** (plain or `.gz`), merged into one guide.
- **Xtream Codes logins** loaded through the Xtream API like TiviMate, with the provider's guide, catch-up, and optional movies & series.
- **Smart guide matching:** real listings always win over "Programming" placeholders, and overlapping listings are sorted out.
- **Catch-up:** watch past shows, restart what's on now with **Watch from the beginning**, and skip through replays with a seek bar. **Prefer m3u8** makes seeking smoother on Xtream providers.
- **Clear error messages** when a provider refuses a request, times out or blocks the app.

### 🗂️ Channel management
- **Manage visibility** (TiviMate style) for channels *and* groups: see everything, hidden ones included, and show or hide in one pass.
- **Reorder** channels and groups, **copy** a channel into another group, **rename**, **renumber**, and **create your own groups**.
- **Number channels 1, 2, 3…** in each group, like TiviMate's number override.
- **Favorites** and **Recently watched**.
- **Assign EPG:** pick the right guide channel for any channel, with an **Unassigned** filter and a **Full scan** button.

### 🎞️ On Demand
- **Movies and series** from your Xtream providers, by category, with **All**, **Recently added** and **Search**.
- **Long-press a category** for **Manage visibility** or **Lock with PIN**. Hidden and locked categories stay out of All, search and Nuvio's stream list.
- **Opening a title:** titles your addons know open on Nuvio's normal details page. Others open the provider's details, with seasons and episodes for series.
- **"Watch On Demand"** in Nuvio's stream list, with addon-style details and badges.
- **Stored on your device:** the catalog is imported once and updated daily, and posters are remembered between visits.

### 🔒 Parental controls
- A PIN that locks any group or category. Adult content locks automatically.
- Locked content stays out of All channels, Favorites and search until unlocked.

### ▶️ Watching
- **Zap** with Up/Down or CH+/CH−, type a channel number, or jump to the **last channel**.
- **Info bar** with poster, time, progress, description and what's next.
- **Player menu:** audio, subtitles, screen size (Fit, Crop, Stretch, Cinema Zoom and more), stream info, sleep timer, find & stream.
- **Match frame rate**, **auto-reconnect**, and it **pauses when you press Home**.

### 🔎 Part of Nuvio, not bolted on
- **Live TV and On Demand in Nuvio's side menu,** with their settings inside Nuvio's own Settings.
- **Live TV in Nuvio search,** with LIVE / UPCOMING badges and a long-press menu.
- **Find & stream in Nuvio:** see a movie or show in the guide? Stream it through your own addons and debrid.
- **Smart posters:** movie vs. series, release year and "Show - Episode" titles are all handled.
- **Account sign-ins:** Nuvio account, Trakt, Simkl, MDBList and Premiumize.

### ☁️ Google Drive sync
- Keep your Live TV setup the same on every TV: playlists, guides, favorites, groups, settings and more.
- It's saved in a **private app folder in your own Google Drive**. Sign in by scanning a QR code.

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

**Updating:** updates appear **inside the app**. Each new Nuvio release is followed by a matching version with Live TV.

---

## 🚀 Getting started

1. Open **Settings → Live TV**.
2. Choose **Add M3U playlist** or **Add Xtream Codes login**. For Xtream, pick **Include TV channels** and/or **Include movies & series**.
3. Optionally **Add EPG source** for an extra TV guide.
4. Open **Live TV** or **On Demand** from Nuvio's side menu. The first load can take a minute on big providers; the loading pill shows progress.
5. Optional: set a **Start page**, a parental **PIN**, or **Google Drive sync**, all in **Settings → Live TV**.

---

## 🎮 Remote controls

### In the guide
| Button | Action |
|---|---|
| **OK** on what's on now | Play in the preview · again for full screen |
| **OK** on a later show | Show info · **Remind me** · Find & stream in Nuvio |
| **OK** on an earlier show | Play from the archive (catch-up channels) |
| **◀ / ▶** | Move through time · **◀** at the start opens the groups |
| **▲ / ▼** · **CH+ / CH−** | Channels · page up / down |
| **0–9** | Jump to a channel number |
| **Long-press OK** or **Menu** | Channel menu: favorites, **Remind me**, hide, **Manage visibility**, **Reorder**, **Copy**, rename, renumber, Assign EPG |
| **Back** | Back to "now" → the main group → Nuvio's menu |

### In the group list
| Button | Action |
|---|---|
| **▲ / ▼** | Browse groups (the guide follows along) |
| **OK** or **▶** | Open the group |
| **Long-press OK** | Rename, **Reorder groups**, **Manage visibility**, **Lock with PIN**, hide, delete |

### Manage visibility and reorder modes
| Button | Manage visibility | Reorder |
|---|---|---|
| **OK** | Show or hide | Save |
| **◀ / ▶** | Hide all / show all | — |
| **▲ / ▼** | Move between items | Move the item |
| **Back** | Save and return | Save |

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
| **▶** from the categories | Into the posters (the categories slide away) |
| **◀** from the first column, or **Back** | Categories again |
| **Long-press a category** | Manage visibility · Lock with PIN |

---

## 🐞 Reporting bugs and requesting features

This fork is about **Live TV and On Demand**. Please search [existing issues](https://github.com/homelessbrian/NuvioTV/issues) first.

| | |
|---|---|
| 🐛 **Something isn't working** | [Report a Live TV bug](https://github.com/homelessbrian/NuvioTV/issues/new?template=live_tv_bug.yml) |
| 📺 **An idea for a new feature** | [Request a Live TV feature](https://github.com/homelessbrian/NuvioTV/issues/new?template=live_tv_feature.yml) |
| 🎨 **The look or layout could be better** | [Suggest a UI / UX improvement](https://github.com/homelessbrian/NuvioTV/issues/new?template=live_tv_ui.yml) |

Problems with Nuvio's movies, shows, addons or accounts that also happen in the official app belong with [Nuvio](https://github.com/NuvioMedia/NuvioTV/issues).

---

## ❓ FAQ

<details>
<summary><b>Does this include any channels or movies?</b></summary>

No. It only plays what you add: your own playlists, guides, Xtream logins, addons and debrid services.
</details>

<details>
<summary><b>I updated and don't see On Demand.</b></summary>

Existing Xtream logins start with movies & series switched off. Turn them on under **Settings → Live TV → On Demand**. On Demand appears in the side menu once the first import finishes.
</details>

<details>
<summary><b>Some channels show "Programming" or the wrong listings.</b></summary>

Long-press the channel and choose **Assign EPG**. The line under the channel name shows which guide it's using; pick the right one. If every channel is off by the same amount, use **Guide time shift**.
</details>

<details>
<summary><b>My playlist or Xtream login won't load.</b></summary>

Check the message under the playlist in **Settings → Live TV → Playlists**. A 403 or 884 usually means the provider blocks the app (try a user agent). A 502–504 means the provider's server didn't answer. For Xtream, the server box only needs the address and port.
</details>

<details>
<summary><b>I forgot my parental PIN.</b></summary>

Clearing the app's data resets it, along with your Live TV setup. If you use Google Drive sync, restore afterwards and set a new PIN.
</details>

<details>
<summary><b>What does Google Drive sync store, and where?</b></summary>

Your Live TV setup, in a private app folder in your own Google Drive that only this app can see. See the [privacy policy](https://homelessbrian.github.io/privacy.html).
</details>

---

## 🙏 Credits

- **[Nuvio](https://github.com/NuvioMedia/NuvioTV)** by the NuvioMedia team: this fork is built on their work and follows their releases.
- Guide and overlay design inspired by **TiviMate**.
- Built with Kotlin, Jetpack Compose, TV Material 3 and Media3 / ExoPlayer.

This is an unofficial fork and is **not affiliated with or endorsed by NuvioMedia or TiviMate**. Please report Live TV and On Demand issues here, not to the Nuvio team.

## ⚖️ Legal

This app is a client-side player. It does not host, store or distribute any media, and it ships with no channels, playlists or content sources. Only use sources you own or are authorized to access. See Nuvio's [legal and DMCA information](https://github.com/NuvioMedia/NuvioTV#legal--dmca), which applies to this fork as well.

Licensed under the **GNU General Public License v3.0**, like upstream Nuvio. See [LICENSE](LICENSE).
