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
        patterns = [
            r'getMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getMessageAsComponent\s*\(\s*["\']([^"\']+)["\']',
            r'getPhaseMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getTeamMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getCommandMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getGameMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getBlockMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getArmorMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getTimeMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getGeneralMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getResultMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getUIMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getTeamsMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getCommandExtendedMessage\s*\(\s*["\']([^"\']+)["\']',
            r'getGameStateMessage\s*\(\s*["\']([^"\']+)["\']'
        ]
        
        for pattern in patterns:
            matches = re.findall(pattern, content)
            keys.update(matches)
    
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

def remove_keys_from_yaml(yaml_file, keys_to_remove):
    """Remove specified keys from YAML file"""
    with open(yaml_file, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    # Track which lines to keep
    keep_lines = []
    skip_until_outdent = False
    skip_indent_level = 0
    
    for line in lines:
        # Calculate indent level
        stripped = line.lstrip()
        if not stripped or stripped.startswith('#'):
            # Keep empty lines and comments
            if not skip_until_outdent:
                keep_lines.append(line)
            continue
            
        indent = len(line) - len(stripped)
        
        # Check if we should stop skipping
        if skip_until_outdent and indent <= skip_indent_level:
            skip_until_outdent = False
        
        if skip_until_outdent:
            continue
            
        # Check if this line starts a key we want to remove
        should_skip = False
        for key in keys_to_remove:
            key_parts = key.split('.')
            # Simple check - could be improved
            if any(part in line for part in key_parts[-1:]):
                # More detailed check would be needed here
                should_skip = True
                skip_until_outdent = True
                skip_indent_level = indent
                break
        
        if not should_skip:
            keep_lines.append(line)
    
    return keep_lines

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
    
    # Handle special cases - helper methods that construct keys
    # For example, getPhaseMessage constructs "phase.{phase}-{action}"
    special_prefixes = [
        "phase.",
        "team.",
        "command.",
        "game.",
        "block.",
        "armor.",
        "time.",
        "general.",
        "result.",
        "ui.",
        "teams.",
        "command-extended.",
        "game-states."
    ]
    
    # Add dynamically constructed keys
    for prefix in special_prefixes:
        for key in list(used_keys):
            if key.startswith(prefix.replace(".", "")):
                used_keys.add(f"{prefix}{key}")
    
    print(f"Found {len(used_keys)} unique language keys used in code")
    
    # Load YAML keys
    print("\nLoading language files...")
    ja_keys = load_yaml_keys(lang_ja)
    en_keys = load_yaml_keys(lang_en)
    
    print(f"Japanese file has {len(ja_keys)} keys")
    print(f"English file has {len(en_keys)} keys")
    
    # Find unused keys
    print("\n=== UNUSED KEYS IN JAPANESE FILE ===")
    unused_ja = ja_keys - used_keys
    
    # Filter out some known dynamic keys
    filtered_unused_ja = set()
    for key in unused_ja:
        # Skip keys that are likely used dynamically
        if "${" in key or "$" in key:
            continue
        # Skip placeholder action keys
        if key.endswith(".$action"):
            continue
        filtered_unused_ja.add(key)
    
    # Sort by category for better readability
    categories = {}
    for key in sorted(filtered_unused_ja):
        category = key.split('.')[0] if '.' in key else 'root'
        if category not in categories:
            categories[category] = []
        categories[category].append(key)
    
    total_unused = 0
    for category, keys in sorted(categories.items()):
        if keys:
            print(f"\n{category}: ({len(keys)} keys)")
            for key in keys[:10]:  # Show first 10 of each category
                print(f"  - {key}")
            if len(keys) > 10:
                print(f"  ... and {len(keys) - 10} more")
            total_unused += len(keys)
    
    print(f"\nTotal unused keys: {total_unused}")
    
    # Create a file with all unused keys for review
    with open("unused_keys.txt", "w", encoding="utf-8") as f:
        f.write("# Unused keys in language files\n")
        f.write(f"# Total: {total_unused} keys\n\n")
        for category, keys in sorted(categories.items()):
            if keys:
                f.write(f"\n## {category} ({len(keys)} keys)\n")
                for key in sorted(keys):
                    f.write(f"{key}\n")
    
    print("\nFull list saved to unused_keys.txt")
    print("\nWARNING: Review the list before deleting!")
    print("Some keys might be used in ways not detected by this script.")

if __name__ == "__main__":
    main()