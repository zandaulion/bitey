# Bitey 1.1.3 (`versionCode` 20) — Play release notes

Play Console takes all languages in one box, each wrapped in its locale tag;
`release-notes-1.1.3.xml` beside this file is that paste-ready form, and
this copy is generated from it. Play allows 500 characters per locale; the
longest here, French, is 237.

1.1.3 fixes a Bitey AI refusal being hidden: the waiting card wrote its next
joke line 180 ms after fading the old one out, and stopping the wait did not
cancel that write, so a refusal arriving inside the fade was replaced by a
joke shown in red as the error. It is also the first release built with R8
(shrinking, optimisation and obfuscation), which took the bundle from 12.2 MB
to 7.95 MB; its mapping travels inside the bundle, where Play reads it. The `analyse` function
now logs why the model refused a reading (reason, mode, timing, tokens; never
the photo or the note); deploy it with
`firebase deploy --only functions:bitey-ai:analyse`.

## English (en-US)

A clearer Bitey AI message, and a smaller app.

- When Bitey AI turns a photo down, the reason now stays on screen instead of being replaced by a waiting message
- The app is about a third smaller

## Arabic (ar)

رسالة أوضح من Bitey AI، وتطبيق أصغر.

- عندما يرفض Bitey AI صورة، يبقى السبب الآن ظاهرًا على الشاشة بدلًا من أن تحل محله رسالة انتظار
- أصبح التطبيق أصغر بنحو الثلث

## Chinese, Simplified (zh-CN)

Bitey AI 提示更清楚，应用也更小了。

- Bitey AI 无法识别照片时，原因现在会留在屏幕上，不再被等待提示替换
- 应用体积缩小了约三分之一

## French (fr-FR)

Un message de Bitey AI plus clair, et une application plus légère.

- Quand Bitey AI refuse une photo, la raison reste désormais à l'écran au lieu d'être remplacée par un message d'attente
- L'application est environ un tiers plus légère

## German (de-DE)

Eine klarere Meldung von Bitey AI und eine kleinere App.

- Lehnt Bitey AI ein Foto ab, bleibt der Grund jetzt sichtbar, statt von einer Wartenachricht ersetzt zu werden
- Die App ist etwa ein Drittel kleiner

## Hindi (hi-IN)

Bitey AI का साफ़ संदेश, और छोटा ऐप।

- जब Bitey AI किसी फ़ोटो को नहीं पढ़ पाता, तो कारण अब स्क्रीन पर बना रहता है, प्रतीक्षा संदेश उसकी जगह नहीं लेता
- ऐप लगभग एक-तिहाई छोटा हो गया है

## Japanese (ja-JP)

Bitey AI のメッセージがわかりやすく、アプリも小さくなりました。

- Bitey AI が写真を読み取れなかったとき、待機メッセージに置き換わらず理由が画面に残ります
- アプリのサイズが約3分の1小さくなりました

## Korean (ko-KR)

더 명확한 Bitey AI 메시지와 더 가벼워진 앱.

- Bitey AI가 사진을 읽지 못하면 이제 대기 메시지로 바뀌지 않고 이유가 화면에 남습니다
- 앱 크기가 약 3분의 1 줄었습니다

## Portuguese, Brazil (pt-BR)

Uma mensagem mais clara do Bitey AI e um app menor.

- Quando o Bitey AI recusa uma foto, o motivo agora fica na tela em vez de ser trocado por uma mensagem de espera
- O app está cerca de um terço menor

## Romanian (ro)

Un mesaj mai clar de la Bitey AI și o aplicație mai mică.

- Când Bitey AI refuză o fotografie, motivul rămâne acum pe ecran în loc să fie înlocuit de un mesaj de așteptare
- Aplicația e cu aproximativ o treime mai mică

## Spanish (es-ES)

Un mensaje de Bitey AI más claro y una app más ligera.

- Cuando Bitey AI rechaza una foto, el motivo ahora se queda en pantalla en lugar de ser sustituido por un mensaje de espera
- La app ocupa aproximadamente un tercio menos

## Ukrainian (uk)

Зрозуміліше повідомлення Bitey AI і менший застосунок.

- Коли Bitey AI відхиляє фото, причина тепер залишається на екрані, а не замінюється повідомленням очікування
- Застосунок став приблизно на третину меншим
