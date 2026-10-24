# The local personal-test build does not enable shrinking.
# Preserve JavaScript bridge entry points if shrinking is enabled later.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
