# Library rules (Room, Billing, Firebase, CameraX, zxing-cpp) ship inside their
# AARs; only what this app reaches by name has to be kept here.

# The page calls PlateNativeBridge's methods by name through
# addJavascriptInterface. The default optimize rules already keep annotated
# methods; this states it for the class the page depends on.
-keepclassmembers class com.zandaulion.bitey.PlateNativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep file and line numbers for Play Console crash reports, without exposing
# the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
