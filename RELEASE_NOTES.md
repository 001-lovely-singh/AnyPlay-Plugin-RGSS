## AnyPlay RGSS Plugin

Runs RPG Maker XP, VX and VX Ace games for **AnyPlay**.

### Install
1. Download **AnyPlay-RGSS-Plugin.apk** below and install it (allow "Install unknown apps" if Android asks).
2. Open AnyPlay, add your game (folder or zip) and press play.

New versions install over the old one.

### What's new
- Old Ruby 1.8 games: more scripts are repaired automatically at start-up (`return` inside procs, old `when x:` syntax, byte/character differences, missing old methods, and more).
- Pokémon Essentials cheats now work with v17, v19 and v21: items, Pokémon, full bag, speed, no random battles, fast text.
- Audio scripts written for newer mkxp-z (extra position argument, `bgm_position`) no longer cause errors.
- New problem report: `AnyPlayLog.txt` in the game folder (created only when needed, fresh on every start). Attach it when you report a problem.
- Frame-speed summary in that report, with the busiest script lines, to find slow spots.

### Note
The first start of a big game can take a while because the plugin builds a file list of the game folder. Games stored in the internal AnyPlay folder start faster than games in Download.

Signing key changed in this release. If Android says the app "cannot be updated", uninstall the old plugin once and install this one. Later updates install normally.
