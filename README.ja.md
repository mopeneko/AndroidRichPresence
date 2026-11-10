# AndroidRichPresence

[English](README.md)

Android の YouTube メディアセッションから動画タイトル・再生・一時停止を取得し、連携した Discord アカウントの Rich Presence に共有します。日本語の画面で公式 YouTube または Morphe を選択できます。共有をオフにしたまま、再生情報の確認や Discord との連携もできます。

## 使い方

1. アプリを開き、「通知へのアクセス設定を開く」からアクセスを許可します。
2. 公式 YouTube または Morphe を選んで設定を保存します。
3. 「Discordと連携」を押し、Discord の公式認証画面で許可します。
4. 共有をオンにし、対象の YouTube アプリで動画を再生します。
5. 別の Discord クライアントのプロフィールでタイトルと再生状態を確認します。

一時停止はタイトルを残して「一時停止」に更新します。停止、セッション消失、タイトル不足、バッファリング、監視サービス切断、共有オフでは表示の消去を要求します。停止してすぐ再開した場合も消去要求を挟みます。動画 URL はメタデータから推測しません。

「連携を解除」は、この端末の認証情報を削除して接続を閉じます。接続済みなら表示の消去も要求します。Discord 側の OAuth 許可の取り消しではありません。許可自体を取り消す場合は Discord の認証済みアプリの設定を使用してください。

## 通常ビルド

Discord Social SDK **1.10.19337** の Android AAR を `app/libs/discord_partner_sdk.aar` に配置してください。SDK ファイルは Git 管理対象外です。NDK `28.2.13676358` と CMake `3.22.1` が必要です。

このアプリは Application ID `1567132201588367360` と、次の固定リダイレクト URI を使用します。以前保存された任意の Application ID は使用しません。

```text
discord-1567132201588367360:/authorize/callback
```

アプリケーションの管理者は [Discord Developer Portal](https://discord.com/developers/applications) の OAuth2 設定で **Public Client** を有効にし、この URI を登録します。ID を変更して配布する場合は、ネイティブの ID・URI と Android マニフェストのスキームをそろえて変更し、対応するアプリケーション側にも URI を登録してください。クライアントシークレット、Bot Token、個人アカウントのトークンを入力する機能はありません。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
```

生成物は `app/build/outputs/apk/debug/app-debug.apk` です。AAR がない場合、通常ビルドは明示的に失敗します。SDK が含む `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` をビルドします。

認証は SDK の PKCE `Authorize` と `GetToken`、既定の Presence スコープを使用します。Android の `AuthenticationActivity` が認証結果を受け取り、ネイティブ側でも返却 URI の完全一致を確認します。認証情報を保存してから `UpdateToken` を行い、成功後に `Connect` します。SDK の Ready・認証済み・トークン設定済みを満たす場合だけ表示を更新・消去します。切断状態の RPC を起動する `SetApplicationId` は使用しません。ただし SDK 自体は Ready 時もローカル RPC を併用する場合があります。

## 監視専用ビルド

```sh
./gradlew -PpresenceMonitorOnly=true :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

SDK と NDK を使わず、認証や Discord への送信は行いません。通知アクセスを許可すれば、対象アプリの再生情報を確認できます。通常ビルドへの自動切り替えはありません。

## 認証情報とプライバシー

Android は通知全体へのアクセスを許可しますが、このアプリは選択したパッケージの MediaSession のタイトル・media ID・再生状態を読み取り、通知本文は解析しません。共有オンかつ認証済み接続中の場合、動画タイトルと再生・一時停止の状態を Discord に送ります。

アクセストークン・リフレッシュトークン・期限は Android Keystore の鍵による AES/GCM で暗号化し、ランダム IV と Application ID・形式バージョンを認証データとして保存します。保存先はバックアップ対象外の `noBackupFilesDir` です。UI、サービス診断、アプリのログに認証情報を出しません。SDK 内部の文字列コピーまで完全に消去できるという保証はありません。

通知リスナーが起動すると、画面を開かずに保存済みの連携を復元します。通信できない間は接続とトークン更新の開始を待ち、検証済みのネットワークが戻ると自動で再開します。リフレッシュ前に処理中マーカーを永続化し、新しいトークンの保存後に接続へ使用します。リフレッシュの失敗・タイムアウト、リフレッシュ中のプロセス終了、破損した保存データ、鍵の利用不能、保存失敗では古いトークンで再試行せず、再連携が必要です。トークン更新の結果が不明な場合まで、再連携なしの復旧を保証するものではありません。

SDK 1.10.19337 の公開初期化 API は Activity を要求します。このバージョンの Context 操作と RPC の bind/unbind に対応するため、プロセス内で固定した `SdkContextActivity` を使用します。基底 Context はアプリから生成した専用の構成 Context です。認証画面の起動だけは、弱参照で保持する実際の画面へ委譲します。SDK の Activity を画面の開閉で交換しません。このアダプターは SDK の呼び出し箇所に依存するため、AAR 更新時には画面なしの計装テストを実行してください。

## 動作と確認範囲

認証と送信はプロセス内の一つの所有者が直列に処理します。通知リスナーはメディアの監視だけを担当し、その切断や画面回転で認証の所有者を作り直しません。画面へ戻った際、権限があるのにリスナーが未接続なら Android に再接続を要求します。Android 14 以降では未接続のコンポーネントを一度アンバインドしてから再接続します。許可済みのまま接続だけ失われた状態では、再接続要求だけが無処理になる Android の挙動に対応しています。

更新は同時に一件だけ行い、待機中の変化は最新の状態にまとめます。更新が15秒で応答しなければ SDK クライアント全体を破棄して古い応答を無効にし、認証済みの接続を作り直します。自動再試行は1・2・4・8・16・32秒後に行い、その後は最大60秒間隔で継続します。再生情報が頻繁に変わっても失敗回数はリセットしません。SDK 内部で再接続した場合も、現在の再生情報を再送します。切断前に開始した更新の遅い成功応答では再送を省略しません。

`ClearRichPresence()` には確認応答がありません。「消去を要求しました」は遠隔の消去確認ではありません。Android の強制停止やプロセス終了後に即座に消える保証はありません。2026年11月3日の正式版の実機確認では、Morphe の再生・一時停止・再開・動画切替について、取得状態と対応する SDK 成功応答を照合しました。同じ端末の Discord プロフィールで異なる動画タイトルと再生・一時停止を確認し、Morphe だけの終了および共有オフで表示が消えることも確認しました。送信アプリを先に、Morphe を後に強制停止した試行では、消去処理や再起動を行わず表示が消え、その後も60秒の観察終了まで再出現しませんでした。実際に消えた時刻は不明です。再起動後は追加認可なしで接続と監視が復元され、過去の動画は再送されませんでした。これはその端末での試行結果であり、別アカウントからの表示やすべての終了条件の保証ではありません。以前の非認証 RPC が残した表示は独立に期限切れとなる場合があり、OAuth 連携が過去の表示を消去するとは限りません。

ローカルの単体テストは認証前の送信防止、保存失敗、更新トークンのローテーション、最新状態の集約、消去順序、古い応答の拒否、タイムアウト時の破棄、長い通信断からの自動復旧、オフライン中のトークン更新の延期、連携解除を検証します。Keystore の計装テストは偽の認証情報だけを使って暗号化、復元、ID 不一致、破損、鍵の消失、リフレッシュ中断を検証します。これらは Discord 側の表示を証明するものではありません。

## 端末で再生情報を確認する

```sh
adb shell dumpsys activity service cc.lem0n.androidrichpresence/.youtube.YouTubeNotificationListener
python3 scripts/verify_youtube.py --serial SERIAL --source-package com.google.android.youtube --evidence-dir /tmp/youtube-presence-proof
```

Morphe ではアプリ設定と `--source-package` を `app.morphe.android.youtube` に変更します。スクリプトは動画を開いて再生・一時停止・再開・別タイトルへの変化を照合し、最後に一時停止します。結果は `result.json` と診断出力に保存します。診断には取得した動画タイトルが含まれるため、共有前に内容を確認してください。

`--expected-title-a` と `--expected-title-b` で検証タイトルを指定できます。通常ビルドで共有オン・連携済みなら `--require-discord-accepted` により対応する SDK 成功応答も待ちます。プロフィールの実表示は別の Discord クライアントで確認してください。広告、Shorts、バックグラウンド再生のメタデータ提供は対象アプリやアカウントによって異なります。

強制停止の再検証には、再生中かつ SDK 成功応答を確認した状態で Discord の自分のプロフィールを開き、次を実行します。両アプリを強制停止するため、再生は終了します。スクリプトはプロセス不在とスクリーンショットを記録し、プロフィールの消失は画像を目視で判断します。

```sh
python3 scripts/verify_lifecycle.py --serial SERIAL --source-package app.morphe.android.youtube --evidence-dir /tmp/youtube-presence-lifecycle
```

## 画面を開かない復旧を検証する

共有と通知アクセスを有効にし、Discord との連携を保存して、対象アプリを再生または一時停止にしてください。次の計装テストは MainActivity を起動せずに既存の連携と現在の再生情報を使用します。`backgroundPresence=true` を指定しない通常の計装テストでは、このケースをスキップします。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w \
  -e class cc.lem0n.androidrichpresence.presence.BackgroundPresenceTest \
  -e backgroundPresence true -e backgroundObserveSeconds 240 \
  cc.lem0n.androidrichpresence.test/androidx.test.runner.AndroidJUnitRunner
```

計装テストが接続している間に、別のターミナルから次を実行します。このスクリプトは端末全体の Wi-Fi とモバイルデータを160秒間切断し、正常終了、通常のエラー、Ctrl-C では元の設定への復元を試み、その結果を記録します。USB ADB を使用してください。ADB 接続の喪失やプロセスの強制終了では復元できない場合があります。通話や他のアプリの通信を使用していない端末で実行してください。保存した診断には動画タイトルが含まれます。

```sh
python3 scripts/verify_background.py --serial SERIAL --evidence-dir /tmp/presence-background-proof
```

成功条件は、送信アプリを画面に出さずに認証済み接続が復帰し、その時点のメディアに対する SDK 成功応答が得られることです。遠隔プロフィールの表示は別の Discord クライアントで確認してください。Android による強制停止、通知アクセスの取り消し、メーカーによるプロセス起動の制限は接続ループだけでは解除できません。計装テストの終了時には Android がテスト対象のプロセスも終了させる場合があります。

## 注意

個人プロジェクトのソース公開であり、Google Play での配布やインストーラはありません。Discord Social SDK のバイナリも同梱しません。Discord、Google、YouTube による承認やサポートを受けたものではありません。接続するサービスと再生アプリの利用規約は各自で確認してください。

同梱の Application ID `1567132201588367360` はメンテナの Discord アプリケーション用です。自分のアプリケーションでビルドする場合は、README.md の「Connect your own Discord application」に従って ID とリダイレクト URI を差し替えてください。

## ライセンス

MIT ライセンスです。[LICENSE](LICENSE) を参照してください。

## リリース APK

リリースビルドは、リポジトリ直下に `keystore.properties` がある場合だけ署名されます。ない場合は `app-release-unsigned.apk` が生成され、Android にはインストールできません。`keystore.properties` と署名鍵は Git 管理対象外です。署名鍵をリポジトリ外に保管してください。鍵を失うと、インストール済みの端末を更新できなくなります。

リリースは APK を添付し、GitHub の immutable release として公開します。公開後はタグと添付ファイルが変更できません。ダウンロードした APK は、リリースノートに記載した SHA-256 と署名証明書のフィンガープリントで確認してください。

## 通知へのアクセスを許可できない場合

Android 13 以降では、ストア以外からインストールしたアプリ（ファイルマネージャからリリース APK を入れた場合など）の通知アクセスが制限されます。設定画面のスイッチは「Controlled by Restricted Setting」と表示され、操作できません。この制限はアプリから検出も解除もできないため、アプリは手順を表示します。

インストールごとに一度だけ許可します。

1. Android の設定で「アプリ」を開き、AndroidRichPresence を選びます。
2. 右上のメニュー（⋮）を開きます。
3. 「Allow restricted settings（制限付き設定を許可）」を選び、画面ロックで確認します。
4. 通知アクセスの画面に戻り、スイッチをオンにします。

アプリの「このアプリの設定を開く」ボタンから 1 の画面を直接開けます。メニューに項目がない場合は、ストア経由か `adb install` で入れたため、この制限は適用されていません。`adb install` では制限が付かないため、検証用の端末ではこちらが簡単です。
