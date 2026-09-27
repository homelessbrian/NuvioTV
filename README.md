<div align="center">

# Nuvio Live TV

**Nuvio for Android TV, with a full TiviMate-style Live TV guide built in.**

Everything you love about Nuvio — plus your IPTV channels, a real TV guide, and one-press streaming of anything in the guide through your own addons and debrid.

[![Latest release](https://img.shields.io/github/v/release/homelessbrian/NuvioTV?include_prereleases&label=latest&style=for-the-badge)](https://github.com/homelessbrian/NuvioTV/releases)
[![Downloads](https://img.shields.io/github/downloads/homelessbrian/NuvioTV/total?style=for-the-badge)](https://github.com/homelessbrian/NuvioTV/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge)](LICENSE)
[![Based on Nuvio](https://img.shields.io/badge/based%20on-NuvioMedia%2FNuvioTV-8A2BE2?style=for-the-badge)](https://github.com/NuvioMedia/NuvioTV)

[Download](#-installation) · [Features](#-features) · [Remote controls](#-remote-controls) · [Setup](#-getting-started) · [Building your own](#-building-your-own-copy)

</div>

---

## ✨ Features

### 📺 A proper TV guide
- **TiviMate-style EPG grid** — channels down the side, a scrolling timeline across, and a red "now" line.
- **Live preview window** in the top corner plays the selected channel, with the show's title, time left, progress and description beside it.
- **Groups built into the guide** — Search, Favourites, Recently watched, All channels, your own groups and your playlist's groups, always on screen.
- **Long titles scroll** on the selected programme, channel and group, so nothing is cut off.
- **Show or hide** channel names, numbers and logos, the preview and the details panel. Compact rows fit more channels on screen.
- **Outline or solid highlight** — the default outline keeps channel logos readable.
- Works with every Nuvio theme, including **pure black surfaces** (guide cells stay grey so you can read them).

### 📡 Your sources, your way
- **Multiple M3U playlists** and **multiple XMLTV guides** (plain or `.gz`), all merged into one guide.
- **Xtream Codes logins** — enter server, username and password; the guide is added automatically.
- Guides linked inside a playlist (`url-tvg`) load on their own.
- Smart guide matching by `tvg-id`, then by cleaned-up channel name.
- Automatic updates on a schedule you choose, plus an **EPG time shift** for guides that run early or late.
- Per-playlist user agent, and support for `#EXTVLCOPT` and `url|User-Agent=…` headers.

### 🗂️ Channel management
- **Hide** channels and whole groups with a long press — and bring them back from settings.
- **Favourites** and **Recently watched**, with your own favourite order.
- **Rename** and **renumber** channels.
- **Create your own groups**, add channels to them and put them in any order.
- **Rename, reorder and hide** playlist groups.
- Sort channels by playlist order, channel number or name.

### ▶️ Watching
- **Instant zapping** with up / down or CH+ / CH−, within the current group or across all channels.
- **Type a channel number** to jump straight to it, and **Last Channel** to flip back.
- **Info banner** with what's on now and next.
- **Channel list overlay** without leaving full screen.
- **Audio tracks, subtitles and aspect ratio** (Fit / Zoom / Stretch).
- **Auto-reconnect** when a stream drops.
- **Catch-up / archive** playback for providers that support it (default, append, shift, Flussonic and Xtream formats).
- Going full screen and back **never re-buffers** — the same stream keeps playing in the preview.

### 🔎 Part of Nuvio, not bolted on
- **Live TV in Nuvio's side menu**, and its settings inside **Nuvio's own Settings screen**, matching the rest of the app.
- **Live TV in Nuvio search** — matching channels, plus channels showing a matching programme now or in the next few hours.
- **Find & stream in Nuvio** — see a movie or show in the guide that hasn't started yet? Press OK and stream it right away through your addons and debrid. Also available from the long-press menu for anything that's on now.
- **In-app updates** from this repo's releases, just like official Nuvio.

---

## 📥 Installation

1. Go to the **[latest release](https://github.com/homelessbrian/NuvioTV/releases)**.
2. Download the APK for your device:
   - `arm64-v8a` — most modern Android TV / Google TV devices (Chromecast with Google TV, Shield, onn 4K, Fire TV 4K Max 2nd gen)
   - `armeabi-v7a` — older or budget devices
   - `universal` — if you're not sure
3. Install it with a file manager or **Downloader**, allowing installs from unknown sources when asked.

This fork installs **alongside** the official Nuvio app — it uses its own app ID, so you can keep both.

### Updating
Updates show up **inside the app**, the same way official Nuvio updates do. Each time Nuvio releases a new version, this fork follows with a matching version that includes Live TV.

---

## 🚀 Getting started

1. Open **Settings → Live TV**.
2. Choose **Add M3U playlist** (a playlist URL) or **Add Xtream Codes login**.
3. Optionally **Add EPG source** for an extra XMLTV guide. Guides linked in your playlist are picked up automatically.
4. Open **Live TV** from Nuvio's side menu. The first load can take a moment on large playlists.

---

## 🎮 Remote controls

### In the guide
| Button | Action |
|---|---|
| **OK** | Preview the channel · press again for full screen |
| **OK** on a future show | Programme info, with **Find & stream in Nuvio** |
| **Long-press OK** / **Menu** | Channel menu: favourite, hide, rename, renumber, add to group, find & stream, catch-up… |
| **◀ / ▶** | Move through time · ◀ from the channels goes to the groups · ◀ again opens Nuvio's menu |
| **▲ / ▼** | Move between channels |
| **CH+ / CH−** | Page up / down |
| **0–9** | Jump to a channel number |
| **Back** | Back to "now" → back to All channels → Nuvio's menu |

### In the group list
| Button | Action |
|---|---|
| **▲ / ▼** | Browse groups (the guide follows along) |
| **OK** | Open the group · on Search, search channels |
| **Long-press OK** | Rename, move, hide or delete the group |

### Full screen
| Button | Action |
|---|---|
| **▲ / ▼**, **CH+ / CH−** | Next / previous channel |
| **OK** or **◀** | Channel list |
| **▶** or **Info** | Info banner |
| **Long-press OK** / **Menu** | Audio, subtitles, aspect ratio, favourite, find & stream, reload, hide |
| **0–9** · **Last Channel** | Jump to a number · previous channel |
| **Back** | Back to the guide, with the channel still playing in the preview |

---

## 🛠️ Building your own copy

Everything builds on GitHub — no Android Studio needed.

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
| `NUVIO_BACKEND_URL` / `NUVIO_BACKEND_KEY` | Nuvio account sign-in (QR login and sync) |
| `TMDB_API_KEY` | Artwork and metadata from TMDB |
| `TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET` | Trakt scrobbling and sync |
| `SIMKL_CLIENT_ID` | Simkl |
| `LIVETV_KEYSTORE_BASE64` | Your own private signing key (otherwise the included one is used) |

### Local build
```bash
git clone https://github.com/homelessbrian/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullDebug
```
Requires JDK 17 and the Android SDK. See the [upstream README](https://github.com/NuvioMedia/NuvioTV#readme) for full development details.

---

## ❓ FAQ

<details>
<summary><b>Does this include any channels?</b></summary>

No. Like Nuvio itself, this app plays only the sources you add — your own playlists, guides, addons and debrid services.
</details>

<details>
<summary><b>Can I sign in with my Nuvio account?</b></summary>

Yes, when the build includes the Nuvio backend settings (see *Secrets*). Your addons, library and progress then sync as usual.
</details>

<details>
<summary><b>The guide is empty or wrong for some channels.</b></summary>

Check that the channel's `tvg-id` matches the guide, or that the names are similar. If times are off, use **EPG time shift** in Live TV settings.
</details>

<details>
<summary><b>How do I get back a channel I hid?</b></summary>

**Settings → Live TV → Hidden channels** (or **Hidden groups**), then select it.
</details>

---

## 🙏 Credits

- **[Nuvio](https://github.com/NuvioMedia/NuvioTV)** by the NuvioMedia team — this fork is built on their work and follows their releases.
- Live TV guide design inspired by **TiviMate**.
- Built with Kotlin, Jetpack Compose, TV Material 3 and Media3 / ExoPlayer.

This is an unofficial fork and is **not affiliated with or endorsed by NuvioMedia or TiviMate**. Please report Live TV issues here, not to the Nuvio team.

## ⚖️ Legal

This app is a client-side player. It does not host, store or distribute any media, and it ships with no channels, playlists or content sources. You are responsible for only using sources you own or are authorised to access. See Nuvio's [legal and DMCA information](https://github.com/NuvioMedia/NuvioTV#legal--dmca), which applies to this fork as well.

Licensed under the **GNU General Public License v3.0**, the same as upstream Nuvio. See [LICENSE](LICENSE).
