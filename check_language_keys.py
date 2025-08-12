#!/usr/bin/env python3
import yaml
import re
import os
from pathlib import Path

def find_kotlin_files(root_dir):
    """Find all Kotlin files in the project"""
    kotlin_files = []
    for path in Path(root_dir).rglob("*.kt"):
        kotlin_files.append(path)
    return kotlin_files

def extract_language_keys(file_path):
    """Extract all getMessage calls from a Kotlin file"""
    keys = set()
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
        # Match getMessage("key" patterns
        pattern = r'getMessage\s*\(\s*["\']([^"\']+)["\']'
        matches = re.findall(pattern, content)
        keys.update(matches)
        
        # Also match getMessageAsComponent patterns
        pattern2 = r'getMessageAsComponent\s*\(\s*["\']([^"\']+)["\']'
        matches2 = re.findall(pattern2, content)
        keys.update(matches2)
    
    return keys

def load_yaml_keys(yaml_file):
    """Load all keys from a YAML language file"""
    with open(yaml_file, 'r', encoding='utf-8') as f:
        data = yaml.safe_load(f)
    
    def get_keys(d, prefix=''):
        keys = set()
        if isinstance(d, dict):
            for k, v in d.items():
                new_prefix = f"{prefix}.{k}" if prefix else k
                if isinstance(v, dict):
                    keys.update(get_keys(v, new_prefix))
                else:
                    keys.add(new_prefix)
        return keys
    
    return get_keys(data)

def main():
    # Project paths
    kotlin_src = "src/main/kotlin"
    lang_ja = "src/main/resources/lang_ja.yml"
    lang_en = "src/main/resources/lang_en.yml"
    
    # Find all Kotlin files and extract keys
    print("Scanning Kotlin files...")
    kotlin_files = find_kotlin_files(kotlin_src)
    used_keys = set()
    
    for kf in kotlin_files:
        keys = extract_language_keys(kf)
        if keys:
            used_keys.update(keys)
    
    print(f"Found {len(used_keys)} unique language keys used in code")
    
    # Load YAML keys
    print("\nLoading language files...")
    ja_keys = load_yaml_keys(lang_ja)
    en_keys = load_yaml_keys(lang_en)
    
    print(f"Japanese file has {len(ja_keys)} keys")
    print(f"English file has {len(en_keys)} keys")
    
    # Find missing keys
    print("\n=== MISSING KEYS IN JAPANESE FILE ===")
    missing_ja = used_keys - ja_keys
    if missing_ja:
        for key in sorted(missing_ja):
            print(f"  - {key}")
    else:
        print("  None")
    
    print("\n=== MISSING KEYS IN ENGLISH FILE ===")
    missing_en = used_keys - en_keys
    if missing_en:
        for key in sorted(missing_en):
            print(f"  - {key}")
    else:
        print("  None")
    
    # Find unused keys
    print("\n=== POTENTIALLY UNUSED KEYS IN JAPANESE FILE ===")
    unused_ja = ja_keys - used_keys
    if len(unused_ja) > 20:
        print(f"  {len(unused_ja)} keys (showing first 20)")
        for key in sorted(unused_ja)[:20]:
            print(f"  - {key}")
    elif unused_ja:
        for key in sorted(unused_ja):
            print(f"  - {key}")
    else:
        print("  None")
    
    # Specific scoreboard checks
    print("\n=== SCOREBOARD SPECIFIC CHECKS ===")
    scoreboard_keys = [
        "scoreboard.title-ctf",
        "scoreboard.match-game-number",
        "scoreboard.match-wins",
        "scoreboard.match-wins-compact",
        "scoreboard.game-score",
        "scoreboard.players-count",
        "scoreboard.players-count-spectator",
        "scoreboard.team-currency",
        "scoreboard.both-currency",
        "scoreboard.team-players",
        "scoreboard.team-players-spectator",
        "scoreboard.build-combat-time",
        "scoreboard.min-players"
    ]
    
    print("Checking scoreboard keys in Japanese file:")
    for key in scoreboard_keys:
        if key in ja_keys:
            print(f"  ✓ {key}")
        else:
            print(f"  ✗ {key} - MISSING!")
    
    print("\nChecking scoreboard keys in English file:")
    for key in scoreboard_keys:
        if key in en_keys:
            print(f"  ✓ {key}")
        else:
            print(f"  ✗ {key} - MISSING!")

if __name__ == "__main__":
    main()