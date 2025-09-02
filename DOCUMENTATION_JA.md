# EasyCTF 詳細ドキュメント

## 目次

1. [インストールと初期設定](#インストールと初期設定)
2. [ゲーム作成ガイド](#ゲーム作成ガイド)
3. [マップ作成詳細](#マップ作成詳細)
4. [ゲームメカニクス](#ゲームメカニクス)
5. [管理者ガイド](#管理者ガイド)
6. [プレイヤーガイド](#プレイヤーガイド)
7. [設定リファレンス](#設定リファレンス)
8. [トラブルシューティング](#トラブルシューティング)

## インストールと初期設定

### 必要環境

- Paper Server 1.21.5以上
- Java 21以上
- 最小3GB、推奨4GB以上のRAM
- 複数ゲーム同時実行の場合は6GB以上推奨

### インストール手順

1. **プラグインのダウンロード**
   ```bash
   wget https://github.com/0x48lab/easy_ctf/releases/latest/download/easy_ctf-2.1.0.jar
   ```

2. **プラグインの配置**
   ```bash
   cp easy_ctf-2.1.0.jar /path/to/server/plugins/
   ```

3. **サーバー起動**
   ```bash
   java -Xms3G -Xmx4G -jar paper-1.21.5.jar nogui
   ```

4. **初期設定の確認**
   - `plugins/EasyCTF/config.yml`が自動生成されます
   - 言語設定を`ja`または`en`に設定

### 権限設定

| 権限 | 説明 | デフォルト |
|------|------|-----------|
| `ctf.admin` | 管理者コマンド全般 | OP |
| `ctf.use` | プレイヤーコマンド | 全員 |
| `ctf.bypass` | 制限回避 | OP |

### コマンド完全リファレンス

#### 管理者コマンド
| コマンド | 説明 | 権限 |
|---------|------|------|
| `/ctf create <game>` | 新規ゲーム作成（対話形式） | ctf.admin |
| `/ctf update <game>` | ゲーム設定更新 | ctf.admin |
| `/ctf delete <game>` | ゲーム削除 | ctf.admin |
| `/ctf start <game> [match] [数]` | ゲーム/マッチ開始 | ctf.admin |
| `/ctf stop <game>` | 強制終了 | ctf.admin |
| `/ctf setpos1 <game>` | マップ領域始点設定 | ctf.admin |
| `/ctf setpos2 <game>` | マップ領域終点設定 | ctf.admin |
| `/ctf savemap <game>` | マップ保存（自動検出） | ctf.admin |
| `/ctf setflag <game> <team>` | 旗位置手動設定 | ctf.admin |
| `/ctf setspawn <game> <team>` | スポーン設定 | ctf.admin |
| `/ctf addspawn <game> <team>` | スポーン追加 | ctf.admin |
| `/ctf removespawn <game> <team> <番号>` | スポーン削除 | ctf.admin |
| `/ctf listspawns <game>` | スポーン一覧 | ctf.admin |
| `/ctf addplayer <game> <player> [team]` | プレイヤー強制参加 | ctf.admin |
| `/ctf changeteam <game> <player> <team>` | チーム変更 | ctf.admin |
| `/ctf balance <game> [apply]` | チームバランス確認/適用 | ctf.admin |
| `/ctf resetstats [player]` | 統計リセット | ctf.admin |

#### プレイヤーコマンド
| コマンド | 説明 | 権限 |
|---------|------|------|
| `/ctf list` | 全ゲーム一覧 | ctf.use |
| `/ctf info <game>` | ゲーム詳細情報 | ctf.use |
| `/ctf join <game>` | ゲーム参加 | ctf.use |
| `/ctf leave` | ゲーム離脱 | ctf.use |
| `/ctf team [red\|blue]` | チーム確認/変更 | ctf.use |
| `/ctf status [game]` | 状況確認 | ctf.use |
| `/ctf spectator [game]` | 観戦モード | ctf.use |
| `/ctf stats [player]` | 統計表示 | ctf.use |
| `/ctf leaderboard [category]` | ランキング表示 | ctf.use |

## ゲーム作成ガイド

### 対話形式での作成（推奨）

```bash
/ctf create game1
```

対話形式で以下を設定：
1. **最小プレイヤー数** (2-20)
2. **チーム最大人数** (1-10) 
3. **先取り点数** (1-10)
4. **建築フェーズ時間** (60-600秒)
5. **戦闘フェーズ時間** (60-600秒)
6. **建築モード** (ADVENTURE/SURVIVAL/CREATIVE)
7. **マップ領域** (pos1, pos2)
8. **自動検出** または手動設定

### マップの自動検出

配置すべきブロック：
- **赤コンクリート**: 赤チームスポーン地点
- **青コンクリート**: 青チームスポーン地点
- **ビーコン+赤ガラス**: 赤チームの旗
- **ビーコン+青ガラス**: 青チームの旗

```
赤陣地                     青陣地
[R] = 赤コンクリート      [B] = 青コンクリート
[🚩] = ビーコン+赤ガラス   [🚩] = ビーコン+青ガラス

[R][R][R]                 [B][B][B]
[R][🚩][R]                [B][🚩][B]
[R][R][R]                 [B][B][B]
```

## マップ作成詳細

### マップ要件

1. **最小サイズ**: 30x30ブロック
2. **推奨サイズ**: 50x50〜100x100ブロック
3. **高さ**: 最低10ブロック

### 旗の設置

ビーコンの下に3x3の鉄ブロックベースが必要：

```
レイヤー1（地面）:
[鉄][鉄][鉄]
[鉄][鉄][鉄]
[鉄][鉄][鉄]

レイヤー2:
[ ][ ][ ]
[ ][ビーコン][ ]
[ ][ ][ ]

レイヤー3:
[ ][ ][ ]
[ ][色ガラス][ ]
[ ][ ][ ]
```

### 複数スポーン地点

各チーム最大5箇所のスポーン地点を設定可能：

```bash
/ctf addspawn game1 red    # 現在地を赤チームスポーンに追加
/ctf addspawn game1 blue   # 現在地を青チームスポーンに追加
/ctf listspawns game1      # スポーン地点一覧
/ctf removespawn game1 red 2  # 赤チームの2番目のスポーンを削除
```

## ゲームメカニクス

### フェーズシステム

ゲームは2つのフェーズで構成されています：

#### 建築フェーズ
- **時間**: デフォルト120秒（60-600秒で設定可能）
- **ゲームモード**: SURVIVAL/ADVENTURE/CREATIVE
- **PvP**: 無効
- **keepInventory**: true
- **ブロック配布**: チームカラーコンクリート
- **飛行**: 許可（CREATIVEモード以外）

#### 戦闘フェーズ
- **時間**: デフォルト120秒（60-600秒で設定可能）
- **ゲームモード**: SURVIVAL固定
- **PvP**: 強制有効
- **keepInventory**: false
- **リスポーン**: 即座（遅延なし）
- **旗奪取**: 可能

#### 結果表示
- **時間**: 15秒（設定可能）
- **ゲーム結果の確認**
- **統計更新**
- **マッチモード時は次ゲームへ自動移行**

### シールドシステム

```yaml
最大シールド: 100
敵陣での減少: 2/秒
自陣での回復: 5/秒
シールド0時: 1.5ダメージ/秒

警告レベル:
- 40以下: 黄色警告
- 20以下: 赤色警告（クリティカル）
```

### ブロック接続システム

チームブロックの配置ルール：
1. 自チームのビーコンに隣接
2. 既存の自チームブロックに隣接
3. 接続が切れると中立化（白色）

```
✓ 有効な配置:
[ビーコン][赤][赤][赤]

✗ 無効な配置:
[ビーコン][ ][赤]  <- 接続なし
```

### スキルシステム

#### スキルスコア計算
```
基本スコア = (キル × 10) + (キャプチャ × 30) - (死亡 × 5)

例:
- 10キル、2キャプチャ、5死亡
- スコア = (10 × 10) + (2 × 30) - (5 × 5) = 135
```

#### チームバランシング

新規参加者の配置アルゴリズム：
1. 両チームの現在のスキル合計を計算
2. 参加者を追加した場合の差を計算
3. 差が最小になるチームに配置

```
例:
赤チーム合計スキル: 500
青チーム合計スキル: 450
新規参加者スキル: 100

→ 青チームに配置（550 vs 500でバランス改善）
```

## 管理者ガイド

### ゲーム管理コマンド

#### 基本操作
```bash
/ctf create game1           # 新規ゲーム作成（対話形式）
/ctf update game1           # ゲーム設定更新
/ctf delete game1           # ゲーム削除
/ctf list                    # 全ゲーム一覧
/ctf info game1             # 詳細情報表示
/ctf start game1            # シングルゲーム開始
/ctf start game1 match 5    # 5ラウンドマッチ開始
/ctf stop game1             # 強制終了
```

#### マップ設定
```bash
/ctf setpos1 game1          # マップ領域始点設定
/ctf setpos2 game1          # マップ領域終点設定
/ctf savemap game1          # マップ保存（自動検出）
/ctf setflag game1 red      # 赤チームの旗位置手動設定
/ctf setspawn game1 red     # 赤チームのスポーン設定
/ctf addspawn game1 red     # 赤チームのスポーン追加
/ctf removespawn game1 red 2  # 赤チームの2番目のスポーン削除
/ctf listspawns game1       # スポーン一覧表示
```

#### プレイヤー管理
```bash
/ctf addplayer game1 Steve red     # Steveを赤チームに強制参加
/ctf changeteam game1 Alex blue    # Alexを青チームに変更
/ctf balance game1                 # バランス確認
/ctf balance game1 apply           # バランス調整実行
```

#### 統計管理
```bash
/ctf resetstats              # 全プレイヤーの統計リセット
/ctf resetstats Steve        # Steveの統計のみリセット
```

### マッチモード設定

```yaml
# マッチ設定例（config.yml）
match-settings:
  default-rounds: 5           # デフォルトラウンド数
  win-condition: "first-to"   # 先取り方式
  round-interval: 0           # ラウンド間隔（秒）
```

### パフォーマンス最適化

大規模サーバー向け設定：
```yaml
# 最適化設定
optimization:
  async-chat: true            # 非同期チャット処理
  batch-teleport: true        # バッチテレポート
  cache-connections: true     # ブロック接続キャッシュ
  compression-level: 6        # マップ圧縮レベル（1-9）
```

## プレイヤーガイド

### 基本コマンド

```bash
/ctf join game1             # ゲーム参加
/ctf leave                  # ゲーム離脱
/ctf team                   # 現在のチーム確認
/ctf team red              # 赤チームに変更（可能な場合）
/ctf status                 # 現在のゲーム状態
/ctf spectator game1        # 観戦モード
```

### 統計コマンド

```bash
/ctf stats                  # 自分の統計表示
/ctf stats Steve           # Steveの統計表示
/ctf leaderboard           # 総合ランキング
/ctf leaderboard kills     # キルランキング
/ctf leaderboard captures  # キャプチャランキング
/ctf leaderboard kd        # K/Dランキング
```

### ショップの使い方

1. **エメラルドを右クリック**でショップを開く
2. **カテゴリ選択**: 武器、防具、消耗品、ブロック
3. **アイテム購入**: クリックで購入（チーム共有通貨）
4. **割引確認**: スコア差による自動割引

### 戦術ガイド

#### 建築フェーズの戦術
- 旗周りに防壁を構築
- 高所に狙撃ポイント作成
- 落とし穴やトラップ設置
- 迷路構造で時間稼ぎ

#### 戦闘フェーズの戦術
- チーム連携で旗奪取
- 囮役と実行役の分担
- シールド管理（自陣で回復）
- イベントチェスト確保

## 設定リファレンス

### config.yml 完全リファレンス

```yaml
# 基本設定
language: "ja"                    # 言語（ja/en）
debug: false                      # デバッグモード
auto-save-interval: 300           # 自動保存間隔（秒）

# ゲームデフォルト設定
default-game:
  min-players: 2                  # 最小プレイヤー数
  max-players-per-team: 10        # チーム最大人数
  respawn-delay-base: 0           # 基本リスポーン時間
  respawn-delay-per-death: 0      # 死亡ごとの追加時間
  respawn-delay-max: 0            # 最大リスポーン時間
  force-pvp: true                 # PvP強制有効
  friendly-fire: false            # 味方への攻撃

# フェーズ設定
default-phases:
  build-duration: 120             # 建築フェーズ（秒）
  build-phase-gamemode: "SURVIVAL" # 建築時のゲームモード
  build-phase-blocks: 64          # 配布ブロック数
  combat-duration: 120            # 戦闘フェーズ（秒）
  combat-phase-blocks: 0          # 戦闘時の追加ブロック
  result-duration: 15             # 結果表示（秒）

# 通貨設定
currency:
  name: "G"                       # 通貨名
  initial: 50                     # 初期所持金
  kill-reward: 15                 # キル報酬
  kill-assist-reward: 10          # キルアシスト
  carrier-kill-reward: 25         # 旗持ちキル
  carrier-kill-assist-reward: 10  # 旗持ちアシスト
  capture-reward: 50              # キャプチャー
  capture-assist-reward: 20       # キャプチャーアシスト
  phase-end-bonus: 100           # フェーズ終了ボーナス
  
  # キルストリークボーナス
  kill-streak-bonus:
    2-kills: 5
    3-kills: 10
    4-kills: 15
    5-plus-kills: 20

# シールド設定
shield:
  enabled: true                   # シールド有効化
  max-shield: 100                 # 最大シールド
  decrease-rate: 2.0              # 敵陣での減少速度
  recovery-rate: 5.0              # 自陣での回復速度
  damage-amount: 1.5              # シールド0時のダメージ
  damage-interval: 1000           # ダメージ間隔（ms）
  warning-threshold: 40           # 警告閾値
  critical-threshold: 20          # 危険閾値

# ショップ設定
shop:
  enabled: true                   # ショップ有効化
  use-range: -1                   # 使用範囲（-1で無制限）
  
  # スコア差割引
  discount:
    1-point: 0.1                  # 10%割引
    2-point: 0.2                  # 20%割引
    3-point: 0.3                  # 30%割引
    4-point-plus: 0.4             # 40%割引

# イベントチェスト設定
event-chest:
  enabled: true                   # イベントチェスト有効化
  spawn-count: 1                  # 出現回数
  spawn-delay: 60                 # 出現遅延（秒）
  despawn-time: 120              # 消滅時間（秒）
  
  # レアアイテムリスト
  rare-items:
    - NETHERITE_SWORD
    - NETHERITE_AXE
    - TOTEM_OF_UNDYING
    - NOTCH_APPLE
    - ELYTRA

# マップ設定
map:
  compression-enabled: true       # 圧縮有効化
  compression-level: 6           # 圧縮レベル（1-9）
  auto-save: true               # 自動保存
  clear-containers: true        # コンテナクリア
  
# スキルシステム設定
skill-system:
  enabled: true                  # スキルシステム有効化
  auto-balance: true            # 自動バランス
  balance-threshold: 100        # バランス閾値
  
  # スコア計算
  score-calculation:
    kill-points: 10
    capture-points: 30
    death-penalty: 5

# 観戦者設定
spectator:
  allow-flying: true            # 飛行許可
  see-inventory: true          # インベントリ閲覧
  teleport-to-players: true    # プレイヤーへのTP
```

## トラブルシューティング

### よくある問題と解決方法

#### 1. マップが保存されない
```
原因: 領域が設定されていない
解決: /ctf setpos1 と /ctf setpos2 を実行
```

#### 2. 旗が検出されない
```
原因: ビーコンの下に鉄ブロックがない
解決: 3x3の鉄ブロックベースを設置
```

#### 3. ブロックが配置できない
```
原因: 接続ルール違反
解決: ビーコンまたは既存ブロックに隣接して配置
```

#### 4. シールドが回復しない
```
原因: 自陣にいない
解決: 自チームのブロックまたはビーコン付近へ移動
```

#### 5. ショップが開かない
```
原因: エメラルドではないアイテム
解決: 初期配布のエメラルドを使用
```

### エラーメッセージ対応表

| エラー | 原因 | 解決方法 |
|--------|------|----------|
| `Game not found` | ゲームが存在しない | ゲーム名を確認 |
| `Already in game` | 既に参加中 | /ctf leave で離脱 |
| `Team full` | チーム満員 | 他チームか観戦モード |
| `Not enough players` | 人数不足 | 最小人数待ち |
| `Map not set` | マップ未設定 | マップ保存を実行 |

### パフォーマンス問題

#### ラグが発生する場合
1. **view-distance を調整**
   ```yaml
   view-distance: 8  # 10から8に減らす
   ```

2. **エンティティ制限**
   ```yaml
   max-entities: 100  # エンティティ上限設定
   ```

3. **非同期処理を有効化**
   ```yaml
   async-chat: true
   batch-teleport: true
   ```

### デバッグモード

問題の詳細調査用：
```yaml
debug: true
debug-level: VERBOSE  # INFO, DEBUG, VERBOSE
```

ログファイル確認：
```bash
tail -f plugins/EasyCTF/logs/latest.log
```

## サポート

- **GitHub Issues**: https://github.com/0x48lab/easy_ctf/issues
- **Discord**: https://discord.gg/yourdiscord
- **Wiki**: https://github.com/0x48lab/easy_ctf/wiki
- **公式サイト**: https://0x48lab.github.io/easy_ctf/

---

最終更新: 2024年1月
バージョン: 2.1.0