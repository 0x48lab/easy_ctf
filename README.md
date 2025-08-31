# 🏳️ EasyCTF - Minecraft CTFプラグイン

<div align="center">
  
[![Version](https://img.shields.io/badge/Version-2.0-blue.svg)](https://github.com/0x48lab/easy_ctf)
[![Paper](https://img.shields.io/badge/Paper-1.21.5+-green.svg)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)

**Minecraft Paper サーバー用の戦略的CTFプラグイン**

日本語 | [English](README_EN.md)

</div>

## 📋 概要

EasyCTFは、Minecraft Paper Server用の高機能CTFプラグインです。2つのチームに分かれて相手の旗を奪い合う、戦略性の高いPvPゲームモードを提供します。

### ✨ 主な特徴

- 🎮 **複数ゲーム同時実行** - 同一サーバーで複数のCTFゲームを並行実行
- 🏆 **マッチシステム** - 複数ラウンドで総合優勝を決定
- 💰 **ショップシステム** - チーム共有通貨で戦略的な買い物
- 🛡️ **シールドシステム** - 敵陣でのダメージ管理（自陣でのみ回復）
- 🎁 **イベントチェスト** - 戦闘中に出現する特別な報酬
- 🗺️ **マップ作成ツール** - 簡単にカスタムマップを作成
- 🌏 **多言語対応** - 日本語・英語をサポート

## 🚀 クイックスタート

### 必要環境

- Paper Server 1.21.5以上
- Java 21以上
- 4GB以上のRAM推奨

### インストール

1. [Releases](https://github.com/0x48lab/easy_ctf/releases)から最新版をダウンロード
2. `easy_ctf-x.x.x.jar`をサーバーの`plugins`フォルダに配置
3. サーバーを起動

### 基本的な使い方

```bash
# ゲーム作成（自動検出方式）
/ctf setpos1 game1        # マップ領域の始点を設定
/ctf setpos2 game1        # マップ領域の終点を設定
/ctf savemap game1        # マップを保存（旗・スポーンを自動検出）

# ゲーム開始
/ctf start game1          # 単一ゲーム開始
/ctf start game1 match 5  # 5ラウンドマッチ開始

# プレイヤー参加
/ctf join game1           # ゲームに参加（自動チーム割り当て）
```

## 🎯 ゲームの流れ

### 1️⃣ 建築フェーズ（2分）
- 自チームの色のコンクリートブロックで防衛施設を建設
- ブロックは自陣ビーコンまたは既存ブロックに接続必須
- ショップでアイテムを購入して準備
- 敵チーム領域に侵入不可

### 2️⃣ 戦闘フェーズ（2分）
- 敵の旗（ビーコン）を奪取して自陣に持ち帰る
- 旗キャリアは発光効果・移動制限あり
- キル・キャプチャーで通貨獲得
- 即座にリスポーン（遅延なし）

### 3️⃣ 作戦会議フェーズ（15秒）
- ラウンド結果の確認
- 次ラウンドへの準備
- インターバルなしで次フェーズへ移行

## 💎 ショップシステム

エメラルドを右クリックしてショップを開く（**どこでも利用可能**）

### カテゴリ
- **武器** - 剣、斧、弓など
- **防具** - 各種防具セット
- **消耗品** - エンダーパール、金リンゴ、矢
- **建築ブロック** - 各種ブロック、TNT

### 通貨獲得方法
- 初期資金: 50G
- キル報酬: 15G（旗キャリア: 25G）
- キルアシスト: 10G
- キャプチャー: 50G
- アシスト: 20G
- フェーズ終了ボーナス: 100G
- キルストリークボーナス: 5G〜20G

### 割引システム
スコア差による敗者チーム割引：
- 1点差: 10%割引
- 2点差: 20%割引
- 3点差: 30%割引
- 4点差以上: 40%割引

## 🛡️ 特殊システム

### シールドシステム
- 最大値: 100
- 敵陣ブロック/ビーコンエリアで毎秒2減少
- シールド0で1.5ダメージ/秒
- **自陣でのみ毎秒5回復**（重要な戦術要素）
- 警告: 40以下で警告、20以下でクリティカル

### ビーコンエリア効果
- 3x3の範囲が特殊エリア
- 敵: ダメージゾーン（シールド減少）
- 味方: 空腹度・シールド回復

### チームブロックシステム
- **赤チーム**: 赤のコンクリートのみ
- **青チーム**: 青のコンクリートのみ
- ブロック接続必須（切断時は中立化）
- 無限に使用可能

### イベントチェスト
- 戦闘フェーズ中に1回出現
- 高価なショップアイテムを獲得可能
- 全プレイヤーに通知

## 🗺️ マップ作成

### 自動検出方式（推奨）

1. **マップを構築**
   - 赤のコンクリート: 赤チームスポーン（複数可）
   - 青のコンクリート: 青チームスポーン（複数可）
   - ビーコン + 赤ガラス: 赤チームの旗
   - ビーコン + 青ガラス: 青チームの旗

2. **領域を設定**
   ```bash
   /ctf setpos1 game1  # 始点設定
   /ctf setpos2 game1  # 終点設定
   ```

3. **保存**
   ```bash
   /ctf savemap game1  # 自動検出して保存
   ```

### テンポラリワールドシステム
- ゲーム開始時に専用ワールド生成
- マップを自動復元
- チェスト内容物は自動クリア
- ゲーム終了時にワールド削除

## 📊 管理者コマンド

| コマンド | 説明 |
|---------|------|
| `/ctf create <game>` | 新規ゲーム作成（対話形式） |
| `/ctf update <game>` | ゲーム設定更新 |
| `/ctf delete <game>` | ゲーム削除 |
| `/ctf list` | 全ゲーム一覧 |
| `/ctf info <game>` | ゲーム詳細情報 |
| `/ctf start <game> [match] [数]` | ゲーム/マッチ開始 |
| `/ctf stop <game>` | 強制終了 |
| `/ctf setflag <game> <team>` | 旗位置設定 |
| `/ctf setspawn <game> <team>` | スポーン設定 |
| `/ctf addspawn <game> <team>` | スポーン追加 |
| `/ctf removespawn <game> <team> <番号>` | スポーン削除 |
| `/ctf listspawns <game>` | スポーン一覧 |
| `/ctf savemap <game>` | マップ保存 |

## 🎮 プレイヤーコマンド

| コマンド | 説明 |
|---------|------|
| `/ctf join <game>` | ゲーム参加 |
| `/ctf leave` | ゲーム離脱 |
| `/ctf team [red\|blue]` | チーム確認/変更 |
| `/ctf status [game]` | 状況確認 |
| `/ctf spectator [game]` | 観戦モード |

## ⚙️ 設定

`plugins/EasyCTF/config.yml`で詳細設定可能：

```yaml
# 言語設定
language: "ja"  # "en" または "ja"

# フェーズ時間
default-phases:
  build-duration: 120      # 建築フェーズ（秒）
  combat-duration: 120     # 戦闘フェーズ（秒）
  result-duration: 15      # 結果表示（秒）
  build-phase-gamemode: "SURVIVAL"  # ADVENTURE/SURVIVAL/CREATIVE

# リスポーン設定
default-game:
  respawn-delay-base: 0    # 即座にリスポーン
  respawn-delay-per-death: 0
  respawn-delay-max: 0

# 通貨設定
currency:
  initial: 50              # 初期通貨
  kill-reward: 15          # キル報酬
  kill-assist-reward: 10   # キルアシスト報酬
  carrier-kill-reward: 25  # 旗キャリアキル
  carrier-kill-assist-reward: 10  # 旗キャリアキルアシスト
  capture-reward: 50       # キャプチャー報酬
  capture-assist-reward: 20  # キャプチャーアシスト報酬
  phase-end-bonus: 100     # フェーズ終了ボーナス
  kill-streak-bonus:       # キルストリークボーナス
    2-kills: 5
    3-kills: 10
    4-kills: 15
    5-plus-kills: 20

# シールド設定
shield:
  enabled: true
  max-shield: 100
  decrease-rate: 2.0       # 敵陣での減少速度
  recovery-rate: 5.0       # 自陣での回復速度
  damage-amount: 1.5       # シールド0時のダメージ

# イベントチェスト
event-chest:
  enabled: true
  spawn-count: 1           # 戦闘フェーズ中の出現回数
```

## 🔧 ビルド方法

```bash
git clone https://github.com/0x48lab/easy_ctf.git
cd easy_ctf
./gradlew shadowJar
```

生成されたJARファイル: `build/libs/easy_ctf-x.x.x-all.jar`

## 📝 リリースノート

### 最新の変更点
- ✅ フェーズ間のインターバル削除（即座に移行）
- ✅ リスポーン遅延を0に設定（即座に復活）
- ✅ 通貨報酬を調整（キル15G、旗キャリアキル25G、キャプチャ50G、フェーズボーナス100G）
- ✅ シールド回復を自陣のみに制限
- ✅ ショップ使用範囲制限を削除（どこでも利用可能）
- ✅ チームブロックをコンクリートのみに統一
- ✅ ビーコン周囲がダメージゾーンに
- ✅ イベントチェストの中身をショップアイテムに限定
- ✅ 距離ベースの旗検出アルゴリズム実装

### パフォーマンス最適化
- バッチ処理によるテレポート最適化
- 非同期チャット処理
- 効率的なブロック接続チェック
- GZIP圧縮によるマップ保存

## 🤝 貢献

プルリクエストを歓迎します！バグ報告や機能提案は[Issues](https://github.com/0x48lab/easy_ctf/issues)へ。

## 📞 サポート

- 📚 [ドキュメント](https://0x48lab.github.io/easy_ctf/)
- 📚 [Wiki](https://github.com/0x48lab/easy_ctf/wiki)

## 📄 ライセンス

このプロジェクトはMITライセンスの下で公開されています。

---

<div align="center">
Made with ❤️ for Minecraft Community
</div>