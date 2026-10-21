# Bitey 1.1.5 (`versionCode` 22) — Play release notes

Play Console takes all languages in one box, each wrapped in its locale tag;
`release-notes-1.1.5.xml` beside this file is that paste-ready form, and
this copy is generated from it. Play allows 500 characters per locale; the
longest here, French, is 399.

1.1.5 adds a home-screen widget. "Today in Bitey" (TodayWidget.kt, 4x2,
resizable) shows today's total as a large number against the burn estimate, a
bar of protein, carbs and fat by energy, a legend with fibre, and Photo,
Barcode and Type buttons that reuse the shortcut actions (Photo still passes
the Bitey AI gate). Below 160dp it keeps only the buttons; from about three
rows it adds Bitey, drawn by the page from its own SVG in the current mood,
with the macro advice computed as the card's is. The page sends a snapshot of
totals and translated words whenever it draws today, never entries; after
midnight the 30-minute widget update shows 0 and the greeting for the new day.
Settings gains a Home screen widget section that asks the launcher to place it
(requestPinAppWidget), and the Meal photo Quick Settings tile now shows
Bitey's head instead of a thin camera outline. Checked on a Fold4 before
release at one, two and three rows, in dark mode, and with a snapshot dated
yesterday.

## English (en-US)

Your day on the home screen.

- New Today in Bitey widget: today's calories against what you burn, your macros and fibre, with Photo, Barcode and Type buttons
- Make it taller to see Bitey's advice; at one row it keeps just the buttons
- Add it from Settings → Home screen widget
- The Meal photo tile now shows Bitey

## Arabic (ar)

يومك على الشاشة الرئيسية.

- أداة جديدة «اليوم في Bitey»: سعرات اليوم مقابل ما تحرقه، والمغذيات والألياف، مع أزرار الصورة والباركود واكتب
- كبّرها لرؤية نصائح Bitey؛ وفي صف واحد تبقى الأزرار فقط
- أضفها من الإعدادات ← أداة الشاشة الرئيسية
- مربع «صورة وجبة» يعرض الآن Bitey

## Chinese, Simplified (zh-CN)

把你的一天放到主屏幕上。

- 全新“Bitey 今日”小组件：今日热量与消耗对比、宏量营养素和膳食纤维，以及照片、条码和输入按钮
- 调高后可查看 Bitey 的建议；只有一行时仅保留按钮
- 在设置 → 主屏幕小组件中添加
- “餐食照片”图块现在显示 Bitey

## French (fr-FR)

Votre journée sur l'écran d'accueil.

- Nouveau widget Aujourd’hui dans Bitey : les calories du jour face à ce que vous brûlez, vos macros et fibres, avec les boutons Photo, Code à barres et Saisir
- Agrandissez-le pour voir les conseils de Bitey ; sur une ligne, il ne garde que les boutons
- Ajoutez-le depuis Paramètres → Widget d'écran d'accueil
- La vignette Photo repas affiche désormais Bitey

## German (de-DE)

Dein Tag auf dem Startbildschirm.

- Neues Widget „Heute in Bitey“: heutige Kalorien gegenüber deinem Verbrauch, Makros und Ballaststoffe, mit den Tasten Foto, Barcode und Eingeben
- Zieh es größer, um Biteys Tipps zu sehen; in einer Zeile bleiben nur die Tasten
- Hinzufügen unter Einstellungen → Startbildschirm-Widget
- Die Kachel „Mahlzeitfoto“ zeigt jetzt Bitey

## Hindi (hi-IN)

आपका दिन होम स्क्रीन पर।

- नया "आज Bitey में" विजेट: आज की कैलोरी बनाम आप कितना बर्न करते हैं, मैक्रो और फ़ाइबर, साथ में फ़ोटो, बारकोड और लिखें बटन
- Bitey की सलाह देखने के लिए इसे लंबा करें; एक पंक्ति में सिर्फ़ बटन रहते हैं
- सेटिंग्स → होम स्क्रीन विजेट से जोड़ें
- "भोजन फ़ोटो" टाइल अब Bitey दिखाती है

## Japanese (ja-JP)

今日の記録をホーム画面に。

- 新しい「今日の Bitey」ウィジェット：今日のカロリーと消費量、マクロと食物繊維、写真・バーコード・入力ボタン
- 縦に大きくすると Bitey のアドバイスも表示。1 行ではボタンだけになります
- 設定 → ホーム画面ウィジェットから追加できます
- 「食事の写真」タイルに Bitey が登場

## Korean (ko-KR)

오늘 하루를 홈 화면에.

- 새 "오늘의 Bitey" 위젯: 오늘의 칼로리와 소모량, 매크로와 식이섬유, 사진·바코드·입력 버튼
- 세로로 늘리면 Bitey의 조언이 보이고, 한 줄일 때는 버튼만 남습니다
- 설정 → 홈 화면 위젯에서 추가하세요
- "식사 사진" 타일에 이제 Bitey가 보입니다

## Portuguese, Brazil (pt-BR)

Seu dia na tela inicial.

- Novo widget Hoje no Bitey: as calorias de hoje frente ao que você queima, macros e fibras, com os botões Foto, Código de barras e Escrever
- Deixe-o mais alto para ver as dicas do Bitey; com uma linha, ficam só os botões
- Adicione em Configurações → Widget da tela inicial
- O bloco Foto refeição agora mostra o Bitey

## Romanian (ro)

Ziua ta pe ecranul principal.

- Widget nou Azi în Bitey: caloriile de azi față de cât arzi, macronutrienții și fibrele, cu butoanele Fotografie, Cod de bare și Scrie
- Fă-l mai înalt ca să vezi sfaturile lui Bitey; pe un singur rând rămân doar butoanele
- Adaugă-l din Setări → Widget pentru ecranul principal
- Butonul Poză masă îl arată acum pe Bitey

## Spanish (es-ES)

Tu día en la pantalla de inicio.

- Nuevo widget Hoy en Bitey: las calorías de hoy frente a lo que quemas, tus macros y fibra, con los botones Foto, Código de barras y Escribir
- Hazlo más alto para ver los consejos de Bitey; en una fila solo quedan los botones
- Añádelo desde Configuración → Widget de pantalla de inicio
- El botón Foto comida ahora muestra a Bitey

## Ukrainian (uk)

Ваш день на головному екрані.

- Новий віджет «Сьогодні в Bitey»: калорії за сьогодні проти того, скільки ви спалюєте, макроси й клітковина, з кнопками «Фото», «Штрих-код» і «Ввести»
- Зробіть його вищим, щоб бачити поради Bitey; в один рядок лишаються тільки кнопки
- Додайте в Налаштування → Віджет головного екрана
- Плитка «Фото страви» тепер показує Bitey
