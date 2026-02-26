package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.database.AppDatabase
import org.json.JSONObject

object UserPreferences {

    private const val TAG = "UserPrefsDebug"

    private lateinit var prefs: SharedPreferences
    private val cacheLock = Any()

    private const val DEFAULT_DOH_PROVIDER_URL = "https://cloudflare-dns.com/dns-query"
    const val DOH_DISABLED_VALUE = ""
    private const val DEFAULT_STREAMINGCOMMUNITY_DOMAIN = "streamingunity.bike"

    const val PROVIDER_URL = "URL"
    const val PROVIDER_LOGO = "LOGO"
    const val PROVIDER_PORTAL_URL = "PORTAL_URL"
    const val PROVIDER_AUTOUPDATE = "AUTOUPDATE_URL"
    const val PROVIDER_NEW_INTERFACE = "NEW_INTERFACE"

    private lateinit var providerCache: JSONObject

    fun setup(context: Context) {
        val appContext = context.applicationContext
        val prefsName = "${BuildConfig.APPLICATION_ID}.preferences"
        
        prefs = appContext.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE,
        )
        
        synchronized(cacheLock) {
            val jsonString = Key.PROVIDER_CACHE.getString() ?: "{}"
            providerCache = runCatching { JSONObject(jsonString) }.getOrDefault(JSONObject())
        }
        
        Log.d(TAG, "UserPreferences initialized with Application Context")
    }


    var currentProvider: Provider?
        get() {
            val providerName = Key.CURRENT_PROVIDER.getString()
            if (providerName?.startsWith("TMDb (") == true && providerName.endsWith(")")) {
                val lang = providerName.substringAfter("TMDb (").substringBefore(")")
                return TmdbProvider(lang)
            }
            return Provider.providers.keys.find { it.name == providerName }
        }
        set(value) {
            AppDatabase.resetInstance()
            Key.CURRENT_PROVIDER.setString(value?.name)
            ProviderChangeNotifier.notifyProviderChanged()
        }

    fun getProviderCache(provider: Provider, key: String): String = synchronized(cacheLock) {
        return providerCache
            .optJSONObject(provider.name)
            ?.optString(key)
            .orEmpty()
    }

    fun setProviderCache(provider: Provider?, key: String, value: String) = synchronized(cacheLock) {
        val providerName = provider?.name ?: currentProvider?.name ?: return@synchronized
        val innerJson = providerCache.optJSONObject(providerName)
            ?: JSONObject().also { providerCache.put(providerName, it) }
        innerJson.put(key, value)
        Key.PROVIDER_CACHE.setString(providerCache.toString())
    }

    fun clearProviderCache(providerName: String) = synchronized(cacheLock) {
        if (providerCache.has(providerName)) {
            providerCache.remove(providerName)
            Key.PROVIDER_CACHE.setString(providerCache.toString())
        }
    }

    var currentLanguage: String?
        get() = Key.CURRENT_LANGUAGE.getString()
        set(value) = Key.CURRENT_LANGUAGE.setString(value)

    var captionTextSize: Float
        get() = Key.CAPTION_TEXT_SIZE.getFloat()
            ?: PlayerSettingsView.Settings.Subtitle.Style.TextSize.DEFAULT.value
        set(value) {
            Key.CAPTION_TEXT_SIZE.setFloat(value)
        }

    var autoplay: Boolean
        get() = Key.AUTOPLAY.getBoolean() ?: true
        set(value) {
            Key.AUTOPLAY.setBoolean(value)
        }

    var keepScreenOnWhenPaused: Boolean
        get() = Key.KEEP_SCREEN_ON_WHEN_PAUSED.getBoolean() ?: false
        set(value) {
            Key.KEEP_SCREEN_ON_WHEN_PAUSED.setBoolean(value)
        }

    var playerGestures: Boolean
        get() = Key.PLAYER_GESTURES.getBoolean() ?: true
        set(value) {
            Key.PLAYER_GESTURES.setBoolean(value)
        }

    var immersiveMode: Boolean
        get() = Key.IMMERSIVE_MODE.getBoolean() ?: false
        set(value) {
            Key.IMMERSIVE_MODE.setBoolean(value)
        }

    var selectedTheme: String
        get() = Key.SELECTED_THEME.getString() ?: "default"
        set(value) = Key.SELECTED_THEME.setString(value)

    var tmdbApiKey: String
        get() = Key.TMDB_API_KEY.getString() ?: ""
        set(value) {
            Key.TMDB_API_KEY.setString(value)
            TMDb3.rebuildService()
        }

    var subdlApiKey: String
        get() = Key.SUBDL_API_KEY.getString() ?: ""
        set(value) {
            Key.SUBDL_API_KEY.setString(value)
        }

    var forceExtraBuffering: Boolean
        get() = Key.FORCE_EXTRA_BUFFERING.getBoolean() ?: false
        set(value) {
            Key.FORCE_EXTRA_BUFFERING.setBoolean(value)
        }

    var serverVoeAutoSubtitlesDisabled: Boolean
        get() = Key.SERVER_VOE_AUTO_SUBTITLES_DISABLED.getBoolean() ?: false
        set(value) {
            Key.SERVER_VOE_AUTO_SUBTITLES_DISABLED.setBoolean(value)
        }

    enum class PlayerResize(
        val stringRes: Int,
        val resizeMode: Int,
    ) {
        Fit(R.string.player_aspect_ratio_fit, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        Fill(R.string.player_aspect_ratio_fill, AspectRatioFrameLayout.RESIZE_MODE_FILL),
        Zoom(R.string.player_aspect_ratio_zoom, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
        Stretch43(R.string.player_aspect_ratio_zoom_4_3, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        StretchVertical(R.string.player_aspect_ratio_stretch_vertical, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        SuperZoom(R.string.player_aspect_ratio_super_zoom, AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    var playerResize: PlayerResize
        get() = PlayerResize.entries.find { it.resizeMode == Key.PLAYER_RESIZE.getInt() && it.name == Key.PLAYER_RESIZE_NAME.getString() }
            ?: PlayerResize.entries.find { it.resizeMode == Key.PLAYER_RESIZE.getInt() }
            ?: PlayerResize.Fit
        set(value) {
            Key.PLAYER_RESIZE.setInt(value.resizeMode)
            Key.PLAYER_RESIZE_NAME.setString(value.name)
        }

    var captionStyle: CaptionStyleCompat
        get() = CaptionStyleCompat(
            Key.CAPTION_STYLE_FONT_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.foregroundColor,
            Key.CAPTION_STYLE_BACKGROUND_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.backgroundColor,
            Key.CAPTION_STYLE_WINDOW_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.windowColor,
            Key.CAPTION_STYLE_EDGE_TYPE.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.edgeType,
            Key.CAPTION_STYLE_EDGE_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.edgeColor,
            PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.typeface
        )
        set(value) {
            Key.CAPTION_STYLE_FONT_COLOR.setInt(value.foregroundColor)
            Key.CAPTION_STYLE_BACKGROUND_COLOR.setInt(value.backgroundColor)
            Key.CAPTION_STYLE_WINDOW_COLOR.setInt(value.windowColor)
            Key.CAPTION_STYLE_EDGE_TYPE.setInt(value.edgeType)
            Key.CAPTION_STYLE_EDGE_COLOR.setInt(value.edgeColor)
        }

    var captionMargin: Int
        get() = Key.CAPTION_STYLE_MARGIN.getInt() ?: 24
        set(value) {
            Key.CAPTION_STYLE_MARGIN.setInt(value)
        }

    var qualityHeight: Int?
        get() = Key.QUALITY_HEIGHT.getInt()
        set(value) {
            Key.QUALITY_HEIGHT.setInt(value)
        }

    var subtitleName: String?
        get() = Key.SUBTITLE_NAME.getString()
        set(value) = Key.SUBTITLE_NAME.setString(value)

    var streamingcommunityDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_STREAMINGCOMMUNITY_DOMAIN
            return prefs.getString(Key.STREAMINGCOMMUNITY_DOMAIN.name, null) ?: DEFAULT_STREAMINGCOMMUNITY_DOMAIN
        }
        set(value) {
            if (!::prefs.isInitialized) return
            val oldDomain = prefs.getString(Key.STREAMINGCOMMUNITY_DOMAIN.name, null)
            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("StreamingCommunity")
            }
            with(prefs.edit()) {
                if (value.isNullOrEmpty()) remove(Key.STREAMINGCOMMUNITY_DOMAIN.name)
                else putString(Key.STREAMINGCOMMUNITY_DOMAIN.name, value)
                apply()
            }
        }

    var dohProviderUrl: String
        get() = Key.DOH_PROVIDER_URL.getString() ?: DEFAULT_DOH_PROVIDER_URL
        set(value) {
            Key.DOH_PROVIDER_URL.setString(value)
            DnsResolver.setDnsUrl(value)
        }

    var paddingX: Int
        get() = Key.SCREEN_PADDING_X.getInt() ?: 0
        set(value) = Key.SCREEN_PADDING_X.setInt(value)

    var paddingY: Int
        get() = Key.SCREEN_PADDING_Y.getInt() ?: 0
        set(value) = Key.SCREEN_PADDING_Y.setInt(value)

    private enum class Key {
        APP_LAYOUT,
        CURRENT_LANGUAGE,
        CURRENT_PROVIDER,
        PLAYER_RESIZE,
        PLAYER_RESIZE_NAME,
        CAPTION_TEXT_SIZE,
        CAPTION_STYLE_FONT_COLOR,
        CAPTION_STYLE_BACKGROUND_COLOR,
        CAPTION_STYLE_WINDOW_COLOR,
        CAPTION_STYLE_EDGE_TYPE,
        CAPTION_STYLE_EDGE_COLOR,
        CAPTION_STYLE_MARGIN,
        SCREEN_PADDING_X,
        SCREEN_PADDING_Y,
        QUALITY_HEIGHT,
        SUBTITLE_NAME,
        STREAMINGCOMMUNITY_DOMAIN,
        DOH_PROVIDER_URL,
        AUTOPLAY,
        PROVIDER_CACHE,
        KEEP_SCREEN_ON_WHEN_PAUSED,
        PLAYER_GESTURES,
        IMMERSIVE_MODE,
        TMDB_API_KEY,
        SUBDL_API_KEY,
        SELECTED_THEME,
        FORCE_EXTRA_BUFFERING,
        SERVER_VOE_AUTO_SUBTITLES_DISABLED;

        fun getBoolean(): Boolean? = if (prefs.contains(name)) prefs.getBoolean(name, false) else null
        fun getFloat(): Float? = if (prefs.contains(name)) prefs.getFloat(name, 0F) else null
        fun getInt(): Int? = if (prefs.contains(name)) prefs.getInt(name, 0) else null
        fun getString(): String? = if (prefs.contains(name)) prefs.getString(name, null) else null

        fun setBoolean(value: Boolean?) = value?.let { with(prefs.edit()) { putBoolean(name, it); apply() } } ?: remove()
        fun setFloat(value: Float?) = value?.let { with(prefs.edit()) { putFloat(name, it); apply() } } ?: remove()
        fun setInt(value: Int?) = value?.let { with(prefs.edit()) { putInt(name, it); apply() } } ?: remove()
        fun setString(value: String?) = value?.let { with(prefs.edit()) { putString(name, it); apply() } } ?: remove()

        fun remove() = with(prefs.edit()) { remove(name); apply() }
    }
}