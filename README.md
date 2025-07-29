# EasyCTF - Minecraft CTF プラグイン

[English](README_EN.md) | 日本語

[![Build and Test](https://github.com/0x48lab/easy_ctf/actions/workflows/build.yml/badge.svg)](https://github.com/0x48lab/easy_ctf/actions/workflows/build.yml)
[![Build and Release](https://github.com/0x48lab/easy_ctf/actions/workflows/release.yml/badge.svg)](https://github.com/0x48lab/easy_ctf/actions/workflows/release.yml)

**🏆 hackCraft2 公式プラグイン**

Minecraft Paper サーバー向けの高機能な Capture The Flag プラグインです。複数のCTFゲームを同時に管理・実行でき、直感的なUIとガイドシステムにより、初心者から上級者まで楽しめる対戦体験を提供します。

📚 **[ドキュメント](https://0x48lab.github.io/easy_ctf/)** - ゲームの遊び方や詳細な機能説明

## 主な機能

### 🎮 ゲームシステム
- **複数ゲーム同時実行**: 1つのサーバーで複数のCTFゲームを並行管理
- **3フェーズシステム**: 建築→戦闘→作戦会議の段階的ゲーム進行
- **テンポラリワールド**: 各ゲーム専用のワールドを自動生成・削除
- **マップ保存・復元**: 作成したマップを圧縮保存、ゲーム開始時に自動復元

### 💰 経済・ショップシステム
- **チーム共有通貨**: チーム全員で通貨（G）を共有
- **ショップシステム**: 武器、防具、ブロック、消耗品を購入可能
- **動的価格設定**: 負けているチームに自動割引（最大40%）
- **死亡時挙動**: アイテムごとにKEEP/DROP/DESTROY設定

### 🏆 マッチシステム
- **固定ラウンド方式**: 指定回数のゲームを連続実施
- **インベントリ継続**: マッチ中はアイテムを保持
- **詳細な統計**: キル、キャプチャー、アシスト、建築などを記録
- **MVP表彰**: 各カテゴリーのトッププレイヤーを発表

### 🏗️ 建築システム
- **ブロック接続管理**: チームブロックはビーコンまたは既存ブロックに接続必須
- **切断ブロックの中立化**: 接続が切れたブロックは白色に変化
- **チーム専用ブロック**: 無限に使える色付きコンクリート・ガラス
- **敵陣シールドシステム**: 敵チームブロック上で継続ダメージ

### 🌍 多言語対応
- **日本語・英語対応**: すべてのメッセージを言語ファイルで管理
- **簡単切り替え**: config.ymlで言語設定を変更
- **カスタマイズ可能**: lang_ja.yml, lang_en.ymlを編集可能

## インストール

### リリースから（推奨）

**📦 [GitHub Releases](https://github.com/0x48lab/easy_ctf/releases) でビルド済みファイルをダウンロード**

**インストール手順：**
1. **[📥 最新リリースをダウンロード](https://github.com/0x48lab/easy_ctf/releases/latest)** から `EasyCTF-x.x.x.jar` を取得
2. サーバーの `plugins` ディレクトリにJARファイルを配置
3. サーバーを再起動
4. `/ctf` コマンドでプラグインを設定

### ソースから

1. このリポジトリをクローン
2. `./gradlew shadowJar` を実行
3. `build/libs/EasyCTF-x.x.x-all.jar` をpluginsディレクトリにコピー

## 動作要件

- **Minecraftサーバー**: Paper 1.21.5+ (または互換フォーク)
- **Java**: 21 以上
- **権限プラグイン**: オプション (Bukkit権限を使用)

## クイックスタート

### 方法1: マップ自動検出方式（推奨）

1. **マップ領域の設定**
   ```
   /ctf setpos1 <ゲーム名>  # 始点を現在地に設定
   /ctf setpos2 <ゲーム名>  # 終点を現在地に設定
   ```

2. **必須ブロックの配置**
   - 赤のコンクリート: 赤チームのスポーン地点（1つのみ）
   - 青のコンクリート: 青チームのスポーン地点（1つのみ）
   - ビーコン + 赤のガラス: 赤チームの旗位置
   - ビーコン + 青のガラス: 青チームの旗位置

3. **マップの保存**
   ```
   /ctf savemap <ゲーム名>
   ```

### 方法2: 対話形式での作成

1. ゲーム作成開始: `/ctf create <ゲーム名>`
2. チャットの指示に従って設定
3. プレイヤーが参加: `/ctf join <ゲーム名>`
4. ゲーム開始: `/ctf start <ゲーム名>`

### マッチモードの開始

複数ゲームを連続で実施する場合：
```
/ctf start <ゲーム名> match [ゲーム数]
```
例: `/ctf start arena1 match 5` （5ゲーム実施）

## 主要コマンド

### プレイヤー用
- `/ctf list` - 全ゲーム一覧を表示
- `/ctf join <ゲーム名>` - 指定ゲームに参加
- `/ctf leave` - 現在のゲームから離脱
- `/ctf team [red|blue]` - チーム確認・変更（開始前のみ）
- `/ctf status [ゲーム名]` - ゲーム状況を確認

### 管理者用
- `/ctf create <ゲーム名>` - 新規ゲーム作成
- `/ctf update <ゲーム名>` - ゲーム設定の更新
- `/ctf delete <ゲーム名>` - ゲーム削除
- `/ctf start <ゲーム名> [match] [数]` - ゲーム/マッチ開始
- `/ctf stop <ゲーム名>` - ゲーム強制終了
- `/ctf setpos1/setpos2 <ゲーム名>` - マップ領域設定
- `/ctf savemap <ゲーム名>` - マップ保存

## ゲームプレイ

### フェーズ

1. **建築フェーズ** 🏗️
   - デフォルト2分（設定可能）
   - 防御設備の構築
   - PvP無効
   - ショップ利用可能

2. **戦闘フェーズ** ⚔️
   - デフォルト2分（設定可能）
   - 敵の旗を奪取して自陣に持ち帰る
   - PvP強制有効
   - ブロック設置不可、破壊は一部可能

3. **作戦会議フェーズ** 💭
   - デフォルト15秒（設定可能）
   - 試合結果の確認とMVP発表
   - 次ゲームへの準備（マッチモード時）

### 旗システム

- **取得**: 敵の旗（ビーコン）に1.5ブロック以内に接近
- **キャリア効果**: 発光、エンダーパール・エリトラ使用不可
- **得点条件**: 自チームの旗が自陣にある時のみキャプチャー可能
- **ドロップ**: 死亡時にその場にドロップ（15秒で自動返却）

### ショップシステム

- **開き方**: インベントリのエメラルドを右クリック
- **使用場所**: スポーン地点から15ブロック以内
- **カテゴリー**: 武器、防具、消耗品、ブロック
- **特殊機能**: 死亡時の挙動設定（KEEP/DROP/DESTROY）

## 設定ファイル

### config.yml（主要設定）

```yaml
# 言語設定
language: "ja"  # "en" または "ja"

# デフォルトゲーム設定
default-game:
  min-players: 2
  max-players-per-team: 10
  respawn-delay-base: 10
  respawn-delay-per-death: 2
  respawn-delay-max: 20

# フェーズ設定
default-phases:
  build-duration: 120        # 建築フェーズ（秒）
  build-phase-gamemode: "SURVIVAL"
  combat-duration: 120       # 戦闘フェーズ（秒）
  result-duration: 15        # 作戦会議フェーズ（秒）
  intermediate-result-duration: 15  # マッチ中間の時間

# 通貨設定
currency:
  initial: 50
  kill-reward: 10
  carrier-kill-reward: 20
  capture-reward: 30
```

## トラブルシューティング

### よくある問題

1. **ショップが開かない**
   - スポーン地点から15ブロック以内で使用してください

2. **旗が取れない**
   - 1.5ブロック以内に近づく必要があります
   - 既に旗を持っていないか確認してください

3. **ブロックが設置できない**
   - 戦闘フェーズ中は設置不可です
   - 建築フェーズでチームブロックに接続して設置してください

4. **言語が変わらない**
   - config.ymlを変更後、サーバー再起動が必要です

## 開発

### ビルド
```bash
./gradlew clean build
```

### テスト
```bash
./gradlew test
```

### 開発サーバー
```bash
./gradlew runServer
```

## サポート

- **問題報告**: [GitHub Issues](https://github.com/0x48lab/easy_ctf/issues)
- **ドキュメント**: [オンラインドキュメント](https://0x48lab.github.io/easy_ctf/)
- **Wiki**: [GitHub Wiki](https://github.com/0x48lab/easy_ctf/wiki)

## ライセンス

このプロジェクトは MIT ライセンスの下でライセンスされています。