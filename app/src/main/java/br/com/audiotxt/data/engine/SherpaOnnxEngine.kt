package br.com.audiotxt.data.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Motor STT 100% on-device baseado no modelo Whisper Tiny (multilíngue, configurado para PT-BR).
 */
class SherpaOnnxEngine(private val context: Context) : SttEngine {

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (recognizer != null) return@withContext

        val whisper = OfflineWhisperModelConfig().apply {
            encoder = "whisper-tiny/tiny-encoder.int8.onnx"
            decoder = "whisper-tiny/tiny-decoder.int8.onnx"
            language = "pt"
            task = "transcribe"
        }

        val modelConfig = OfflineModelConfig().apply {
            this.whisper = whisper
            this.tokens = "whisper-tiny/tiny-tokens.txt"
            this.numThreads = 2
            this.debug = false
            this.provider = "cpu"
        }

        val recognizerConfig = OfflineRecognizerConfig().apply {
            this.modelConfig = modelConfig
            this.decodingMethod = "greedy_search"
        }

        recognizer = OfflineRecognizer(
            assetManager = context.assets,
            config = recognizerConfig
        )
    }

    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): String = withContext(Dispatchers.Default) {
        if (samples.isEmpty()) return@withContext "Nenhum áudio detectado."

        val currentRecognizer = recognizer ?: throw IllegalStateException("Motor de transcrição não inicializado.")
        val stream = currentRecognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            currentRecognizer.decode(stream)
            val result = currentRecognizer.getResult(stream)
            val text = result.text.trim()
            if (text.isEmpty()) {
                "Áudio processado, mas nenhuma palavra foi identificada."
            } else {
                text
            }
        } finally {
            stream.release()
        }
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
    }
}
