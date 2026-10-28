package br.com.audiotxt.data.engine

import android.content.Context
import android.util.Log
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

    companion object {
        private const val TAG = "SherpaOnnxEngine"
    }

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (recognizer != null) return@withContext

        Log.d(TAG, "Inicializando OfflineRecognizer com Whisper Tiny...")

        val whisper = OfflineWhisperModelConfig().apply {
            encoder = "whisper-tiny/tiny-encoder.int8.onnx"
            decoder = "whisper-tiny/tiny-decoder.int8.onnx"
            language = "pt"
            task = "transcribe"
            tailPaddings = 1000
        }

        val modelConfig = OfflineModelConfig().apply {
            this.whisper = whisper
            this.tokens = "whisper-tiny/tiny-tokens.txt"
            this.modelType = "whisper"
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
        Log.d(TAG, "OfflineRecognizer inicializado com sucesso!")
    }

    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): String = withContext(Dispatchers.Default) {
        if (samples.isEmpty()) return@withContext "Nenhum áudio detectado."

        val currentRecognizer = recognizer ?: throw IllegalStateException("Motor de transcrição não inicializado.")
        Log.d(TAG, "Iniciando decodificação de ${samples.size} amostras a ${sampleRate}Hz")

        val stream = currentRecognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            currentRecognizer.decode(stream)
            val result = currentRecognizer.getResult(stream)
            var text = result.text.trim()
            Log.d(TAG, "Resultado da transcrição primária: '$text'")

            // Se retornar vazio, tenta auto-detecção de idioma como fallback
            if (text.isEmpty()) {
                Log.d(TAG, "Texto vazio com language=pt, tentando fallback auto-detect...")
                val fallbackWhisper = OfflineWhisperModelConfig().apply {
                    encoder = "whisper-tiny/tiny-encoder.int8.onnx"
                    decoder = "whisper-tiny/tiny-decoder.int8.onnx"
                    language = "" // Auto-detect
                    task = "transcribe"
                    tailPaddings = 1000
                }
                val fallbackModelConfig = OfflineModelConfig().apply {
                    this.whisper = fallbackWhisper
                    this.tokens = "whisper-tiny/tiny-tokens.txt"
                    this.modelType = "whisper"
                    this.numThreads = 2
                    this.provider = "cpu"
                }
                val fallbackConfig = OfflineRecognizerConfig().apply {
                    this.modelConfig = fallbackModelConfig
                    this.decodingMethod = "greedy_search"
                }
                val fallbackRecognizer = OfflineRecognizer(context.assets, fallbackConfig)
                val fallbackStream = fallbackRecognizer.createStream()
                try {
                    fallbackStream.acceptWaveform(samples, sampleRate)
                    fallbackRecognizer.decode(fallbackStream)
                    val fallbackResult = fallbackRecognizer.getResult(fallbackStream)
                    text = fallbackResult.text.trim()
                    Log.d(TAG, "Resultado fallback auto-detect: '$text'")
                } finally {
                    fallbackStream.release()
                    fallbackRecognizer.release()
                }
            }

            if (text.isEmpty()) {
                "Áudio processado, mas nenhuma palavra foi identificada com clareza."
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
