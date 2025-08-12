#!/usr/bin/env python3
import yaml
from collections import OrderedDict

# 確実に未使用のキーのリスト（慎重に選定）
UNUSED_KEYS = [
    # game-phases - 未使用（確認済み）
    "game-phases.build",
    "game-phases.combat", 
    "game-phases.intermission",
    
    # game-states - 未使用（確認済み）
    "game-states.ending",
    "game-states.running",
    "game-states.starting",
    "game-states.waiting",
    
    # general - 未使用（確認済み）
    "general.disabled",
    "general.enabled",
    
    # items - 未使用（確認済み、building-blockは使用されている）
    "items.build",
    "items.combat",
    "items.intermission",
    
    # gameplay - 未使用（確認済み）
    "gameplay.item-name-error",
    
    # mvp - headerは未使用（announcement-titleが使用されている）
    "mvp.header",
    
    # spectator
    "spectator.mode-label",
    
    # time
    "time.warning",
    
    # log - デバッグ用で実際は未使用
    "log.shop-available-items",
    "log.shop-item-list", 
    "log.shop-item-not-found",
    "log.shop-purchase-called",
    
    # report - 未使用（確認済み）
    "report.draw",
    "report.final-score",
    "report.game-duration",
    "report.match-score",
    "report.mvp",
    "report.top-assists",
    "report.top-captures",
    "report.top-kills",
    "report.winner",
    
    # ui - 一部は使用されているが、これらは未使用
    "ui.cancelled",
    "ui.disabled",
    "ui.enabled",
    "ui.ending",
    "ui.failed",
    "ui.loading",
    "ui.running",
    "ui.saving",
    "ui.starting",
    "ui.success",
    
    # action-bar
    "action-bar.cannot-place-block",
]

def remove_keys_from_yaml(file_path, keys_to_remove):
    """Remove specified keys from YAML file while preserving structure"""
    with open(file_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    new_lines = []
    skip_line = False
    
    for i, line in enumerate(lines):
        # Skip empty processing for comments
        if line.strip().startswith('#'):
            new_lines.append(line)
            continue
            
        skip_line = False
        for key in keys_to_remove:
            # Split key into parts
            parts = key.split('.')
            
            # Check if this line contains the key
            if len(parts) == 1:
                # Root level key
                if line.strip().startswith(f"{parts[0]}:"):
                    skip_line = True
                    break
            elif len(parts) == 2:
                # Nested key
                if line.strip().startswith(f"{parts[1]}:"):
                    # Check previous lines for parent
                    for j in range(i-1, max(0, i-10), -1):
                        if lines[j].strip().startswith(f"{parts[0]}:"):
                            skip_line = True
                            break
                        if not lines[j].strip().startswith(' ') and lines[j].strip():
                            break
        
        if not skip_line:
            new_lines.append(line)
    
    # Write back
    with open(file_path, 'w', encoding='utf-8') as f:
        f.writelines(new_lines)
    
    return len(lines) - len(new_lines)

def main():
    files = [
        "src/main/resources/lang_ja.yml",
        "src/main/resources/lang_en.yml"
    ]
    
    for file_path in files:
        print(f"Processing {file_path}...")
        removed = remove_keys_from_yaml(file_path, UNUSED_KEYS)
        print(f"  Removed approximately {removed} lines")
    
    print("\nDone! Please review the changes.")

if __name__ == "__main__":
    main()