package br.com.audiotxt.data.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodifica arquivos de áudio (Opus, OGG, AAC, MP3) recebidos do WhatsApp para amostras PCM FloatArray (16kHz mono).
 */
class AudioDecoder(private val context: Context) {

    companion object {
        const val TARGET_SAMPLE_RATE = 16000
    }

    suspend fun decodeToFloatArray(audioUri: Uri): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        // Salva cópia temporária para garantir leitura sequencial sem falhas de seek no ContentProvider
        val tempFile = File(context.cacheDir, "audio_incoming_${System.currentTimeMillis()}.tmp")

        try {
            context.contentResolver.openInputStream(audioUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalArgumentException("Não foi possível acessar os bytes do áudio compartilhado.")

            extractor.setDataSource(tempFile.absolutePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                throw IllegalStateException("Nenhuma faixa de áudio compatível encontrada no arquivo.")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                TARGET_SAMPLE_RATE
            }
            var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmBytes = ArrayList<Byte>()
            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            val timeoutUs = 5000L

            while (!isEos) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                while (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        for (b in chunk) {
                            pcmBytes.add(b)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEos = true
                        break
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                }
            }

            // Atualiza com formato de saída real após processamento
            try {
                val outFormat = codec.outputFormat
                if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            } catch (_: Exception) {
            }

            codec.stop()
            codec.release()

            if (pcmBytes.isEmpty()) {
                return@withContext FloatArray(0)
            }

            // Converte bytes PCM 16-bit para shorts
            val byteArray = pcmBytes.toByteArray()
            val shortBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val rawShorts = ShortArray(shortBuffer.remaining())
            shortBuffer.get(rawShorts)

            // Converte para mono se for stereo
            val monoShorts = if (channelCount > 1) {
                ShortArray(rawShorts.size / channelCount) { i ->
                    var sum = 0
                    for (ch in 0 until channelCount) {
                        sum += rawShorts[i * channelCount + ch]
                    }
                    (sum / channelCount).toShort()
                }
            } else {
                rawShorts
            }

            // Resampling para TARGET_SAMPLE_RATE (16000 Hz)
            val resampledShorts = if (sampleRate != TARGET_SAMPLE_RATE && sampleRate > 0) {
                resampleLinear(monoShorts, sampleRate, TARGET_SAMPLE_RATE)
            } else {
                monoShorts
            }

            // Normaliza para [-1.0f, 1.0f]
            FloatArray(resampledShorts.size) { i ->
                resampledShorts[i] / 32768.0f
            }
        } finally {
            extractor.release()
            tempFile.delete()
        }
    }

    private fun resampleLinear(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputLength = (input.size / ratio).toInt()
        val output = ShortArray(outputLength)

        for (i in 0 until outputLength) {
            val srcIndex = i * ratio
            val index0 = srcIndex.toInt()
            val index1 = (index0 + 1).coerceAtMost(input.size - 1)
            val frac = (srcIndex - index0).toFloat()
            val sample = (input[index0] * (1.0f - frac) + input[index1] * frac).toInt()
            output[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }
}
