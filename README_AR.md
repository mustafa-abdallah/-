# MediaScout — المرحلة الأولى

مشروع Android مبدئي باستخدام Kotlin وJetpack Compose لفحص بيانات الوسائط والصيغ التي يعيدها yt-dlp. **لا يبدأ تنزيل الملفات في هذه المرحلة.**

## ما الذي يعمل في هذه المرحلة؟
- إدخال رابط `http://` أو `https://`.
- فحص البيانات باستخدام `--dump-single-json` و`--skip-download`.
- عرض عنوان المحتوى، المدة، نوعه، وعدد الصيغ.
- تقسيم الصيغ إلى صوت فقط، فيديو مع صوت، وفيديو فقط.
- عرض الدقة/الوصف والامتداد ومعرّف الصيغة والترميز وحجم الملف إن توفر.
- تمييز الحجم التقريبي بالعلامة `≈` وإظهار «الحجم غير معروف» عند غياب البيانات.
- تبديل واجهة التطبيق بين العربية والإنجليزية مع اتجاه RTL/LTR.

## فتح المشروع
1. ثبّت Android Studio بإصدار حديث.
2. تأكد من وجود JDK 17 أو استخدم JDK المرفق مع Android Studio.
3. افتح مجلد `MediaScout-Android-Phase1` من Android Studio.
4. ثبّت Android SDK Platform 36 عند طلبه.
5. نفّذ Gradle Sync ثم شغّل التطبيق على جهاز Android 7.0 (API 24) أو أحدث.

> حزمة المصدر لا تتضمن Gradle Wrapper. عند فتح المشروع، استخدم Android Studio لإعداد Gradle Wrapper إذا طلب ذلك، أو أنشئ مشروع Empty Activity جديدًا ثم انقل ملفات المشروع وإعداداته إليه.

## التبعيات الأساسية
- Android Gradle Plugin `8.13.2`، متوافق مع Gradle `8.13` وJDK `17`، ويدعم حتى API `36.1`.
- Kotlin `2.2.20` مع Compose Compiler Gradle Plugin المطابق.
- Compose BOM `2025.12.00`.
- `dev.ffmpegkit-maintained:yt-dlp-android:2.0.2` — حسب README الخاص بالمكتبة وقت إعداد هذه الحزمة.

مكتبة yt-dlp-android تضم Python وyt-dlp داخل التطبيق؛ ويذكر المشروع أن حجم AAR يقارب 60–80 MB، وأن ABI المدعوم هو `arm64-v8a` و`x86_64`. لذلك قد لا تعمل على أجهزة 32-bit القديمة، وقد يكون حجم APK/AAB كبيرًا.

## ملاحظات مهمة
- لم يتم بناء المشروع فعليًا في بيئة إنشاء هذه الحزمة؛ يلزم إجراء Gradle Sync وBuild في Android Studio والتحقق من نجاحهما.
- تتغير المواقع الخارجية وقد تتطلب بعض الروابط تسجيل دخول أو لا تسمح باستخراج البيانات. التطبيق يعرض الخطأ ولا يحاول تجاوز المصادقة أو قيود الوصول.
- لا تستخدم التطبيق إلا مع المحتوى الذي تملك حق الوصول إليه وتنزيله، والتزم بشروط الخدمة وحقوق أصحاب المحتوى.
- المرحلة الأولى تستخرج بيانات JSON من yt-dlp؛ قد تكون بعض الصيغ أو أحجام الملفات غير متاحة قبل التنزيل، كما أن بعض عناصر قوائم التشغيل قد لا تحتوي على تفاصيل الصيغ كاملة.

## مراجع
- مكتبة Android: https://github.com/ffmpegkit-maintained/yt-dlp-android
- توثيق API للمكتبة: https://github.com/ffmpegkit-maintained/yt-dlp-android/wiki/API-Reference
- yt-dlp وخيارات الصيغ: https://github.com/yt-dlp/yt-dlp
- إعداد Jetpack Compose: https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- توافق Android Gradle Plugin: https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes


## بناء APK عبر GitHub Actions (بديل عند عدم توفر Android SDK محليًا)
1. أنشئ مستودعًا جديدًا في حساب GitHub.
2. ارفع محتويات هذا المجلد إلى الفرع `main` أو `master`.
3. افتح تبويب **Actions** وانتظر انتهاء سير العمل **Build Android APK**.
4. افتح نتيجة التشغيل الناجح ونزّل Artifact باسم `MediaScout-debug-apk`.
5. فك ضغط Artifact؛ ستجد `app-debug.apk` لتثبيته على جهاز Android متوافق.

ملاحظة: هذا المسار يُجري البناء في خوادم GitHub، لكنه لا يضمن نجاح البناء قبل أن يتم اختباره؛ إذا فشل سير العمل، افتح سجل البناء وأصلح الخطأ أولًا. ملف APK الناتج نسخة debug للتجربة وليس إصدارًا موقّعًا للنشر في المتاجر.
