# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keepnames class com.castcharm.android.data.api.** { *; }
-keep class com.squareup.okhttp3.** { *; }
-keep interface com.squareup.okhttp3.** { *; }

# Moshi
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }

# Room
-keepclasseswithmembers class * {
    @androidx.room.* <methods>;
}
