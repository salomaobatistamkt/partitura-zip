# Partitura 0.2

App Android (Kotlin + Jetpack Compose) para tablet e celular. Seis áreas: Início, Editor, Reprodução, Transposição, Teoria e Arquivos.

## O que existe
- **Abrir**: MusicXML (.musicxml, .xml, .mxl) e 3 partituras de exemplo; projetos salvos no aparelho; novo projeto (tonalidade, compasso, nº de compassos).
- **Editor**: pauta com clave de Sol e Fá, armadura, fórmula de compasso, acidentes; toque numa nota para selecionar; teclado virtual que grava a nota e avança; subir/descer semitom ou grau; ♯ ♭ ♮; duração e ponto; nota/pausa; ligadura; inserir/apagar; adicionar/remover compasso; desfazer/refazer; aviso de compasso incompleto; salvar.
- **Reprodução**: tocar/parar, loop, andamento 10–240 bpm sem mudar o tom (atalhos 0,2x a 1,5x), trecho por compassos, volume por instrumento.
- **Transposição**: por semitons, para uma tonalidade, instrumentos em Si♭/Mi♭/Fá, com prévia e opção de aplicar (desfazível).
- **Teoria**: escalas (8 tipos), acordes (7 tipos), campo harmônico (tríades e sétimas), ciclo das quintas; ouvir e abrir no editor.
- **Arquivos**: exportar MusicXML, MIDI, WAV, PNG e PDF.
- Tema escuro e claro.

## O que NÃO existe ainda
Leitura por foto/PDF/câmera (OMR, impresso ou manuscrito), escrita com caneta, assistente de IA, nuvem, exportar MP3, importar MIDI, separação automática de vozes. O som é um sintetizador simples. A pauta mostra cada instrumento em sua própria linha (sem alinhar compassos entre instrumentos) e não desenha vigas, só colchetes.

## Testes
- Núcleo (lógica, sem Android): 44 testes automáticos, rodados no GitHub Actions antes de compilar (veja `.github/workflows`).
- Interface Android: **não foi compilada nem executada pelo autor**. Se a compilação falhar, o log do GitHub Actions mostra o erro.

## Gerar o APK
GitHub: envie esta pasta, aba Actions > "Build APK" > baixe o artefato `partitura-debug-apk`. Ou Android Studio: abra a pasta e Build > Build APK.
