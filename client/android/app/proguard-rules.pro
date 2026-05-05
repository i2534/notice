# Paho MQTT
-keep class org.eclipse.paho.** { *; }
-keepclassmembers class org.eclipse.paho.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.** { *; }

# Keep data classes
-keepclassmembers class site.e9e.notice.data.** { *; }
