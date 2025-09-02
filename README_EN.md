# 🏳️ EasyCTF - Minecraft CTF Plugin

<div align="center">
  
[![Version](https://img.shields.io/badge/Version-2.1-blue.svg)](https://github.com/0x48lab/easy_ctf)
[![Paper](https://img.shields.io/badge/Paper-1.21.5+-green.svg)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)

**Strategic CTF Plugin for Minecraft Paper Server**

[日本語](README.md) | English

</div>

## 📋 Overview

EasyCTF is a feature-rich CTF plugin for Minecraft Paper Server. It provides a strategic PvP game mode where two teams compete to capture each other's flags.

### ✨ Key Features

- 🎮 **Multiple Concurrent Games** - Run multiple CTF games simultaneously on the same server
- 🏆 **Match System** - Determine the overall winner through multiple rounds
- 💰 **Shop System** - Strategic shopping with team-shared currency
- 🛡️ **Shield System** - Damage management in enemy territory (recovery only in home base)
- 🎁 **Event Chests** - Special rewards that appear during combat
- 📊 **Skill-Based Matchmaking** - Automatic team balancing based on player skills
- 🗺️ **Map Creation Tools** - Easily create custom maps
- 🌏 **Multi-language Support** - Full support for Japanese and English

## 🚀 Quick Start

### Requirements

- Paper Server 1.21.5 or higher
- Java 21 or higher
- 4GB+ RAM recommended

### Installation

1. Download the latest version from [Releases](https://github.com/0x48lab/easy_ctf/releases)
2. Place `easy_ctf-x.x.x.jar` in your server's `plugins` folder
3. Start the server

### Basic Usage

#### 1. First, Build Your Map
**Important: Place the following in your map**
- 🔴 Red Concrete = Red team spawn
- 🔵 Blue Concrete = Blue team spawn
- Beacon = Team flag (place near spawns)

#### 2. Create Game
```bash
/ctf setpos1 game1        # Set map region start point
/ctf setpos2 game1        # Set map region end point
/ctf create game1         # Create game (interactive setup)

# Start game
/ctf start game1          # Start single game
/ctf start game1 match 5  # Start 5-round match

# Player participation
/ctf join game1           # Join game (skill-based auto team assignment)
```

## 🎯 Game Flow

### 1️⃣ Build Phase (Default 2 minutes)
- Build defensive structures with your team's colored concrete blocks
- Blocks must connect to your beacon or existing blocks
- Purchase items from the shop to prepare
- Cannot enter enemy territory
- Items are kept on death (keepInventory)

### 2️⃣ Combat Phase (Default 2 minutes)
- Capture the enemy flag (beacon) and bring it back to your base
- Flag carriers have glowing effect and movement restrictions
- Earn currency through kills and captures
- Instant respawn (no delay)

### 3️⃣ Result Display (15 seconds)
- Review game results
- Auto-transition to next game in match mode

## 📊 Player Statistics System

### Skill Score Calculation
```
Skill Score = (Kills × 10) + (Captures × 30) - (Deaths × 5)
```

### Statistics Commands
- `/ctf stats [player]` - Display personal statistics
- `/ctf leaderboard [category]` - Display rankings
  - Categories: skill, kills, captures, wins, kd
- `/ctf balance <game>` - Check team balance
- `/ctf balance <game> apply` - Apply balance adjustment

### Automatic Team Balancing
- New participants are automatically assigned to balance team skill totals
- Administrators can manually adjust balance

## 💎 Shop System

Right-click emerald to open shop (**available anywhere**)

### Categories
- **Weapons** - Swords, axes, bows, etc.
- **Armor** - Various armor sets
- **Consumables** - Ender pearls, golden apples, arrows
- **Building Blocks** - Various blocks, TNT

### Currency Acquisition
- Initial funds: 50G
- Kill reward: 15G (Flag carrier: 25G)
- Kill assist: 10G
- Capture: 50G
- Assist: 20G
- Phase end bonus: 100G
- Kill streak bonus: 5G-20G

### Discount System
Losing team discount based on score difference:
- 1 point difference: 10% discount
- 2 point difference: 20% discount
- 3 point difference: 30% discount
- 4+ point difference: 40% discount

## 🛡️ Special Systems

### Shield System
- Maximum: 100
- Decreases by 2/second in enemy blocks/beacon area
- 1.5 damage/second at 0 shield
- **Recovers 5/second only in home base** (important tactical element)
- Warning: Alert at 40 or below, critical at 20 or below

### Beacon Area Effects
- 3x3 range special area
- Enemy: Damage zone (shield decrease)
- Ally: Hunger & shield recovery (1 point/second)

### Team Block System
- **Red Team**: Red concrete only
- **Blue Team**: Blue concrete only
- Connection required (neutralizes when disconnected)
- Unlimited usage

### Event Chests
- Appear once during combat phase
- Can obtain 1 rare item
- Notifies all players

### Kill Streak System
- Special notifications and bonuses for consecutive kills
- Title display only for killer
- Chat notification for other players

## 🗺️ Map Creation

### Map Creation Method

1. **Place Required Elements**
   - 🔴 **Red Concrete**: Red team spawn (multiple allowed)
   - 🔵 **Blue Concrete**: Blue team spawn (multiple allowed)
   - **Beacon**: Team flag (place near spawns)

2. **Set Region**
   ```bash
   /ctf setpos1 game1  # Set start point
   /ctf setpos2 game1  # Set end point
   ```

3. **Create Game**
   ```bash
   /ctf create game1   # Create game with interactive setup
   ```

   After creating the game, save the map:
   ```bash
   /ctf savemap game1  # Auto-detect and save
   ```

### Temporary World System
- Generates dedicated world at game start
- Automatically restores map
- Container contents are automatically cleared
- World deleted at game end

## 📊 Admin Commands

| Command | Description |
|---------|-------------|
| `/ctf create <game>` | Create new game (interactive) |
| `/ctf update <game>` | Update game settings |
| `/ctf delete <game>` | Delete game |
| `/ctf list` | List all games |
| `/ctf info <game>` | Game details |
| `/ctf start <game> [match] [count]` | Start game/match |
| `/ctf stop <game>` | Force stop |
| `/ctf setpos1 <game>` | Set map region start point |
| `/ctf setpos2 <game>` | Set map region end point |
| `/ctf savemap <game>` | Save map (auto-detection) |
| `/ctf setflag <game> <team>` | Manually set flag position |
| `/ctf setspawn <game> <team>` | Set spawn |
| `/ctf addspawn <game> <team>` | Add spawn |
| `/ctf removespawn <game> <team> <number>` | Remove spawn |
| `/ctf listspawns <game>` | List spawns |
| `/ctf addplayer <game> <player> [team]` | Force player join |
| `/ctf changeteam <game> <player> <team>` | Change team |
| `/ctf balance <game> [apply]` | Check/apply team balance |
| `/ctf resetstats [player]` | Reset statistics |

## 🎮 Player Commands

| Command | Description |
|---------|-------------|
| `/ctf join <game>` | Join game |
| `/ctf leave` | Leave game |
| `/ctf team [red\|blue]` | Check/change team |
| `/ctf status [game]` | Check status |
| `/ctf spectator [game]` | Spectator mode |
| `/ctf stats [player]` | Display statistics |
| `/ctf leaderboard [category]` | Display rankings |

## ⚙️ Configuration

Detailed configuration available in `plugins/EasyCTF/config.yml`:

```yaml
# Language settings
language: "en"  # "en" or "ja"

# Phase durations
default-phases:
  build-duration: 120      # Build phase (seconds)
  combat-duration: 120     # Combat phase (seconds)
  result-duration: 15      # Result display (seconds)
  build-phase-gamemode: "SURVIVAL"  # ADVENTURE/SURVIVAL/CREATIVE

# Respawn settings
default-game:
  respawn-delay-base: 0    # Instant respawn
  respawn-delay-per-death: 0
  respawn-delay-max: 0

# Currency settings
currency:
  initial: 50              # Initial currency
  kill-reward: 15          # Kill reward
  kill-assist-reward: 10   # Kill assist reward
  carrier-kill-reward: 25  # Flag carrier kill
  carrier-kill-assist-reward: 10  # Flag carrier kill assist
  capture-reward: 50       # Capture reward
  capture-assist-reward: 20  # Capture assist reward
  phase-end-bonus: 100     # Phase end bonus
  kill-streak-bonus:       # Kill streak bonus
    2-kills: 5
    3-kills: 10
    4-kills: 15
    5-plus-kills: 20

# Shield settings
shield:
  enabled: true
  max-shield: 100
  decrease-rate: 2.0       # Decrease rate in enemy territory
  recovery-rate: 5.0       # Recovery rate in home base
  damage-amount: 1.5       # Damage at 0 shield

# Event chest
event-chest:
  enabled: true
  spawn-count: 1           # Spawn count during combat phase
```

## 🔧 Build Instructions

```bash
git clone https://github.com/0x48lab/easy_ctf.git
cd easy_ctf
./gradlew shadowJar
```

Generated JAR file: `build/libs/easy_ctf-x.x.x-all.jar`

## 📝 Release Notes

### v2.1.0 - Skill-Based Matchmaking
- ✨ Implemented player statistics persistence system
- ✨ Skill-based automatic team balancing
- ✨ Ranking/leaderboard functionality
- ✨ Admin commands for force join and team change
- 🐛 Fixed block duplication bug when dying in build phase
- 🌏 Complete Japanese resource file translation (53 fixes)
- 🎯 Limited kill streak notifications to killer only
- 🎁 Simplified event chests to 1 rare item

### v2.0.0 - Performance Optimization
- ✅ Removed intervals between phases (instant transition)
- ✅ Set respawn delay to 0 (instant revival)
- ✅ Adjusted currency rewards (Kill 15G, Flag carrier kill 25G, Capture 50G, Phase bonus 100G)
- ✅ Limited shield recovery to home base only
- ✅ Removed shop usage range restriction (available anywhere)
- ✅ Unified team blocks to concrete only
- ✅ Beacon area becomes damage zone
- ✅ Implemented distance-based flag detection algorithm

## 🤝 Contributing

Pull requests are welcome! Report bugs and suggest features in [Issues](https://github.com/0x48lab/easy_ctf/issues).

## 📞 Support

- 📚 [Documentation](DOCUMENTATION_EN.md)
- 📚 [日本語ドキュメント](DOCUMENTATION_JA.md)
- 🌐 [Official Site](https://0x48lab.github.io/easy_ctf/)
- 💬 [Discord Server](https://discord.gg/yourdiscord)
- 🐛 [Bug Reports](https://github.com/0x48lab/easy_ctf/issues)

## 📄 License

This project is released under the MIT License.

---

<div align="center">
Made with ❤️ for Minecraft Community
</div>