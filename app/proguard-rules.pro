# ----------------------------------------------------------------------------
# LibreTube ProGuard/R8 Rules - Ubdated
# ----------------------------------------------------------------------------

# Enable aggressive optimizations for smaller APK size
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Keep line numbers for crash reporting and debugging
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# Rename source file attribute to hide original names
-renamesourcefileattribute SourceFile

# Preserve Kotlin metadata for reflection
-keepattributes Metadata

# Don't warn about missing dependencies (common in Android libraries)
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# ----------------------------------------------------------------------------
# KOTLIN COROUTINES

# Keep coroutine dispatcher factories (loaded via ServiceLoader)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ----------------------------------------------------------------------------
# KOTLINX SERIALIZATION

# Keep serializable classes (required for JSON parsing)
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ----------------------------------------------------------------------------
# APP-SPECIFIC DATA CLASSES

# Keep data classes used for JSON serialization and IPC
-keep class com.github.libretube.obj.** { *; }
-keep class com.github.libretube.api.obj.** { *; }
-keep class com.github.libretube.obj.update.** { *; }
-keep class com.github.libretube.parcelable.** { *; }

# Keep Parcelable CREATOR fields
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ----------------------------------------------------------------------------
# SETTINGS FRAGMENTS (loaded via reflection)

# Keep preference fragments loaded dynamically
-keep class com.github.libretube.ui.preferences.** { *; }

# ----------------------------------------------------------------------------
# CONSTRAINTLAYOUT MOTIONLAYOUT (Fix for miniplayer issue)

# Only keep MotionLayout classes (specific fix for miniplayer)
-keep class androidx.constraintlayout.motion.widget.MotionLayout { *; }
-keep class androidx.constraintlayout.motion.widget.TransitionAdapter { *; }

# ----------------------------------------------------------------------------
# NEWPIPE EXTRACTOR (No built-in ProGuard rules)

# Keep only the classes used via reflection
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }

# ✅ CORRECTION : Remplacement des règles Rhino par dontwarn
# Rhino JavaScript engine classes are handled by NewPipe Extractor internally
-dontwarn org.mozilla.javascript.**

# Suppress warnings for optional dependencies
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn com.google.re2j.**

# ----------------------------------------------------------------------------
# CRYPTOGRAPHY LIBRARIES

# Suppress warnings for optional crypto providers
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ----------------------------------------------------------------------------
# PROTOBUF

# Optimise protobuf classes
-shrinkunusedprotofields

# ----------------------------------------------------------------------------
# GOOGLE PLAY SERVICES

-dontwarn com.google.android.gms.**

# ----------------------------------------------------------------------------
# R8 OPTIMIZATION HINTS

# Allow more aggressive optimizations
-allowaccessmodification

# Merge classes when possible to reduce APK size
-repackageclasses ''

# Remove unused code aggressively
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}