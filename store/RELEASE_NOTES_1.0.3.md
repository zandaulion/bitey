# Bitey 1.0.3 (`versionCode` 4) — Play release notes

Play Console takes all languages in one box, each wrapped in its locale tag;
`release-notes-1.0.3.xml` beside this file is that paste-ready form. Play
allows 500 characters per locale and every entry below is within it.

The headings use Play's own locale codes, which are not the Android resource
codes: a language with one main variant is bare (`ar`, `ro`, `uk`) while one
with several carries the region (`en-US`, `pt-BR`, `zh-CN`, `es-ES`). Play
rejects the whole box over a single wrong code, naming one at a time.

1.0.3 exists because 1.0.2 shipped a photo button that did nothing at all. Two
faults sat behind it: entitlement results were delivered on Google Play's own
thread, which a WebView silently discards, and the WebView had no file chooser,
so the camera and gallery could never open. Both are fixed.

These notes deliberately describe only what a tester can see. Bitey AI itself
still has no analysis behind it until the Firebase/Gemini work in
`MANUAL_RELEASE_GUIDE.md` section 7 is done, so nothing here promises results
from a photograph. Keep this build in closed testing.

## English (en-US)

Fixes for photo entries.

- The photo button now opens the Bitey AI plans instead of doing nothing
- The camera and the gallery open for photo entries
- Plan prices come from Google Play, in your own currency
- Restoring and managing a subscription now answers reliably

## Arabic (ar)

إصلاحات لتسجيل الوجبات بالصور.

- زر الصورة يفتح الآن خطط Bitey AI بدل ألا يفعل شيئًا
- تفتح الكاميرا ومعرض الصور عند التسجيل بصورة
- تأتي أسعار الخطط من Google Play بعملتك
- استعادة الشراء وإدارة الاشتراك يستجيبان الآن بشكل صحيح

## Chinese, Simplified (zh-CN)

照片记录相关修复。

- 修复了照片按钮没有任何反应的问题，现在会打开 Bitey AI 套餐
- 照片记录可以打开相机和相册
- 套餐价格来自 Google Play，以你所在地区的货币显示
- 恢复购买和管理订阅现在都能正常响应

## French (fr-FR)

Corrections pour les entrées avec photo.

- Le bouton photo ouvre désormais les formules Bitey AI au lieu de ne rien faire
- L'appareil photo et la galerie s'ouvrent pour les entrées avec photo
- Les prix viennent de Google Play, dans votre devise
- La restauration et la gestion de l'abonnement répondent correctement

## German (de-DE)

Korrekturen für Einträge mit Foto.

- Die Foto-Schaltfläche öffnet jetzt die Bitey-AI-Tarife, statt nichts zu tun
- Kamera und Galerie öffnen sich für Einträge mit Foto
- Die Tarifpreise kommen von Google Play, in deiner Währung
- Abo wiederherstellen und verwalten antwortet jetzt zuverlässig

## Hindi (hi-IN)

फ़ोटो से एंट्री करने से जुड़े सुधार।

- फ़ोटो बटन अब कुछ न करने के बजाय Bitey AI प्लान खोलता है
- फ़ोटो एंट्री के लिए कैमरा और गैलरी खुलते हैं
- प्लान की कीमतें Google Play से, आपकी मुद्रा में आती हैं
- खरीद बहाल करना और सदस्यता प्रबंधित करना अब सही जवाब देता है

## Japanese (ja-JP)

写真からの記録に関する修正。

- 写真ボタンが反応しない問題を修正し、Bitey AI のプランが開くようになりました
- 写真の記録でカメラとギャラリーが開くようになりました
- プランの料金は Google Play から、お使いの通貨で表示されます
- 購入の復元と定期購入の管理が正しく応答します

## Korean (ko-KR)

사진 기록 관련 수정.

- 아무 반응이 없던 사진 버튼이 이제 Bitey AI 요금제를 엽니다
- 사진 기록에서 카메라와 갤러리가 열립니다
- 요금제 가격은 Google Play에서 현지 통화로 표시됩니다
- 구매 복원과 구독 관리가 정상적으로 응답합니다

## Portuguese, Brazil (pt-BR)

Correções nas entradas com foto.

- O botão de foto agora abre os planos do Bitey AI em vez de não fazer nada
- A câmera e a galeria abrem para entradas com foto
- Os preços dos planos vêm do Google Play, na sua moeda
- Restaurar e gerenciar a assinatura agora responde corretamente

## Romanian (ro)

Remedieri pentru intrările cu fotografie.

- Butonul pentru fotografie deschide acum planurile Bitey AI, în loc să nu facă nimic
- Camera și galeria se deschid pentru intrările cu fotografie
- Prețurile planurilor vin de la Google Play, în moneda ta
- Restaurarea și gestionarea abonamentului răspund corect

## Spanish (es-ES)

Correcciones en las entradas con foto.

- El botón de foto ahora abre los planes de Bitey AI en lugar de no hacer nada
- La cámara y la galería se abren para las entradas con foto
- Los precios de los planes llegan desde Google Play, en tu moneda
- Restaurar y gestionar la suscripción ya responde correctamente

## Ukrainian (uk)

Виправлення для записів із фото.

- Кнопка фото тепер відкриває тарифи Bitey AI, а не лишається без дії
- Камера та галерея відкриваються для записів із фото
- Ціни тарифів надходять із Google Play у вашій валюті
- Відновлення та керування підпискою працюють надійно
