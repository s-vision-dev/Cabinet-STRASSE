# Cabinet by VIASTRASSE 理想形設計書

## 1. 文書情報

|項目|内容|
|---|---|
|文書名|Cabinet by VIASTRASSE 理想形設計書|
|対象アプリ|Cabinet by VIASTRASSE|
|パッケージ名|`jp.viastrasse.cabinet`|
|コンセプト名|The Cabinet|
|ブランド表記|Powered by VIASTRASSE|
|位置付け|ファイルマネージャー兼デジタル資産管理アプリ|
|状態|初期構想・理想形|

---

## 2. 概要

Cabinet by VIASTRASSE は、通常のファイルマネージャーとして利用できることを前提にしながら、VIASTRASSE ファミリーと連携し、ファイル、資料、添付、URL、成果物を整理、検索、参照、関連付けできるデジタル資産管理アプリである。

単なるファイル一覧ではなく、資料を保管し、整理し、必要なときに取り出すための「書庫」として機能する。

コンセプトは以下とする。

```text
Cabinet by VIASTRASSE
The Cabinet
Powered by VIASTRASSE
```

---

## 3. 基本思想

Cabinet by VIASTRASSE は、普通のファイルマネージャーとして成立することを前提とする。

ただし、それだけではなく、VIASTRASSE ファミリーの中では「資料の正本」を管理する役割を持つ。

### 3.1 役割分担

|アプリ|役割|
|---|---|
|Home by VIASTRASSE|入口・ダッシュボード|
|Mail by VIASTRASSE|情報取得・添付取得|
|Notify by VIASTRASSE|イベント取得|
|Task by VIASTRASSE|実行管理|
|Atelier by VIASTRASSE|思考・調査・結果・整理|
|Cabinet by VIASTRASSE|資料・ファイル・URL・成果物の保管庫|

### 3.2 Atelierとの違い

Atelier は、思考、メモ、調査、Task結果、成果を育てる工房である。

Cabinet は、PDF、画像、Office文書、添付、URL、成果物などの資料本体を保管する書庫である。

```text
Atelier = 思考と成果を育てる場所
Cabinet = 資料とファイルを保管する場所
```

Atelier はファイル本体を抱え込まず、Cabinet上の資料を参照する設計を基本とする。

---

## 4. 単体アプリとしての価値

Cabinet by VIASTRASSE は、他の STRASSE アプリがインストールされていなくても、通常のファイルマネージャーとして成立する必要がある。

### 4.1 単体利用で提供する価値

- 内部ストレージ閲覧
- SDカード閲覧
- USBストレージ閲覧
- ファイル操作
- 圧縮・解凍
- 検索
- タグ管理
- お気に入り
- 最近使ったファイル
- PDF / 画像 / テキスト閲覧
- URL保存
- 資料分類
- 未整理ファイル整理
- ファイルプレビュー
- メタデータ管理
- バージョン管理

### 4.2 基本方針

```text
普通のファイルマネージャーとして使える。
その上で、STRASSE連携によって資料管理アプリとして進化する。
```

---

## 5. 主要モード

Cabinet by VIASTRASSE は以下の主要モードを持つ。

```text
Explorer
Library
Collection
Smart Folder
Inbox
Search
```

---

## 6. Explorer

通常のファイルマネージャー画面。

### 6.1 対象

- 内部ストレージ
- SDカード
- USBストレージ
- Download
- Documents
- Pictures
- Movies
- Music
- 任意フォルダ
- 将来的には SMB / WebDAV / FTP / SFTP / NAS / クラウドストレージ

### 6.2 基本操作

- 開く
- コピー
- 移動
- 削除
- 名前変更
- 複製
- 新規フォルダ作成
- 一括選択
- 一括移動
- 一括削除
- 一括タグ付け
- 圧縮
- 解凍
- 共有
- プロパティ表示
- 並び替え
- 表示形式切替

### 6.3 表示形式

- リスト表示
- グリッド表示
- 詳細表示
- サムネイル表示
- プレビュー付き一覧表示

スマホでは「このファイル何だっけ？」を減らすため、プレビュー付き一覧表示を重視する。

---

## 7. Library

ファイルパスではなく、資料種別で見る画面。

### 7.1 分類例

- PDF
- 画像
- Office文書
- テキスト
- Markdown
- 音声
- 動画
- ZIP
- URL
- スクリーンショット
- メール添付
- Task成果物
- Atelier関連資料

### 7.2 目的

Library は、ファイルの保存場所を意識せずに、資料の種類や意味でアクセスできるようにする。

---

## 8. Collection

資料をテーマ単位でまとめる機能。

### 8.1 例

- Azure App Service
- STRASSEブランド
- 顧客A案件
- 北海道旅行
- Android開発
- 契約書類

### 8.2 方針

Collection はフォルダとは異なる。

実ファイルの保存場所を変更せずに、複数の資料をテーマ単位でまとめる。

1つの資料は複数の Collection に所属できる。

### 8.3 Atelierとの関係

Atelier が思考や作業の流れを扱うのに対し、Collection は資料群そのものを扱う。

---

## 9. Smart Folder

条件で自動抽出する仮想フォルダ。

### 9.1 条件例

- タグ = Azure
- 種別 = PDF
- 更新日 = 今月
- 取得元 = Mail
- 未整理 = true
- 関連Atelierあり
- 関連Taskあり
- OCR済み
- お気に入り
- バージョンあり
- メモあり

### 9.2 用途

保存検索として機能し、資料を動的に整理する。

---

## 10. Inbox

一時保管場所。

### 10.1 目的

Android共有、Mail添付、Download監視などで取り込まれた資料を一旦保存する。

すぐに整理できない資料を失わず、後で整理できるようにする。

### 10.2 Inboxで行うこと

- タグ付け
- Collectionへ振り分け
- Atelierへ関連付け
- Taskへ添付
- メモ追加
- 不要資料の削除

---

## 11. ファイル操作機能

### 11.1 基本操作

- 開く
- コピー
- 移動
- 削除
- 名前変更
- 複製
- 新規フォルダ作成
- 一括選択
- 一括移動
- 一括削除
- 一括タグ付け

### 11.2 圧縮・解凍

対応候補:

- zip
- 7z
- tar
- gz

### 11.3 ゴミ箱

削除時に即時完全削除せず、アプリ内ゴミ箱へ移動できる。

機能:

- 復元
- 完全削除
- 自動削除期限

---

## 12. Viewer

Cabinet 内で主要ファイルを閲覧できるようにする。

### 12.1 対象

- PDF
- 画像
- テキスト
- Markdown
- Office文書
- 音声
- 動画

### 12.2 方針

- 可能なものは内蔵ビューアで表示する。
- 非対応形式は外部アプリで開く。
- 外部アプリで開いても Cabinet 側のメタデータは維持する。

---

## 13. プレビュー生成

Cabinet by VIASTRASSE は、ファイルを開かなくても内容を推測できるように、可能な範囲でプレビュー情報を生成して保存する。

スマホではファイル名だけでは内容を判断しにくいため、プレビューは重要機能とする。

### 13.1 プレビュー対象

|種類|プレビュー内容|
|---|---|
|画像|サムネイル、サイズ、撮影日時|
|PDF|1ページ目サムネイル、先頭テキスト、ページ数|
|Office文書|先頭行、見出し、作成者、更新日時|
|テキスト / Markdown|先頭数行、見出し|
|ZIP|中のファイル一覧の一部|
|音声|長さ、ファイル名、タグ情報|
|動画|サムネイル、長さ、解像度|
|URL|タイトル、説明、サムネイル|

### 13.2 Office文書プレビュー

対象:

```text
.docx
.xlsx
.pptx
```

Office文書の完全表示や編集ではなく、一覧・詳細表示用に先頭テキストや見出しを抽出する。

Office 2007以降の `.docx` / `.xlsx` / `.pptx` はZIP構造であるため、以下のような内部XMLから文字列を抽出する方針を検討する。

```text
.docx -> word/document.xml
.xlsx -> xl/sharedStrings.xml
.pptx -> ppt/slides/slide1.xml
```

### 13.3 プレビュー保存項目

```text
CabinetPreview
├ itemId
├ previewType
├ title
├ summaryText
├ thumbnailPath
├ extractedText
├ pageCount
├ duration
├ width
├ height
├ generatedAt
└ status
```

### 13.4 利用箇所

- ファイル一覧
- 詳細画面
- Cabinet内検索
- 将来のSearch Plugin連携
- Homeタイル
- Collection表示

---

## 14. メタデータ管理

Cabinet はファイルそのものに加えて、STRASSE独自のメタデータを管理する。

### 14.1 基本メタデータ

- ID
- ファイル名
- パス
- MIMEタイプ
- サイズ
- 作成日時
- 更新日時
- 最終閲覧日時
- ハッシュ
- お気に入り
- アーカイブ状態
- 未整理状態

### 14.2 拡張メタデータ

- タグ
- Collection
- 取得元
- 取得元アプリ
- 取得元ID
- 関連Atelier
- 関連Task
- 関連Mail
- 関連Notify
- メモ
- OCRテキスト
- プレビュー本文
- AI分類結果
- バージョン情報

---

## 15. タグ

1つの資料に複数タグを付与できる。

### 15.1 用途

- 案件
- 技術
- 顧客
- 年月
- 状態
- 用途

### 15.2 例

- Azure
- STRASSE
- 顧客A
- 2026
- 契約書
- 設計
- 参考資料

タグはフォルダ階層とは独立する。

---

## 16. Cabinet内検索

Cabinet by VIASTRASSE は、Cabinet内のファイル・URL・メモ・タグ・プレビュー情報を検索できる。

### 16.1 Search Pluginとの役割分担

将来的に STRASSE Platform には横断検索機能または Search Plugin を用意する可能性がある。

そのため、Cabinet側の検索は以下に限定する。

```text
Cabinet内検索 = Cabinet内だけを検索する機能
Search Plugin = Home / Mail / Task / Notify / Atelier / Cabinet を横断検索する機能
```

Cabinet は将来、Search Plugin に対して検索対象を提供する側となる。

### 16.2 Cabinet内検索対象

- ファイル名
- タグ
- Collection名
- メモ
- URLタイトル
- PDF本文
- Office文書の抽出テキスト
- テキスト本文
- Markdown本文
- OCR結果
- プレビュー本文
- 関連Atelier名
- 関連Task名
- 関連Mail件名

### 16.3 Search Provider方針

将来の横断検索に備え、Cabinet は検索Providerとして振る舞える設計にする。

例:

```text
CabinetSearchProvider
```

Search Plugin は CabinetSearchProvider を介して Cabinet の検索結果を取得する。

---

## 17. OCR

画像やPDFから文字を抽出し、Cabinet内検索やプレビューに利用する。

### 17.1 対象

- スクリーンショット
- 写真
- PDF
- スキャン画像
- ホワイトボード
- 名刺

### 17.2 利用用途

- Cabinet内検索
- プレビュー表示
- タグ候補
- Task作成候補
- Atelier追加候補

### 17.3 方針

画像OCRは比較的軽量に実装できるが、PDF OCRはページを画像化してからOCRする必要がある。

OCRは最初から完全な解析を目指さず、検索補助と内容把握を目的とする。

---

## 18. URL管理

Cabinet はファイルだけでなく URL も資料として扱う。

### 18.1 URL項目

- URL
- タイトル
- 説明
- サムネイル
- 取得日時
- タグ
- Collection
- 関連Atelier
- 関連Task
- メモ

### 18.2 用途

- 技術記事
- Microsoft Learn
- GitHub
- YouTube
- PDFリンク
- 参考サイト

---

## 19. バージョン管理

Cabinet by VIASTRASSE は、同一資料の版を管理できる。

### 19.1 基本思想

バージョン管理はファイル名の一致に依存しない。

同名ファイルだけでなく、ユーザーが明示的に指定した別名ファイルも同一資料の新バージョンとして扱える。

### 19.2 想定ケース

- ファイル名に日付が付いた資料
- 「修正版」「最終版」など別名保存された資料
- 上書きしたくなくて別名保存した資料
- メールで再送された改訂資料
- PDF化された提出版
- 拡張子が異なる提出版

### 19.3 概念モデル

```text
Cabinet Document = 資料としてのまとまり
Cabinet Version  = Document内の版
Cabinet File     = 実ファイル
```

例:

```text
Cabinet Document: 設計書

Version 1
└ 設計書.docx

Version 2
└ 設計書_20260627.docx

Version 3
└ 設計書_修正版_最終.docx

Version 4
└ 顧客提出版.pdf
```

### 19.4 操作

ファイル長押しメニューに以下を用意する。

```text
新しい資料として登録
既存資料の新バージョンとして登録
既存バージョンと関連付け
```

資料詳細画面には以下を用意する。

```text
バージョンを追加
最新版に設定
旧版を開く
版メモを編集
```

### 19.5 バージョン情報

各バージョンには以下を持たせる。

- versionId
- documentId
- versionNumber
- fileId
- displayName
- originalFileName
- registeredAt
- sourceApp
- sourceId
- note
- isCurrent

---

## 20. 重複検出

ハッシュ、ファイルサイズ、類似度を使って重複を検出する。

### 20.1 対象

- 完全一致ファイル
- 同名ファイル
- 類似画像
- 同一URL
- 同一PDF

### 20.2 機能

- 重複候補表示
- 片方を削除
- メタデータ統合
- Collection関連付け統合
- バージョンとして登録する提案

---

## 21. 監視

指定フォルダを監視できる。

### 21.1 監視対象例

- Download
- Documents
- Pictures/Screenshots
- Bluetooth
- 任意フォルダ

### 21.2 検出時の動作

- Inboxへ追加
- 自動タグ候補
- Collection候補
- OCRキュー登録
- プレビュー生成キュー登録
- 通知表示

---

## 22. セキュリティ

### 22.1 保護領域

一部資料を保護領域に入れられる。

機能:

- PIN
- 生体認証
- 非表示
- 暗号化

### 22.2 共有制御

重要資料の誤共有を防ぐ。

例:

- 外部共有前の確認
- パスワード付きZIP作成
- メタデータ除去

### 22.3 PPAP対応メモ

PPAPで送られてきたZIPとパスワードメールを扱うため、Cabinet Itemには保存メモと複数メール紐付けを持たせる。

パスワードそのものを保存する場合は、保護メモとして扱うことを検討する。

---

## 23. Android共有連携

Android共有メニューから Cabinet に保存できる。

### 23.1 対応対象

- text/plain
- URL
- image/*
- application/pdf
- application/zip
- audio/*
- video/*
- 任意ファイル

### 23.2 共有時の選択

- Inboxへ保存
- Collectionへ保存
- タグを付けて保存
- Atelierへ関連付け
- Taskへ添付
- URLとして保存
- ファイルとして保存
- メモを追加して保存

---

## 24. Mail by VIASTRASSE連携

Mail by VIASTRASSEとの連携は、Cabinet by VIASTRASSEの重要機能である。

### 24.1 添付保存

Mailの添付を Cabinet へ保存できる。

保存時に保持する情報:

- メールID
- 件名
- 差出人
- 受信日時
- 添付ファイル名
- 保存日時

### 24.2 保存メモ

Mail by VIASTRASSEから添付ファイルをCabinetへ保存する場合、保存時に任意のメモを入力できる。

用途例:

- PPAPで送付されたZIPファイルのパスワードメモ
- 添付ファイルの用途
- 保存理由
- 顧客名や案件名の補足
- 後で確認すべき点

### 24.3 複数メール紐付け

1つの Cabinet Item には複数のMail参照を紐付けられるものとする。

例:

```text
Cabinet Item
├ ファイル
├ 元メール
│  └ 添付ファイルが送付されたメール
├ 関連メール
│  ├ パスワード通知メール
│  ├ 補足説明メール
│  └ 差し替え連絡メール
└ メモ
```

### 24.4 Mail参照

Cabinet側から元メールや関連メールを開ける。

Mail本文をCabinetへ丸ごとコピーするのではなく、原則としてMail by VIASTRASSEへの参照を保持する。

### 24.5 添付分類

メール添付を自動で分類候補に出す。

例:

- 請求書
- 見積書
- 契約書
- 設計書
- 画像
- 圧縮ファイル

---

## 25. Task by VIASTRASSE連携

### 25.1 Taskへ添付

Cabinetの資料を Task に添付できる。

### 25.2 Task成果物保存

Task完了時に成果物を Cabinet に保存できる。

例:

- 作成したPDF
- 調査資料
- スクリーンショット
- 出力ファイル
- メモファイル

### 25.3 関連Task表示

Cabinetの資料から関連Taskを確認できる。

---

## 26. Atelier by VIASTRASSE連携

### 26.1 Atelierへ関連付け

Cabinetの資料を Atelierページへ関連資料として追加できる。

### 26.2 Atelierから参照

Atelierはファイル本体を持たず、Cabinet上の資料を参照する。

### 26.3 関連Atelier表示

Cabinet側から、その資料を参照しているAtelierを表示できる。

### 26.4 役割分担

```text
Atelier = 思考・調査・結果・整理
Cabinet = 資料・添付・ファイル・URLの正本
```

---

## 27. Notify by VIASTRASSE連携

通知から保存すべき情報を Cabinet に追加できる。

### 27.1 例

- 障害通知のスクリーンショット
- 外部サービスの通知内容
- ダウンロード完了通知
- システム警告

通知はノイズが多いため、自動保存ではなく選択保存を基本とする。

---

## 28. Home by VIASTRASSE連携

Homeタイルに以下を表示できる。

- 最近追加した資料
- 未整理Inbox件数
- お気に入り資料
- 最近開いた資料
- 今日追加された添付
- 監視フォルダの新着
- 最近更新されたCollection

---

## 29. Plugin連携

将来、各Pluginから Cabinet に資料を保存できる。

### 29.1 例

- GitHub Plugin: Issue添付、PR差分
- Azure Plugin: 障害レポート、コストCSV
- Weather Plugin: 気象記録
- Finance Plugin: 明細CSV、請求書PDF
- TV/Radio Plugin: 番組資料、URL

---

## 30. Platform連携

Cabinet は STRASSE Platform の Event Contract / Data Contract / Deep Link に従う。

### 30.1 発行イベント

- Cabinet.ItemAdded
- Cabinet.ItemUpdated
- Cabinet.ItemDeleted
- Cabinet.ReferenceAdded
- Cabinet.CollectionCreated
- Cabinet.TagAdded
- Cabinet.VersionAdded
- Cabinet.PreviewGenerated

### 30.2 購読イベント

- Mail.AttachmentSaveRequested
- Task.AttachmentAddRequested
- Task.ResultSaveRequested
- Atelier.ReferenceAddRequested
- Home.QuickSaveRequested

### 30.3 Deep Link例

```text
viastrasse-cabinet://open/{itemId}
viastrasse-cabinet://collection/{collectionId}
viastrasse-cabinet://search?q=...
viastrasse-cabinet://inbox
viastrasse-cabinet://add
viastrasse-cabinet://version/add/{documentId}
```

---

## 31. データモデル初期案

### 31.1 CabinetItem

```text
id
fileId
documentId
title
displayName
mimeType
path
size
hash
createdAt
updatedAt
lastOpenedAt
isFavorite
isArchived
isUnsorted
note
```

### 31.2 CabinetFile

```text
id
path
originalFileName
mimeType
size
hash
createdAt
updatedAt
storageType
```

### 31.3 CabinetDocument

```text
id
title
description
currentVersionId
createdAt
updatedAt
status
```

### 31.4 CabinetVersion

```text
id
documentId
versionNumber
fileId
displayName
originalFileName
registeredAt
sourceApp
sourceId
note
isCurrent
```

### 31.5 CabinetTag

```text
id
name
color
createdAt
```

### 31.6 CabinetCollection

```text
id
title
description
createdAt
updatedAt
```

### 31.7 CabinetReference

```text
id
itemId
referenceType
sourceApp
sourceId
title
uri
note
createdAt
```

referenceType例:

```text
mail
task
atelier
notify
url
manual
```

### 31.8 CabinetPreview

```text
id
itemId
previewType
title
summaryText
thumbnailPath
extractedText
pageCount
duration
width
height
generatedAt
status
```

### 31.9 CabinetMemo

```text
id
itemId
body
isProtected
createdAt
updatedAt
```

---

## 32. UI方針

### 32.1 メインナビゲーション

- Explorer
- Library
- Collection
- Inbox
- Search
- Settings

### 32.2 ファイル詳細画面

表示項目:

- プレビュー
- ファイル名
- 種別
- 保存場所
- タグ
- Collection
- メモ
- 関連Atelier
- 関連Task
- 関連Mail
- バージョン
- プロパティ

### 32.3 長押しメニュー

- 開く
- 共有
- コピー
- 移動
- 削除
- 名前変更
- タグ追加
- Collectionへ追加
- Atelierへ関連付け
- Taskへ添付
- Mail参照追加
- 新バージョンとして登録
- メモ追加

---

## 33. 非機能方針

- オフライン利用可能
- ローカルDB保存
- 将来同期可能な設計
- 他アプリ未インストール時も正常動作
- 連携失敗時もCabinet単体機能を阻害しない
- 大量ファイルでも一覧表示が破綻しない
- サムネイル・プレビュー生成は非同期
- OCR・プレビュー生成はバッテリーと負荷に配慮する

---

## 34. 将来拡張候補

- リモートストレージProviderの追加拡張
- AIタグ候補
- AI資料分類
- PDF全文解析
- Office全文解析
- 類似画像検索
- 文書比較
- バージョン差分表示
- 暗号化保管庫
- 共有リンク生成
- STRASSE横断Search連携
- Plugin SDK連携

---

---

## 35. リモートストレージ連携

Cabinet by VIASTRASSE は、将来的にローカルストレージだけでなく、クラウドストレージ、NAS、ネットワークストレージを同じ UI で扱えるようにする。

ただし、各サービスのファイルを端末へ常時同期することを前提とはしない。

基本方針は、各ストレージを **Storage Provider** として抽象化し、Cabinet 上では一つの仮想ファイルシステムとして表示することである。

```text
Cabinet
├ Local
│  ├ Internal Storage
│  ├ SD Card
│  └ USB Storage
│
├ Network
│  ├ SMB
│  ├ WebDAV
│  ├ FTP / SFTP
│  └ NAS
│
├ Cloud
│  ├ Google Drive
│  ├ Dropbox
│  ├ Box
│  ├ OneDrive
│  └ Nextcloud
│
└ STRASSE
   ├ Atelier References
   ├ Mail Attachments
   ├ Task Outputs
   └ Collections
```

### 35.1 対象サービス候補

クラウドストレージ:

- Google Drive
- Dropbox
- Box
- OneDrive
- Nextcloud

ネットワークストレージ:

- SMB
- WebDAV
- FTP
- SFTP
- NAS

その他:

- USBストレージ
- SDカード
- Android Storage Access Framework 対応ストレージ

### 35.2 Storage Provider方式

Cabinet はストレージ種別ごとの差異を UI 側へ直接持ち込まず、Provider として扱う。

Provider の責務:

- 接続設定
- 認証
- ファイル一覧取得
- ファイル情報取得
- ダウンロード
- アップロード
- 削除
- 名前変更
- 更新日時・サイズなどのメタデータ取得

概念例:

```text
StorageProvider
├ LocalStorageProvider
├ UsbStorageProvider
├ SmbStorageProvider
├ WebDavStorageProvider
├ GoogleDriveProvider
├ DropboxProvider
├ BoxProvider
├ OneDriveProvider
└ NextcloudProvider
```

Cabinet の Explorer / Library / Collection / Smart Folder は、Provider の違いを意識せずに同じ操作体系で扱えることを目指す。

### 35.3 アカウント設定

クラウドストレージを利用する場合は、各サービスごとにアカウント設定を行う。

想定項目:

- Provider種別
- 表示名
- アカウントID
- 認証方式
- アクセストークン
- リフレッシュトークン
- 接続状態
- 同期・キャッシュ方針

OAuth2 を利用するサービスでは、認証後にアクセストークンを安全に保存する。

認証情報は Android Keystore などを利用して保護する。

### 35.4 同期ではなくオンデマンド取得を基本とする

クラウドストレージ連携では、すべてのファイルを端末へ常時同期しない。

基本方針:

- 一覧はメタデータ中心で取得する
- ファイル本体は必要時にダウンロードする
- プレビューやサムネイルは可能な範囲でキャッシュする
- オフライン利用が必要な資料のみ明示的に保存する

これにより、端末容量、通信量、バッテリー消費を抑える。

### 35.5 仮想ファイルシステム

Cabinet 上では、ローカル、クラウド、NAS をできる限り同じ操作感で扱う。

例:

```text
Cabinet
├ Internal Storage
├ Google Drive
├ Dropbox
├ Box
├ NAS
└ Collections
```

ユーザーは、保存場所が異なっていても、Cabinet 内の資料として一覧、検索、タグ付け、Collection登録を行える。

### 35.6 Collectionとの連携

Collection は、保存場所に依存しない。

1つの Collection に以下を混在させられる。

- ローカルPDF
- Google Drive上のWord文書
- Dropbox上の画像
- NAS上のExcel
- Box上の契約書
- URL

例:

```text
Collection: Azure App Service
├ Local: appservice-memo.pdf
├ Google Drive: customer-design.docx
├ Dropbox: screenshot.png
├ NAS: estimate.xlsx
└ URL: Microsoft Learn
```

Collection はファイル本体をコピーするのではなく、Cabinet Item と Storage Provider 上の参照を保持する。

### 35.7 プレビュー・OCR・検索の扱い

クラウドやNAS上のファイルに対して、プレビュー、OCR、検索をどこまで行うかは Provider とキャッシュ状態に依存する。

基本方針:

- ファイル名、サイズ、更新日時などのメタデータ検索は即時対象にする
- プレビュー生成は必要時に行う
- OCRやOffice解析は、対象ファイルを一時取得できる場合のみ行う
- 大容量ファイルは自動解析しない
- モバイル通信時は自動ダウンロードを抑制する

Cabinet内検索では、クラウドファイルについて以下を検索対象にできる。

- ファイル名
- Provider名
- パス
- タグ
- Collection
- 保存メモ
- 取得済みプレビュー
- 取得済みOCRテキスト

未取得のクラウド本文を毎回ダウンロードして全文検索することは行わない。

### 35.8 Search Pluginとの役割分担

Cabinet は、Cabinet内検索を提供する。

将来の Search Plugin は、Cabinet を含む STRASSE ファミリー全体を横断検索する。

そのため Cabinet は、Search Plugin から参照可能な検索Providerとして振る舞えるようにする。

```text
Search Plugin
├ MailSearchProvider
├ TaskSearchProvider
├ AtelierSearchProvider
└ CabinetSearchProvider
```

CabinetSearchProvider は、Cabinet が保持するメタデータ、タグ、プレビュー、OCR結果、Collection情報を返す。

### 35.9 STRASSE連携上の意味

リモートストレージ対応により、Atelier / Task / Mail から参照する資料は、ローカルに存在する必要がなくなる。

例:

```text
Atelier: Azure移行
├ Google Drive上の設計書
├ NAS上の見積Excel
├ Mailから保存したZIP
└ Cabinet内URL
```

利用者は、資料がどこに保存されているかを意識せず、Cabinet上の資料として扱える。

これにより Cabinet は、単なるファイルマネージャーではなく、VIASTRASSE ファミリー全体の資料アクセス層として機能する。

### 35.10 データモデル追加案

#### StorageProviderAccount

```text
id
providerType
displayName
accountName
authType
connectionStatus
createdAt
updatedAt
lastConnectedAt
```

#### RemoteFileReference

```text
id
providerAccountId
remoteFileId
remotePath
displayName
mimeType
size
modifiedAt
etag
webUrl
isCached
cachedFilePath
lastSyncedAt
```

#### CabinetItemとの関係

CabinetItem は、ローカルファイルだけでなく RemoteFileReference も参照できる。

```text
CabinetItem
├ localFilePath
└ remoteFileReferenceId
```

ローカルファイルとリモートファイルのどちらも、Cabinet 上では同じ資料として扱う。

## 36. まとめ

Cabinet by VIASTRASSE は、普通のファイルマネージャーとして利用できることを前提としながら、VIASTRASSEファミリーの中では「資料の正本」を管理するアプリである。

Atelier が思考と成果を育てる工房であるのに対し、Cabinet は資料・添付・URL・成果物を保管し、整理し、必要なときに取り出す書庫である。

ファイルを開かなくても内容を把握できるプレビュー、Mail添付とPPAPへの実用対応、別名ファイルを含む柔軟なバージョン管理、ローカル・クラウド・NASを横断するStorage Provider構想、そしてAtelier / Task / Mailとの関連付けにより、単なるExplorerではなく、VIASTRASSEの情報基盤として機能する。


---

## 37. Storage Strategy（Cabinet の位置付け）

### 37.1 基本方針

Cabinet by VIASTRASSE は STRASSE Platform のストレージ基盤となり得るが、必須コンポーネントではない。

各 STRASSE アプリは Cabinet がインストールされていなくても単体で正常動作し、自身で保存・読込を行えることを前提とする。

Cabinet は導入されている場合のみ、ストレージ管理・バックアップ・リモートストレージ・資料管理を提供する。

### 37.2 各アプリの責務

各アプリは以下を必ず持つ。

- ローカル保存
- ローカル読込
- インポート
- エクスポート

Cabinet が存在する場合は、保存先として Cabinet Storage を選択できる。

### 37.3 リモートストレージ

Cabinet は以下を保存先として利用できる。

- Local
- SD Card
- USB Storage
- Google Drive
- Dropbox
- OneDrive
- Box
- Nextcloud
- SMB
- WebDAV
- NAS

アプリごとにデフォルト保存先を設定できる。

例:

- Atelier → Google Drive
- Mail 添付 → Local
- Task 成果物 → NAS

### 37.4 バックアップ・復元

Cabinet は STRASSE アプリのバックアップ管理を行う。

対象例:

- Atelier データ
- Task データ
- Cabinet メタデータ
- タグ
- Collection
- 設定

機能:

- 手動バックアップ
- 定期バックアップ
- 世代管理
- 復元
- 機種変更対応

リアルタイム同期は MVP 対象外とし、将来機能とする。
