# BlockShips

A Minecraft plugin that lets players create rideable, physics-enabled ships. Build custom ships from blocks or spawn pre-built vessels, then sail the seas or take to the skies. All of this *without any client side mods or resource packs!*

## **WARNING:** THIS PROJECT IS IN PRE-ALPHA. EXPECT BUGS, MISSING FEATURES, AND BREAKING CHANGES. USE AT YOUR OWN RISK, AND BACKUP YOUR WORLD OFTEN. THE DEVELOPER IS NOT RESPONSIBLE FOR ANY DAMAGE CAUSED BY THIS PLUGIN.

![BlockShips](docs/assets/main.png)

# Features

Ships can include:
- **Functional blocks** - Crafting tables, anvils, enchanting tables work as normal. (furnaces/brewing stands don't yet work)
- **Seats** - Stairs for passengers
- **Cannons** - a dispenser with a block of obsidian behind it will shoot its contents. fire all through the ship menu, or right click on the obsidian to fire.

|                  Cannons Firing                   |                                         Cannon Menu                                          |
| :-----------------------------------------------: | :------------------------------------------------------------------------------------------: |
| ![Cannons Firing](docs/assets/cannons_firing.png) | ![Cannon Menu](docs/assets/menu_cannons.png) (or, right click obsidian to fire individually) |
- **Storage** - Chests, barrels, dispensers remain accessible
- **Lead points** - Anything leashed to a fence will stay tied to the ship. You can lead things to the ship while its moving. Prefab ships have a single lead point.

## Custom Ships

1. Build a structure from allowed blocks (see or edit this in `blocks.yml`)
  - generally, wood/metal/functional blocks are allowed, while stone/dirt/other natural blocks are not
  - light-giving blocks, like glowstone, end rods, and beacons, serve as floatation aids. enough of these, and you get an airship! 
3. Craft a "Ship Wheel"
4. Place the wheel on your structure
5. Right-click the wheel to assemble
6. Right-click again to board and steer
7. Right-click the wheel, or sneak right-click, to open menu and disassemble

## Prefab Ships

Spawn ready-to-use ships, with customizable banners/colors/wood types:

- **Small Ship** - Fast, lightweight water vessel
- **Large Ship** - Larger water vessel with more health
- **Small Airship** - Floats in the air with vertical controls

**Command:** `/blockships give <small_ship|large_ship|small_airship>`

## Physics System
- **Walk on your ships** - Players can walk around on deck while sailing/flying. this is still buggy!
- **Buoyancy** - Ships float based on block weight and density. this is buggy sometimes!
- **Movement** - Acceleration, drag, and collision response
- **Collision detection** - Ships interact with terrain and entities (interacting with other ships is buggy)

## Controls

| Key    | Action                  |
| ------ | ----------------------- |
| W      | Move forward            |
| S      | Move backward / brake   |
| A      | Rotate left             |
| D      | Rotate right            |
| Space  | Ascend (airships only)  |
| Sprint | Descend (airships only) |



## Crafting Recipes

| Item                                                              | Recipe                                                   |
| :---------------------------------------------------------------- | :------------------------------------------------------- |
| **Ship Wheel**                        | ![Ship Wheel](docs/assets/crafting/ship_wheel.png)       |
| **Small Ship**<br>*Wood type, banner customizable*                | ![Small Ship](docs/assets/crafting/small_ship.png)       |
| **Large Ship**<br>*Wood type, banner customizable*                | ![Large Ship](docs/assets/crafting/large_ship.png)       |
| **Ship Balloon**<br>*Wool color customizable*                   | ![Ship Balloon](docs/assets/crafting/ship_balloon.png)   |
| **Small Airship**<br>*Wood type, balloon type customizable* | ![Small Airship](docs/assets/crafting/small_airship.png) |



## Commands

| Command                           | Description                                  | Permission           |
| --------------------------------- | -------------------------------------------- | -------------------- |
| `/blockships reload`              | Reload configuration                         | `blockships.reload`  |
| `/blockships give <type>`         | Give yourself a prefab ship kit              | `blockships.give`    |
| `/blockships recipes [player]`    | Unlock crafting recipes                      | `blockships.recipes` |
| `/blockships forcedisassembleall` | **(DANGEROUS) Disassemble all custom ships** | `blockships.admin`   |
| `/blockships killentities`        | **(DANGEROUS) Remove all ship entities**     | `blockships.admin`   |

# Installation

1. Download the BlockShips jar file
2. **IMPORTANT: Download [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/)**
3. Place both jars in your server's `plugins` folder
4. Restart the server

# Configuration

## config.yml
Main plugin settings including:
- Ship physics (speed, acceleration, drag)
- Buoyancy values (density, strength)
- Collision settings (mass, response)
- Crafting recipes

## blocks.yml
Configure which blocks can be used in custom ships:
- **Weight scale** - Affects buoyancy
- **Collider** - Custom collision shapes
- **Seat/storage** - Special block behaviors

# Inspiration

This plugin is inspired by mods which also implement rideable ships, as well as plugins which have attempted similar functionality. I made this plugin because I realized that with the addition of display entities, it might be possible to create a better ship plugin than previously possible, but without requiring any client-side mods. No code from any of other project has been used. In particular, this plugin was inspired by:

- [Ships](https://dev.bukkit.org/projects/ships) and [Movecraft](https://github.com/APDevTeam/Movecraft) plugins
- [Archimedes Ships mod](https://www.curseforge.com/minecraft/mc-mods/archimedes-ships)
- [Eureka Ships / Valkyrian Skies mods](https://www.valkyrienskies.org/)


# License

You are free to use this plugin only for non-commercial projects and servers. For commercial use, please contact the author for a license. For more details, see the `LICENSE.txt` file.

