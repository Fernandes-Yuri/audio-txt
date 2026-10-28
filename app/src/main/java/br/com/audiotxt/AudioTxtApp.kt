package br.com.audiotxt

import android.app.Application
import android.util.Log
import br.com.audiotxt.data.engine.SherpaOnnxEngine
import br.com.audiotxt.data.engine.SttEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AudioTxtApp : Application() {

    companion object {
        private const val TAG = "AudioTxtApp"
        lateinit var instance: AudioTxtApp
            private set
    }

    val sttEngine: SttEngine by lazy {
        SherpaOnnxEngine(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Pré-carrega o modelo Whisper em background na inicialização do app para eliminar o cold-start
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Pré-aquecendo modelo Whisper em background...")
                sttEngine.initialize()
                Log.d(TAG, "Modelo Whisper pré-carregado e pronto para uso imediato.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao pré-carregar modelo: ${e.message}")
            }
        }
    }
}
