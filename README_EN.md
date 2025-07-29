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
- **Map Save/Restore**: Save created maps with compression, auto-restore at game start

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
- **Disconnected Block Neutralization**: Disconnected blocks turn white
- **Team-Exclusive Blocks**: Infinite colored concrete and glass
- **Enemy Territory Shield System**: Take continuous damage on enemy team blocks

### 🌍 Multi-Language Support
- **Japanese & English Support**: All messages managed in language files
- **Easy Switching**: Change language in config.yml
- **Customizable**: Edit lang_ja.yml, lang_en.yml

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
   /ctf setpos1 <game-name>  # Set start point at current location
   /ctf setpos2 <game-name>  # Set end point at current location
   ```

2. **Place Required Blocks**
   - Red Concrete: Red team spawn point (1 only)
   - Blue Concrete: Blue team spawn point (1 only)
   - Beacon + Red Glass: Red team flag location
   - Beacon + Blue Glass: Blue team flag location

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

### For Admins
- `/ctf create <game-name>` - Create new game
- `/ctf update <game-name>` - Update game settings
- `/ctf delete <game-name>` - Delete game
- `/ctf start <game-name> [match] [number]` - Start game/match
- `/ctf stop <game-name>` - Force stop game
- `/ctf setpos1/setpos2 <game-name>` - Set map area
- `/ctf savemap <game-name>` - Save map

## Gameplay

### Phases

1. **Build Phase** 🏗️
   - Default 2 minutes (configurable)
   - Construct defensive structures
   - PvP disabled
   - Shop available

2. **Combat Phase** ⚔️
   - Default 2 minutes (configurable)
   - Capture enemy flag and bring it back
   - PvP force enabled
   - Block placement disabled, some breaking allowed

3. **Strategy Meeting Phase** 💭
   - Default 15 seconds (configurable)
   - View match results and MVP announcements
   - Prepare for next game (in match mode)

### Flag System

- **Capture**: Get within 1.5 blocks of enemy flag (beacon)
- **Carrier Effects**: Glowing, cannot use ender pearls/elytra
- **Scoring Condition**: Can only capture when your team's flag is at base
- **Drop**: Drops on death (auto-returns after 15 seconds)

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
  respawn-delay-base: 10
  respawn-delay-per-death: 2
  respawn-delay-max: 20

# Phase settings
default-phases:
  build-duration: 120        # Build phase (seconds)
  build-phase-gamemode: "SURVIVAL"
  combat-duration: 120       # Combat phase (seconds)
  result-duration: 15        # Strategy meeting phase (seconds)
  intermediate-result-duration: 15  # Time between match games

# Currency settings
currency:
  initial: 50
  kill-reward: 10
  carrier-kill-reward: 20
  capture-reward: 30
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