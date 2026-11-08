# Partitura (MVP, etapa 1)

App Android (Kotlin + Jetpack Compose) para abrir MusicXML, ver a pauta, tocar com andamento ajustável e loop, transpor e exportar MusicXML, MIDI e WAV.

## Estado real
- **Testado (computador, 14 testes):** modelo, leitura/escrita MusicXML, transposição, validação de compassos, síntese de áudio (andamento sem mudar o tom), WAV e MIDI. Rodar: veja `.github/workflows/build-apk.yml`.
- **Escrito mas NÃO compilado nem testado em aparelho:** a interface Android (`app/`), o player (AudioTrack), abrir/salvar arquivos e o desenho da pauta.
- **Ainda não existe:** OMR (foto/PDF), caneta, editor de notas, teoria, assistente de IA, nuvem, export MP3/PDF/PNG, separação de vozes, volume por instrumento na interface.

## Gerar o APK
**GitHub Actions:** crie um repositório, envie esta pasta, abra a aba Actions > "Build APK" > Run workflow; baixe o artefato `partitura-debug-apk`.
**Android Studio:** abra esta pasta, aguarde o sync e rode `Build > Build APK(s)`. Ou `gradle :app:assembleDebug` (JDK 17 + Android SDK 35).
Instale o `app-debug.apk` no celular (permitir fontes desconhecidas).
Licenças: o APK debug é assinado automaticamente com chave de depuração; release assinado exige sua chave.
