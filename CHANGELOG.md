# Changelog

## 1.4.0

- **Tree chopping** (`/ab chop [radius]`): chops natural trees around you, nearest first, picks up the drops and replants saplings. Only trees with natural leaves count, and logs touching planks, glass, doors etc. are left alone, so log houses are safe.
- **Branch mining** (`/ab mine [length] [branch]`): digs a 1×2 main tunnel in the direction you face with side tunnels every 4 blocks, and mines the ores the tunnels expose, following veins. It doesn't dig for ores hidden in the rock (that would be X-ray). Never digs next to lava or water, throws away cobblestone and other junk when the inventory is full, stops when no usable pickaxe is left.
- **Material substitution**: when a wood type or colour runs out it uses another one of the same block (oak planks for birch planks, a blue bed for a red one, stained glass for glass...), sticking to one substitute per build. Separate settings for wood and colours.
- **Searches nearby containers**: when materials run out it also opens unregistered chests, barrels and shulker boxes nearby (trapped chests excluded) and takes what's missing, including substitutes. It never puts anything into them.
- Pathfinding: no longer stands on natural leaves (they decay once the logs are gone), and gives up on a step at once when it has clearly fallen off the path instead of waiting 10 seconds at a pillar.
- Picks up dropped tools and buckets.
- **Drains water**: where the blueprint has air but the world has water (or lava) sources, it fills them with scaffold blocks first and takes the blocks back once the spot is dry. Only spots that outside water can't flood again are drained, so it doesn't try to empty the lake around a building. Blocks that got waterlogged by being placed in water are emptied with a bucket. Setting: *Drain water*.
- **Grass, farmland and paths**: grass blocks are built from dirt (dirt and grass count as the same, so it no longer breaks and re-places them forever); farmland and dirt paths are built from dirt and then tilled with a hoe / flattened with a shovel. It won't jump onto farmland.
- **Doesn't drown**: when its head is under water or it's swimming, it stops working, surfaces and swims to the nearest dry land. It no longer refuses to bridge over water.
- **Recovers on low health**: instead of pausing, it stops, eats and waits until health is back, then continues. It only pauses if it keeps getting hurt or has no food.
- **Less jumping in place**: a path step that fails is avoided for a minute, and it lines up straight before jumping up a block so it doesn't hit its head on the block beside it.
- **Selection wand**: hold a stick, left-click a block for corner 1 and right-click for corner 2 (up to 128 blocks away). The clicks don't break or use blocks. `/ab wand on|off`, item configurable.
- **Hide preview**: press **H**, use the *Hide preview* button or `/ab hide`; building continues. Setting *Hide preview while building*.
- **Saving leaves out lake water**: water that continues outside the selection (lake, river, sea, plus lily pads on it) isn't saved; water enclosed inside, like a farm's streams, is kept. Toggle on the save screen or in the settings.

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
