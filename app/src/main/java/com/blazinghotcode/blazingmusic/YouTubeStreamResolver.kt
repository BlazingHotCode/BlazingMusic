package com.blazinghotcode.blazingmusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Resolver for direct playable YouTube audio stream URLs using NewPipe Extractor.
 * This follows the same strategy used by Metrolist to avoid frequent 403s from raw InnerTube URLs.
 */
object YouTubeStreamResolver {
    private val initialized = AtomicBoolean(false)
    private val downloader = OkHttpNewPipeDownloader()

    suspend fun resolveBestAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null
        ensureInitialized()
        runCatching {
            val streamInfo = StreamInfo.getInfo(NewPipe.getService(0), "https://www.youtube.com/watch?v=$videoId")
            selectBestAudio(streamInfo.audioStreams)
        }.getOrNull()
    }

    fun ensureInitializedForCipher(): Unit = ensureInitialized()

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(downloader)
        }
    }

    private fun selectBestAudio(audioStreams: List<AudioStream>): String? {
        if (audioStreams.isEmpty()) return null
        val preferred = audioStreams
            .filter { it.content.isNotBlank() }
            .sortedWith(
                compareByDescending<AudioStream> { it.averageBitrate }
                    .thenByDescending { it.bitrate }
            )
        return preferred.firstOrNull()?.content
    }

    private class OkHttpNewPipeDownloader : Downloader() {
        private val client = OkHttpClient.Builder().build()

        @Throws(IOException::class, ReCaptchaException::class)
        override fun execute(request: Request): Response {
            val requestBuilder = okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .addHeader("User-Agent", USER_AGENT_WEB)

            request.headers().forEach { (name, values) ->
                requestBuilder.removeHeader(name)
                values.forEach { value -> requestBuilder.addHeader(name, value) }
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha challenge requested", request.url())
            }

            val responseBody = response.body?.string().orEmpty()
            val latestUrl = response.request.url.toString()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                latestUrl
            )
        }
    }

    private const val USER_AGENT_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
}
