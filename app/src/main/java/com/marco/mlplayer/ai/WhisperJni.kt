package com.marco.mlplayer.ai

/** Ponte verso whisper.cpp (riconoscimento vocale offline). Solo arm64: altrove [isAvailable] è falso. */
object WhisperJni {
    fun interface Progress { fun onProgress(percent: Int) }

    private val loaded = runCatching { System.loadLibrary("sonarawhisper") }.isSuccess
    val isAvailable: Boolean get() = loaded && runCatching { available() }.getOrDefault(false)

    @JvmStatic private external fun available(): Boolean
    @JvmStatic external fun init(modelPath: String): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun cancel()
    /** Righe "inizio_ms<TAB>fine_ms<TAB>testo". [lang] = codice ("it", "en"…) o "auto". */
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, lang: String, threads: Int, cb: Progress?): String
}
