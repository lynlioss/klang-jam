# Error Prone annotations reference the JDK compiler model, which Android lacks.
# They are only metadata in the instrumentation dependencies, never runtime code.
-dontwarn javax.lang.model.**
