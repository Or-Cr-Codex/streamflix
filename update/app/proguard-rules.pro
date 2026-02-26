# Streamflix ProGuard Rules

# 1. Conserva i modelli di dati (fondamentale per Gson/Parsing)
-keep class com.streamflixreborn.streamflix.models.** { *; }
-keepclassmembers class com.streamflixreborn.streamflix.models.** { *; }

# 2. Conserva le interfacce dei Provider e i loro servizi (fondamentale per Retrofit)
-keep class com.streamflixreborn.streamflix.providers.** { *; }
-keep interface com.streamflixreborn.streamflix.providers.** { *; }

# 3. Conserva le classi degli estrattori
-keep class com.streamflixreborn.streamflix.extractors.** { *; }

# 4. Conserva le classi legate a TMDb
-keep class com.streamflixreborn.streamflix.utils.TMDb3** { *; }
-keep class com.streamflixreborn.streamflix.utils.TmdbUtils** { *; }

# 5. Regole standard per Retrofit e OkHttp
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# 6. Regole per Room
-keep class * extends androidx.room.RoomDatabase
-keep class com.streamflixreborn.streamflix.database.dao.** { *; }

# 7. Regole per Jsoup (usato per lo scraping)
-keep class org.jsoup.** { *; }

# 8. Regole per Glide (caricamento immagini)
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
-keep class com.bumptech.glide.integration.okhttp3.OkHttpGlideModule { *; }

# 9. Conserva le classi per gli Update (GitHub release)
-keep class com.streamflixreborn.streamflix.utils.GitHub** { *; }
