
# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep class names for JNI
-keepattributes Signature
-keepattributes *Annotation*

# MediaCodec related
-keep class android.media.** { *; }

# Keep service
-keep class com.snapdragon.screenrecorder.ScreenRecordService { *; }
