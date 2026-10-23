# Pending push messages are persisted with Gson and may survive an app upgrade.
# Keep the class and field names stable across consumer release builds.
-keep class com.starxen.lib.google.push.GooglePushMessage { *; }
