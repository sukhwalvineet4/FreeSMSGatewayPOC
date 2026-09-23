# Preserve Firebase Cloud Messaging
-keepattributes *Annotation*
-dontwarn com.google.firebase.**
-keep class com.google.firebase.messaging.** { *; }

# Preserve App Components
-keep class com.freesmsgateway.poc.** { *; }

# Preserve OkHttp WebSockets
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
