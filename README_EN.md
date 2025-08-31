# 🏳️ EasyCTF - Minecraft CTF Plugin

<div align="center">
  
[![Version](https://img.shields.io/badge/Version-2.0-blue.svg)](https://github.com/0x48lab/easy_ctf)
[![Paper](https://img.shields.io/badge/Paper-1.21.5+-green.svg)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)

**Strategic CTF Plugin for Minecraft Paper Servers**

</div>

## 📖 Overview

EasyCTF is a feature-rich Capture The Flag plugin for Minecraft Paper servers. Two teams (Red and Blue) compete to capture each other's flags while defending their own.

### ✨ Key Features
- 🎮 **Multiple Concurrent Games** - Run multiple CTF games simultaneously on the same server
- 🛡️ **Shield System** - Take damage in enemy territory, recover only in your own
- 💎 **Shop System** - Team-shared currency for strategic item purchases  
- 🏆 **Match System** - Multiple rounds to determine overall winner
- 🌍 **Multi-language** - Full support for Japanese and English
- 🎁 **Event Chests** - Special rewards during combat phase

## 🎯 Game Phases

### 1️⃣ Build Phase (2 minutes)
- Place blocks for defense and offense
- No PvP (combat disabled)  
- Shop available for purchases
- Cannot enter enemy territory
- Instant respawn (no delay)

### 2️⃣ Combat Phase (2 minutes)
- Main CTF gameplay
- PvP enabled
- Capture enemy flags
- Shop available anywhere (right-click emerald)
- Instant respawn (no delay)

### 3️⃣ Strategy Phase (15 seconds)
- View round results
- Prepare for next round
- Transitions immediately to next phase

## 💎 Shop System

Right-click emerald to open shop (**usable anywhere**)

### Categories
- **Weapons** - Swords, axes, bows, etc.
- **Armor** - Various armor sets
- **Consumables** - Ender pearls, golden apples, arrows
- **Building Blocks** - Various blocks, TNT

### Currency Earning
- Initial funds: 50G
- Kill reward: 15G (Flag carrier: 25G)
- Kill assist: 10G
- Capture: 50G
- Assist: 20G
- Phase end bonus: 100G
- Kill streak bonus: 5G-20G

### Discount System
Discounts for losing team based on score difference:
- 1 point behind: 10% off
- 2 points behind: 20% off
- 3 points behind: 30% off
- 4+ points behind: 40% off

## 🛡️ Special Systems

### Shield System
- Maximum: 100
- Decreases by 2/sec on enemy blocks/beacon area
- 1.5 damage/sec when shield is 0
- **Recovers 5/sec only in own territory** (important tactical element)
- Warning: Below 40 warning, below 20 critical

### Beacon Area Effects
- 3x3 area is special zone
- Enemy: Damage zone (shield decrease)
- Ally: Hunger & shield recovery

### Team Block System
- **Red Team**: Red concrete only
- **Blue Team**: Blue concrete only
- Block connection required (neutralizes when disconnected)
- Unlimited usage

### Event Chests
- Appear once during combat phase
- Obtain expensive shop items
- Notification to all players

## 🗺️ Map Creation

### Auto-Detection Method (Recommended)

1. **Build Map**
   - Red concrete: Red team spawn (multiple allowed)
   - Blue concrete: Blue team spawn (multiple allowed)
   - Beacon + Red glass: Red team flag
   - Beacon + Blue glass: Blue team flag

2. **Set Region**
   ```bash
   /ctf setpos1 game1  # Set first corner
   /ctf setpos2 game1  # Set second corner
   ```

3. **Save**
   ```bash
   /ctf savemap game1  # Auto-detect and save
   ```

### Temporary World System
- Dedicated world generated at game start
- Map automatically restored
- Chest contents automatically cleared
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
| `/ctf setflag <game> <team>` | Set flag position |
| `/ctf setspawn <game> <team>` | Set spawn |
| `/ctf addspawn <game> <team>` | Add spawn |
| `/ctf removespawn <game> <team> <number>` | Remove spawn |

## 🎮 Player Commands

| Command | Description |
|---------|-------------|
| `/ctf join <game>` | Join game |
| `/ctf leave` | Leave current game |
| `/ctf team [red/blue]` | Check/change team |
| `/ctf spectator <game>` | Join as spectator |
| `/ctf status [game]` | Game status |

## ⚙️ Configuration

Main configuration file: `config.yml`

```yaml
# Language setting
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
  recovery-rate: 5.0       # Recovery rate in own territory
  damage-amount: 1.5       # Damage when shield is 0

# Event chest
event-chest:
  enabled: true
  spawn-count: 1           # Spawns during combat phase
```

## 🔧 Build Instructions

```bash
git clone https://github.com/0x48lab/easy_ctf.git
cd easy_ctf
./gradlew shadowJar
```

Generated JAR file: `build/libs/easy_ctf-x.x.x-all.jar`

## 📝 Release Notes

### Latest Changes
- ✅ Removed intervals between phases (instant transition)
- ✅ Set respawn delay to 0 (instant respawn)
- ✅ Adjusted currency rewards (Kill 15G, Flag carrier kill 25G, Capture 50G, Phase bonus 100G)
- ✅ Limited shield recovery to own territory only
- ✅ Removed shop usage range restriction (usable anywhere)
- ✅ Unified team blocks to concrete only
- ✅ Beacon area becomes damage zone
- ✅ Event chest contents limited to shop items
- ✅ Distance-based flag detection algorithm

### Performance Optimizations
- Batch teleport processing
- Asynchronous chat processing
- Efficient block connection checking
- GZIP compression for map storage

## 🤝 Contributing

Pull requests are welcome! Report bugs and suggest features in [Issues](https://github.com/0x48lab/easy_ctf/issues).

## 📞 Support

- 📚 [docs](https://0x48lab.github.io/easy_ctf/)
- 📚 [wiki](https://github.com/0x48lab/easy_ctf/wiki)

## 📄 License

This project is released under the MIT License.

---

<div align="center">
Made with ❤️ for Minecraft Community
</div>