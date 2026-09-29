<div align="center">

# Nuvio Live TV

**Nuvio for Android TV and Google TV, with a full TiviMate-style Live TV guide built in.**

Everything you love about Nuvio, plus your IPTV channels, a real TV guide, and one-press streaming of anything in the guide through your own addons and debrid.

[![Latest release](https://img.shields.io/github/v/release/homelessbrian/NuvioTV?include_prereleases&label=latest&style=for-the-badge)](https://github.com/homelessbrian/NuvioTV/releases)
[![Downloads](https://img.shields.io/github/downloads/homelessbrian/NuvioTV/total?style=for-the-badge)](https://github.com/homelessbrian/NuvioTV/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge)](LICENSE)
[![Based on Nuvio](https://img.shields.io/badge/based%20on-NuvioMedia%2FNuvioTV-8A2BE2?style=for-the-badge)](https://github.com/NuvioMedia/NuvioTV)

[Download](#-installation) · [Features](#-features) · [Setup](#-getting-started) · [Remote controls](#-remote-controls) · [Report a bug](#-reporting-bugs-and-requesting-features) · [Building your own](#-building-your-own-copy)

</div>

---

## ✨ Features

### 📺 A proper TV guide
- **TiviMate-style guide**: channels down the side, a timeline across, and a line marking "now". Fits **8 or more channels** on screen.
- **Group list that slides out** from the left when you press Left, and tucks away when you pick a group, so the guide gets the full width.
- **Info panel** with the highlighted show's **poster**, title, time left and description, plus a **live preview window** of the playing channel.
- **Make room for more channels:** hide the preview, hide the info panel, shrink both, use compact rows, or hide channel numbers, logos or names.
- **Opens where you left off:** Live TV opens on the group you last watched in, with your last channel playing in the preview (you can turn this off).
- **Long titles scroll** on the selected show, so nothing is cut off.
- **Outline or solid highlight**, and full support for every Nuvio theme, including pure black surfaces.

### 📡 Your sources, your way
- **Multiple M3U playlists** and **multiple XMLTV guides** (plain or `.gz`), merged into one guide.
- **Xtream Codes logins**, loaded through the Xtream API like TiviMate (with automatic fallback), including the provider's guide and catch-up.
- **Paste anything:** full Xtream links are cleaned up automatically, and the login is filled in from the link.
- **Smart guide matching:** `tvg-id` first, then cleaned-up channel names. Real listings always win over placeholder "Programming" entries, and overlapping listings are sorted out.
- **Clear error messages** when a provider refuses a request, times out or blocks the app.
- Automatic updates on a schedule you choose, a **guide time shift**, per-playlist user agents, and `#EXTVLCOPT` / `url|User-Agent=…` headers.

### 🗂️ Channel management
- **Manage visibility** (TiviMate style): see every channel in a group, hidden ones included, and show or hide them in one pass.
- **Hide** channels and groups, **rename** and **renumber** channels, and **create your own groups**.
- **Favorites** and **Recently watched**, in your own order.
- **Assign EPG:** pick the right guide channel for any channel from one alphabetical list of every guide, with an **Unassigned** filter and a **Full scan** button.

### ▶️ Watching
- **Zap** with Up/Down or CH+/CH−, type a channel number, or jump to the **last channel**.
- **Info bar** with the show's poster, time, progress, description and what's next.
- **Channel list** without leaving full screen, plus **audio, subtitle and aspect ratio** options.
- **Catch-up** for providers that support it (default, append, shift, Flussonic and Xtream formats).
- **Auto-reconnect** when a stream drops, and playback **pauses when you press Home**.
- Going full screen and back never re-buffers.

### 🔎 Part of Nuvio, not bolted on
- **Live TV in Nuvio's side menu**, with its settings inside **Nuvio's own Settings**.
- **Live TV in Nuvio search:** channels and shows appear with posters, descriptions and a clear **LIVE** or **UPCOMING** badge.
- **Find & stream in Nuvio:** see a movie or show in the guide? Stream it through your own addons and debrid.
- **Smart posters:** movie vs. series and release year are worked out from the guide, so *Total Recall (1990)* gets the right poster.
- **Account sign-ins:** Nuvio account, Trakt, Simkl (including Simkl's new sign-in) and MDBList.

### ☁️ Google Drive sync
- Keep your Live TV setup (playlists, guides, favorites, hidden channels, groups, renames and settings) the same on every TV.
- Saved in a **private app folder in your own Google Drive**. Nothing is stored anywhere else.
- Sign in by scanning a **QR code** with your phone. Works on Google TV, Android TV and Fire TV.

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

Requires **Android 7.0 or newer** (Fire OS 6 or later on Fire TV). It installs **alongside** the official Nuvio app, so you can keep both.

### Updating
Updates appear **inside the app**, just like official Nuvio. Each new Nuvio release is followed by a matching version with Live TV.

---

## 🚀 Getting started

1. Open **Settings → Live TV**.
2. Choose **Add M3U playlist** or **Add Xtream Codes login**.
3. Optionally **Add EPG source** for an extra TV guide. Guides linked in your playlist load automatically.
4. Open **Live TV** from Nuvio's side menu. The first load can take a moment on large playlists.
5. Optional: turn on **Google Drive sync** at the top of the Live TV settings to use the same setup on your other TVs.

---

## 🎮 Remote controls

### In the guide
| Button | Action |
|---|---|
| **OK** on what's on now | Play in the preview · press again for full screen |
| **OK** on a later show | Show info, with **Find & stream in Nuvio** |
| **OK** on an earlier show | Play it from the archive (catch-up channels) |
| **◀ / ▶** | Move through time · **◀** at the start opens the group list |
| **▲ / ▼** · **CH+ / CH−** | Move between channels · page up / down |
| **0–9** | Jump to a channel number |
| **Long-press OK** or **Menu** | Channel menu: favorites, hide, **Manage visibility**, rename, renumber, **Assign EPG**, groups, find & stream |
| **Back** | Back to "now" → back to the main group → Nuvio's menu |

### In the group list
| Button | Action |
|---|---|
| **▲ / ▼** | Browse groups (the guide follows along) |
| **OK** or **▶** | Open the group and return to the channels |
| **Long-press OK** | Rename, move, hide or delete the group |

### Manage visibility
| Button | Action |
|---|---|
| **OK** | Show or hide the channel |
| **◀** | Hide all |
| **▶** | Show all |
| **Back** | Save and return |

### Full screen
| Button | Action |
|---|---|
| **▲ / ▼**, **CH+ / CH−** | Next / previous channel |
| **OK** | Show or hide the info bar |
| **◀** | Channel list |
| **Long-press OK** or **Menu** | Audio, subtitles, aspect ratio, favorite, find & stream, reload, hide |
| **0–9** · **Last Channel** | Jump to a number · previous channel |
| **Back** | Back to the guide, with the channel still playing in the preview |

---

## 🐞 Reporting bugs and requesting features

This fork is about **Live TV**: IPTV, M3U, Xtream Codes, EPG, channels, groups, favorites, catch-up, Live TV playback, search and the Live TV look and feel. Please search [existing issues](https://github.com/homelessbrian/NuvioTV/issues) first.

| | |
|---|---|
| 🐛 **Something isn't working** | [Report a Live TV bug](https://github.com/homelessbrian/NuvioTV/issues/new?template=live_tv_bug.yml) |
| 📺 **An idea for a new feature** | [Request a Live TV feature](https://github.com/homelessbrian/NuvioTV/issues/new?template=live_tv_feature.yml) |
| 🎨 **The look or layout could be better** | [Suggest a UI / UX improvement](https://github.com/homelessbrian/NuvioTV/issues/new?template=live_tv_ui.yml) |

For problems with Nuvio's movies, shows, addons or accounts that also happen in the official app, please report them to [Nuvio](https://github.com/NuvioMedia/NuvioTV/issues) instead.

---

## 🛠️ Building your own copy

Everything builds on GitHub; no Android Studio needed.

| Workflow | What it does |
|---|---|
| **Build Live TV APK** | Test build; download the APK from the run's *Artifacts* |
| **Release Live TV update** | Publishes an in-app update between Nuvio releases (`1.1.0-beta.2.1`, `.2`, …) |
| **Sync Nuvio releases** | Checks hourly for a new Nuvio release, merges it, builds it and publishes a matching release |
| **Apply Live TV kit** | Unzips an uploaded `nuvio-livetv-kit.zip` over the repo and commits it |

### Secrets
Add these under **Settings → Secrets and variables → Actions**. All are optional except `SYNC_TOKEN` for the sync workflow.

| Secret | Used for |
|---|---|
| `SYNC_TOKEN` | Classic personal access token with `repo` + `workflow`, so the sync can merge Nuvio updates |
| `NUVIO_BACKEND_URL` / `NUVIO_BACKEND_KEY` | Nuvio account sign-in and sync |
| `TMDB_API_KEY` | Artwork and metadata from TMDB |
| `TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET` | Trakt |
| `SIMKL_CLIENT_ID` / `SIMKL_APP_NAME` | Simkl |
| `MDBLIST_CLIENT_ID` | MDBList |
| `GOOGLE_DRIVE_CLIENT_ID` / `GOOGLE_DRIVE_CLIENT_SECRET` | Google Drive sync (OAuth client of type "TVs and Limited Input devices") |
| `LIVETV_KEYSTORE_BASE64` | Your own private signing key (otherwise the included one is used) |

### Local build
```bash
git clone https://github.com/homelessbrian/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullRelease
```
Requires JDK 17 and the Android SDK. See the [upstream README](https://github.com/NuvioMedia/NuvioTV#readme) for full development details.

---

## ❓ FAQ

<details>
<summary><b>Does this include any channels?</b></summary>

No. Like Nuvio itself, this app only plays what you add: your own playlists, guides, addons and debrid services.
</details>

<details>
<summary><b>Some channels show "Programming" or the wrong listings.</b></summary>

Long-press the channel and choose **Assign EPG**. The line under the channel name shows which guide it's using; pick the right one from the list. If the times are off for every channel, use **Guide time shift** in Live TV settings.
</details>

<details>
<summary><b>My playlist or Xtream login won't load.</b></summary>

Check the red message under the playlist in **Settings → Live TV → Playlists**. A 403 or 884 usually means the provider blocks the app (try setting a user agent). A 502–504 means the provider's server didn't answer; try again later. For Xtream, the server box only needs the address and port, like `http://example.com:8080`.
</details>

<details>
<summary><b>How do I get back a channel I hid?</b></summary>

Long-press any channel and choose **Manage visibility**, or go to **Settings → Live TV → Your channel changes → Hidden channels**.
</details>

<details>
<summary><b>What does Google Drive sync store, and where?</b></summary>

Your Live TV setup, in a private app folder in your own Google Drive that only this app can see. The developer never receives it. See the [privacy policy](https://homelessbrian.github.io/privacy.html).
</details>

---

## 🙏 Credits

- **[Nuvio](https://github.com/NuvioMedia/NuvioTV)** by the NuvioMedia team: this fork is built on their work and follows their releases.
- Guide design inspired by **TiviMate**.
- Built with Kotlin, Jetpack Compose, TV Material 3 and Media3 / ExoPlayer.

This is an unofficial fork and is **not affiliated with or endorsed by NuvioMedia or TiviMate**. Please report Live TV issues here, not to the Nuvio team.

## ⚖️ Legal

This app is a client-side player. It does not host, store or distribute any media, and it ships with no channels, playlists or content sources. You are responsible for only using sources you own or are authorized to access. See Nuvio's [legal and DMCA information](https://github.com/NuvioMedia/NuvioTV#legal--dmca), which applies to this fork as well.

Licensed under the **GNU General Public License v3.0**, the same as upstream Nuvio. See [LICENSE](LICENSE).
