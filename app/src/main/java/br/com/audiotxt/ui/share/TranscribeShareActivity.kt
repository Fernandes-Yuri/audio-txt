package br.com.audiotxt.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import br.com.audiotxt.data.audio.AudioDecoder
import br.com.audiotxt.data.engine.SherpaOnnxEngine
import br.com.audiotxt.data.engine.SttEngine
import kotlinx.coroutines.launch

class TranscribeShareActivity : ComponentActivity() {

    private lateinit var audioDecoder: AudioDecoder
    private lateinit var sttEngine: SttEngine
    private var uiState by mutableStateOf<TranscriptionUiState>(TranscriptionUiState.Idle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioDecoder = AudioDecoder(applicationContext)
        sttEngine = SherpaOnnxEngine(applicationContext)

        setContent {
            ShareBottomSheetContent(
                uiState = uiState,
                onCopy = { text -> copyToClipboard(text) },
                onShare = { text -> shareText(text) },
                onClose = { finish() }
            )
        }

        processIncomingAudio(intent)
    }

    private fun processIncomingAudio(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) {
            uiState = TranscriptionUiState.Error("Nenhum áudio compartilhado com o audio-txt.")
            return
        }

        val audioUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: intent.clipData?.getItemAt(0)?.uri ?: intent.data

        if (audioUri == null) {
            uiState = TranscriptionUiState.Error("Não foi possível acessar o áudio compartilhado.")
            return
        }

        lifecycleScope.launch {
            try {
                uiState = TranscriptionUiState.Loading("Decodificando áudio...")
                val samples = audioDecoder.decodeToFloatArray(audioUri)
                val durationSec = (samples.size / 16000)

                uiState = TranscriptionUiState.Loading("Preparando inteligência no aparelho...")
                sttEngine.initialize()

                uiState = TranscriptionUiState.Loading("Transcrevendo áudio (${durationSec}s)...")
                val text = sttEngine.transcribe(samples)

                uiState = TranscriptionUiState.Success(text, durationSec)
            } catch (e: Exception) {
                uiState = TranscriptionUiState.Error("Falha na transcrição: ${e.localizedMessage ?: "Erro desconhecido"}")
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Áudio Transcrito", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Texto copiado para a área de transferência!", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Compartilhar texto transcrito")
        startActivity(shareIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        sttEngine.release()
    }
}
