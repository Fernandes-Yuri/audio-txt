package br.com.audiotxt.data.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Decodifica arquivos de áudio (Opus/OGG do WhatsApp, AAC, MP3) para amostras PCM FloatArray (16kHz mono).
 */
class AudioDecoder(private val context: Context) {

    companion object {
        private const val TAG = "AudioDecoder"
        const val TARGET_SAMPLE_RATE = 16000
    }

    suspend fun decodeToFloatArray(audioUri: Uri): FloatArray = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "audio_incoming_${System.currentTimeMillis()}.tmp")

        try {
            // Salva cópia temporária para garantir leitura sequencial sem falhas de seek
            context.contentResolver.openInputStream(audioUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalArgumentException("Não foi possível acessar os bytes do áudio compartilhado.")

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(tempFile.absolutePath)

                var audioTrackIndex = -1
                var inputFormat: MediaFormat? = null

                for (i in 0 until extractor.trackCount) {
                    val trackFormat = extractor.getTrackFormat(i)
                    val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        inputFormat = trackFormat
                        break
                    }
                }

                if (audioTrackIndex < 0 || inputFormat == null) {
                    throw IllegalStateException("Nenhuma faixa de áudio compatível encontrada no arquivo.")
                }

                extractor.selectTrack(audioTrackIndex)
                val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""

                // Opus decoders no Android decodificam nativamente a 48000 Hz
                var actualSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    48000
                }
                var actualChannelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    1
                }

                Log.d(TAG, "Input track mime: $mime, initial sampleRate: $actualSampleRate, channels: $actualChannelCount")

                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                val pcmOutputStream = ByteArrayOutputStream()
                val bufferInfo = MediaCodec.BufferInfo()
                var isEos = false
                val timeoutUs = 5000L

                try {
                    while (!isEos) {
                        val inIndex = codec.dequeueInputBuffer(timeoutUs)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)
                            if (inputBuffer != null) {
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
                        while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                val outputFormat = codec.outputFormat
                                if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                    actualSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                }
                                if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                    actualChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                }
                                Log.d(TAG, "Decoder output format changed: rate=$actualSampleRate, channels=$actualChannelCount")
                            } else if (outIndex >= 0) {
                                val outputBuffer = codec.getOutputBuffer(outIndex)
                                if (outputBuffer != null && bufferInfo.size > 0) {
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                    val chunk = ByteArray(bufferInfo.size)
                                    outputBuffer.get(chunk)
                                    pcmOutputStream.write(chunk)
                                }
                                codec.releaseOutputBuffer(outIndex, false)

                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    isEos = true
                                    break
                                }
                            }
                            outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                        }
                    }
                } finally {
                    try { codec.stop() } catch (_: Exception) {}
                    try { codec.release() } catch (_: Exception) {}
                }

                val rawPcm = pcmOutputStream.toByteArray()
                Log.d(TAG, "Total decoded PCM bytes: ${rawPcm.size}, decoded at ${actualSampleRate}Hz, $actualChannelCount channels")

                if (rawPcm.isEmpty()) {
                    return@withContext FloatArray(0)
                }

                // Converte bytes PCM 16-bit little-endian para ShortArray
                val shortBuffer = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val totalShorts = shortBuffer.remaining()
                val rawShorts = ShortArray(totalShorts)
                shortBuffer.get(rawShorts)

                // Downmixing para mono se o decodificador emitir mais de 1 canal
                val monoShorts = if (actualChannelCount > 1) {
                    val monoLength = totalShorts / actualChannelCount
                    ShortArray(monoLength) { i ->
                        var sum = 0
                        for (ch in 0 until actualChannelCount) {
                            sum += rawShorts[i * actualChannelCount + ch]
                        }
                        (sum / actualChannelCount).toShort()
                    }
                } else {
                    rawShorts
                }

                // Resampling para 16.000 Hz caso o decodificador tenha emitido a 48.000 Hz ou 44.100 Hz
                val resampledShorts = if (actualSampleRate != TARGET_SAMPLE_RATE && actualSampleRate > 0) {
                    Log.d(TAG, "Resampling from ${actualSampleRate}Hz to ${TARGET_SAMPLE_RATE}Hz")
                    resampleLinear(monoShorts, actualSampleRate, TARGET_SAMPLE_RATE)
                } else {
                    monoShorts
                }

                Log.d(TAG, "Final audio samples: ${resampledShorts.size} (duration: ${resampledShorts.size / TARGET_SAMPLE_RATE}s)")

                // Converte para FloatArray [-1.0f, 1.0f]
                val floatSamples = FloatArray(resampledShorts.size) { i ->
                    resampledShorts[i] / 32768.0f
                }

                // Normalização de amplitude (ganho de volume se o áudio estiver muito baixo)
                var maxAmp = 0.0f
                for (s in floatSamples) {
                    val a = abs(s)
                    if (a > maxAmp) maxAmp = a
                }
                if (maxAmp in 0.005f..0.5f) {
                    val gain = 0.85f / maxAmp
                    Log.d(TAG, "Boosting quiet audio: maxAmp=$maxAmp, applying gain=$gain")
                    for (i in floatSamples.indices) {
                        floatSamples[i] *= gain
                    }
                }

                floatSamples
            } finally {
                extractor.release()
            }
        } finally {
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
