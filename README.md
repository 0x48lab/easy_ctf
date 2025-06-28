# EasyCTF - Minecraft CTF プラグイン

[![Build and Test](https://github.com/hacklab/easy_ctf/actions/workflows/build.yml/badge.svg)](https://github.com/hacklab/easy_ctf/actions/workflows/build.yml)
[![Build and Release](https://github.com/hacklab/easy_ctf/actions/workflows/release.yml/badge.svg)](https://github.com/hacklab/easy_ctf/actions/workflows/release.yml)

**🏆 hackCraft2 公式プラグイン**

Minecraft Paper サーバー向けの包括的な Capture The Flag プラグインです。hackCraft2イベント用に開発された公式プラグインとして、プログラミング学習とゲームプレイを融合した体験を提供します。

## 機能

- **多段階ゲームプレイ**: 建築、戦闘、結果の3フェーズ
- **チーム管理**: 赤チーム vs 青チームシステム
- **高度な統計**: キル/デス比、旗奪取数、MVP追跡
- **TABリストカスタマイズ**: チーム色、統計、ゲーム状況表示
- **多言語サポート**: 日本語・英語対応
- **スポーン保護**: ビーコン付きスポーンエリア自動設置
- **距離検証**: 旗・スポーン配置の戦術的問題を防止
- **設定可能ゲームモード**: 建築フェーズの異なるゲームモード対応

## インストール

### リリースから（推奨）

**📦 [GitHub Releases](https://github.com/hacklab/easy_ctf/releases) でビルド済みファイルをダウンロード**

すべてのリリースには以下のビルド済みファイルが含まれています：
- `EasyCTF-x.x.x.jar` - メインプラグインファイル（すぐに使用可能）
- `plugin.yml` - プラグイン設定ファイル
- 自動生成された変更ログ
- インストール・設定手順

**インストール手順：**
1. **[📥 最新リリースをダウンロード](https://github.com/hacklab/easy_ctf/releases/latest)** から `EasyCTF-x.x.x.jar` を取得
2. サーバーの `plugins` ディレクトリにJARファイルを配置
3. サーバーを再起動
4. `/ctf` コマンドでプラグインを設定

> **💡 ヒント**: [Releases ページ](https://github.com/hacklab/easy_ctf/releases)では、すべてのバージョンの履歴と各バージョンの詳細な変更内容を確認できます。

### ソースから

1. このリポジトリをクローン
2. `./gradlew shadowJar` を実行
3. `build/libs/EasyCTF-x.x.x.jar` をpluginsディレクトリにコピー

## 動作要件

- **Minecraftサーバー**: Paper 1.21+ (または互換フォーク)
- **Java**: 21 以上
- **権限プラグイン**: オプション (Bukkit権限を使用)

## クイックスタート

1. チームスポーンを設定: `/ctf setspawn red` と `/ctf setspawn blue`
2. 旗の位置を設定: `/ctf setflag red` と `/ctf setflag blue`
3. プレイヤーがチームに参加: `/ctf join red` または `/ctf join blue`
4. ゲーム開始: `/ctf start`

## コマンド

### プレイヤー用コマンド
- `/ctf join [team]` - チームに参加またはチーム人数を表示
- `/ctf leave` - 現在のチームから離脱
- `/ctf status` - ゲーム状況とチーム情報を確認

### 管理者用コマンド
- `/ctf start` - ゲーム開始
- `/ctf stop` - ゲーム停止
- `/ctf skipphase` - 現在のフェーズをスキップ
- `/ctf setflag <team>` - 旗の位置設定
- `/ctf setspawn <team>` - チームスポーン設定
- `/ctf setteam <player> <team>` - プレイヤーのチーム設定

## 設定

プラグインは豊富なカスタマイズオプションを持つ `config.yml` ファイルを作成します：

```yaml
# 言語設定 (en/ja)
language: "ja"

# ゲームフェーズ
phases:
  build-duration: 300  # 5分
  build-phase-gamemode: "ADVENTURE"  # ADVENTURE/SURVIVAL/CREATIVE
  combat-duration: 600  # 10分
  result-duration: 60   # 1分

# スポーン保護
world:
  spawn-protection:
    enabled: true
    radius: 5
    height: 3
    min-flag-spawn-distance: 15
```

## ゲームフェーズ

### 1. 建築フェーズ
- プレイヤーが防御設備を建築
- ツールとブロックを配布
- 設定可能なゲームモード (Adventure/Survival/Creative)

### 2. 戦闘フェーズ
- 旗奪取が目標
- 戦闘装備を配布
- 統計追跡

### 3. 結果フェーズ
- MVP発表
- リーダーボード
- 最終スコア

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

- **問題報告**: [GitHub Issues](https://github.com/hacklab/easy_ctf/issues)
- **ドキュメント**: [CLAUDE.md](CLAUDE.md)
- **Wiki**: [GitHub Wiki](https://github.com/hacklab/easy_ctf/wiki)

## リリース

### 自動ビルドシステム

このプロジェクトはGitHub Actionsによる自動ビルドシステムを使用しています：

- **🏷️ タグプッシュ**: `v*.*.*` パターンのタグ（例：v1.0.0）がプッシュされると自動リリース
- **🔨 自動ビルド**: Java 21環境でGradleによる完全ビルド
- **📝 変更ログ**: 前回リリースからの変更を自動生成
- **📦 成果物**: ビルド済みJARファイルとドキュメントを自動添付

### 📥 ダウンロード

- **[🚀 最新リリース](https://github.com/hacklab/easy_ctf/releases/latest)** - 最新の安定版
- **[📋 全リリース履歴](https://github.com/hacklab/easy_ctf/releases)** - すべてのバージョン

### 各リリースに含まれるファイル

- **`EasyCTF-x.x.x.jar`** - メインプラグインファイル（すぐに使用可能）
- **`plugin.yml`** - プラグイン設定ファイル（参考用）
- **変更ログ** - そのバージョンでの変更・修正内容
- **インストール手順** - 詳細なセットアップガイド
- **動作要件** - 必要なMinecraft/Javaバージョン情報

> **🔄 自動更新**: 新機能や修正が追加されるたびに新しいリリースが自動作成されます。[Releases ページ](https://github.com/hacklab/easy_ctf/releases)をウォッチして最新情報をお見逃しなく！