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
 * Motor STT 100% on-device baseado no modelo Whisper Tiny (multilíngue, otimizado para PT-BR).
 */
class SherpaOnnxEngine(private val context: Context) : SttEngine {

    companion object {
        private const val TAG = "SherpaOnnxEngine"
    }

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (recognizer != null) return@withContext

        // Otimização de threads: em processadores octa-core (comuns no Android), 4 threads
        // utilizam os núcleos de performance sem sobrecarregar os núcleos de eficiência.
        val numCores = Runtime.getRuntime().availableProcessors()
        val optimalThreads = when {
            numCores >= 8 -> 4
            numCores >= 4 -> 3
            else -> 2
        }

        Log.d(TAG, "Inicializando OfflineRecognizer Whisper Tiny com $optimalThreads threads ($numCores núcleos detectados)...")

        val whisper = OfflineWhisperModelConfig().apply {
            encoder = "whisper-tiny/tiny-encoder.int8.onnx"
            decoder = "whisper-tiny/tiny-decoder.int8.onnx"
            language = "pt"
            task = "transcribe"
            tailPaddings = 0 // Evita preenchimento desnecessário para acelerar o processamento
        }

        val modelConfig = OfflineModelConfig().apply {
            this.whisper = whisper
            this.tokens = "whisper-tiny/tiny-tokens.txt"
            this.modelType = "whisper"
            this.numThreads = optimalThreads
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
        Log.d(TAG, "OfflineRecognizer Whisper carregado na memória!")
    }

    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): String = withContext(Dispatchers.Default) {
        if (samples.isEmpty()) return@withContext "Nenhum áudio detectado."

        val currentRecognizer = recognizer ?: throw IllegalStateException("Motor de transcrição não inicializado.")
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Iniciando transcrição de ${samples.size} amostras (~${samples.size / sampleRate}s de áudio)")

        val stream = currentRecognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            currentRecognizer.decode(stream)
            val result = currentRecognizer.getResult(stream)
            val elapsed = System.currentTimeMillis() - startTime
            val text = result.text.trim()
            Log.d(TAG, "Transcrição concluída em ${elapsed}ms: '$text'")

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
        // Mantemos o modelo em cache enquanto a aplicação estiver ativa
    }
}
