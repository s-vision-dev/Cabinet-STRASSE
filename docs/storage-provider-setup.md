# ストレージプロバイダ設定

CabinetはクラウドサービスのOAuth資格情報をリポジトリへ保存しない。
各サービスの開発者コンソールでCabinetをネイティブアプリとして登録し、
取得した値をリポジトリ直下の`local.properties`へ設定する。

```properties
cabinet.oauth.dropbox.clientId=...
cabinet.oauth.googleDrive.clientId=...
cabinet.oauth.oneDrive.clientId=...
cabinet.oauth.box.clientId=...
cabinet.oauth.box.clientSecret=...
```

登録するリダイレクトURIは次のとおり。

```text
viastrasse-cabinet://oauth/dropbox
viastrasse-cabinet://oauth/google_drive
viastrasse-cabinet://oauth/onedrive
viastrasse-cabinet://oauth/box
```

## 権限

- Dropbox: アカウント情報、ファイルメタデータ、ファイル内容の読み書き
- Google Drive: Driveの読み書き
- OneDrive: `offline_access`、`User.Read`、`Files.ReadWrite`
- Box: Box内のファイルとフォルダの読み書き

Boxの標準OAuthはトークン交換時にClient Secretを要求する。APK内の値は完全には
秘匿できないため、公開配布へ移行する場合はトークン交換をVIASTRASSE管理の
バックエンドへ移す。現行のサイドロード配布では`local.properties`から注入する。

WebDAV、Nextcloud、SMB/NASのパスワードは設定画面で入力する。値はSQLiteへ保存せず、
Android Keystoreで保護した暗号文としてアプリ専用SharedPreferencesへ保存する。
