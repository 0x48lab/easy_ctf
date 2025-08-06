# EasyCTF - Minecraft CTF Plugin

English | [日本語](README.md)

[![Build and Test](https://github.com/0x48lab/easy_ctf/actions/workflows/build.yml/badge.svg)](https://github.com/0x48lab/easy_ctf/actions/workflows/build.yml)
[![Build and Release](https://github.com/0x48lab/easy_ctf/actions/workflows/release.yml/badge.svg)](https://github.com/0x48lab/easy_ctf/actions/workflows/release.yml)

**🏆 Official hackCraft2 Plugin**

A feature-rich Capture The Flag plugin for Minecraft Paper servers. Manage and run multiple CTF games simultaneously with an intuitive UI and guide system that provides an enjoyable competitive experience for beginners to advanced players.

📚 **[Documentation](https://0x48lab.github.io/easy_ctf/)** - Learn how to play and detailed feature explanations

## Main Features

### 🎮 Game System
- **Multiple Concurrent Games**: Manage multiple CTF games in parallel on one server
- **3-Phase System**: Progressive gameplay through Build → Combat → Strategy Meeting phases
- **Temporary Worlds**: Automatically generate and delete dedicated worlds for each game
- **Map Save/Restore**: Save created maps with compression (GZIP + Base64), auto-restore at game start
- **Multiple Spawn Points**: Set multiple respawn points per team (randomly selected)
- **Spectator Mode**: Watch games without participating

### 💰 Economy & Shop System
- **Team-Shared Currency**: All team members share currency (G)
- **Shop System**: Purchase weapons, armor, blocks, and consumables
- **Dynamic Pricing**: Automatic discounts for losing teams (up to 40%)
- **Death Behavior**: KEEP/DROP/DESTROY settings per item

### 🏆 Match System
- **Fixed Rounds Mode**: Run a specified number of consecutive games
- **Inventory Persistence**: Keep items during matches
- **Detailed Statistics**: Track kills, captures, assists, building, etc.
- **MVP Awards**: Announce top players in each category

### 🏗️ Building System
- **Block Connection Management**: Team blocks must connect to beacon or existing blocks
- **Disconnected Block Neutralization**: Disconnected blocks turn white (Quartz blocks)
- **Team-Exclusive Blocks**: Infinite colored concrete and glass
- **Enemy Territory Damage System**: Take continuous damage on enemy team blocks
- **Build from Multiple Points**: Build from all spawn points (multiple allowed)
- **Spawn Decoration Protection**: 3x3 platform at spawn points cannot be destroyed

### 🌍 Multi-Language Support
- **Japanese & English Support**: All messages managed in language files
- **Easy Switching**: Change language in config.yml
- **Customizable**: Edit lang_ja.yml, lang_en.yml
- **Color Code Support**: Automatic conversion of &c, &9, etc. color codes

## Installation

### From Releases (Recommended)

**📦 Download pre-built files from [GitHub Releases](https://github.com/0x48lab/easy_ctf/releases)**

**Installation Steps:**
1. **[📥 Download Latest Release](https://github.com/0x48lab/easy_ctf/releases/latest)** and get `EasyCTF-x.x.x.jar`
2. Place the JAR file in your server's `plugins` directory
3. Restart the server
4. Configure the plugin with `/ctf` commands

### From Source

1. Clone this repository
2. Run `./gradlew shadowJar`
3. Copy `build/libs/EasyCTF-x.x.x-all.jar` to plugins directory

## Requirements

- **Minecraft Server**: Paper 1.21.5+ (or compatible forks)
- **Java**: 21 or higher
- **Permission Plugin**: Optional (uses Bukkit permissions)

## Quick Start

### Method 1: Automatic Map Detection (Recommended)

1. **Set Map Area**
   ```
   /ctf setpos1  # Set start point at current location (temporary)
   /ctf setpos2  # Set end point at current location (temporary)
   ```
   Or for existing game:
   ```
   /ctf setpos1 <game-name>  # Set start point for specific game
   /ctf setpos2 <game-name>  # Set end point for specific game
   ```

2. **Place Required Blocks**
   - Red Concrete: Red team spawn point (1 or more, multiple allowed)
   - Blue Concrete: Blue team spawn point (1 or more, multiple allowed)
   - Beacon + Red Glass: Red team flag location (only 1)
   - Beacon + Blue Glass: Blue team flag location (only 1)
   
   **Note**: 
   - Spawn points must be at least 4 blocks apart from each other
   - Flags and spawn points must be at least 3 blocks apart

3. **Save the Map**
   ```
   /ctf savemap <game-name>
   ```

### Method 2: Interactive Creation

1. Start game creation: `/ctf create <game-name>`
2. Follow chat instructions to configure
3. Players join: `/ctf join <game-name>`
4. Start game: `/ctf start <game-name>`

### Starting Match Mode

To run multiple consecutive games:
```
/ctf start <game-name> match [number-of-games]
```
Example: `/ctf start arena1 match 5` (runs 5 games)

## Main Commands

### For Players
- `/ctf list` - Show all games
- `/ctf join <game-name>` - Join specified game
- `/ctf leave` - Leave current game
- `/ctf team [red|blue]` - Check/change team (before start only)
- `/ctf status [game-name]` - Check game status
- `/ctf spectator [game-name]` - Join as spectator

### For Admins
- `/ctf create <game-name>` - Create new game (interactive)
- `/ctf update <game-name>` - Update game settings (interactive)
- `/ctf delete <game-name>` - Delete game
- `/ctf start <game-name> [match] [number]` - Start game/match
- `/ctf stop <game-name>` - Force stop game
- `/ctf setflag <game-name> <red|blue>` - Manually set flag location
- `/ctf setspawn <game-name> <red|blue>` - Manually set spawn location
- `/ctf addspawn <game-name> <red|blue>` - Add spawn point
- `/ctf removespawn <game-name> <red|blue> <number>` - Remove spawn point
- `/ctf listspawns <game-name>` - List spawn points
- `/ctf setpos1 [game-name]` - Set map start point (temporary if name omitted)
- `/ctf setpos2 [game-name]` - Set map end point (temporary if name omitted)
- `/ctf savemap <game-name>` - Save map (auto-detection)

## Gameplay

### Phases

1. **Build Phase** 🏗️
   - Default 2 minutes (configurable)
   - Construct defensive structures (Game mode: ADVENTURE/SURVIVAL/CREATIVE selectable)
   - PvP disabled
   - Shop available
   - Block connection system active

2. **Combat Phase** ⚔️
   - Default 2 minutes (configurable)
   - Capture enemy flag and bring it back
   - PvP force enabled (configurable)
   - Shop available (near spawn points only)
   - Block breaking: Only with specific tools (pickaxe, shovel, bucket)

3. **Strategy Meeting Phase** 💭
   - Default 15 seconds (configurable)
   - View match results and MVP announcements
   - Prepare for next game (in match mode)
   - Different time settings available for final game vs intermediate games

### Flag System

- **Implementation**: Beacon (with 3x3 iron block base)
- **Capture**: Get within 1.5 blocks of enemy flag (beacon)
- **Carrier Effects**: Glowing, movement restriction (cannot use ender pearls/elytra)
- **Scoring Condition**: Can only capture when your team's flag is at base
- **Drop**: Drops on death (auto-returns after 30 seconds)

### Respawn System

- **Multiple Spawn Points**: Respawn at randomly selected spawn location
- **Respawn Delay**: Base delay + penalty per death (up to maximum)
- **Spawn Protection**: 3 seconds of invincibility and glowing after respawn
- **Protection Removal**: Removed immediately on attack or flag pickup

### Shop System

- **How to Open**: Right-click emerald in inventory
- **Usage Location**: Within 15 blocks of spawn point
- **Categories**: Weapons, armor, consumables, blocks
- **Special Features**: Death behavior settings (KEEP/DROP/DESTROY)

## Configuration

### config.yml (Main Settings)

```yaml
# Language settings
language: "en"  # "en" or "ja"

# Default game settings
default-game:
  min-players: 2
  max-players-per-team: 10
  respawn-delay-base: 10        # Base respawn delay (seconds)
  respawn-delay-per-death: 2    # Additional penalty per death (seconds)
  respawn-delay-max: 20         # Maximum respawn delay (seconds)
  force-pvp: true               # Force enable PvP in combat phase

# Phase settings
default-phases:
  build-duration: 120           # Build phase (seconds)
  build-phase-gamemode: "SURVIVAL"
  combat-duration: 120          # Combat phase (seconds)
  result-duration: 15           # Strategy meeting phase (seconds)
  intermediate-result-duration: 15  # Time between match games

# Match settings
match:
  default-target: 3             # Default number of games
  interval-duration: 15         # Interval between games (seconds)

# Currency settings
currency:
  name: "G"
  initial: 50                   # Initial currency
  phase-end-bonus: 50           # Phase end bonus
  kill-reward: 10               # Kill reward
  carrier-kill-reward: 20       # Flag carrier kill reward
  capture-reward: 30            # Capture reward

# Shop settings
shop:
  enabled: true
  use-range: 15                 # Usable distance from spawn
  discount:
    1-point: 0.1               # 10% discount for 1 point difference
    2-point: 0.2               # 20% discount for 2 point difference
    3-point: 0.3               # 30% discount for 3 point difference
    4-point-plus: 0.4          # 40% discount for 4+ point difference
```

## Troubleshooting

### Common Issues

1. **Shop won't open**
   - Use within 15 blocks of spawn point

2. **Can't capture flag**
   - Need to be within 1.5 blocks
   - Check if you're already carrying a flag

3. **Can't place blocks**
   - Block placement disabled during combat phase
   - In build phase, connect to team blocks

4. **Language won't change**
   - Server restart required after changing config.yml

## Development

### Build
```bash
./gradlew clean build
```

### Test
```bash
./gradlew test
```

### Development Server
```bash
./gradlew runServer
```

## Support

- **Issue Reports**: [GitHub Issues](https://github.com/0x48lab/easy_ctf/issues)
- **Documentation**: [Online Documentation](https://0x48lab.github.io/easy_ctf/)
- **Wiki**: [GitHub Wiki](https://github.com/0x48lab/easy_ctf/wiki)

## License

This project is licensed under the MIT License.