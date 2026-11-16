# R8 rules for release builds. Libraries (Retrofit, OkHttp, Room, WorkManager,
# Media3, Compose, Coil, DataStore, Moshi) ship their own rules; this file
# covers only what is specific to this app.

# Moshi reads and writes JSON by reflection (KotlinJsonAdapterFactory), so the
# classes it handles must keep their names, fields and constructors: field
# names are the JSON keys. That is the API models plus SerializedCookie (the
# saved login cookie) and ServerLimits, all under data.api.
-keep class com.castcharm.android.data.api.** { *; }

# Keep line numbers in stack traces (Play vitals, the diagnostics log), so a
# trace can be decoded with the build's mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
