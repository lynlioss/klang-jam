-keep class org.webrtc.** { *; }
# WebRTC M144 also invokes JNI Zero's Java bootstrap/helpers by literal names.
# These have no Java callers; removing them makes native initialization abort.
-keep class org.jni_zero.** { *; }
