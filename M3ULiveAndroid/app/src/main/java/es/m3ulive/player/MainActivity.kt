package es.m3ulive.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import es.m3ulive.player.data.M3uParser
import es.m3ulive.player.model.Channel
import es.m3ulive.player.ui.ChannelAdapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt
import android.util.LruCache

@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_IMPORT_PLAYLIST = 4102
        private const val PREFS_NAME = "m3u_live_preferences"
        private const val KEY_SOURCE_URL = "source_url"
        private const val KEY_SOURCE_NAME = "source_name"
        private const val KEY_FAVORITES = "favorites"
        private const val PLAYLIST_FILE = "current_playlist.m3u"
        private const val MAX_PLAYLIST_BYTES = 32 * 1024 * 1024
        private const val MAX_LOGO_BYTES = 2 * 1024 * 1024
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val logoExecutor = Executors.newFixedThreadPool(3)
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val favoriteIds = linkedSetOf<String>()
    private var allChannels: List<Channel> = emptyList()
    private var selectedCategory = "Todos"
    private var playlistUrl: String? = null
    private var playlistName: String = ""
    private var loadGeneration = 0

    private lateinit var searchInput: EditText
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var categoryContainer: LinearLayout
    private lateinit var channelAdapter: ChannelAdapter
    private var homeView: View? = null

    private var exoPlayer: ExoPlayer? = null
    private var activePlayerView: PlayerView? = null
    private var previousOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private val logoCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.byteCount / 1024)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 24)
        window.navigationBarColor = Color.rgb(11, 16, 24)
        favoriteIds.addAll(prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty())

        homeView = buildHomeView()
        setContentView(homeView)
        restoreSavedPlaylist()
    }

    private fun buildHomeView(): View {
        channelAdapter = ChannelAdapter(
            isFavorite = { favoriteIds.contains(it.id) },
            onChannelClick = { playChannel(it) },
            onFavoriteClick = { toggleFavorite(it) },
            loadLogo = { url, imageView -> loadLogo(url, imageView) },
            dp = { dp(it) }
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 16, 24))
            setPadding(dp(18), dp(14), dp(18), dp(8))
        }
        // Android 15+ enforces edge-to-edge for apps targeting API 35 or newer.
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                root.setPadding(dp(18), bars.top + dp(14), dp(18), bars.bottom + dp(8))
                insets
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleColumn.addView(TextView(this).apply {
            text = "M3U LIVE TV"
            textSize = 21f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            letterSpacing = 0.06f
        })
        titleColumn.addView(TextView(this).apply {
            text = "TELEVISIÓN EN DIRECTO"
            textSize = 10f
            letterSpacing = 0.16f
            setTextColor(Color.rgb(131, 151, 170))
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleColumn)
        header.addView(actionButton("＋ LISTA", accent = true) { showAddPlaylistDialog() })
        header.addView(space(dp(8), 1))
        header.addView(actionButton("↻", accent = false) { refreshPlaylist() })
        root.addView(header)

        searchInput = EditText(this).apply {
            hint = "Buscar canal..."
            setHintTextColor(Color.rgb(125, 143, 161))
            setTextColor(Color.WHITE)
            textSize = 15f
            singleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedDrawable(Color.rgb(23, 33, 46), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(17)
                bottomMargin = dp(10)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applyFilters()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchInput)

        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            )
        }
        categoryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        categoryScroll.addView(categoryContainer)
        root.addView(categoryScroll)

        statusView = TextView(this).apply {
            text = "Añade una lista M3U para comenzar."
            textSize = 12f
            setTextColor(Color.rgb(132, 151, 169))
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        root.addView(statusView)

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = channelAdapter
            setHasFixedSize(true)
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(12))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        contentFrame.addView(recycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        emptyView = TextView(this).apply {
            text = "Todavía no hay canales\n\nAñade una lista M3U o M3U8 desde el botón ＋ LISTA."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(133, 151, 169))
            visibility = View.VISIBLE
        }
        contentFrame.addView(emptyView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(contentFrame)

        renderCategories()
        return root
    }

    private fun actionButton(label: String, accent: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = if (label == "↻") 22f else 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(if (accent) Color.rgb(8, 27, 34) else Color.WHITE)
            setPadding(dp(if (label == "↻") 13 else 15), 0, dp(if (label == "↻") 13 else 15), 0)
            background = roundedDrawable(
                if (accent) Color.rgb(85, 199, 181) else Color.rgb(34, 48, 64),
                dp(10)
            )
            isFocusable = true
            isClickable = true
            minHeight = dp(42)
            setOnClickListener { onClick() }
        }

    private fun renderCategories() {
        if (!::categoryContainer.isInitialized) return
        categoryContainer.removeAllViews()
        val categoryNames = buildList {
            add("Todos")
            add("Favoritos")
            addAll(allChannels.map { it.group.ifBlank { "Sin categoría" } }.distinct().sorted())
        }.distinct()
        if (selectedCategory !in categoryNames) selectedCategory = "Todos"

        categoryNames.forEach { category ->
            val selected = category == selectedCategory
            val chip = TextView(this).apply {
                text = when (category) {
                    "Favoritos" -> "★ Favoritos"
                    "Todos" -> "Todos"
                    else -> category
                }
                textSize = 12f
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER
                setTextColor(if (selected) Color.rgb(9, 29, 35) else Color.rgb(184, 198, 211))
                setPadding(dp(13), 0, dp(13), 0)
                background = roundedDrawable(
                    if (selected) Color.rgb(85, 199, 181) else Color.rgb(24, 35, 48),
                    dp(18)
                )
                isFocusable = true
                isClickable = true
                minHeight = dp(32)
                setOnClickListener {
                    selectedCategory = category
                    renderCategories()
                    applyFilters()
                }
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32)
            ).apply { rightMargin = dp(7) }
            categoryContainer.addView(chip, params)
        }
    }

    private fun applyFilters() {
        if (!::channelAdapter.isInitialized) return
        val query = if (::searchInput.isInitialized) searchInput.text.toString().trim().lowercase(Locale.ROOT) else ""
        val filtered = allChannels.filter { channel ->
            val matchesCategory = when (selectedCategory) {
                "Todos" -> true
                "Favoritos" -> favoriteIds.contains(channel.id)
                else -> channel.group.equals(selectedCategory, ignoreCase = true)
            }
            val matchesQuery = query.isBlank() || channel.name.lowercase(Locale.ROOT).contains(query) ||
                channel.group.lowercase(Locale.ROOT).contains(query) ||
                channel.tvgId.lowercase(Locale.ROOT).contains(query)
            matchesCategory && matchesQuery
        }
        channelAdapter.submitChannels(filtered)
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = when {
            allChannels.isEmpty() -> "Todavía no hay canales\n\nAñade una lista M3U o M3U8 desde el botón ＋ LISTA."
            selectedCategory == "Favoritos" && query.isBlank() -> "Todavía no tienes canales favoritos.\n\nPulsa la estrella de un canal para guardarlo."
            else -> "No se han encontrado canales con ese criterio."
        }
    }

    private fun toggleFavorite(channel: Channel) {
        if (!favoriteIds.add(channel.id)) favoriteIds.remove(channel.id)
        prefs.edit().putStringSet(KEY_FAVORITES, HashSet(favoriteIds)).apply()
        renderCategories()
        applyFilters()
    }

    private fun showAddPlaylistDialog() {
        AlertDialog.Builder(this)
            .setTitle("Añadir lista de TV")
            .setItems(arrayOf("Cargar desde una URL", "Importar archivo M3U / M3U8")) { _, which ->
                if (which == 0) showUrlDialog() else pickPlaylistFile()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://servidor/lista.m3u"
            setText(playlistUrl.orEmpty())
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("URL de la lista")
            .setMessage("Introduce la dirección M3U o M3U8.")
            .setView(input)
            .setPositiveButton("Cargar") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("https://", true) || url.startsWith("http://", true)) {
                    loadPlaylistFromUrl(url)
                } else {
                    showToast("Introduce una URL HTTP o HTTPS válida.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickPlaylistFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "audio/x-mpegurl",
                "application/x-mpegURL",
                "application/vnd.apple.mpegurl",
                "application/octet-stream",
                "text/plain"
            ))
        }
        startActivityForResult(intent, REQUEST_IMPORT_PLAYLIST)
    }

    @Deprecated("The Activity Result API is not required for this small file picker flow.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_PLAYLIST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importPlaylistFile(uri)
    }

    private fun importPlaylistFile(uri: Uri) {
        val generation = ++loadGeneration
        setStatus("Importando lista local...")
        ioExecutor.execute {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("No se pudo abrir el archivo seleccionado.")
                val raw = stream.use { readLimitedText(it, MAX_PLAYLIST_BYTES) }
                val channels = M3uParser.parse(raw)
                if (channels.isEmpty()) throw IllegalArgumentException("La lista no contiene canales reconocibles.")
                val name = queryDisplayName(uri) ?: "Lista local"
                savePlaylistFile(raw)
                runOnUiThread {
                    if (generation == loadGeneration) acceptPlaylist(channels, null, name)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (generation == loadGeneration) {
                        setStatus("No se pudo importar el archivo.")
                        showToast(error.message ?: "Error al leer la lista M3U.")
                    }
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadPlaylistFromUrl(url: String) {
        val generation = ++loadGeneration
        setStatus("Descargando y leyendo lista...")
        ioExecutor.execute {
            try {
                val raw = downloadText(url)
                val channels = M3uParser.parse(raw)
                if (channels.isEmpty()) throw IllegalArgumentException("La lista se ha leído, pero no contiene canales reconocibles.")
                savePlaylistFile(raw)
                runOnUiThread {
                    if (generation == loadGeneration) acceptPlaylist(channels, url, url)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (generation == loadGeneration) {
                        setStatus("No se pudo cargar la lista.")
                        showToast(error.message ?: "Error de conexión o formato M3U no válido.")
                    }
                }
            }
        }
    }

    private fun downloadText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) M3ULiveTV/0.1")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            val response = connection.responseCode
            if (response !in 200..299) throw IllegalStateException("El servidor respondió con HTTP $response.")
            val stream = connection.inputStream.let { raw ->
                if (connection.contentEncoding.equals("gzip", ignoreCase = true)) java.util.zip.GZIPInputStream(raw) else raw
            }
            return stream.use { readLimitedText(it, MAX_PLAYLIST_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimitedText(input: InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IllegalArgumentException("La lista supera el límite de ${maxBytes / (1024 * 1024)} MB.")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
    }

    private fun acceptPlaylist(channels: List<Channel>, url: String?, sourceName: String) {
        try {
            playlistUrl = url
            playlistName = sourceName
            prefs.edit()
                .putString(KEY_SOURCE_URL, url)
                .putString(KEY_SOURCE_NAME, sourceName)
                .apply()
            allChannels = channels
            selectedCategory = "Todos"
            if (::searchInput.isInitialized) searchInput.setText("")
            renderCategories()
            applyFilters()
            val groups = channels.map { it.group }.distinct().size
            setStatus("${channels.size} canales  ·  $groups categorías  ·  ${if (url == null) "lista local" else "lista online"}")
            showToast("Lista cargada correctamente.")
        } catch (error: Exception) {
            setStatus("No se pudo guardar la lista en el dispositivo.")
            showToast(error.message ?: "Error al guardar la lista.")
        }
    }

    private fun savePlaylistFile(raw: String) {
        val destination = File(filesDir, PLAYLIST_FILE)
        val temporary = File(filesDir, "$PLAYLIST_FILE.tmp")
        temporary.writeText(raw, Charsets.UTF_8)
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IllegalStateException("No se pudo actualizar el archivo local de la lista.")
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun restoreSavedPlaylist() {
        val file = File(filesDir, PLAYLIST_FILE)
        playlistUrl = prefs.getString(KEY_SOURCE_URL, null)
        playlistName = prefs.getString(KEY_SOURCE_NAME, "Lista guardada") ?: "Lista guardada"
        if (!file.exists()) return

        setStatus("Recuperando la última lista...")
        val generation = ++loadGeneration
        ioExecutor.execute {
            try {
                val raw = file.readText(Charsets.UTF_8)
                val channels = M3uParser.parse(raw)
                runOnUiThread {
                    if (generation == loadGeneration) {
                        allChannels = channels
                        renderCategories()
                        applyFilters()
                        setStatus("${channels.size} canales  ·  lista guardada en el dispositivo")
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (generation == loadGeneration) setStatus("La última lista guardada no se puede leer. Añade otra lista.")
                }
            }
        }
    }

    private fun refreshPlaylist() {
        val url = playlistUrl
        if (url.isNullOrBlank()) {
            showToast("La lista actual es local. Para actualizarla, vuelve a importar el archivo.")
        } else {
            loadPlaylistFromUrl(url)
        }
    }

    private fun loadLogo(url: String, imageView: ImageView) {
        val cached = logoCache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        logoExecutor.execute {
            try {
                val connection = URL(url).openConnection().apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) M3ULiveTV/0.1")
                }
                val bytes = connection.getInputStream().use { readLimitedBytes(it, MAX_LOGO_BYTES) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 320) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@execute
                logoCache.put(url, bitmap)
                mainHandler.post {
                    if (imageView.tag == url) imageView.setImageBitmap(bitmap)
                }
            } catch (_: Exception) {
                // A missing logo must never interrupt channel browsing or playback.
            }
        }
    }

    private fun readLimitedBytes(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IllegalArgumentException("Logo demasiado grande")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun playChannel(channel: Channel) {
        closePlayer(restoreHome = false)
        previousOrientation = requestedOrientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val playerContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val mediaPlayerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 5_000
            controllerAutoShow = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            keepScreenOn = true
            contentDescription = "Reproductor de televisión"
        }
        activePlayerView = mediaPlayerView
        playerContainer.addView(mediaPlayerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val backButton = TextView(this).apply {
            text = "‹  VOLVER"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(15), dp(10), dp(15), dp(10))
            background = roundedDrawable(Color.argb(175, 15, 25, 37), dp(10))
            isFocusable = true
            isClickable = true
            setOnClickListener { closePlayer() }
        }
        playerContainer.addView(backButton, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(42),
            Gravity.TOP or Gravity.START
        ).apply {
            topMargin = dp(14)
            leftMargin = dp(14)
        })

        val connectionStatus = TextView(this).apply {
            text = "Conectando con ${channel.name}..."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedDrawable(Color.argb(175, 15, 25, 37), dp(10))
        }
        playerContainer.addView(connectionStatus, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        setContentView(playerContainer)
        hideSystemBars()

        val requestHeaders = LinkedHashMap(channel.headers)
        val userAgentKey = requestHeaders.keys.firstOrNull { it.equals("User-Agent", true) }
        val userAgent = userAgentKey?.let { requestHeaders.remove(it) }
            ?: "Mozilla/5.0 (Android) M3ULiveTV/0.1"
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(requestHeaders)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build().also { player ->
                mediaPlayerView.player = player
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> connectionStatus.visibility = View.GONE
                            Player.STATE_BUFFERING -> {
                                connectionStatus.visibility = View.VISIBLE
                                connectionStatus.text = "Cargando señal..."
                            }
                            Player.STATE_ENDED -> {
                                connectionStatus.visibility = View.VISIBLE
                                connectionStatus.text = "La emisión ha terminado."
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        connectionStatus.visibility = View.VISIBLE
                        connectionStatus.text = "No se pudo reproducir este canal.\nComprueba la lista o prueba otra emisión."
                        showToast("Error de reproducción: ${error.errorCodeName}")
                    }
                })

                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(channel.streamUrl)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build())
                if (channel.streamUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                player.setMediaItem(mediaItemBuilder.build())
                player.prepare()
                player.playWhenReady = true
                mediaPlayerView.showController()
            }
    }

    private fun closePlayer(restoreHome: Boolean = true) {
        activePlayerView?.player = null
        activePlayerView = null
        exoPlayer?.release()
        exoPlayer = null
        if (restoreHome) {
            requestedOrientation = previousOrientation
            showSystemBars()
            homeView?.let { setContentView(it) }
        }
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun showSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
    }

    override fun onBackPressed() {
        if (exoPlayer != null) closePlayer() else super.onBackPressed()
    }

    override fun onPause() {
        exoPlayer?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        closePlayer(restoreHome = false)
        ioExecutor.shutdownNow()
        logoExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun space(width: Int, height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, height)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun roundedDrawable(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }
}
