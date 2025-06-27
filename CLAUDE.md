# EasyCTF - Minecraft Capture The Flag Plugin

## プロジェクト Overview

EasyCTFは、Minecraft Paper Server用のシンプルなCapture The Flag（CTF）プラグインです。Red TeamとBlue Teamに分かれて旗を取り合う対戦ゲームを実装しています。

## プロジェクト構成

```
src/main/kotlin/com/hacklab/ctf/
├── Main.kt                    # メインプラグインクラス
├── commands/
│   └── CTFCommand.kt         # コマンド処理
├── listeners/
│   └── GameListener.kt       # ゲームイベント処理
├── managers/
│   └── GameManager.kt        # ゲーム状態管理
└── utils/
    ├── GameState.kt          # ゲーム状態定義
    ├── GamePhase.kt          # ゲームフェーズ定義
    └── Team.kt               # チーム定義

src/main/resources/
├── plugin.yml                # プラグイン設定
└── config.yml                # ゲーム設定
```

## 技術スタック

- **言語**: Kotlin 1.9.22
- **プラットフォーム**: Minecraft Paper API 1.21.5
- **ビルドツール**: Gradle with Kotlin DSL
- **JVM**: Java 21

## ゲーム仕様

### チーム構成
- **Red Team**: 赤チーム（最大10名）
- **Blue Team**: 青チーム（最大10名）

### ゲーム状態
- `WAITING`: プレイヤー待機中
- `STARTING`: ゲーム開始準備中
- `RUNNING`: ゲーム進行中
- `ENDING`: ゲーム終了処理中

### ゲームフェーズ
ゲームは3つのフェーズで構成されます：

1. **BUILD PHASE（建築フェーズ）**
   - 時間: デフォルト5分（設定可能）
   - 内容: プレイヤーは建築ツールとブロックを配布され、防御設備を構築
   - 装備: 木製ツール、建築ブロック（石、木材、土）
   - 制限: 戦闘は禁止、旗は配置されない

2. **COMBAT PHASE（戦闘フェーズ）**
   - 時間: デフォルト10分（設定可能）
   - 内容: CTFの本戦、相手チームの旗を奪取して自陣に持ち帰る
   - 装備: チーム色防具、鉄剣、弓矢、食料
   - 目標: 敵の旗をキャプチャしてスコアを獲得

3. **RESULT PHASE（リザルトフェーズ）**
   - 時間: デフォルト1分（設定可能）
   - 内容: 試合結果の表示、勝利チームの発表
   - 制限: 戦闘・移動制限、装備没収

### ゲームルール
1. **目標**: 相手チームの旗を取って自陣に持ち帰る
2. **時間制限**: デフォルト10分（設定可能）
3. **最小プレイヤー数**: 2名
4. **装備**: チーム色の革防具を自動装備
5. **リスポーン**: 死亡後5秒でリスポーン

### 旗システム
- 各チームの旗は指定座標に配置
- 旗を持ったプレイヤーは発光効果
- 旗キャリアが死亡すると旗をドロップ
- 自陣の旗を持ち帰ると得点

## コマンド仕様

### 基本コマンド: `/ctf`

| サブコマンド | 権限 | 説明 |
|-------------|------|------|
| `start` | `ctf.admin` | ゲーム開始 |
| `stop` | `ctf.admin` | ゲーム停止 |
| `join <red\|blue>` | `ctf.use` | チーム参加 |
| `leave` | `ctf.use` | チーム離脱 |
| `setflag <red\|blue>` | `ctf.admin` | 旗の位置設定 |
| `setspawn <red\|blue>` | `ctf.admin` | スポーン地点設定 |
| `setteam <player> <red\|blue>` | `ctf.admin` | 指定プレイヤーのチーム設定 |
| `status` | `ctf.use` | ゲーム状態確認 |

### 権限システム
- `ctf.use`: 基本コマンド使用権限（デフォルト: true）
- `ctf.admin`: 管理者コマンド使用権限（デフォルト: op）
- `ctf.*`: 全権限（デフォルト: op）

## 主要機能

### 1. チーム管理
- プレイヤーのチーム所属管理
- 管理者による他プレイヤーのチーム設定
- チーム色の防具自動配布
- チーム別スポーン地点
- チーム人数制限の自動チェック

### 2. 旗システム
- 旗の配置・回収システム
- 旗キャリア追跡
- 旗状態の表示

### 3. スコアボード
- リアルタイムスコア表示
- 残り時間表示
- チーム情報表示

### 4. BossBar
- 現在のフェーズ表示
- フェーズ別残り時間の視覚化
- フェーズ別色分け表示

### 5. フェーズ管理システム
- 自動フェーズ遷移
- フェーズ別プレイヤー装備管理
- フェーズ別ゲームルール適用
- フェーズ別時間管理

### 6. ゲーム制限
- 防具の取り外し禁止（戦闘フェーズのみ）
- ブロック破壊・設置制限（フェーズ別）
- アイテムドロップ制限

## 設定ファイル（config.yml）

### ゲーム設定
```yaml
game:
  time-limit: 600           # ゲーム時間（秒）- 非推奨、フェーズ設定を使用
  min-players: 2            # 最小プレイヤー数
  max-players-per-team: 10  # チーム最大人数
  respawn-delay: 5          # リスポーン遅延（秒）

# フェーズ設定
phases:
  build-duration: 300       # 建築フェーズ時間（秒）
  combat-duration: 600      # 戦闘フェーズ時間（秒）
  result-duration: 60       # リザルトフェーズ時間（秒）
```

### エフェクト設定
```yaml
effects:
  flag-beacon:
    enabled: true           # 旗ビーコン効果
    particles:
      enabled: true         # パーティクル効果
      type: "DUST"
      count: 5
  flag-carrier:
    glow: true             # 旗キャリア発光
```

### ワールド設定
```yaml
world:
  disable-block-break: true  # ブロック破壊禁止
  disable-block-place: true  # ブロック設置禁止
  disable-item-drop: true    # アイテムドロップ禁止
```

## 開発・ビルド手順

### 前提条件
- Java 21
- Gradle 8.3+
- Paper Server 1.21.5

### ビルドコマンド
```bash
# プロジェクトビルド
./gradlew build

# プラグインJAR作成
./gradlew shadowJar

# 開発サーバー起動
./gradlew runServer
```

### 生成ファイル
- `build/libs/easy_ctf-1.0-SNAPSHOT-all.jar` - プラグインファイル

## 主要クラス詳細

### Main.kt
- プラグインのエントリーポイント
- GameManagerの初期化
- コマンド・リスナーの登録

### GameManager.kt
- ゲーム状態の管理
- 3フェーズシステムの制御
- プレイヤー・チーム情報の管理
- 管理者によるプレイヤーチーム設定機能
- フェーズ別装備・ルール管理
- スコアボード・BossBarの制御
- 旗システムの実装

### CTFCommand.kt
- 全コマンドの処理
- 権限チェック
- タブ補完機能

### GameListener.kt
- ゲーム中のイベント処理
- プレイヤー移動・死亡・アイテム操作
- 防具保護システム

## 今後の拡張予定

1. **統計システム**: プレイヤー個人成績の追跡
2. **マップ管理**: 複数マップの対応
3. **アビリティシステム**: 特殊能力の追加
4. **データベース連携**: 永続化データの管理
5. **Web API**: 外部システムとの連携

## トラブルシューティング

### よくある問題

1. **コンパイルエラー**: Kotlin 1.9.22とJava 21の組み合わせを確認
2. **権限エラー**: plugin.ymlの権限設定を確認
3. **スコアボードが表示されない**: GameManagerの初期化を確認

### デバッグ設定
```yaml
debug:
  enabled: true
  log-level: "INFO"
```

## ライセンス

このプロジェクトは学習・研究目的で作成されています。