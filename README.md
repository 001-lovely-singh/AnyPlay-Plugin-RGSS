# 🎮 Any Play - RGSS Plugin

A small companion app for **[Any Play](https://001-lovely-singh.github.io/)**. It runs **RPG Maker XP, VX and VX Ace** games (the "RGSS" / Ruby ones) on Android. It has no icon of its own: you add the game in AnyPlay, press play, and AnyPlay opens this plugin for you.

It is built on [mkxp-z](https://github.com/mkxp-z/mkxp-z), using the Android port [BookerRues9/mkxp-z-android-reworked](https://github.com/BookerRues9/mkxp-z-android-reworked) as the starting point.

> This plugin is only distributed here, through the **Releases** page of this repository.

## 📥 Install

1. Open the [latest release](../../releases/latest) and download **`AnyPlay-RGSS-Plugin.apk`**
   (direct link: `https://github.com/001-lovely-singh/AnyPlay-Plugin-RGSS/releases/latest/download/AnyPlay-RGSS-Plugin.apk`).
2. Open the file and allow "Install unknown apps" for your browser or file manager if Android asks.
3. Open AnyPlay, add an RPG Maker game (a folder, or a `.zip`), and press play.

New versions install over the old one. Nothing needs to be uninstalled.

## 📦What it includes

- One APK for 64-bit and 32-bit phones (arm64-v8a and armeabi-v7a).
- Touch controls: tap the map to walk, on-screen pad and buttons.
- Cheat menu (opened from the AnyPlay overlay) for XP / VX / VX Ace and for Pokémon Essentials games (v17, v19, v21 style).
- Fixes for old scripts. Many games were written for Ruby 1.8; the plugin repairs the common problems automatically when the game starts, so the game does not need to be edited.
- `AnyPlayLog.txt`, a problem report written into the game folder (see below).

## 🎯 If a game does not work

When something goes wrong, the plugin writes a file called **`AnyPlayLog.txt`** into the game's folder. The file is created fresh every time the game is started, and only if there is something to report. It lists script errors, the error that stopped the game (with the script name and line), and, if the game runs slowly, which part is slow.

Please report it on **[Discord](https://discord.gg/Y9Yjsn5yDP)** and attach that file, together with the game name and your phone model.

## 👨‍🍳 Build it yourself

The APK is built by GitHub Actions (`.github/workflows/android.yml`) whenever a tag like `v1.0.41` is pushed. The result is attached to the release as `AnyPlay-RGSS-Plugin.apk`.

The release is signed with a key that is kept in the repository secrets (`ANYPLAY_KEYSTORE_BASE64`, `ANYPLAY_STORE_PASSWORD`, `ANYPLAY_KEY_ALIAS`, `ANYPLAY_KEY_PASSWORD`). If you fork this repository without those secrets, the build still works and is signed with a temporary debug key.

## 📄 Credits and license

- mkxp-z and its contributors.
- [BookerRues9](https://github.com/BookerRues9) for the Android port this project started from.
- Ruby, SDL2, OpenAL Soft and the other libraries bundled in the build keep their own licenses.

This project is licensed under the **GNU General Public License v2** (see [`app/jni/mkxp-z/COPYING`](app/jni/mkxp-z/COPYING)). The complete source of every release is this repository, at the release's tag.
