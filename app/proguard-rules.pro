# Keep ExoPlayer and Media3 reflective metadata
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep jmDNS
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# Keep Kotlin metadata
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.jetbrains.annotations.**
