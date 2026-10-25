package com.abu.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Captures the player's internal playback audio (not the microphone) and runs
 * on-device speech recognition with Vosk.
 *
 * The first use downloads the selected language model. Models are supplied by
 * Vosk/Alpha Cephei. The recognition itself runs locally after the model exists.
 */
class LiveCaptionEngine(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onText: (String, Boolean) -> Unit
) {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    fun start(resultCode: Int, data: Intent, language: String) {
        if (running) return
        running = true
        onStatus("جاري تجهيز الذكاء الاصطناعي…")

        executor.execute {
            try {
                val modelDir = ensureModel(language)
                if (!running) return@execute

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        val manager = pm as android.media.projection.MediaProjectionManager
                        projection = manager.getMediaProjection(resultCode, data)
                    } catch (e: Exception) {
                        running = false
                        onStatus("تعذر تفعيل التقاط صوت الفيديو")
                    }
                }

                while (running && projection == null) Thread.sleep(50)
                if (!running) return@execute

                val localProjection = projection ?: throw IllegalStateException("projection=null")
                model = Model(modelDir.absolutePath)
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(localProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                val min = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = maxOf(min * 2, SAMPLE_RATE / 2)

                record = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()

                record?.startRecording()
                onStatus("الترجمة الحية تعمل من صوت الفيديو")

                val buffer = ByteArray(bufferSize)
                while (running) {
                    val n = record?.read(buffer, 0, buffer.size) ?: -1
                    if (n <= 0) continue
                    val r = recognizer ?: break
                    try {
                        if (r.acceptWaveForm(buffer, n)) {
                            val result = JSONObject(r.result)
                            val text = result.optString("text", "").trim()
                            if (text.isNotEmpty()) onText(text, true)
                        } else {
                            val partial = JSONObject(r.partialResult).optString("partial", "").trim()
                            if (partial.isNotEmpty()) onText(partial, false)
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                running = false
                onStatus("تعذر تشغيل الترجمة من صوت الفيديو: ${e.message ?: "خطأ غير معروف"}")
            } finally {
                releaseAudio()
            }
        }
    }

    fun stop() {
        running = false
        executor.execute { releaseAudio() }
    }

    private fun ensureModel(language: String): File {
        val spec = modelSpec(language)
        val root = File(context.filesDir, "live_caption_models")
        val modelDir = File(root, spec.dirName)
        if (File(modelDir, "am").exists() || File(modelDir, "conf").exists()) {
            return modelDir
        }

        root.mkdirs()
        val zipFile = File(root, "${spec.dirName}.zip")
        onStatus("تنزيل نموذج الكلام ${spec.label}…")
        download(spec.url, zipFile)
        onStatus("تجهيز نموذج الكلام…")
        unzip(zipFile, root)

        // Vosk archives normally contain one top-level directory. Rename/move
        // it to our stable directory when necessary.
        if (!modelDir.exists()) {
            val candidate = root.listFiles()
                ?.firstOrNull { it.isDirectory && File(it, "am").exists() }
            if (candidate != null) candidate.renameTo(modelDir)
        }
        zipFile.delete()

        if (!modelDir.exists()) throw IllegalStateException("لم يكتمل تنزيل نموذج اللغة")
        return modelDir
    }

    private data class ModelSpec(val dirName: String, val label: String, val url: String)

    private fun modelSpec(language: String): ModelSpec {
        return when (language.lowercase()) {
            "ar", "ar-sa", "ar-iq" -> ModelSpec(
                "vosk-model-ar-mgb2-0.4",
                "العربية",
                "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"
            )
            "tr", "tr-tr" -> ModelSpec(
                "vosk-model-small-tr-0.3",
                "التركية",
                "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"
            )
            "fa", "fa-ir" -> ModelSpec(
                "vosk-model-small-fa-0.42",
                "الفارسية",
                "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip"
            )
            "fr", "fr-fr" -> ModelSpec(
                "vosk-model-small-fr-0.22",
                "الفرنسية",
                "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
            )
            "de", "de-de" -> ModelSpec(
                "vosk-model-small-de-0.15",
                "الألمانية",
                "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip"
            )
            "es", "es-es" -> ModelSpec(
                "vosk-model-small-es-0.42",
                "الإسبانية",
                "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
            )
            else -> ModelSpec(
                "vosk-model-small-en-us-0.15",
                "الإنجليزية",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
            )
        }
    }

    private fun download(urlString: String, out: File) {
        val c = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            c.connect()
            if (c.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${c.responseCode}")
            }
            BufferedInputStream(c.inputStream).use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        if (!running) throw InterruptedException("stopped")
                        output.write(buf, 0, n)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun unzip(zip: File, target: File) {
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
            var entry: ZipEntry?
            val root = target.canonicalFile
            while (zis.nextEntry.also { entry = it } != null) {
                val e = entry ?: continue
                val dest = File(target, e.name).canonicalFile
                if (!dest.path.startsWith(root.path + File.separator)) {
                    throw SecurityException("Invalid zip entry")
                }
                if (e.isDirectory) {
                    dest.mkdirs()
                } else {
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (zis.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun releaseAudio() {
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { model?.close() } catch (_: Exception) {}
        model = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
    }
}
