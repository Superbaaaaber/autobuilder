# Changelog

## 1.3.0

- English translation: every screen, the HUD, chat messages, `/ab help` and the settings follow the game language (English / 简体中文).
- Built-in blueprints now have English ids (`builtin:cabin`, `builtin:tower`, `builtin:yard`) so they're easy to type; the old Chinese names still work and saved progress carries over.
- The config file comments are now in English; the settings screen shows translated tooltips.

## 1.2.1

- Fix: a finished build was saved again as a resume point, so re-entering the world said there was an unfinished build.

## 1.2.0

First public release.

- Server opt-out: a server can disable automatic building by putting `[NoAutoBuilder]` in its MOTD or in a system message.
- `/ab help` lists every command with a description (two pages, click to fill in, hover for details); new `/ab menu` and `/ab config`.
- *Settings…* button on the main screen; settings screen fully translated.
- Supply chests, save selection as schematic, resume after disconnect, self-enclosure check.
- Fluids and redstone machines: build order for water/lava, observers, TNT and redstone components.
