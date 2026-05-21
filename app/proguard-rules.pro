# Keep Gemini SDK classes
-keep class com.google.ai.client.generativeai.** { *; }
-keep class com.google.protobuf.** { *; }

# Keep accessibility service
-keep class com.phoneclaw.service.PhoneclawAccessibilityService { *; }

# Keep data classes used with Gson/JSON
-keep class com.phoneclaw.agent.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
