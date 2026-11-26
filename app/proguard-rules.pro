# Keep rules are added as libraries require them.

# Debug and verbose logging never runs in a release build (#140). Every Log.d site is already free of secrets; this
# removes the calls as a second line of defence.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
