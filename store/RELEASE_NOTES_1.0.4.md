# Bitey 1.0.4 (`versionCode` 5) — Play release notes

Play Console takes all languages in one box, each wrapped in its locale tag;
`release-notes-1.0.4.xml` beside this file is that paste-ready form. Play
allows 500 characters per locale and every entry below is within it.

1.0.3 made the Bitey AI plans reachable, but a photograph still could not be
taken: a WebView opens a file chooser only for a click carrying a user gesture,
and neither the native action bar nor a click issued after the Play check has
one. 1.0.4 has native code open the camera and the gallery directly. The
gallery button was also drawn underneath the native action bar, where its taps
landed on Barcode instead.

The quoted gallery button below uses each locale's own wording from
`web/i18n/`, so the notes name the button people actually see.

Nothing here promises that a photograph is analysed. Bitey AI still answers
"Photo analysis is not connected yet" until the Firebase/Gemini work in
`MANUAL_RELEASE_GUIDE.md` section 7 is done, so this build stays in closed
testing.

## English (en-US)

Photo entries now work.

- The camera opens when you add a meal from a photo
- The picture you take arrives in the diary for checking
- "Already have a photo?" opens your gallery instead of hiding behind the buttons

## Arabic (ar)

تسجيل الوجبات بالصور يعمل الآن.

- تفتح الكاميرا عند إضافة وجبة من صورة
- تصل الصورة التي تلتقطها إلى اليوميات لمراجعتها
- يفتح زر "هل لديك صورة بالفعل؟" معرض صورك بدل أن يختفي خلف الأزرار

## Chinese, Simplified (zh-CN)

照片记录现在可以使用了。

- 通过照片添加一餐时会打开相机
- 拍好的照片会进入日记供你核对
- “已经有照片了吗？”会打开相册，不再被按钮挡住

## French (fr-FR)

Les entrées avec photo fonctionnent.

- L'appareil photo s'ouvre quand vous ajoutez un repas à partir d'une photo
- La photo prise arrive dans le journal pour vérification
- « Vous avez déjà une photo ? » ouvre votre galerie au lieu de se cacher derrière les boutons

## German (de-DE)

Einträge mit Foto funktionieren jetzt.

- Die Kamera öffnet sich, wenn Sie eine Mahlzeit per Foto hinzufügen
- Das aufgenommene Bild landet im Tagebuch zum Prüfen
- „Haben Sie bereits ein Foto?“ öffnet die Galerie, statt hinter den Schaltflächen zu verschwinden

## Hindi (hi-IN)

फ़ोटो से एंट्री अब काम करती है।

- फ़ोटो से भोजन जोड़ने पर कैमरा खुलता है
- ली गई तस्वीर जाँच के लिए डायरी में आ जाती है
- "क्या आपके पास पहले से ही एक फोटो है?" अब बटनों के पीछे छिपने के बजाय गैलरी खोलता है

## Japanese (ja-JP)

写真からの記録が使えるようになりました。

- 写真で食事を追加するとカメラが開きます
- 撮った写真は確認のために記録へ届きます
- 「すでに写真をお持ちですか?」がボタンの後ろに隠れず、ギャラリーを開きます

## Korean (ko-KR)

사진 기록이 이제 작동합니다.

- 사진으로 식사를 추가하면 카메라가 열립니다
- 찍은 사진이 확인을 위해 기록에 들어옵니다
- "이미 사진이 있나요?"가 버튼 뒤에 가려지지 않고 갤러리를 엽니다

## Portuguese, Brazil (pt-BR)

As entradas com foto agora funcionam.

- A câmera abre quando você adiciona uma refeição por foto
- A foto tirada chega ao diário para conferência
- "Já tem foto?" abre sua galeria em vez de ficar atrás dos botões

## Romanian (ro)

Intrările cu fotografie funcționează acum.

- Camera se deschide când adaugi o masă dintr-o fotografie
- Fotografia făcută ajunge în jurnal, pentru verificare
- „Ai deja o fotografie?” îți deschide galeria, în loc să stea ascuns în spatele butoanelor

## Spanish (es-ES)

Las entradas con foto ya funcionan.

- La cámara se abre cuando añades una comida a partir de una foto
- La foto que haces llega al diario para revisarla
- «¿Ya tienes una foto?» abre tu galería en vez de quedarse detrás de los botones

## Ukrainian (uk)

Записи з фото тепер працюють.

- Камера відкривається, коли ви додаєте страву з фото
- Зроблене фото потрапляє у щоденник для перевірки
- «Уже є фото?» відкриває галерею, а не ховається за кнопками
