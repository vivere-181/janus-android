# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in {SDKHOME}/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
-keep class org.kde.kdeconnect.** {*;}


# The android-smsmms library uses class casting and reflection internally.
# R8 optimization breaks PduPersister, causing SMSPlugin.onCreate() to crash.
-keep class com.google.android.mms.** {*;}


# ge.connect: Room databases are created by reflection (WorkManager's WorkDatabase_Impl, used by the widget)
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ge.connect: Glance creates widget action callbacks by reflection (no-arg constructor)
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# ge.connect: plugins are created by reflection (PluginFactory), wherever their package is
-keep class * extends org.kde.kdeconnect.plugins.Plugin { <init>(); }

# ge.connect: AirPlay receiving (src/airplay): the engine calls these from native code by name.
-keep class io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class * implements io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class io.github.jqssun.airplay.bridge.LogListener { *; }
-keep class * implements io.github.jqssun.airplay.bridge.LogListener { *; }
-keep class io.github.jqssun.airplay.bridge.NativeBridge { *; }
