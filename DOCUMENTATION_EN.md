# EasyCTF Detailed Documentation

## Table of Contents

1. [Installation and Setup](#installation-and-setup)
2. [Game Creation Guide](#game-creation-guide)
3. [Map Creation Details](#map-creation-details)
4. [Game Mechanics](#game-mechanics)
5. [Administrator Guide](#administrator-guide)
6. [Player Guide](#player-guide)
7. [Configuration Reference](#configuration-reference)
8. [Troubleshooting](#troubleshooting)

## Installation and Setup

### Requirements

- Paper Server 1.21.5 or higher
- Java 21 or higher
- Minimum 3GB, recommended 4GB+ RAM
- 6GB+ recommended for multiple concurrent games

### Installation Steps

1. **Download the plugin**
   ```bash
   wget https://github.com/0x48lab/easy_ctf/releases/latest/download/easy_ctf-2.1.0.jar
   ```

2. **Place the plugin**
   ```bash
   cp easy_ctf-2.1.0.jar /path/to/server/plugins/
   ```

3. **Start the server**
   ```bash
   java -Xms3G -Xmx4G -jar paper-1.21.5.jar nogui
   ```

4. **Verify initial setup**
   - `plugins/EasyCTF/config.yml` is auto-generated
   - Set language to `en` or `ja`

### Permission Setup

| Permission | Description | Default |
|------------|-------------|---------|
| `ctf.admin` | Admin commands | OP |
| `ctf.use` | Player commands | Everyone |
| `ctf.bypass` | Bypass restrictions | OP |

### Complete Command Reference

#### Admin Commands
| Command | Description | Permission |
|---------|-------------|------------|
| `/ctf create <game>` | Create new game (interactive) | ctf.admin |
| `/ctf update <game>` | Update game settings | ctf.admin |
| `/ctf delete <game>` | Delete game | ctf.admin |
| `/ctf start <game> [match] [count]` | Start game/match | ctf.admin |
| `/ctf stop <game>` | Force stop | ctf.admin |
| `/ctf setpos1 <game>` | Set map region start point | ctf.admin |
| `/ctf setpos2 <game>` | Set map region end point | ctf.admin |
| `/ctf savemap <game>` | Save map (auto-detection) | ctf.admin |
| `/ctf setflag <game> <team>` | Manually set flag position | ctf.admin |
| `/ctf setspawn <game> <team>` | Set spawn | ctf.admin |
| `/ctf addspawn <game> <team>` | Add spawn | ctf.admin |
| `/ctf removespawn <game> <team> <number>` | Remove spawn | ctf.admin |
| `/ctf listspawns <game>` | List spawns | ctf.admin |
| `/ctf addplayer <game> <player> [team]` | Force player join | ctf.admin |
| `/ctf changeteam <game> <player> <team>` | Change team | ctf.admin |
| `/ctf balance <game> [apply]` | Check/apply team balance | ctf.admin |
| `/ctf resetstats [player]` | Reset statistics | ctf.admin |

#### Player Commands
| Command | Description | Permission |
|---------|-------------|------------|
| `/ctf list` | List all games | ctf.use |
| `/ctf info <game>` | Game details | ctf.use |
| `/ctf join <game>` | Join game | ctf.use |
| `/ctf leave` | Leave game | ctf.use |
| `/ctf team [red\|blue]` | Check/change team | ctf.use |
| `/ctf status [game]` | Check status | ctf.use |
| `/ctf spectator [game]` | Spectator mode | ctf.use |
| `/ctf stats [player]` | Display statistics | ctf.use |
| `/ctf leaderboard [category]` | Display rankings | ctf.use |

## Game Creation Guide

### Interactive Creation (Recommended)

```bash
/ctf create game1
```

Interactive setup for:
1. **Minimum players** (2-20)
2. **Max players per team** (1-10)
3. **Points to win** (1-10)
4. **Build phase duration** (60-600 seconds)
5. **Combat phase duration** (60-600 seconds)
6. **Build mode** (ADVENTURE/SURVIVAL/CREATIVE)
7. **Map region** (pos1, pos2)
8. **Auto-detection** or manual setup

### Map Auto-Detection

Required block placement:
- **Red concrete**: Red team spawn points
- **Blue concrete**: Blue team spawn points
- **Beacon + Red glass**: Red team flag
- **Beacon + Blue glass**: Blue team flag

```
Red Base                  Blue Base
[R] = Red concrete       [B] = Blue concrete
[🚩] = Beacon+Red glass  [🚩] = Beacon+Blue glass

[R][R][R]                [B][B][B]
[R][🚩][R]               [B][🚩][B]
[R][R][R]                [B][B][B]
```

## Map Creation Details

### Map Requirements

1. **Minimum size**: 30x30 blocks
2. **Recommended size**: 50x50 to 100x100 blocks
3. **Height**: Minimum 10 blocks

### Flag Setup

Beacon requires 3x3 iron block base:

```
Layer 1 (Ground):
[Iron][Iron][Iron]
[Iron][Iron][Iron]
[Iron][Iron][Iron]

Layer 2:
[ ][ ][ ]
[ ][Beacon][ ]
[ ][ ][ ]

Layer 3:
[ ][ ][ ]
[ ][Colored Glass][ ]
[ ][ ][ ]
```

### Multiple Spawn Points

Each team can have up to 5 spawn points:

```bash
/ctf addspawn game1 red    # Add current location as red spawn
/ctf addspawn game1 blue   # Add current location as blue spawn
/ctf listspawns game1      # List all spawn points
/ctf removespawn game1 red 2  # Remove red team's 2nd spawn
```

## Game Mechanics

### Phase System

The game consists of two phases:

#### Build Phase
- **Duration**: Default 120 seconds (60-600 seconds configurable)
- **Game mode**: SURVIVAL/ADVENTURE/CREATIVE
- **PvP**: Disabled
- **keepInventory**: true
- **Block distribution**: Team color concrete
- **Flying**: Allowed (except CREATIVE mode)

#### Combat Phase
- **Duration**: Default 120 seconds (60-600 seconds configurable)
- **Game mode**: SURVIVAL (fixed)
- **PvP**: Force enabled
- **keepInventory**: false
- **Respawn**: Instant (no delay)
- **Flag capture**: Enabled

#### Result Display
- **Duration**: 15 seconds (configurable)
- **Review game results**
- **Statistics update**
- **Auto-transition to next game in match mode**

### Shield System

```yaml
Max shield: 100
Enemy territory decrease: 2/second
Home base recovery: 5/second
At 0 shield: 1.5 damage/second

Warning levels:
- Below 40: Yellow warning
- Below 20: Red warning (critical)
```

### Block Connection System

Team block placement rules:
1. Adjacent to team beacon
2. Adjacent to existing team blocks
3. Disconnected blocks neutralize (turn white)

```
✓ Valid placement:
[Beacon][Red][Red][Red]

✗ Invalid placement:
[Beacon][ ][Red]  <- No connection
```

### Skill System

#### Skill Score Calculation
```
Base Score = (Kills × 10) + (Captures × 30) - (Deaths × 5)

Example:
- 10 kills, 2 captures, 5 deaths
- Score = (10 × 10) + (2 × 30) - (5 × 5) = 135
```

#### Team Balancing

New player placement algorithm:
1. Calculate current skill totals for both teams
2. Calculate difference if player is added
3. Place on team that minimizes difference

```
Example:
Red team total skill: 500
Blue team total skill: 450
New player skill: 100

→ Placed on blue team (550 vs 500 improves balance)
```

## Administrator Guide

### Game Management Commands

#### Basic Operations
```bash
/ctf create game1           # Create new game (interactive)
/ctf update game1           # Update game settings
/ctf delete game1           # Delete game
/ctf list                    # List all games
/ctf info game1             # Show detailed info
/ctf start game1            # Start single game
/ctf start game1 match 5    # Start 5-round match
/ctf stop game1             # Force stop
```

#### Map Configuration
```bash
/ctf setpos1 game1          # Set map region start point
/ctf setpos2 game1          # Set map region end point
/ctf savemap game1          # Save map (auto-detection)
/ctf setflag game1 red      # Manually set red flag position
/ctf setspawn game1 red     # Set red team spawn
/ctf addspawn game1 red     # Add red team spawn
/ctf removespawn game1 red 2  # Remove red team's 2nd spawn
/ctf listspawns game1       # List all spawn points
```

#### Player Management
```bash
/ctf addplayer game1 Steve red     # Force Steve to red team
/ctf changeteam game1 Alex blue    # Change Alex to blue team
/ctf balance game1                 # Check balance
/ctf balance game1 apply           # Apply balance adjustment
```

#### Statistics Management
```bash
/ctf resetstats              # Reset all player stats
/ctf resetstats Steve        # Reset only Steve's stats
```

### Match Mode Configuration

```yaml
# Match settings example (config.yml)
match-settings:
  default-rounds: 5           # Default number of rounds
  win-condition: "first-to"   # First to X points
  round-interval: 0           # Interval between rounds (seconds)
```

### Performance Optimization

Settings for large servers:
```yaml
# Optimization settings
optimization:
  async-chat: true            # Async chat processing
  batch-teleport: true        # Batch teleportation
  cache-connections: true     # Cache block connections
  compression-level: 6        # Map compression level (1-9)
```

## Player Guide

### Basic Commands

```bash
/ctf join game1             # Join game
/ctf leave                  # Leave game
/ctf team                   # Check current team
/ctf team red              # Change to red team (if allowed)
/ctf status                 # Current game status
/ctf spectator game1        # Spectator mode
```

### Statistics Commands

```bash
/ctf stats                  # Show your stats
/ctf stats Steve           # Show Steve's stats
/ctf leaderboard           # Overall rankings
/ctf leaderboard kills     # Kill rankings
/ctf leaderboard captures  # Capture rankings
/ctf leaderboard kd        # K/D rankings
```

### Shop Usage

1. **Right-click emerald** to open shop
2. **Select category**: Weapons, Armor, Consumables, Blocks
3. **Purchase items**: Click to buy (team shared currency)
4. **Check discounts**: Auto-discount based on score difference

### Tactical Guide

#### Build Phase Tactics
- Build walls around flag
- Create sniper positions at height
- Set pit traps
- Build maze structures for time delay

#### Combat Phase Tactics
- Team coordination for flag capture
- Decoy and executor roles
- Shield management (recover at base)
- Secure event chests

## Configuration Reference

### Complete config.yml Reference

```yaml
# Basic settings
language: "en"                    # Language (en/ja)
debug: false                      # Debug mode
auto-save-interval: 300           # Auto-save interval (seconds)

# Default game settings
default-game:
  min-players: 2                  # Minimum players
  max-players-per-team: 10        # Max players per team
  respawn-delay-base: 0           # Base respawn time
  respawn-delay-per-death: 0      # Additional time per death
  respawn-delay-max: 0            # Max respawn time
  force-pvp: true                 # Force PvP enabled
  friendly-fire: false            # Friendly fire

# Phase settings
default-phases:
  build-duration: 120             # Build phase (seconds)
  build-phase-gamemode: "SURVIVAL" # Build phase game mode
  build-phase-blocks: 64          # Distributed blocks
  combat-duration: 120            # Combat phase (seconds)
  combat-phase-blocks: 0          # Additional combat blocks
  result-duration: 15             # Result display (seconds)

# Currency settings
currency:
  name: "G"                       # Currency name
  initial: 50                     # Initial funds
  kill-reward: 15                 # Kill reward
  kill-assist-reward: 10          # Kill assist
  carrier-kill-reward: 25         # Flag carrier kill
  carrier-kill-assist-reward: 10  # Flag carrier assist
  capture-reward: 50              # Capture reward
  capture-assist-reward: 20       # Capture assist
  phase-end-bonus: 100           # Phase end bonus
  
  # Kill streak bonuses
  kill-streak-bonus:
    2-kills: 5
    3-kills: 10
    4-kills: 15
    5-plus-kills: 20

# Shield settings
shield:
  enabled: true                   # Enable shields
  max-shield: 100                 # Maximum shield
  decrease-rate: 2.0              # Decrease rate in enemy territory
  recovery-rate: 5.0              # Recovery rate at home base
  damage-amount: 1.5              # Damage at 0 shield
  damage-interval: 1000           # Damage interval (ms)
  warning-threshold: 40           # Warning threshold
  critical-threshold: 20          # Critical threshold

# Shop settings
shop:
  enabled: true                   # Enable shop
  use-range: -1                   # Usage range (-1 for unlimited)
  
  # Score difference discounts
  discount:
    1-point: 0.1                  # 10% discount
    2-point: 0.2                  # 20% discount
    3-point: 0.3                  # 30% discount
    4-point-plus: 0.4             # 40% discount

# Event chest settings
event-chest:
  enabled: true                   # Enable event chests
  spawn-count: 1                  # Spawn count
  spawn-delay: 60                 # Spawn delay (seconds)
  despawn-time: 120              # Despawn time (seconds)
  
  # Rare items list
  rare-items:
    - NETHERITE_SWORD
    - NETHERITE_AXE
    - TOTEM_OF_UNDYING
    - NOTCH_APPLE
    - ELYTRA

# Map settings
map:
  compression-enabled: true       # Enable compression
  compression-level: 6           # Compression level (1-9)
  auto-save: true               # Auto-save
  clear-containers: true        # Clear containers
  
# Skill system settings
skill-system:
  enabled: true                  # Enable skill system
  auto-balance: true            # Auto-balance
  balance-threshold: 100        # Balance threshold
  
  # Score calculation
  score-calculation:
    kill-points: 10
    capture-points: 30
    death-penalty: 5

# Spectator settings
spectator:
  allow-flying: true            # Allow flying
  see-inventory: true          # View inventories
  teleport-to-players: true    # Teleport to players
```

## Troubleshooting

### Common Issues and Solutions

#### 1. Map won't save
```
Cause: Region not set
Solution: Run /ctf setpos1 and /ctf setpos2
```

#### 2. Flag not detected
```
Cause: No iron blocks under beacon
Solution: Place 3x3 iron block base
```

#### 3. Can't place blocks
```
Cause: Connection rule violation
Solution: Place adjacent to beacon or existing blocks
```

#### 4. Shield not recovering
```
Cause: Not in home base
Solution: Move to team blocks or beacon area
```

#### 5. Shop won't open
```
Cause: Not using emerald item
Solution: Use the initially distributed emerald
```

### Error Message Reference

| Error | Cause | Solution |
|-------|-------|----------|
| `Game not found` | Game doesn't exist | Check game name |
| `Already in game` | Already participating | Use /ctf leave |
| `Team full` | Team at capacity | Try other team or spectate |
| `Not enough players` | Insufficient players | Wait for minimum |
| `Map not set` | Map not configured | Save map |

### Performance Issues

#### If experiencing lag
1. **Adjust view-distance**
   ```yaml
   view-distance: 8  # Reduce from 10 to 8
   ```

2. **Entity limits**
   ```yaml
   max-entities: 100  # Set entity cap
   ```

3. **Enable async processing**
   ```yaml
   async-chat: true
   batch-teleport: true
   ```

### Debug Mode

For detailed investigation:
```yaml
debug: true
debug-level: VERBOSE  # INFO, DEBUG, VERBOSE
```

Check log files:
```bash
tail -f plugins/EasyCTF/logs/latest.log
```

## Support

- **GitHub Issues**: https://github.com/0x48lab/easy_ctf/issues
- **Discord**: https://discord.gg/yourdiscord
- **Wiki**: https://github.com/0x48lab/easy_ctf/wiki
- **Official Site**: https://0x48lab.github.io/easy_ctf/

---

Last Updated: January 2024
Version: 2.1.0