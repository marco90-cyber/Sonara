package com.marco.mlplayer.audio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.marco.mlplayer.MLPlayerApp
import com.marco.mlplayer.MainActivity
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.yt.PlaylistImport
import com.marco.mlplayer.yt.Yt
import com.marco.mlplayer.yt.YtApi
import com.marco.mlplayer.yt.YtDownloads
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Servizio in background: notifica con controlli, riproduzione a schermo spento e
 * libreria sfogliabile per Android Auto (preferiti, salvati, scaricati, cronologia, ricerca).
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    /** Brani conosciuti dalle ultime liste mostrate (per risolvere un id in un brano). */
    private val pool = lru<String, Yt.Item>(3000)
    private val searches = lru<String, List<Yt.Item>>(8)
    /** Playlist, album e artisti trovati da una ricerca (cartelle da aprire). */
    private val searchFolders = lru<String, List<MediaItem>>(8)
    /** Raccolte viste nelle liste e nelle ricerche, per sapere di che tipo è quella che si apre. */
    private val entryPool = lru<String, Yt.Entry>(600)
    private val children = lru<String, List<Yt.Item>>(12)
    @Volatile private var lastBrowsed: List<Yt.Item> = emptyList()

    private val app get() = application as MLPlayerApp

    /** Mappa con un tetto: scarta le voci usate meno di recente, così il servizio non accumula liste all'infinito. */
    private fun <K, V> lru(max: Int): MutableMap<K, V> = Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
        }
    )

    override fun onCreate() {
        super.onCreate()
        val engine = app.engine
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, engine.controls, LibraryCallback())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ voci della libreria

    private fun playable(i: Yt.Item): MediaItem {
        pool[i.videoId] = i
        return MediaItem.Builder()
            .setMediaId("yt_${i.videoId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(i.title).setArtist(i.artist)
                    .setArtworkUri(Uri.parse(i.cover))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsBrowsable(false).setIsPlayable(true)
                    .build()
            ).build()
    }

    private fun folder(id: String, title: String, subtitle: String = "", thumb: String = ""): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .apply { if (subtitle.isNotBlank()) setSubtitle(subtitle) }
                    .apply { if (thumb.isNotBlank()) setArtworkUri(Uri.parse(thumb)) }
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setIsBrowsable(true).setIsPlayable(false)
                    .build()
            ).build()

    private fun entryFolder(e: Yt.Entry): MediaItem {
        entryPool[e.browseId] = e
        return folder("e:${e.browseId}", e.title, e.subtitle, e.thumb)
    }

    /** I brani di una playlist importata (anche se salvata con l'id della ricerca); null se non è una playlist importata. */
    private fun importedTracks(browseId: String): List<Yt.Item>? {
        val ref = PlaylistImport.refOf(browseId) ?: return null
        return app.ytImports.find(ref.importId)?.tracks ?: emptyList()
    }

    private fun lookup(videoId: String): Yt.Item {
        pool[videoId]?.let { return it }
        val s = app.ytStore
        return s.favorites.firstOrNull { it.videoId == videoId }
            ?: s.history.firstOrNull { it.videoId == videoId }
            ?: YtDownloads.entries.firstOrNull { it.item.videoId == videoId }?.item
            ?: Yt.Item(videoId, "", "", 0, "")
    }

    private fun page(list: List<MediaItem>, page: Int, size: Int): List<MediaItem> =
        if (size <= 0 || size == Int.MAX_VALUE) list else list.drop(page * size).take(size)

    private fun <T> later(block: () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        io.execute { try { f.set(block()) } catch (e: Throwable) { f.setException(e) } }
        return f
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) }
            val p = LibraryParams.Builder().setExtras(extras).build()
            return Futures.immediateFuture(LibraryResult.ofItem(folder("root", "Sonara"), p))
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = if (mediaId.startsWith("yt_")) playable(lookup(mediaId.removePrefix("yt_"))) else folder(mediaId, mediaId)
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val store = app.ytStore
            fun tracks(list: List<Yt.Item>): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                lastBrowsed = list
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(page(list.map { playable(it) }, page, pageSize)), params))
            }
            return when {
                parentId == "root" -> Futures.immediateFuture(
                    LibraryResult.ofItemList(
                        ImmutableList.of(
                            folder("fav", "Preferiti"), folder("saved", "Salvati"),
                            folder("dl", "Scaricati"), folder("hist", "Cronologia"),
                        ).let { base ->
                            if (app.ytImports.all.isEmpty()) base
                            else ImmutableList.builder<MediaItem>().add(folder("imp", "Importate")).addAll(base).build()
                        }, params,
                    )
                )
                parentId == "fav" -> tracks(store.favorites)
                parentId == "hist" -> tracks(store.history)
                parentId == "dl" -> tracks(YtDownloads.entries.map { it.item })
                parentId == "imp" -> Futures.immediateFuture(
                    LibraryResult.ofItemList(
                        ImmutableList.copyOf(page(app.ytImports.all.map { entryFolder(it.entry) }, page, pageSize)),
                        params,
                    )
                )
                parentId == "saved" -> Futures.immediateFuture(
                    LibraryResult.ofItemList(
                        ImmutableList.copyOf(page(store.saved.map { entryFolder(it) }, page, pageSize)),
                        params,
                    )
                )
                parentId.startsWith("e:") -> {
                    val id = parentId.removePrefix("e:")
                    val e = store.saved.firstOrNull { it.browseId == id } ?: entryPool[id]
                    importedTracks(id)?.let { return tracks(it) }
                    children[id]?.let { return tracks(it) }
                    later {
                        val list = runCatching {
                            if (e?.kind == Yt.Kind.Artists) YtApi.artist(id).tracks
                            else YtApi.playlistTracks(id).map { if (it.thumb.isBlank() && !e?.thumb.isNullOrBlank()) it.copy(thumb = e!!.thumb) else it }
                        }.onFailure { AppLog.e("Auto", "Apertura di $id non riuscita", it) }.getOrDefault(emptyList())
                        children[id] = list
                        lastBrowsed = list
                        LibraryResult.ofItemList(ImmutableList.copyOf(page(list.map { playable(it) }, page, pageSize)), params)
                    }
                }
                else -> Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            io.execute {
                // brani, playlist, album e artisti insieme (le raccolte sono cartelle da aprire)
                val songs = io.submit<List<Yt.Item>> {
                    runCatching { Yt.search(query, 1) }.onFailure { AppLog.e("Auto", "Ricerca brani non riuscita", it) }.getOrDefault(emptyList())
                }
                fun entries(kind: Yt.Kind, max: Int) = io.submit<List<Yt.Entry>> {
                    runCatching { Yt.searchEntries(query, kind, maxPages = 1).take(max) }
                        .onFailure { AppLog.e("Auto", "Ricerca $kind non riuscita", it) }.getOrDefault(emptyList())
                }
                val playlists = entries(Yt.Kind.Playlists, 6)
                val albums = entries(Yt.Kind.Albums, 4)
                val artists = entries(Yt.Kind.Artists, 3)
                fun <T> got(f: java.util.concurrent.Future<List<T>>) = runCatching { f.get() }.getOrDefault(emptyList())
                val imported = app.ytImports.all.map { it.entry }.filter { it.title.contains(query, ignoreCase = true) }.take(4)
                val folders = (imported + got(playlists) + got(albums) + got(artists)).distinctBy { it.browseId }.map { entryFolder(it) }
                val list = got(songs)
                searches[query] = list
                searchFolders[query] = folders
                session.notifySearchResultChanged(browser, query, folders.size + list.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val list = searches[query].orEmpty()
            lastBrowsed = list
            val all = searchFolders[query].orEmpty() + list.map { playable(it) }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(page(all, page, pageSize)), params))
        }

        /** Un tocco su un brano suona tutta la lista in cui si trova; la voce ("suona X") cerca e suona. */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val query = mediaItems.singleOrNull()?.requestMetadata?.searchQuery
            val f = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            io.execute {
                try {
                    var start = 0
                    val items: List<Yt.Item>
                    if (!query.isNullOrBlank()) {
                        items = Yt.search(query, 1)
                    } else {
                        val ids = mediaItems.map { it.mediaId.removePrefix("yt_") }
                        val inList = ids.singleOrNull()?.let { id -> lastBrowsed.indexOfFirst { it.videoId == id } } ?: -1
                        if (inList >= 0) { items = lastBrowsed; start = inList }
                        else { items = ids.map { lookup(it) }; start = startIndex.coerceAtLeast(0) }
                    }
                    if (items.isEmpty()) { f.setException(UnsupportedOperationException("Nessun risultato")); return@execute }
                    val tracks = items.map { Yt.toTrack(it) }
                    main.post {
                        app.engine.adoptQueue(tracks)
                        f.set(MediaSession.MediaItemsWithStartPosition(tracks.map { app.engine.toMediaItem(it) }, start.coerceIn(0, tracks.lastIndex), C.TIME_UNSET))
                    }
                } catch (e: Throwable) {
                    AppLog.e("Auto", "Impossibile avviare la riproduzione", e)
                    f.setException(e)
                }
            }
            return f
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { app.engine.toMediaItem(Yt.toTrack(lookup(it.mediaId.removePrefix("yt_")))) }.toMutableList()
        )
    }
}
