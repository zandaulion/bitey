# Bitey 1.1.6 (`versionCode` 23) — Play release notes

Play Console takes all languages in one box, each wrapped in its locale tag;
`release-notes-1.1.6.xml` beside this file is that paste-ready form, and
this copy is generated from it. Play allows 500 characters per locale; the
longest here, French, is 266.

1.1.6 fixes the Today widget's sizing across launchers. On a HyperOS 4x6 grid
it sat three columns wide and about 180dp tall and overflowed (legend wrapped,
buttons clipped, labels cut to 'P…'); on the Moto launcher it fell to buttons
only because the launcher reported no size. On Android 12 and later the widget
now hands the launcher all its layouts as size-mapped RemoteViews and the
launcher shows the largest that fits: buttons only, a new tightened medium
layout (about 171dp with a two-line legend), full, and full with Bitey's
advice, each narrow (icon-only buttons, names for screen readers) and wide
(labels from 300dp). Narrow versions need more height since text wraps. The
bar is one shared bitmap to keep the update small, and legend items no longer
break between a dot and its name. Before Android 12 one layout is chosen from
the smaller reported size, defaulting to 4x2. Checked on a Moto launcher at
4x2, 3x2, 3x1 and with the medium layout forced and measured, and by the
maintainer on HyperOS with a debug build.

## English (en-US)

The Today in Bitey widget fits every home screen.

- The widget now adapts to your launcher and grid, so nothing is cut off at any size
- On narrow widgets the buttons show their icons instead of cut-off names

## Arabic (ar)

أداة «اليوم في Bitey» تناسب كل شاشة رئيسية.

- تتكيّف الأداة الآن مع المشغّل والشبكة لديك، فلا يُقصّ شيء مهما كان الحجم
- في الأدوات الضيقة تُظهر الأزرار أيقوناتها بدلًا من أسماء مقطوعة

## Chinese, Simplified (zh-CN)

“Bitey 今日”小组件适配所有主屏幕。

- 小组件现在会根据你的桌面和网格自动调整，任何尺寸都不会被截断
- 小组件较窄时，按钮显示图标，而不是被截断的名称

## French (fr-FR)

Le widget Aujourd’hui dans Bitey s'adapte à tous les écrans d'accueil.

- Le widget s'ajuste désormais à votre lanceur et à sa grille : plus rien n'est coupé, quelle que soit la taille
- Sur un widget étroit, les boutons affichent leur icône au lieu d'un nom tronqué

## German (de-DE)

Das Widget „Heute in Bitey“ passt auf jeden Startbildschirm.

- Das Widget passt sich jetzt deinem Launcher und Raster an, sodass in keiner Größe etwas abgeschnitten wird
- In schmalen Widgets zeigen die Tasten ihr Symbol statt abgeschnittener Namen

## Hindi (hi-IN)

"आज Bitey में" विजेट हर होम स्क्रीन पर ठीक बैठता है।

- विजेट अब आपके लॉन्चर और ग्रिड के अनुसार ढल जाता है, इसलिए किसी भी आकार में कुछ नहीं कटता
- संकरे विजेट में बटन कटे हुए नाम की जगह अपने आइकन दिखाते हैं

## Japanese (ja-JP)

「今日の Bitey」ウィジェットがどのホーム画面にもぴったり収まります。

- ランチャーやグリッドに合わせて調整されるので、どのサイズでも表示が切れません
- 幅の狭いウィジェットでは、途切れた名前ではなくボタンのアイコンを表示します

## Korean (ko-KR)

"오늘의 Bitey" 위젯이 모든 홈 화면에 맞게 표시됩니다.

- 위젯이 런처와 그리드에 맞게 조정되어 어떤 크기에서도 잘리지 않습니다
- 좁은 위젯에서는 잘린 이름 대신 버튼 아이콘이 표시됩니다

## Portuguese, Brazil (pt-BR)

O widget Hoje no Bitey se ajusta a qualquer tela inicial.

- O widget agora se adapta ao seu launcher e à grade, então nada fica cortado em nenhum tamanho
- Em widgets estreitos, os botões mostram o ícone em vez de nomes cortados

## Romanian (ro)

Widgetul Azi în Bitey se potrivește pe orice ecran principal.

- Widgetul se adaptează acum lansatorului și grilei tale, așa că nimic nu mai e tăiat, la nicio dimensiune
- Pe un widget îngust, butoanele își arată pictograma în loc de nume tăiate

## Spanish (es-ES)

El widget Hoy en Bitey se adapta a cualquier pantalla de inicio.

- El widget ahora se ajusta a tu launcher y su cuadrícula, así que nada queda cortado en ningún tamaño
- En widgets estrechos, los botones muestran su icono en lugar de nombres cortados

## Ukrainian (uk)

Віджет «Сьогодні в Bitey» підходить для будь-якого головного екрана.

- Віджет тепер підлаштовується під ваш лаунчер і сітку, тож нічого не обрізається за жодного розміру
- У вузькому віджеті кнопки показують значки замість обрізаних назв
