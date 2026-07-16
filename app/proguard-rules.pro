# Add project specific ProGuard rules here.

# ===== 百度定位 SDK 官方混淆规则 =====
-keep class com.baidu.** { *; }
-keep class vi.com.gdi.bgl.** { *; }
-keep class mshield.** { *; }
-dontwarn com.baidu.**

# ===== OkHttp3（百度定位 SDK 网络模块依赖）=====
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
# A resource is loaded with a relative path so the package of this class must be preserved.
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
