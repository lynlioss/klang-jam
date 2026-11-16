-keep class org.webrtc.** { *; }
# WebRTC M144 also invokes JNI Zero's Java bootstrap/helpers by literal names.
# These have no Java callers; removing them makes native initialization abort.
-keep class org.jni_zero.** { *; }
# Android instrumentation shares the app's runtime. Keep its public Kotlin ABI
# intact so the separately optimized test APK can call the same runtime.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
# Peers is also constructed by the separate instrumentation APK. Keep its
# constructor/callback contract, rather than specializing it to JamService.
-keep class app.lynli.klang.jam.Peers { *; }
