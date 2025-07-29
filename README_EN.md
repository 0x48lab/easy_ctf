# EasyCTF - Minecraft CTF Plugin

English | [日本語](README.md)

[![Build and Test](https://github.com/0x48lab/easy_ctf/actions/workflows/build.yml/badge.svg)](https://github.com/0x48lab/easy_ctf/actions/workflows/build.yml)
[![Build and Release](https://github.com/0x48lab/easy_ctf/actions/workflows/release.yml/badge.svg)](https://github.com/0x48lab/easy_ctf/actions/workflows/release.yml)

**🏆 Official hackCraft2 Plugin**

A feature-rich Capture The Flag plugin for Minecraft Paper servers. Manage and run multiple CTF games simultaneously with an intuitive UI and guide system that provides an enjoyable competitive experience for players from beginners to experts.

📚 **[Documentation](https://0x48lab.github.io/easy_ctf/)** - How to play and detailed feature explanations

## Key Features

- **🎮 Multiple Games Simultaneously**: Manage multiple CTF games in parallel on one server
- **💬 Interactive Configuration**: Intuitive game creation/update via commands and chat
- **📍 Real-time Guide**: Current objective display system via ActionBar
- **🏗️ 3-Phase System**: Progressive game flow: Build → Combat → Result
- **🚩 Advanced Flag System**: Proximity capture, team flag recovery, conditional capture
- **👥 Team Management**: Auto-balance, disconnect/reconnect support
- **🛡️ Spawn Protection**: 3-second invincibility
- **⚔️ Force PVP**: PVP enabled during combat phase regardless of server settings
- **📊 Persistence**: Automatic save/restore of game settings via YAML
- **🛒 Shop System**: Team-shared currency for item purchases, discounts for losing team
- **🏆 Match Mode**: Consecutive games to determine overall winner
- **🔗 Block Connection System**: Building blocks must connect to team beacon, disconnected blocks become neutral
- **💢 Enemy Territory Damage**: Continuous damage on enemy team blocks, deep infiltration is high risk

## Installation

### From Releases (Recommended)

**📦 Download pre-built files from [GitHub Releases](https://github.com/0x48lab/easy_ctf/releases)**

All releases include the following pre-built files:
- `EasyCTF-x.x.x.jar` - Main plugin file (ready to use)
- `plugin.yml` - Plugin configuration file
- Auto-generated changelog
- Installation and setup instructions

**Installation Steps:**
1. **[📥 Download Latest Release](https://github.com/0x48lab/easy_ctf/releases/latest)** and get `EasyCTF-x.x.x.jar`
2. Place the JAR file in your server's `plugins` directory
3. Restart the server
4. Configure the plugin with `/ctf` command

> **💡 Tip**: The [Releases page](https://github.com/0x48lab/easy_ctf/releases) shows version history and detailed changes for each version.

### From Source

1. Clone this repository
2. Run `./gradlew shadowJar`
3. Copy `build/libs/EasyCTF-x.x.x.jar` to plugins directory

## Requirements

- **Minecraft Server**: Paper 1.21+ (or compatible fork)
- **Java**: 21 or higher
- **Permission Plugin**: Optional (uses Bukkit permissions)

## Quick Start

### Create New Game (Interactive)
1. Start game creation: `/ctf create <game-name>`
2. Follow chat instructions to set:
   - Red team flag position (type `set` while looking at location)
   - Red team spawn point
   - Blue team flag position
   - Blue team spawn point
   - Build phase game mode
   - Phase durations
3. Players join: `/ctf join <game-name>`
4. Start game: `/ctf start <game-name>`

### Pre-create Maps (Recommended)
Creating dedicated CTF maps in advance ensures more balanced and enjoyable games.

#### Auto-detect Map Creation
1. **Set Map Area**
   ```
   /ctf setpos1 <game-name>  # Set start point at current location
   /ctf setpos2 <game-name>  # Set end point at current location
   ```

2. **Build the Map**
   Place the following blocks within the designated area:
   - **Red Concrete**: Red team spawn point (only one)
   - **Blue Concrete**: Blue team spawn point (only one)
   - **Beacon + Red Stained Glass**: Red team flag position
   - **Beacon + Blue Stained Glass**: Blue team flag position

3. **Save the Map**
   ```
   /ctf savemap <game-name>
   ```
   Automatically detects blocks and creates game configuration.

#### Map Creation Tips
- **Symmetry**: Recommend symmetric maps for fairness between teams
- **Distance**: Flags and spawns must be at least 3 blocks apart
- **Defense**: Can pre-build defensive structures around flags
- **Routes**: Multiple infiltration/escape routes make games more interesting
- **Elevation**: 3D maps increase strategic depth

#### Starting Game with Saved Map
After saving the map, start the game normally:
```
/ctf start <game-name>
```
A temporary world is automatically created and the saved map is restored when the game starts.

## Commands

### Player Commands
- `/ctf list` - Display all games
- `/ctf join <game-name>` - Join specified game (auto team assignment)
- `/ctf leave` - Leave current game
- `/ctf team [red|blue]` - Check/change team (before game start only)
- `/ctf status [game-name]` - Check game status

### Admin Commands
- `/ctf create <game-name>` - Create new game (interactive)
- `/ctf update <game-name>` - Update game settings (interactive)
- `/ctf delete <game-name>` - Delete game
- `/ctf start <game-name>` - Start game
- `/ctf stop <game-name>` - Force stop game
- `/ctf setflag <game-name> <red|blue>` - Set flag position directly
- `/ctf setspawn <game-name> <red|blue>` - Set spawn point directly
- `/ctf setpos1 [game-name]` - Set map area start point
- `/ctf setpos2 [game-name]` - Set map area end point
- `/ctf savemap <game-name>` - Save map (auto-detect)

## Configuration

### Global Settings (`config.yml`)

```yaml
# Plugin settings
plugin:
  auto-save: true           # Auto-save game settings
  max-games: -1             # Max number of games (-1 for unlimited)
  force-pvp: true           # Force enable PVP during combat phase

# Default game settings
default-game:
  min-players: 2            # Minimum players for auto-start
  max-players-per-team: 10  # Max players per team
  respawn-delay: 5          # Respawn delay (seconds)

# Default phase settings
default-phases:
  build-duration: 300           # Build phase duration (seconds)
  build-phase-gamemode: "ADVENTURE"  # ADVENTURE/SURVIVAL/CREATIVE
  combat-duration: 600          # Combat phase duration (seconds)
  result-duration: 60           # Result phase duration (seconds)
```

### Individual Game Settings (`games/<game-name>.yml`)

Each game's settings are automatically saved and restored on server restart.

## Game System

### Game Phases

#### 1. Build Phase 🏗️
- **Objective**: Build defenses to strengthen your base
- **ActionBar Guide**: "Build and fortify your defenses!"
- **Equipment**: Wooden tools, building blocks, food
- **Game Mode**: Configurable (Adventure/Survival/Creative)
- **Restrictions**: PVP disabled, flag/spawn decorations unbreakable

#### 2. Combat Phase ⚔️
- **Objective**: Capture enemy flag and bring it back to base
- **ActionBar Guide**: Dynamic instructions based on situation
  - Normal: "Capture the [enemy team] flag!"
  - Carrying flag: "Return to base!"
  - Own flag taken: "Enemy has your flag! Get it back! (player name)"
- **Equipment**: Team-colored armor, iron sword, bow & arrows, food
- **Restrictions**: All blocks unbreakable, PVP force enabled

#### 3. Result Phase 🏆
- **Objective**: Review match results
- **Content**: Winning team announcement, final score display
- **Restrictions**: Movement and combat disabled

### Flag System

- **Implementation**: Beacon (with team-colored stained glass)
- **Capture Method**: Get within 1.5 blocks of enemy flag
- **Carrier Effects**: Glowing, no ender pearl/elytra use
- **Drop**: Itemizes on death at location
- **Recovery**: 
  - Own team: Instantly restores beacon at base
  - Enemy team: Continue carrying
- **Auto-restore**: Returns to original position after 30 seconds
- **Scoring Condition**: Can only capture when own flag is at base

### Spawn Protection

- **Invincibility**: 3 seconds after respawn
- **Visual Effect**: Glowing during protection
- **Cancel Conditions**: Time expiry, attacking, flag capture

## Development

### Build
```bash
./gradlew clean build
```

### Test
```bash
./gradlew test
```

### Run Development Server
```bash
./gradlew runServer
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

- **Issue Reports**: [GitHub Issues](https://github.com/0x48lab/easy_ctf/issues)
- **Documentation**: [Online Documentation](https://0x48lab.github.io/easy_ctf/)
- **For Developers**: [CLAUDE.md](CLAUDE.md)
- **Wiki**: [GitHub Wiki](https://github.com/0x48lab/easy_ctf/wiki)

## Releases

### Automated Build System

This project uses GitHub Actions automated build system:

- **🏷️ Tag Push**: Auto-release when tags matching `v*.*.*` pattern (e.g., v1.0.0) are pushed
- **🔨 Auto Build**: Full Gradle build in Java 21 environment
- **📝 Changelog**: Auto-generated changes since last release
- **📦 Artifacts**: Pre-built JAR files and documentation automatically attached

### 📥 Download

- **[🚀 Latest Release](https://github.com/0x48lab/easy_ctf/releases/latest)** - Latest stable version
- **[📋 All Release History](https://github.com/0x48lab/easy_ctf/releases)** - All versions

### Files Included in Each Release

- **`EasyCTF-x.x.x.jar`** - Main plugin file (ready to use)
- **`plugin.yml`** - Plugin configuration file (reference)
- **Changelog** - Changes and fixes in that version
- **Installation Instructions** - Detailed setup guide
- **Requirements** - Required Minecraft/Java version information

> **🔄 Auto Updates**: New releases are automatically created when new features or fixes are added. Watch the [Releases page](https://github.com/0x48lab/easy_ctf/releases) to stay up to date!