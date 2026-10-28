# audio-txt 🎙️ ➡️ 📝

Aplicativo Android nativo em **Kotlin** para conversão rápida de áudios (mensagens de voz do WhatsApp e mensageiros) em texto, operando **100% no próprio dispositivo (on-device)**.

---

## ⚡ Destaques

- **Zero Fricção (Sem Login):** Sem contas, senhas ou termos de cadastro. Abra ou compartilhe e use imediatamente.
- **Privacidade Total:** As mensagens de voz não são enviadas para servidores externos. O processamento acontece localmente usando modelos de inteligência artificial otimizados para smartphones.
- **Integração com WhatsApp:** Selecione qualquer áudio em uma conversa, toque em *Compartilhar*, escolha o **audio-txt** e receba a transcrição instantaneamente em uma janela flutuante, sem perder o contexto do bate-papo.
- **Copiar com 1 Toque:** Botão rápido para copiar o texto transcrito diretamente para a área de transferência.

---

## 🛠️ Tecnologias Utilizadas

- **Linguagem:** Kotlin 2.1+
- **Interface:** Jetpack Compose & Material 3
- **Decodificação de Áudio:** Android Native `MediaExtractor` + `MediaCodec` (Opus/OGG para PCM 16kHz)
- **Motor STT On-Device:** [Sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (suporte a modelos Whisper / Zipformer offline)
- **CI/CD:** GitHub Actions para compilação e empacotamento automatizado do APK

---

## 📦 Como Baixar o APK

Como as compilações são executadas exclusivamente nos servidores do GitHub Actions:

1. Acesse a aba **[Actions](../../actions)** deste repositório.
2. Selecione a última execução do workflow **Build APK**.
3. Baixe o artefato `audio-txt-debug-apk` gerado.
4. Ou utilize o GitHub CLI no terminal:
   ```bash
   gh run download <run-id> -n audio-txt-debug-apk
   adb install -r app-debug.apk
   ```

---

## 📋 Documentação Técnica

Para detalhes sobre arquitetura, pipeline de processamento e requisitos, consulte o documento:
* [SPEC.md](SPEC.md)
