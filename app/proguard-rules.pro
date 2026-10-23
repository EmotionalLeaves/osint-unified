# What R8 must not touch.
#
# Everything in this file is here because something finds it by name at
# runtime rather than by a reference R8 can see. A shrinker is allowed to
# delete or rename anything nothing appears to use, and every one of
# these is used by something it cannot read: a generated SQL string, an
# annotation processor's output, a class name in a manifest, a reflective
# lookup inside a library.
#
# The failures this prevents do not appear in a build log. They appear on
# a phone, as a blank screen or a crash on first launch, which is the
# whole reason the shrunk build is a second artifact and not the one you
# download by default.

# ---- kotlinx.serialization -------------------------------------------
# Serializers are generated as synthetic $$serializer classes and found
# through the Companion. Nothing references them directly.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.osint.unified.**$$serializer { *; }
-keepclassmembers class com.osint.unified.** {
    *** Companion;
}
-keepclasseswithmembers class com.osint.unified.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
}

# ---- Room -------------------------------------------------------------
# Room's generated implementations read and write entity fields by name,
# and the migrations in AppDatabase name tables and columns in raw SQL
# that no shrinker can follow. If a field is renamed, the column stops
# matching and the database opens empty — which is the single worst
# failure this app has.
-keep class com.osint.unified.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---- SQLCipher --------------------------------------------------------
# Loads its own native library and is reached through a factory.
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# ---- osmdroid ---------------------------------------------------------
# Tile sources and the configuration object are resolved reflectively
# from names held in preferences and resources.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ---- WorkManager ------------------------------------------------------
# A worker is instantiated from the class name stored in its database row.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class com.osint.unified.work.** { *; }

# ---- OkHttp and friends ----------------------------------------------
# Optional TLS providers that are absent at compile time by design.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- libphonenumber ---------------------------------------------------
# Metadata is loaded from resources whose names are built at runtime.
-keep class com.google.i18n.phonenumbers.** { *; }
-dontwarn com.google.i18n.phonenumbers.**

# ---- Keep the lines that make a crash report readable -----------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
