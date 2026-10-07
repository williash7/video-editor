package com.haessentz.videoeditor.media

object WhisperLib {
    init {
        System.loadLibrary("videojni")
    }

    interface Callback {
        fun onSegment(t0: Long, t1: Long, text: ByteArray)
        fun onProgress(progress: Int)
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)
    external fun setAbort(value: Boolean)
    external fun systemInfo(): String
    external fun transcribe(ptr: Long, pcmPath: String, language: String, threads: Int, maxLen: Int, callback: Callback): Int
}
