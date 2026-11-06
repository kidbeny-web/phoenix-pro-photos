# OpenCV (appels natifs JNI)
-keep class org.opencv.** { *; }
# Carte et lecteur de QR code
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
-keep class com.journeyapps.** { *; }
-keep class com.google.zxing.** { *; }
# Application (WorkManager instancie le worker par réflexion)
-keep class fr.phoenixpro.photos.** { *; }
-dontwarn org.slf4j.**
