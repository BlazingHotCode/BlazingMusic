package com.blazinghotcode.blazingmusic

import android.content.Context
import android.util.Log
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * YouTube Music InnerTube client inspired by Metrolist's WEB_REMIX flow.
 * Supports search, browse navigation (artist/album/playlist), and stream URL resolution.
 */
class YouTubeApiClient(private val appContext: Context? = null) {
    private val streamUrlCache =
        object : LinkedHashMap<String, String>(401, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > 400
            }
        }

    suspend fun searchMusicVideos(
        query: String,
        maxResults: Int = 20,
        filter: YouTubeSearchFilter = YouTubeSearchFilter.ALL
    ): List<YouTubeVideo> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val body = JSONObject()
            .put("context", contextObject())
            .put("query", trimmed)
            .apply {
                filter.params?.let { put("params", it) }
            }

        val capped = maxResults.coerceIn(1, 100)
        val collected = mutableListOf<YouTubeVideo>()
        val seen = hashSetOf<String>()
        val visitedContinuations = hashSetOf<String>()

        var root = post("search", body)
        var pages = 0
        while (root != null && pages < 5 && collected.size < capped) {
            pages += 1
            parseShelfItems(root)
                .forEach { item ->
                    val key = item.videoId ?: item.id
                    if (seen.add(key)) collected += item
                }
            if (collected.size >= capped) break

            val continuation = extractPlaylistPanelContinuation(root)
                ?.takeIf { visitedContinuations.add(it) }
                ?: break
            val continuationBody = JSONObject()
                .put("context", contextObject())
                .put("continuation", continuation)
            root = post("search", continuationBody)
        }

        return collected.take(capped)
    }

    suspend fun browseCollection(
        browseId: String,
        params: String? = null,
        maxResults: Int = 300
    ): List<YouTubeVideo> {
        return browseCollectionPage(
            browseId = browseId,
            params = params,
            maxResults = maxResults
        ).items
    }

    suspend fun browseCollectionPage(
        browseId: String,
        params: String? = null,
        maxResults: Int = 300
    ): YouTubeBrowsePage {
        if (browseId.isBlank()) return YouTubeBrowsePage(emptyList())
        val capped = maxResults.coerceIn(1, 2000)
        val initialBody = JSONObject()
            .put("context", contextObject())
            .put("browseId", browseId)
            .apply {
                if (!params.isNullOrBlank()) put("params", params)
            }

        val collected = mutableListOf<YouTubeVideo>()
        val seen = hashSetOf<String>()
        val visitedContinuations = hashSetOf<String>()

        var root = post("browse", initialBody)
        var artistInfo: YouTubeArtistInfo? = null
        var pages = 0
        while (root != null && pages < 8 && collected.size < capped) {
            pages += 1
            if (artistInfo == null) {
                artistInfo = parseArtistInfo(root)
            }
            parseShelfItems(root)
                .forEach { item ->
                    val key = item.videoId ?: item.id
                    if (seen.add(key)) collected += item
                }
            if (collected.size >= capped) break

            val continuation = extractPlaylistPanelContinuation(root)
                ?.takeIf { visitedContinuations.add(it) }
                ?: break
            val continuationBody = JSONObject()
                .put("context", contextObject())
                .put("continuation", continuation)
            root = post("browse", continuationBody)
        }

        return YouTubeBrowsePage(
            items = collected.take(capped),
            artistInfo = artistInfo
        )
    }

    suspend fun fetchRadioCandidates(
        videoId: String,
        playlistId: String? = null,
        playlistSetVideoId: String? = null,
        params: String? = null,
        index: Int? = null,
        fallbackQuery: String? = null,
        maxResults: Int = 60
    ): List<YouTubeVideo> {
        if (videoId.isBlank()) return emptyList()
        val cappedResults = maxResults.coerceIn(1, 300)
        val baseEndpoint = RadioWatchEndpoint(
            videoId = videoId,
            playlistId = playlistId,
            playlistSetVideoId = playlistSetVideoId,
            params = params,
            index = index
        )

        val collected = mutableListOf<YouTubeVideo>()
        val seen = hashSetOf<String>()
        val endpointVariants = buildRadioEndpointCandidates(baseEndpoint)
        for (endpointVariant in endpointVariants) {
            val raw = fetchRadioCandidatesInternal(
                endpoint = endpointVariant,
                fallbackQuery = fallbackQuery,
                maxResults = cappedResults,
                depth = 0,
                visited = linkedSetOf()
            )
            raw.forEach { candidate ->
                val key = candidate.videoId ?: candidate.id
                if (seen.add(key)) collected += candidate
            }
            if (collected.size >= cappedResults) break
        }

        return normalizeRadioCandidates(
            candidates = collected,
            seedVideoId = videoId,
            fallbackQuery = fallbackQuery,
            maxResults = cappedResults
        )
    }

    private fun buildRadioEndpointCandidates(base: RadioWatchEndpoint): List<RadioWatchEndpoint> {
        val variants = linkedSetOf<RadioWatchEndpoint>()
        variants += base

        if (!base.playlistId.isNullOrBlank()) {
            variants += base.copy(index = null)
        }

        if (base.videoId.isNotBlank()) {
            variants += RadioWatchEndpoint(videoId = base.videoId)
            variants += RadioWatchEndpoint(
                videoId = base.videoId,
                playlistId = "RDAMVM${base.videoId}"
            )
        }

        return variants.toList()
    }

    private fun normalizeRadioCandidates(
        candidates: List<YouTubeVideo>,
        seedVideoId: String,
        fallbackQuery: String?,
        maxResults: Int
    ): List<YouTubeVideo> {
        if (candidates.isEmpty()) return emptyList()
        val capped = maxResults.coerceIn(1, 300)
        val seedTokens = fallbackQuery
            .orEmpty()
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
            .toSet()

        val deduped = linkedMapOf<String, Pair<YouTubeVideo, Int>>()
        candidates.forEach { item ->
            val videoId = item.videoId ?: return@forEach
            if (videoId == seedVideoId) return@forEach
            val text = "${item.title} ${item.channelTitle}".lowercase()
            if (RADIO_BLOCKED_HINTS.any { hint -> text.contains(hint) }) return@forEach

            val score = scoreRadioCandidate(item, text, seedTokens)
            if (score < -40) return@forEach
            val current = deduped[videoId]
            if (current == null || score > current.second) {
                deduped[videoId] = item to score
            }
        }

        if (deduped.isEmpty()) {
            return candidates
                .asSequence()
                .filter { !it.videoId.isNullOrBlank() }
                .filter { it.videoId != seedVideoId }
                .distinctBy { it.videoId ?: it.id }
                .take(capped)
                .toList()
        }

        val orderLookup = candidates.mapIndexed { index, candidate ->
            (candidate.videoId ?: candidate.id) to index
        }.toMap()

        return deduped
            .values
            .sortedWith(
                compareByDescending<Pair<YouTubeVideo, Int>> { it.second }
                    .thenBy { pair ->
                        orderLookup[pair.first.videoId ?: pair.first.id] ?: Int.MAX_VALUE
                    }
            )
            .map { it.first }
            .take(capped)
    }

    private fun scoreRadioCandidate(
        item: YouTubeVideo,
        lowerText: String,
        seedTokens: Set<String>
    ): Int {
        var score = 0
        score += when (item.type) {
            YouTubeItemType.SONG -> 140
            YouTubeItemType.UNKNOWN -> 40
            YouTubeItemType.VIDEO -> -120
            else -> -80
        }

        val section = item.sectionTitle.orEmpty().lowercase()
        if (section.contains("radio") || section.contains("mix") || section.contains("up next")) {
            score += 45
        }
        if (section.contains("video")) score -= 60
        if (item.sourcePlaylistId?.startsWith("RD") == true) score += 15

        if (seedTokens.isNotEmpty()) {
            val matches = seedTokens.count { token -> lowerText.contains(token) }
            score += (matches.coerceAtMost(4) * 8)
        }

        return score
    }

    private suspend fun fetchRadioCandidatesInternal(
        endpoint: RadioWatchEndpoint,
        fallbackQuery: String?,
        maxResults: Int,
        depth: Int,
        visited: MutableSet<String>
    ): List<YouTubeVideo> {
        if ((endpoint.videoId.isBlank() && endpoint.playlistId.isNullOrBlank()) || depth > 2) return emptyList()
        val endpointKey = listOf(
            endpoint.videoId,
            endpoint.playlistId.orEmpty(),
            endpoint.playlistSetVideoId.orEmpty(),
            endpoint.params.orEmpty(),
            endpoint.index?.toString().orEmpty()
        ).joinToString("|")
        if (!visited.add(endpointKey)) return emptyList()

        val cappedResults = maxResults.coerceIn(1, 300)
        val collected = mutableListOf<YouTubeVideo>()
        val seen = hashSetOf<String>()
        val visitedContinuations = hashSetOf<String>()
        var continuation: String? = null
        var lastRoot: JSONObject? = null
        var page = 0
        while (page < 5) {
            val root = postNext(endpoint, continuation) ?: break
            lastRoot = root
            page += 1

            val pageItems = (parseShelfItems(root) + parsePlaylistPanelItems(root))
                .filter { !it.videoId.isNullOrBlank() }

            pageItems.forEach { candidate ->
                val key = candidate.videoId ?: candidate.id
                if (seen.add(key)) {
                    collected += candidate
                }
            }
            if (collected.size >= cappedResults) break

            continuation = extractPlaylistPanelContinuation(root)
                ?.takeIf { visitedContinuations.add(it) }
                ?: break
        }

        val automixEndpoint = lastRoot?.let(::extractAutomixWatchEndpoint)
        if (automixEndpoint != null && collected.size < cappedResults) {
            val automix = fetchRadioCandidatesInternal(
                endpoint = automixEndpoint,
                fallbackQuery = fallbackQuery,
                maxResults = cappedResults - collected.size,
                depth = depth + 1,
                visited = visited
            )
            automix.forEach { candidate ->
                val key = candidate.videoId ?: candidate.id
                if (seen.add(key)) collected += candidate
            }
        }

        if (collected.isNotEmpty()) {
            return collected.take(cappedResults)
        }

        val query = fallbackQuery?.trim().orEmpty()
        if (query.isEmpty()) return emptyList()
        return searchMusicVideos(
            query = query,
            maxResults = maxResults.coerceIn(1, 100),
            filter = YouTubeSearchFilter.SONGS
        )
            .filter { !it.videoId.isNullOrBlank() }
            .distinctBy { it.videoId ?: it.id }
    }

    private suspend fun postNext(endpoint: RadioWatchEndpoint, continuation: String?): JSONObject? {
        val body = JSONObject()
            .put("context", contextObject())
            .put("isAudioOnly", true)
            .apply {
                continuation?.takeIf { it.isNotBlank() }?.let { put("continuation", it) }
                if (continuation.isNullOrBlank()) {
                    endpoint.videoId.takeIf { it.isNotBlank() }?.let { put("videoId", it) }
                    endpoint.playlistId?.takeIf { it.isNotBlank() }?.let { put("playlistId", it) }
                    endpoint.playlistSetVideoId?.takeIf { it.isNotBlank() }
                        ?.let { put("playlistSetVideoId", it) }
                    endpoint.params?.takeIf { it.isNotBlank() }?.let { put("params", it) }
                    endpoint.index?.let { put("index", it) }
                }
            }
        return post("next", body)
    }

    private fun extractPlaylistPanelContinuation(root: JSONObject): String? {
        // Prefer known YouTube Music continuation locations first (more stable than deep recursive scan).
        val prioritized = listOfNotNull(
            root.optJSONObject("continuationContents")
                ?.optJSONObject("musicShelfContinuation")
                ?.optJSONArray("continuations"),
            root.optJSONObject("continuationContents")
                ?.optJSONObject("sectionListContinuation")
                ?.optJSONArray("continuations"),
            root.optJSONObject("continuationContents")
                ?.optJSONObject("playlistPanelContinuation")
                ?.optJSONArray("continuations"),
            root.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("continuations"),
            root.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("continuations")
        )

        prioritized.forEach { continuations ->
            extractContinuationToken(continuations)?.let { return it }
        }
        return findContinuationRecursively(root)
    }

    private fun findContinuationRecursively(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONArray("continuations")?.let { continuations ->
                    extractContinuationToken(continuations)?.let { return it }
                }

                val iterator = node.keys()
                while (iterator.hasNext()) {
                    findContinuationRecursively(node.opt(iterator.next()))?.let { return it }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findContinuationRecursively(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractContinuationToken(continuations: JSONArray): String? {
        for (i in 0 until continuations.length()) {
            val continuationObject = continuations.optJSONObject(i)
            val candidates = listOfNotNull(
                continuationObject?.optJSONObject("nextContinuationData")
                    ?.optString("continuation", "")
                    ?.trim(),
                continuationObject?.optJSONObject("reloadContinuationData")
                    ?.optString("continuation", "")
                    ?.trim()
            )
            candidates.firstOrNull { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun parsePlaylistPanelItems(root: JSONObject): List<YouTubeVideo> {
        val out = mutableListOf<YouTubeVideo>()
        collectPlaylistPanelRenderers(root, out)
        return out
    }

    private fun collectPlaylistPanelRenderers(node: Any?, out: MutableList<YouTubeVideo>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("playlistPanelVideoRenderer")?.let { panel ->
                    val videoId = panel.optString("videoId", "").trim()
                    if (videoId.isNotEmpty()) {
                        val title = panel.optJSONObject("title")
                            ?.optJSONArray("runs")
                            ?.let(::joinRunsText)
                            ?.ifBlank { null }
                            ?: "YouTube Song"
                        val artist = panel.optJSONObject("shortBylineText")
                            ?.optJSONArray("runs")
                            ?.let(::joinRunsText)
                            ?.ifBlank { null }
                            ?: "YouTube Music"
                        val thumb = panel.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                            ?.let { thumbs ->
                                var best: String? = null
                                for (i in 0 until thumbs.length()) {
                                    val candidate =
                                        thumbs.optJSONObject(i)?.optString("url", "")?.trim()
                                    if (!candidate.isNullOrBlank()) best = candidate
                                }
                                best
                            }
                        val watchEndpoint = extractWatchEndpoint(
                            panel.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                        )

                        out += YouTubeVideo(
                            id = "panel:$videoId",
                            title = decodeHtmlEntities(title),
                            channelTitle = decodeHtmlEntities(artist),
                            thumbnailUrl = thumb?.let(::decodeHtmlEntities),
                            sectionTitle = "Radio",
                            type = YouTubeItemType.SONG,
                            videoId = videoId,
                            sourcePlaylistId = watchEndpoint?.playlistId,
                            sourcePlaylistSetVideoId = watchEndpoint?.playlistSetVideoId,
                            sourceParams = watchEndpoint?.params,
                            sourceIndex = watchEndpoint?.index
                        )
                    }
                }

                val iterator = node.keys()
                while (iterator.hasNext()) {
                    collectPlaylistPanelRenderers(node.opt(iterator.next()), out)
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectPlaylistPanelRenderers(node.opt(i), out)
                }
            }
        }
    }

    suspend fun resolveAudioStreamUrl(videoId: String): String? {
        return resolveAudioStreamUrlInternal(videoId, allowCache = true)
    }

    suspend fun resolveAudioStreamUrlFresh(videoId: String): String? {
        return resolveAudioStreamUrlInternal(videoId, allowCache = false)
    }

    private suspend fun resolveAudioStreamUrlInternal(videoId: String, allowCache: Boolean): String? {
        if (videoId.isBlank()) return null
        if (allowCache) {
            getCachedStreamUrl(videoId)?.let { return it }
        }

        // Metrolist-style primary path: extractor-based stream resolution first.
        val extractorCandidate = YouTubeStreamResolver.resolveBestAudioUrl(videoId)
        if (!extractorCandidate.isNullOrBlank() && isStreamPlayable(extractorCandidate)) {
            Log.i(TAG, "Resolved playable stream with extractor primary path for $videoId")
            cacheStreamUrl(videoId, extractorCandidate)
            return extractorCandidate
        }

        val webRemixCandidate = resolveAudioStreamUrlWithClient(
            videoId = videoId,
            context = contextObject(),
            clientName = WEB_REMIX_CLIENT_NAME,
            clientVersion = WEB_REMIX_CLIENT_VERSION,
            clientId = WEB_REMIX_CLIENT_ID,
            userAgent = USER_AGENT_WEB
        )
        if (!webRemixCandidate.isNullOrBlank() && isStreamPlayable(webRemixCandidate)) {
            Log.i(TAG, "Resolved playable stream with WEB_REMIX player for $videoId")
            cacheStreamUrl(videoId, webRemixCandidate)
            return webRemixCandidate
        }

        val androidCandidate = resolveAudioStreamUrlWithClient(
            videoId = videoId,
            context = contextObjectAndroid(),
            clientName = ANDROID_CLIENT_NAME,
            clientVersion = ANDROID_CLIENT_VERSION,
            clientId = ANDROID_CLIENT_ID,
            userAgent = USER_AGENT_ANDROID
        )
        if (!androidCandidate.isNullOrBlank() && isStreamPlayable(androidCandidate)) {
            Log.i(TAG, "Resolved playable stream with ANDROID player for $videoId")
            cacheStreamUrl(videoId, androidCandidate)
            return androidCandidate
        }

        return null
    }

    suspend fun resolveAudioStreamUrlFast(videoId: String): String? {
        if (videoId.isBlank()) return null
        getCachedStreamUrl(videoId)?.let { return it }

        val androidCandidate = resolveAudioStreamUrlWithClient(
            videoId = videoId,
            context = contextObjectAndroid(),
            clientName = ANDROID_CLIENT_NAME,
            clientVersion = ANDROID_CLIENT_VERSION,
            clientId = ANDROID_CLIENT_ID,
            userAgent = USER_AGENT_ANDROID
        )
        if (!androidCandidate.isNullOrBlank() && looksLikeDirectPlayableAudioUrl(androidCandidate)) {
            cacheStreamUrl(videoId, androidCandidate)
            return androidCandidate
        }

        val webRemixCandidate = resolveAudioStreamUrlWithClient(
            videoId = videoId,
            context = contextObject(),
            clientName = WEB_REMIX_CLIENT_NAME,
            clientVersion = WEB_REMIX_CLIENT_VERSION,
            clientId = WEB_REMIX_CLIENT_ID,
            userAgent = USER_AGENT_WEB
        )
        if (!webRemixCandidate.isNullOrBlank() && looksLikeDirectPlayableAudioUrl(webRemixCandidate)) {
            cacheStreamUrl(videoId, webRemixCandidate)
            return webRemixCandidate
        }

        return resolveAudioStreamUrlFresh(videoId)
    }

    fun invalidateCachedStreamUrl(videoId: String) {
        if (videoId.isBlank()) return
        synchronized(streamUrlCache) {
            streamUrlCache.remove(videoId)
        }
        appContext
            ?.getSharedPreferences(STREAM_CACHE_PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove("stream_$videoId")
            ?.apply()
    }

    private fun getCachedStreamUrl(videoId: String): String? {
        val now = System.currentTimeMillis()
        synchronized(streamUrlCache) {
            val memory = streamUrlCache[videoId]
            if (!memory.isNullOrBlank()) return memory
        }

        val prefs = appContext?.getSharedPreferences(STREAM_CACHE_PREFS, Context.MODE_PRIVATE) ?: return null
        val raw = prefs.getString("stream_$videoId", null).orEmpty()
        if (raw.isBlank()) return null
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val expiresAtMs = parsed.optLong("e", 0L)
        val streamUrl = parsed.optString("u", "").trim()
        if (expiresAtMs <= now || streamUrl.isBlank()) {
            prefs.edit().remove("stream_$videoId").apply()
            return null
        }

        synchronized(streamUrlCache) {
            streamUrlCache[videoId] = streamUrl
        }
        return streamUrl
    }

    private fun cacheStreamUrl(videoId: String, streamUrl: String) {
        if (videoId.isBlank() || streamUrl.isBlank()) return
        val expiresAtMs = System.currentTimeMillis() + STREAM_CACHE_TTL_MS
        synchronized(streamUrlCache) {
            streamUrlCache[videoId] = streamUrl
        }
        appContext
            ?.getSharedPreferences(STREAM_CACHE_PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(
                "stream_$videoId",
                JSONObject()
                    .put("u", streamUrl)
                    .put("e", expiresAtMs)
                    .toString()
            )
            ?.apply()
    }

    private suspend fun resolveAudioStreamUrlWithClient(
        videoId: String,
        context: JSONObject,
        clientName: String,
        clientVersion: String,
        clientId: String,
        userAgent: String
    ): String? {
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        val body = JSONObject()
            .put("context", context)
            .put("videoId", videoId)
            .put(
                "playbackContext", JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject()
                        .put("html5Preference", "HTML5_PREF_WANTS")
                        .apply {
                            signatureTimestamp?.let { put("signatureTimestamp", it) }
                        }
                )
            )
            .put("racyCheckOk", true)
            .put("contentCheckOk", true)

        val response = post(
            path = "player",
            body = body,
            clientVersion = clientVersion,
            clientId = clientId,
            userAgent = userAgent
        ) ?: return null

        val playability =
            response.optJSONObject("playabilityStatus")?.optString("status", "UNKNOWN").orEmpty()
        if (playability != "OK") {
            Log.w(TAG, "Player resolve not playable for $videoId using $clientName: $playability")
            return null
        }
        val streamingData = response.optJSONObject("streamingData") ?: return null
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
        val formats = streamingData.optJSONArray("formats") ?: JSONArray()

        val chosen = pickBestAudioFormat(adaptiveFormats, preferMimePrefix = "audio/mp4")
            ?: pickBestAudioFormat(formats, preferMimePrefix = "audio/mp4")
            ?: pickBestAudioFormat(adaptiveFormats)
            ?: pickBestAudioFormat(formats)
            ?: return null

        val directUrl = chosen.optString("url", "").trim()
        if (directUrl.isNotEmpty()) {
            val playableUrl = deobfuscateThrottleUrl(videoId, directUrl) ?: directUrl
            return playableUrl
        }

        val cipher = chosen.optString("signatureCipher", "").trim()
        if (cipher.isNotEmpty()) {
            val params = parseUrlEncodedParams(cipher)
            val cipherUrl = params["url"].orEmpty().trim()
            if (cipherUrl.isNotEmpty()) {
                val sp = params["sp"].orEmpty().ifBlank { "signature" }
                val s = params["s"].orEmpty().trim()
                val candidate = if (s.isNotEmpty()) {
                    buildDecipheredSignatureUrl(
                        videoId = videoId,
                        baseUrl = cipherUrl,
                        sp = sp,
                        signature = s
                    )
                } else {
                    deobfuscateThrottleUrl(videoId, cipherUrl) ?: cipherUrl
                }

                if (!candidate.isNullOrBlank() && looksLikeDirectPlayableAudioUrl(candidate)) {
                    return candidate
                }
            }
        }

        return null
    }

    suspend fun isStreamPlayable(url: String): Boolean {
        if (url.isBlank()) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", USER_AGENT_ANDROID)
                    setRequestProperty("Range", "bytes=0-1")
                    instanceFollowRedirects = true
                }
                try {
                    val code = connection.responseCode
                    code in 200..399
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
        }
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject? {
        return post(
            path = path,
            body = body,
            clientVersion = WEB_REMIX_CLIENT_VERSION,
            clientId = WEB_REMIX_CLIENT_ID,
            userAgent = USER_AGENT_WEB
        )
    }

    private suspend fun post(
        path: String,
        body: JSONObject,
        clientVersion: String,
        clientId: String,
        userAgent: String
    ): JSONObject? {
        return withContext(Dispatchers.IO) {
            val url = URL("$INNER_TUBE_BASE/$path?prettyPrint=false")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Origin", "https://music.youtube.com")
                setRequestProperty("Referer", "https://music.youtube.com/")
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("X-YouTube-Client-Name", clientId)
                setRequestProperty("X-YouTube-Client-Version", clientVersion)
            }
            try {
                connection.outputStream.bufferedWriter(Charsets.UTF_8)
                    .use { it.write(body.toString()) }
                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    Log.w(TAG, "InnerTube $path failed code=${connection.responseCode}")
                    connection.errorStream ?: return@withContext null
                }
                val payload = stream.bufferedReader().use { it.readText() }
                JSONObject(payload)
            } catch (error: Throwable) {
                Log.w(TAG, "InnerTube $path request failed", error)
                null
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun contextObject(): JSONObject {
        return JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", WEB_REMIX_CLIENT_NAME)
                .put("clientVersion", WEB_REMIX_CLIENT_VERSION)
                .put("hl", "en")
                .put("gl", "US")
        )
    }

    private fun contextObjectAndroid(): JSONObject {
        return JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", ANDROID_CLIENT_NAME)
                .put("clientVersion", ANDROID_CLIENT_VERSION)
                .put("androidSdkVersion", 35)
                .put("hl", "en")
                .put("gl", "US")
        )
    }

    private fun parseShelfItems(root: JSONObject): List<YouTubeVideo> {
        val out = mutableListOf<YouTubeVideo>()
        val seen = HashSet<String>()
        collectShelfRenderers(root) { renderer, shelfTitle, shelfBrowseId, shelfBrowseParams ->
            val item = mapRendererToItem(renderer, shelfTitle, shelfBrowseId, shelfBrowseParams)
                ?: return@collectShelfRenderers
            if (seen.add(item.id)) out += item
        }
        return out
    }

    private fun collectShelfRenderers(
        root: JSONObject,
        block: (JSONObject, String?, String?, String?) -> Unit
    ) {
        // Primary response shape for search/browse shelves.
        val contents = root
            .optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")

        if (contents != null) {
            for (i in 0 until contents.length()) {
                val shelf =
                    contents.optJSONObject(i)?.optJSONObject("musicShelfRenderer") ?: continue
                val shelfTitle =
                    shelf.optJSONObject("title")?.optJSONArray("runs")?.let(::joinRunsText)
                val shelfEndpoint = extractShelfBrowseEndpoint(shelf)
                val shelfItems = shelf.optJSONArray("contents") ?: continue
                for (j in 0 until shelfItems.length()) {
                    val renderer = shelfItems.optJSONObject(j)
                        ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    block(renderer, shelfTitle, shelfEndpoint.first, shelfEndpoint.second)
                }
            }
            // Continue with recursive walk for carousel/two-row sections.
        }

        // Browse/continuation shapes can be nested, so fall back to recursive walk.
        collectRenderersRecursively(root, null, null, null, block)
    }

    private fun collectRenderersRecursively(
        node: Any?,
        shelfTitle: String?,
        shelfBrowseId: String?,
        shelfBrowseParams: String?,
        block: (JSONObject, String?, String?, String?) -> Unit
    ) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicShelfRenderer")?.let { shelf ->
                    val title =
                        shelf.optJSONObject("title")?.optJSONArray("runs")?.let(::joinRunsText)
                    val endpoint = extractShelfBrowseEndpoint(shelf)
                    val shelfItems = shelf.optJSONArray("contents")
                    if (shelfItems != null) {
                        for (j in 0 until shelfItems.length()) {
                            val renderer = shelfItems.optJSONObject(j)
                                ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            block(renderer, title, endpoint.first, endpoint.second)
                        }
                    }
                }

                node.optJSONObject("musicCarouselShelfRenderer")?.let { shelf ->
                    val title = shelf.optJSONObject("header")
                        ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                        ?.optJSONObject("title")
                        ?.optJSONArray("runs")
                        ?.let(::joinRunsText)
                    val endpoint = extractShelfBrowseEndpoint(shelf)
                    val carouselItems = shelf.optJSONArray("contents")
                    if (carouselItems != null) {
                        for (j in 0 until carouselItems.length()) {
                            val item = carouselItems.optJSONObject(j) ?: continue
                            item.optJSONObject("musicResponsiveListItemRenderer")?.let { renderer ->
                                block(renderer, title, endpoint.first, endpoint.second)
                            }
                            item.optJSONObject("musicTwoRowItemRenderer")?.let { twoRow ->
                                mapTwoRowRendererToResponsiveLike(twoRow)?.let { mapped ->
                                    block(mapped, title, endpoint.first, endpoint.second)
                                }
                            }
                        }
                    }
                }

                node.optJSONObject("musicResponsiveListItemRenderer")?.let { renderer ->
                    block(renderer, shelfTitle, shelfBrowseId, shelfBrowseParams)
                }

                val iterator = node.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    collectRenderersRecursively(
                        node.opt(key),
                        shelfTitle,
                        shelfBrowseId,
                        shelfBrowseParams,
                        block
                    )
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectRenderersRecursively(
                        node.opt(i),
                        shelfTitle,
                        shelfBrowseId,
                        shelfBrowseParams,
                        block
                    )
                }
            }
        }
    }

    private fun mapTwoRowRendererToResponsiveLike(twoRow: JSONObject): JSONObject? {
        val titleRuns = twoRow
            .optJSONObject("title")
            ?.optJSONArray("runs")
            ?: return null

        val subtitleRuns = twoRow
            .optJSONObject("subtitle")
            ?.optJSONArray("runs")
            ?: JSONArray()

        val nav = twoRow.optJSONObject("navigationEndpoint") ?: JSONObject()
        val thumbSource = twoRow.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?: JSONArray()

        // Build a minimal compatible structure for mapRendererToItem.
        return JSONObject().apply {
            put(
                "flexColumns",
                JSONArray()
                    .put(
                        JSONObject().put(
                            "musicResponsiveListItemFlexColumnRenderer",
                            JSONObject().put("text", JSONObject().put("runs", titleRuns))
                        )
                    )
                    .put(
                        JSONObject().put(
                            "musicResponsiveListItemFlexColumnRenderer",
                            JSONObject().put("text", JSONObject().put("runs", subtitleRuns))
                        )
                    )
            )
            put("navigationEndpoint", nav)
            put(
                "thumbnail",
                JSONObject().put(
                    "musicThumbnailRenderer",
                    JSONObject().put(
                        "thumbnail",
                        JSONObject().put("thumbnails", thumbSource)
                    )
                )
            )
        }
    }

    private fun mapRendererToItem(
        renderer: JSONObject,
        shelfTitle: String?,
        shelfBrowseId: String?,
        shelfBrowseParams: String?
    ): YouTubeVideo? {
        val titleRuns = renderer
            .optJSONArray("flexColumns")
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

        val title = titleRuns?.let(::joinRunsText)?.takeIf { it.isNotBlank() }
            ?: return null

        val secondRuns = renderer
            .optJSONArray("flexColumns")
            ?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

        val videoId = extractVideoId(renderer).ifBlank { null }
        val sourceWatchEndpoint = extractWatchEndpointFromRenderer(renderer)
        val browseEndpoint = extractBrowseEndpoint(renderer, titleRuns)
        val browseId = browseEndpoint.first
        val browseParams = browseEndpoint.second

        val type = detectType(renderer, shelfTitle, videoId != null)
        val subtitle = extractSubtitle(secondRuns, type)
        val thumb = extractThumbnail(renderer)

        val stableId = when {
            videoId != null -> "video:$videoId"
            !browseId.isNullOrBlank() -> "browse:$browseId:${browseParams.orEmpty()}"
            else -> "title:${title.lowercase()}:${subtitle.lowercase()}"
        }

        return YouTubeVideo(
            id = stableId,
            title = decodeHtmlEntities(title),
            channelTitle = decodeHtmlEntities(subtitle),
            thumbnailUrl = thumb,
            sectionTitle = shelfTitle?.let(::decodeHtmlEntities),
            sectionBrowseId = shelfBrowseId,
            sectionBrowseParams = shelfBrowseParams,
            type = type,
            videoId = videoId,
            browseId = browseId,
            browseParams = browseParams,
            sourcePlaylistId = sourceWatchEndpoint?.playlistId,
            sourcePlaylistSetVideoId = sourceWatchEndpoint?.playlistSetVideoId,
            sourceParams = sourceWatchEndpoint?.params,
            sourceIndex = sourceWatchEndpoint?.index
        )
    }

    private fun extractWatchEndpointFromRenderer(renderer: JSONObject): RadioWatchEndpoint? {
        val candidates = listOf(
            renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint"),
            renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchPlaylistEndpoint"),
            renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint"),
            renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchPlaylistEndpoint"),
            renderer.optJSONObject("menu")
                ?.optJSONObject("menuRenderer")
                ?.optJSONArray("items")
                ?.optJSONObject(0)
                ?.optJSONObject("menuNavigationItemRenderer")
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchPlaylistEndpoint")
        )
        return candidates.asSequence()
            .mapNotNull { extractWatchEndpoint(it) }
            .firstOrNull()
    }

    private fun extractWatchEndpoint(raw: JSONObject?): RadioWatchEndpoint? {
        raw ?: return null
        val videoId = raw.optString("videoId", "").trim().ifBlank { null }
        val playlistId = raw.optString("playlistId", "").trim().ifBlank { null }
        val playlistSetVideoId = raw.optString("playlistSetVideoId", "").trim().ifBlank { null }
        val params = raw.optString("params", "").trim().ifBlank { null }
        val index = if (raw.has("index") && !raw.isNull("index")) raw.optInt("index") else null
        if (videoId.isNullOrBlank() && playlistId.isNullOrBlank()) return null
        return RadioWatchEndpoint(
            videoId = videoId.orEmpty(),
            playlistId = playlistId,
            playlistSetVideoId = playlistSetVideoId,
            params = params,
            index = index
        )
    }

    private fun extractAutomixWatchEndpoint(root: JSONObject): RadioWatchEndpoint? {
        return findWatchPlaylistEndpointRecursively(root)
    }

    private fun findWatchPlaylistEndpointRecursively(node: Any?): RadioWatchEndpoint? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("automixPlaylistVideoRenderer")
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchPlaylistEndpoint")
                    ?.let { endpointJson ->
                        extractWatchEndpoint(endpointJson)?.let { return it }
                    }

                val iterator = node.keys()
                while (iterator.hasNext()) {
                    findWatchPlaylistEndpointRecursively(node.opt(iterator.next()))?.let { return it }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findWatchPlaylistEndpointRecursively(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun detectType(
        renderer: JSONObject,
        shelfTitle: String?,
        hasVideoId: Boolean
    ): YouTubeItemType {
        val pageType = renderer
            .optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType", "")
            .orEmpty()

        if (hasVideoId) {
            val mvType = renderer.optString("musicVideoType", "")
            if (mvType == "MUSIC_VIDEO_TYPE_OMV") return YouTubeItemType.VIDEO
            if (mvType == "MUSIC_VIDEO_TYPE_ATV") return YouTubeItemType.SONG
            return when {
                shelfTitle?.contains("video", ignoreCase = true) == true -> YouTubeItemType.VIDEO
                else -> YouTubeItemType.SONG
            }
        }

        return when (pageType) {
            "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL" -> YouTubeItemType.ARTIST
            "MUSIC_PAGE_TYPE_ALBUM" -> YouTubeItemType.ALBUM
            "MUSIC_PAGE_TYPE_PLAYLIST", "MUSIC_PAGE_TYPE_LIBRARY_PLAYLIST" -> YouTubeItemType.PLAYLIST
            else -> when {
                shelfTitle?.contains("artist", ignoreCase = true) == true -> YouTubeItemType.ARTIST
                shelfTitle?.contains("album", ignoreCase = true) == true -> YouTubeItemType.ALBUM
                shelfTitle?.contains(
                    "playlist",
                    ignoreCase = true
                ) == true -> YouTubeItemType.PLAYLIST

                else -> YouTubeItemType.UNKNOWN
            }
        }
    }

    private fun extractSubtitle(secondRuns: JSONArray?, type: YouTubeItemType): String {
        if (secondRuns == null) {
            return when (type) {
                YouTubeItemType.ARTIST -> "Artist"
                YouTubeItemType.ALBUM -> "Album"
                YouTubeItemType.PLAYLIST -> "Playlist"
                YouTubeItemType.SONG -> "Song"
                YouTubeItemType.VIDEO -> "Video"
                else -> "YouTube Music"
            }
        }

        val cleaned = mutableListOf<String>()
        for (i in 0 until secondRuns.length()) {
            val text = secondRuns.optJSONObject(i)?.optString("text", "")?.trim().orEmpty()
            if (text.isEmpty() || isSeparator(text) || looksLikeDuration(text)) continue
            cleaned += text
        }
        if (cleaned.isEmpty()) return "YouTube Music"
        return cleaned.joinToString(" • ")
    }

    private fun extractVideoId(renderer: JSONObject): String {
        val playlistVideoId = renderer
            .optJSONObject("playlistItemData")
            ?.optString("videoId", "")
            .orEmpty()
            .trim()
        if (playlistVideoId.isNotEmpty()) return playlistVideoId

        val overlayVideoId = renderer
            .optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId", "")
            .orEmpty()
            .trim()
        if (overlayVideoId.isNotEmpty()) return overlayVideoId

        return renderer
            .optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId", "")
            .orEmpty()
            .trim()
            .ifBlank {
                renderer
                    .optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchPlaylistEndpoint")
                    ?.optString("videoId", "")
                    .orEmpty()
                    .trim()
            }
    }

    private fun extractBrowseEndpoint(
        renderer: JSONObject,
        titleRuns: JSONArray?
    ): Pair<String?, String?> {
        val fromTitle = titleRuns
            ?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
        if (fromTitle != null) {
            val id = fromTitle.optString("browseId", "").trim()
            if (id.isNotEmpty()) return id to fromTitle.optString("params", "").trim()
                .ifEmpty { null }
        }

        val direct = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        if (direct != null) {
            val id = direct.optString("browseId", "").trim()
            if (id.isNotEmpty()) return id to direct.optString("params", "").trim().ifEmpty { null }
        }

        return null to null
    }

    private fun extractThumbnail(renderer: JSONObject): String? {
        val thumbs = renderer
            .optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?: return null

        for (i in thumbs.length() - 1 downTo 0) {
            val url = thumbs.optJSONObject(i)?.optString("url", "").orEmpty().trim()
            if (url.isNotEmpty()) return url
        }
        return null
    }

    private fun extractShelfBrowseEndpoint(shelfRenderer: JSONObject): Pair<String?, String?> {
        val header = shelfRenderer.optJSONObject("header")
        val basicHeader = header?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
        val endpointFromMoreButton = basicHeader
            ?.optJSONObject("moreContentButton")
            ?.optJSONObject("buttonRenderer")
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")

        val endpointFromTitleRun = basicHeader
            ?.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")

        val endpointFromBottom = shelfRenderer
            .optJSONObject("bottomEndpoint")
            ?.optJSONObject("browseEndpoint")

        val endpoint = endpointFromMoreButton ?: endpointFromTitleRun ?: endpointFromBottom
        val browseId = endpoint?.optString("browseId", "")?.trim().orEmpty()
        val browseParams = endpoint?.optString("params", "")?.trim().orEmpty()
        return browseId.ifBlank { null } to browseParams.ifBlank { null }
    }

    private fun pickBestAudioFormat(
        formats: JSONArray,
        preferMimePrefix: String? = null
    ): JSONObject? {
        var best: JSONObject? = null
        var bestBitrate = -1
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType", "")
            if (!mimeType.startsWith("audio/")) continue
            if (preferMimePrefix != null && !mimeType.startsWith(preferMimePrefix)) continue
            val bitrate = format.optInt("bitrate", 0)
            if (bitrate > bestBitrate) {
                best = format
                bestBitrate = bitrate
            }
        }
        return best
    }

    private fun parseUrlEncodedParams(value: String): Map<String, String> {
        return value.split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                key to v
            }
            .toMap()
    }

    private fun buildDecipheredSignatureUrl(
        videoId: String,
        baseUrl: String,
        sp: String,
        signature: String
    ): String? {
        return runCatching {
            YouTubeStreamResolver.ensureInitializedForCipher()
            val decipheredSignature = YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, signature)
            val separator = if (baseUrl.contains("?")) '&' else '?'
            val withSignature = "$baseUrl$separator${URLEncoder.encode(sp, "UTF-8")}=${URLEncoder.encode(decipheredSignature, "UTF-8")}"
            deobfuscateThrottleUrl(videoId, withSignature) ?: withSignature
        }.getOrNull()
    }

    private fun deobfuscateThrottleUrl(videoId: String, url: String): String? {
        return runCatching {
            YouTubeStreamResolver.ensureInitializedForCipher()
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        }.getOrNull()
    }

    private fun getSignatureTimestampOrNull(videoId: String): Int? {
        return runCatching {
            YouTubeStreamResolver.ensureInitializedForCipher()
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        }.getOrNull()
    }

    private fun looksLikeDirectPlayableAudioUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val normalized = url.lowercase()
        if (!normalized.contains("googlevideo.com")) return false
        val hasAudioHint = normalized.contains("mime=audio") || normalized.contains("mime%3daudio")
        val hasExpiryHint = normalized.contains("expire=") || normalized.contains("ei=")
        return hasAudioHint && hasExpiryHint
    }

    private fun joinRunsText(runs: JSONArray): String {
        val builder = StringBuilder()
        for (i in 0 until runs.length()) {
            builder.append(runs.optJSONObject(i)?.optString("text", "").orEmpty())
        }
        return builder.toString().trim()
    }

    private fun isSeparator(value: String): Boolean {
        return value == "•" || value == "·" || value == "|" || value == "-" || value == "• " || value == " · "
    }

    private fun looksLikeDuration(value: String): Boolean {
        return value.matches(Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$"))
    }

    private fun decodeHtmlEntities(raw: String): String {
        if (raw.isBlank()) return raw
        return HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    private fun parseArtistInfo(root: JSONObject): YouTubeArtistInfo? {
        val header = root.optJSONObject("header") ?: return null
        val description = findTextByKeys(header, setOf("description", "descriptionText"))
        val subscriberCount =
            findTextByKeys(header, setOf("subscriberCountText", "subscriptionsCountText"))
                ?: findTextContaining(header, listOf("subscriber"))
        val monthlyListeners =
            findTextByKeys(header, setOf("monthlyListenerCount", "monthlyListeners"))
                ?: findTextContaining(header, listOf("monthly", "listener"))

        if (description.isNullOrBlank() && subscriberCount.isNullOrBlank() && monthlyListeners.isNullOrBlank()) {
            return null
        }
        return YouTubeArtistInfo(
            description = description?.takeIf { it.isNotBlank() },
            subscriberCount = subscriberCount?.takeIf { it.isNotBlank() },
            monthlyListeners = monthlyListeners?.takeIf { it.isNotBlank() }
        )
    }

    private fun findTextByKeys(node: Any?, keys: Set<String>): String? {
        when (node) {
            is JSONObject -> {
                val iterator = node.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val value = node.opt(key)
                    if (key in keys) {
                        extractText(value)?.let { if (it.isNotBlank()) return it }
                    }
                    findTextByKeys(value, keys)?.let { return it }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findTextByKeys(node.opt(i), keys)?.let { return it }
                }
            }
        }
        return null
    }

    private fun findTextContaining(node: Any?, requiredTerms: List<String>): String? {
        val texts = mutableListOf<String>()
        collectTexts(node, texts)
        return texts.firstOrNull { text ->
            requiredTerms.all { term -> text.contains(term, ignoreCase = true) }
        }
    }

    private fun collectTexts(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                extractText(node)?.let { if (it.isNotBlank()) out += it }
                val iterator = node.keys()
                while (iterator.hasNext()) {
                    collectTexts(node.opt(iterator.next()), out)
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectTexts(node.opt(i), out)
                }
            }
        }
    }

    private fun extractText(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                value.optString("simpleText", "").takeIf { it.isNotBlank() }
                    ?.let(::decodeHtmlEntities)
                    ?: value.optJSONArray("runs")
                        ?.let(::joinRunsText)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::decodeHtmlEntities)
            }

            is JSONArray -> {
                val text = StringBuilder()
                for (i in 0 until value.length()) {
                    val chunk = extractText(value.opt(i)).orEmpty()
                    if (chunk.isNotBlank()) text.append(chunk)
                }
                text.toString().trim().takeIf { it.isNotBlank() }?.let(::decodeHtmlEntities)
            }

            is String -> value.trim().takeIf { it.isNotBlank() }?.let(::decodeHtmlEntities)
            else -> null
        }
    }

    private companion object {
        private const val TAG = "YouTubeApiClient"
        private const val STREAM_CACHE_PREFS = "yt_stream_cache"
        private const val STREAM_CACHE_TTL_MS = 30 * 60 * 1000L
        private const val INNER_TUBE_BASE = "https://music.youtube.com/youtubei/v1"
        private const val WEB_REMIX_CLIENT_ID = "67"
        private const val WEB_REMIX_CLIENT_NAME = "WEB_REMIX"
        private const val WEB_REMIX_CLIENT_VERSION = "1.20260213.01.00"
        private const val ANDROID_CLIENT_ID = "3"
        private const val ANDROID_CLIENT_NAME = "ANDROID"
        private const val ANDROID_CLIENT_VERSION = "21.03.38"
        private const val USER_AGENT_WEB =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        private const val USER_AGENT_ANDROID =
            "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
        private val RADIO_BLOCKED_HINTS = listOf(
            "official music video",
            "music video",
            "lyric video",
            "lyrics video",
            "visualizer"
        )
    }

    private data class RadioWatchEndpoint(
        val videoId: String,
        val playlistId: String? = null,
        val playlistSetVideoId: String? = null,
        val params: String? = null,
        val index: Int? = null
    )
}
