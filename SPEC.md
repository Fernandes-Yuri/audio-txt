# Especificação Técnica (SPEC): Transcritor de Áudio WhatsApp On-Device (Android/Kotlin)

* **Repositório:** [Fernandes-Yuri/audio-txt](https://github.com/Fernandes-Yuri/audio-txt)
* **Local de Trabalho:** `/home/jota1/repos/audio-txt`
* **Linguagem Principal:** Kotlin (Nativo)

## 1. Visão Geral do Produto

### 1.1 Objetivo
Criar um aplicativo Android nativo em **Kotlin**, leve e focado em privacidade, que transcreve mensagens de voz do WhatsApp diretamente no smartphone do usuário, utilizando exclusivamente **recursos de processamento local (on-device)**, sem custos com APIs em nuvem e sem expor dados pessoais do usuário.

### 1.2 Proposta de Valor
* **Zero Fricção (Sem Login):** Sem telas de cadastro, login, senhas ou tokens. O app é 100% utilitário e imediatamente utilizável após a instalação.
* **Privacidade Total:** Áudios não saem do dispositivo.
* **Agilidade no Fluxo:** O usuário seleciona o áudio no WhatsApp, toca em "Compartilhar", seleciona o app e vê o texto transcrito em um *Bottom Sheet* / *Floating Dialog* sem perder o contexto da conversa.
* **Custo Zero:** Sem necessidade de chaves de API pagas ou backend intermediário.

---

## 2. Arquitetura da Solução

```
[ WhatsApp: Áudio (.opus/.ogg) ]
              |
              v (Compartilhar / ACTION_SEND)
[ TranscribeShareActivity (Tema Translúcido) ]
              |
              v
[ AudioDecoder: MediaExtractor + MediaCodec ]
              |
              v (PCM 16kHz Mono)
[ Engine STT On-Device (Sherpa-onnx / Whisper / Vosk) ]
              |
              v (Texto Transcrito)
[ UI Jetpack Compose: ModalBottomSheet ]
              |
      +-------+-------+
      |               |
      v               v
[ Copiar Texto ]  [ Histórico Local Room ]
```

### 2.1 Stack Tecnológica
* **Linguagem:** Kotlin 2.1+
* **UI:** Jetpack Compose + Material 3 (com suporte a Bottom Sheet Modal / Tema translúcido)
* **Arquitetura de Software:** MVVM (Model-View-ViewModel) + Clean Architecture simplificada
* **Decodificação de Áudio:** Android Native `MediaExtractor` + `MediaCodec` (para converter contêiner OGG/Opus em PCM 16-bit 16kHz mono)
* **Motor de Transcrição On-Device (STT):**
  * **Sherpa-onnx** com modelo Whisper Tiny quantizado ou Zipformer PT-BR.
* **Persistência:** Room Database (para histórico local opcional)
* **Injeção de Dependência:** Manual DI / Service Locator leve para evitar overhead de compilação.

---

## 3. Integração com WhatsApp (Share Sheet)

### 3.1 Intent Filters no `AndroidManifest.xml`
O WhatsApp compartilha notas de voz como arquivos OGG com codec Opus (`audio/ogg`, `audio/opus`, `audio/aac`, `audio/mp4` ou genérico `audio/*`).

```xml
<activity
    android:name=".ui.share.TranscribeShareActivity"
    android:theme="@style/Theme.AudioTxt.Translucent"
    android:exported="true">
    
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="audio/*" />
        <data android:mimeType="application/ogg" />
    </intent-filter>
</activity>
```

### 3.2 Extração do Áudio
* O arquivo chega através do extra: `val audioUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)`
* Leitura direta com `contentResolver.openFileDescriptor(audioUri, "r")` ou `contentResolver.openInputStream(audioUri)`.

---

## 4. Pipeline de Processamento de Áudio

1. **Obtenção do URI:** A activity receptora valida o URI recebido.
2. **Decodificação Nativa (Opus -> PCM):**
   * Configuração de `MediaExtractor` apontando para o URI.
   * Seleção da trilha de áudio (`mimeType` `audio/opus` ou `audio/ogg`).
   * Configuração de `MediaCodec` no modo decodificador para emitir amostras lineares PCM.
   * Resampling para 16.000 Hz mono (exigência padrão dos modelos STT).
3. **Inferência STT:**
   * Passagem do buffer de amostras PCM (float array) para o motor Sherpa-onnx.
   * Feedback visual de progresso.
4. **Exibição do Resultado:**
   * Apresentação instantânea do texto com botão de "Copiar para Área de Transferência".

---

## 5. Infraestrutura de Build & CI/CD (GitHub Actions)

* Todo o processo de compilação, lint e geração de APK é delegado ao GitHub Actions (`.github/workflows/android-build.yml`).
* Proibido rodar Gradle localmente no notebook para preservar CPU/RAM e evitar travamentos.
