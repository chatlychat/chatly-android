# Keep Chatly public API + serialization metadata for consumers.
-keep class com.chatly.** { *; }
-keepclassmembers class com.chatly.** {
    *** Companion;
}
-keepclasseswithmembers class com.chatly.** {
    kotlinx.serialization.KSerializer serializer(...);
}
