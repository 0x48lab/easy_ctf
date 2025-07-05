# EasyCTF - Minecraft CTF プラグイン

[![Build and Test](https://github.com/0x48lab/easy_ctf/actions/workflows/build.yml/badge.svg)](https://github.com/0x48lab/easy_ctf/actions/workflows/build.yml)
[![Build and Release](https://github.com/0x48lab/easy_ctf/actions/workflows/release.yml/badge.svg)](https://github.com/0x48lab/easy_ctf/actions/workflows/release.yml)

**🏆 hackCraft2 公式プラグイン**

Minecraft Paper サーバー向けの高機能な Capture The Flag プラグインです。複数のCTFゲームを同時に管理・実行でき、直感的なUIとガイドシステムにより、初心者から上級者まで楽しめる対戦体験を提供します。

## 主な機能

- **🎮 複数ゲーム同時実行**: 1つのサーバーで複数のCTFゲームを並行管理
- **💬 対話形式の設定**: コマンドとチャットによる直感的なゲーム作成・更新
- **📍 リアルタイムガイド**: ActionBarによる現在の目標表示システム
- **🏗️ 3フェーズシステム**: 建築→戦闘→結果の段階的ゲーム進行
- **🚩 高度な旗システム**: 近接取得、自チーム旗回収、条件付きキャプチャー
- **👥 チーム管理**: 自動バランス、切断・再接続対応
- **🛡️ スポーン保護**: 3秒間の無敵時間
- **⚔️ PVP強制有効化**: サーバー設定に関わらず戦闘フェーズでPVP有効
- **📊 永続化**: YAMLによるゲーム設定の自動保存・復元

## インストール

### リリースから（推奨）

**📦 [GitHub Releases](https://github.com/0x48lab/easy_ctf/releases) でビルド済みファイルをダウンロード**

すべてのリリースには以下のビルド済みファイルが含まれています：
- `EasyCTF-x.x.x.jar` - メインプラグインファイル（すぐに使用可能）
- `plugin.yml` - プラグイン設定ファイル
- 自動生成された変更ログ
- インストール・設定手順

**インストール手順：**
1. **[📥 最新リリースをダウンロード](https://github.com/0x48lab/easy_ctf/releases/latest)** から `EasyCTF-x.x.x.jar` を取得
2. サーバーの `plugins` ディレクトリにJARファイルを配置
3. サーバーを再起動
4. `/ctf` コマンドでプラグインを設定

> **💡 ヒント**: [Releases ページ](https://github.com/0x48lab/easy_ctf/releases)では、すべてのバージョンの履歴と各バージョンの詳細な変更内容を確認できます。

### ソースから

1. このリポジトリをクローン
2. `./gradlew shadowJar` を実行
3. `build/libs/EasyCTF-x.x.x.jar` をpluginsディレクトリにコピー

## 動作要件

- **Minecraftサーバー**: Paper 1.21+ (または互換フォーク)
- **Java**: 21 以上
- **権限プラグイン**: オプション (Bukkit権限を使用)

## クイックスタート

### 新規ゲーム作成（対話形式）
1. ゲーム作成開始: `/ctf create <ゲーム名>`
2. チャットの指示に従って以下を設定:
   - 赤チームの旗位置（見ている場所で `set` と入力）
   - 赤チームのスポーン地点
   - 青チームの旗位置
   - 青チームのスポーン地点
   - 建築フェーズのゲームモード
   - 各フェーズの時間
3. プレイヤーが参加: `/ctf join <ゲーム名>`
4. ゲーム開始: `/ctf start <ゲーム名>`

## コマンド

### プレイヤー用コマンド
- `/ctf list` - 全ゲーム一覧を表示
- `/ctf join <ゲーム名>` - 指定ゲームに参加（チーム自動割り当て）
- `/ctf leave` - 現在のゲームから離脱
- `/ctf status [ゲーム名]` - ゲーム状況を確認

### 管理者用コマンド
- `/ctf create <ゲーム名>` - 新規ゲーム作成（対話形式）
- `/ctf update <ゲーム名>` - ゲーム設定の更新（対話形式）
- `/ctf delete <ゲーム名>` - ゲーム削除
- `/ctf start <ゲーム名>` - ゲーム開始
- `/ctf stop <ゲーム名>` - ゲーム強制終了
- `/ctf setflag <ゲーム名> <red|blue>` - 旗位置を直接設定
- `/ctf setspawn <ゲーム名> <red|blue>` - スポーン地点を直接設定

## 設定

### グローバル設定 (`config.yml`)

```yaml
# プラグイン設定
plugin:
  auto-save: true           # ゲーム設定の自動保存
  max-games: -1             # 最大ゲーム数（-1で無制限）
  force-pvp: true           # 戦闘フェーズ中のPVP強制有効化

# デフォルトゲーム設定
default-game:
  min-players: 2            # 自動開始する最小プレイヤー数
  max-players-per-team: 10  # チーム最大人数
  respawn-delay: 5          # リスポーン遅延（秒）

# デフォルトフェーズ設定
default-phases:
  build-duration: 300           # 建築フェーズ時間（秒）
  build-phase-gamemode: "ADVENTURE"  # ADVENTURE/SURVIVAL/CREATIVE
  combat-duration: 600          # 戦闘フェーズ時間（秒）
  result-duration: 60           # リザルトフェーズ時間（秒）
```

### ゲーム個別設定 (`games/<ゲーム名>.yml`)

各ゲームの設定は自動的に保存され、サーバー再起動時に復元されます。

## ゲームシステム

### ゲームフェーズ

#### 1. 建築フェーズ 🏗️
- **目的**: 防御設備を建築して自陣を強化
- **ActionBarガイド**: 「建築して防御を固めよう！」
- **装備**: 木製ツール、建築ブロック、食料
- **ゲームモード**: 設定可能（Adventure/Survival/Creative）
- **制限**: PVP無効、旗・スポーン装飾は破壊不可

#### 2. 戦闘フェーズ ⚔️
- **目的**: 敵の旗を奪取して自陣に持ち帰る
- **ActionBarガイド**: 状況に応じた動的な指示
  - 通常時: 「[敵チーム]の旗を奪取せよ！」
  - 旗保持時: 「自陣に戻れ！」
  - 自旗被奪取時: 「旗が敵に取られた！取り返せ！(プレイヤー名)」
- **装備**: チーム色防具、鉄剣、弓矢、食料
- **制限**: 全ブロック破壊不可、PVP強制有効

#### 3. 結果フェーズ 🏆
- **目的**: 試合結果の確認
- **内容**: 勝利チーム発表、最終スコア表示
- **制限**: 移動・戦闘不可

### 旗システム

- **実装**: ビーコン（チーム色のステンドグラス付き）
- **取得方法**: 敵の旗に1.5ブロック以内に近づく
- **キャリア効果**: 発光、エンダーパール・エリトラ使用不可
- **ドロップ**: 死亡時にその場にアイテム化
- **回収**: 
  - 自チーム: 即座に自陣のビーコンを復活
  - 敵チーム: そのまま運搬継続
- **自動復活**: 30秒間放置で元の位置に戻る
- **得点条件**: 自チームの旗が自陣にある時のみキャプチャー可能

### スポーン保護

- **無敵時間**: リスポーン後3秒間
- **視覚効果**: 保護中は発光
- **解除条件**: 時間経過、攻撃実行、旗取得

## 開発

### ビルド
```bash
./gradlew clean build
```

### テスト
```bash
./gradlew test
```

### 開発サーバー実行
```bash
./gradlew runServer
```

## 貢献

1. リポジトリをフォーク
2. 機能ブランチを作成
3. 変更を加える
4. 該当する場合はテストを追加
5. プルリクエストを送信

## ライセンス

このプロジェクトは MIT ライセンスの下でライセンスされています - 詳細は LICENSE ファイルを参照してください。

## サポート

- **問題報告**: [GitHub Issues](https://github.com/0x48lab/easy_ctf/issues)
- **ドキュメント**: [CLAUDE.md](CLAUDE.md)
- **Wiki**: [GitHub Wiki](https://github.com/0x48lab/easy_ctf/wiki)

## リリース

### 自動ビルドシステム

このプロジェクトはGitHub Actionsによる自動ビルドシステムを使用しています：

- **🏷️ タグプッシュ**: `v*.*.*` パターンのタグ（例：v1.0.0）がプッシュされると自動リリース
- **🔨 自動ビルド**: Java 21環境でGradleによる完全ビルド
- **📝 変更ログ**: 前回リリースからの変更を自動生成
- **📦 成果物**: ビルド済みJARファイルとドキュメントを自動添付

### 📥 ダウンロード

- **[🚀 最新リリース](https://github.com/0x48lab/easy_ctf/releases/latest)** - 最新の安定版
- **[📋 全リリース履歴](https://github.com/0x48lab/easy_ctf/releases)** - すべてのバージョン

### 各リリースに含まれるファイル

- **`EasyCTF-x.x.x.jar`** - メインプラグインファイル（すぐに使用可能）
- **`plugin.yml`** - プラグイン設定ファイル（参考用）
- **変更ログ** - そのバージョンでの変更・修正内容
- **インストール手順** - 詳細なセットアップガイド
- **動作要件** - 必要なMinecraft/Javaバージョン情報

> **🔄 自動更新**: 新機能や修正が追加されるたびに新しいリリースが自動作成されます。[Releases ページ](https://github.com/0x48lab/easy_ctf/releases)をウォッチして最新情報をお見逃しなく！