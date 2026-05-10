# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# 简单粗暴：保护我方包名下的所有类、所有方法、所有变量，不准改名，不准删除
-keep class com.iphone.huchenfeng.** { *; }

# 依然要保护一下 Room 和 协程 的底层
-keep class androidx.room.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.lifecycle.** { *; }