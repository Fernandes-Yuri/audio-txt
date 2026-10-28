package br.com.audiotxt.data.engine

interface SttEngine {
    /**
     * Inicializa os recursos do motor STT (carregamento de modelo local em background).
     */
    suspend fun initialize()

    /**
     * Transcreve amostras de áudio em FloatArray (16kHz mono, valores entre -1.0f e 1.0f).
     */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String

    /**
     * Libera recursos de memória nativa.
     */
    fun release()
}
