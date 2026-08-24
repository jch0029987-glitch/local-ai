# LocalAI for Android

A 100% offline, privacy-first local AI execution environment built for Android. Run GGUF and LiteRT-LM models natively on your device utilizing full hardware acceleration without any cloud dependency, telemetry, or data leakage.

## ✨ Features

- **True Offline Execution:** Run state-of-the-art small language models entirely on-device without hitting external cloud servers.
- **Dual Engine Support:** Native runtime bindings for both **GGUF** (via LlamaKotlin) and **LiteRT-LM / TFLite**.
- **Hugging Face Support:** Built-in flexibility allowing users to configure target model URLs directly from Hugging Face repositories or import custom weights.
- **Local Persistence & Sessions:** Chat history, separate chat threads, and session states are securely stored locally using **Room Database**.
- **Interactive Onboarding & Setup:** Clean multi-page walkthrough on first launch with configuration options for offline storage or target model URL downloads.
- **File Picker Integration:** Seamlessly import custom `.gguf`, `.litertlm`, or `.tflite` model files directly from device storage.
- **Material 3 UI & Drawer Navigation:** Clean modern layout featuring an expandable chat history drawer, custom system prompt support, and real-time generation status feedback.

---

## Notes 
- ** This was built with the help of Google Gemini Most of the Ideas are my own the code was review somewhat because I have little 
knowledge of kotlin and java. Thank you everyone for understanding
