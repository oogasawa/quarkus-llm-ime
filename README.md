# quarkus-llm-ime

LLMとMozcを組み合わせた日本語入力変換デーモン。Mozcの辞書ベース変換（高速・確実）とvLLMの文脈考慮変換（創造的）を並列実行し、候補をマージして返す。

fcitx5プラグイン（[fcitx5-llm-ime](../fcitx5-llm-ime/)）やEmacsパッケージ（`llm-ime.el`）から呼び出して使用する。

## 前提条件

- Java 21+
- Maven 3.9+
- vLLMサーバーが稼働していること（OpenAI互換 `/v1/chat/completions` エンドポイント）
- `mozc-server` が稼働していること（辞書ベース変換に使用。なくても動作するがLLM候補のみになる）

## ビルド

```bash
cd ~/works/quarkus-llm-ime
rm -rf target
mvn install
```

## 起動

vLLMサーバーのURLとモデル名を指定して起動する。

```bash
java \
  -Dquarkus.rest-client.vllm.url=http://<VLLM_HOST>:8000 \
  -Dllm-ime.model=<MODEL_NAME> \
  -jar target/quarkus-app/quarkus-run.jar
```

例:

```bash
java \
  -Dquarkus.rest-client.vllm.url=http://192.168.5.15:8000 \
  -Dllm-ime.model=/models/Qwen3-Coder-Next-FP8 \
  -jar target/quarkus-app/quarkus-run.jar
```

デーモンは `http://localhost:8090` でリクエストを受け付ける。

### 設定プロパティ

| プロパティ | デフォルト | 説明 |
|-----------|-----------|------|
| `quarkus.rest-client.vllm.url` | `http://192.168.5.13:8000` | vLLMサーバーURL |
| `llm-ime.model` | `/models/llm-jp-3-3.7b-instruct` | 使用するモデル名 |
| `llm-ime.max-tokens` | `100` | 変換時の最大トークン数 |
| `llm-ime.temperature` | `0.0` | 変換時のtemperature |
| `llm-ime.completion.max-tokens` | `60` | 文章補完時の最大トークン数 |
| `quarkus.http.port` | `8090` | HTTPポート |

すべて `-D` オプションまたは `application.properties` で設定可能。

## API

### `POST /api/segment-convert` — 文節分割+Mozc候補（メイン・高速）

ひらがなをMozcで文節に分割し、各文節ごとにMozcの変換候補を返す。LLMは呼ばないため高速（数十ms）。fcitx5プラグインの変換開始時に使用。

```json
// Request
{ "input": "きょうはいいてんきです", "n": 5 }

// Response
{
  "segments": [
    { "reading": "きょうは", "candidates": ["今日は", "きょうは"] },
    { "reading": "いいてんきです", "candidates": ["いい天気です", "いいてんきです"] }
  ]
}
```

### `POST /api/segment-candidates` — 単一文節の候補取得（Mozc+LLM）

文節境界の変更後やLLM候補の非同期取得時に使用。MozcとLLMの候補をマージして返す。fcitx5プラグインでは変換開始と同時にバックグラウンドで各文節に対してこのAPIを呼び、LLM候補を非同期取得する。

```json
// Request
{ "input": "きょう", "context": "", "n": 5 }

// Response
{ "candidates": ["今日", "教", "強", "京", "きょう", "経", "卿", ...] }
```

### `POST /api/convert-multi` — 複数候補変換

Mozc + LLMの候補をマージして返す。Mozc候補が先頭、LLM候補が後続。

```json
// Request
{ "input": "きしゃ", "context": "", "n": 5 }

// Response
{ "candidates": ["記者", "貴社", "汽車", "帰社", "騎射", "喜捨", "きしゃ"] }
```

### `POST /api/convert` — 単一変換

ひらがなを漢字仮名混じり文に変換する（LLMのみ）。

```json
// Request
{ "input": "かんじへんかん", "context": "日本語の" }

// Response
{ "output": "漢字変換" }
```

### `POST /api/complete` — 文章補完

文脈に基づいて続きの文章を予測する。

```json
// Request
{ "context": "本日は晴天なり。", "partial": "" }

// Response
{ "completion": "気温は25度で過ごしやすい一日となるでしょう。" }
```

### `POST /api/complete-multi` — 複数候補補完

複数の補完候補を返す。`context` に `[CURSOR]` マーカーを含めるとカーソル位置挿入モードになる。

```json
// Request
{ "context": "報告書を[CURSOR]提出する。", "partial": null, "n": 3 }

// Response
{ "candidates": ["作成して", "まとめて", "完成させて"] }
```

### `POST /api/record` — 変換履歴の記録

ユーザーが選択した変換結果を記録する。記録された履歴は以降のプロンプトに含まれ、変換精度の向上に使われる（直近20件を保持）。

```json
// Request
{ "input": "きしゃ", "output": "記者" }

// Response
{ "status": "ok" }
```

### `POST /api/segment` — 文節区切り変換（LLMのみ）

ひらがなをLLMで文節に分割し、それぞれ変換する。

```json
// Request
{ "input": "たべものをかう", "context": "" }

// Response
{
  "segments": [
    { "reading": "たべもの", "candidates": ["食べ物", "たべもの"] },
    { "reading": "を", "candidates": ["を"] },
    { "reading": "かう", "candidates": ["買う", "かう"] }
  ]
}
```

### `POST /api/candidates` — 文節の変換候補取得（LLMのみ）

特定の読みに対する変換候補を取得する。

```json
// Request
{ "reading": "きしゃ", "context": "" }

// Response
{ "candidates": ["記者", "汽車", "帰社", "貴社", "騎射"] }
```

## クライアント

### fcitx5プラグイン

[fcitx5-llm-ime](../fcitx5-llm-ime/) を参照。ローマ字入力→ひらがな→Mozc+LLM漢字変換をシステム全体で利用可能。

操作方法:
- **ローマ字入力** — ひらがなに変換されプリエディットに表示
- **Space** — 変換開始（Mozcで高速に文節分割+候補表示）
- **Space（変換中）** — 次の候補へ切り替え。LLM候補はバックグラウンドで自動取得され、準備できた時点で候補リストに追加される
- **Enter** — 確定
- **Escape** — キャンセル（変換中は入力モードに戻る）
- **左右キー** — 文節間を移動
- **Shift+左右** — 文節境界の変更（切り直し）。変更後は自動的に候補を再取得
- **上下キー** — 候補の選択
- **1-9** — 候補を番号で直接選択

### Emacs

`~/.doom.d/llm-ime.el` をロードして使用。`M-x llm-ime-convert` でリージョンまたはミニバッファから変換、`M-x llm-ime-complete` で文章補完。

## アーキテクチャ

```
+------------------+     +-------------------+     +-----------+
| fcitx5-llm-ime   |---->| quarkus-llm-ime   |---->| vLLM      |
| (C++ plugin)     |     | (localhost:8090)   |     | (GPU)     |
+------------------+     +-------------------+     +-----------+
                               |
                               +---->+-----------+
                               |     | mozc      |
                               |     | (Unix IPC)|
+------------------+           |     +-----------+
| Emacs llm-ime.el |-----------+
+------------------+
```

- **fcitx5-llm-ime** — fcitx5エンジンプラグイン。ローマ字→ひらがな変換を行い、`/api/segment-convert` で文節分割+Mozc候補を高速取得。同時にバックグラウンドスレッドで `/api/segment-candidates` を呼びLLM候補を非同期取得し、準備でき次第候補リストにマージ。Shift+左右で文節境界の変更が可能
- **quarkus-llm-ime** — 変換デーモン。Mozcへの問い合わせ（高速）とvLLMへの問い合わせ（遅延）を分離。`/api/segment-convert` はMozcのみ、`/api/segment-candidates` はMozc+LLMを並列実行しマージ・重複排除して返す。変換履歴をセッション中保持（直近20件）
- **mozc_server** — 辞書ベースの日本語変換。protobuf over 抽象Unixドメインソケット（`\0tmp/.mozc.<key>.session`）で通信。文節分割と候補生成を担当
- **vLLM** — GPUで動作するLLM推論サーバー（OpenAI互換API）。文脈を考慮した候補生成を担当
