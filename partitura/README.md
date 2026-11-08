# Compasso (Android, Kotlin + Jetpack Compose)

Aplicativo de partituras para celular e tablet. Funciona offline.

## O que o app faz hoje (v0.4)
- **Abrir**: MusicXML (.musicxml, .xml, .mxl), inclusive arquivos do MuseScore/Finale com piano de duas pautas.
  PDF e foto: pelo servidor de leitura em `omr-server/` (ver README dele).
- **Estúdio**: pauta com notação de verdade (fonte Bravura: clave de Sol e Fá, vigas, ligaduras, acidentes, armadura, compasso),
  reprodução com cursor que acompanha a música, rolagem automática e **piano embaixo** mostrando as notas tocando.
  Andamento sem mudar o tom, repetição, trecho, volume por instrumento. Edição: tocar na nota, teclado de piano, duração, acidentes,
  ligadura, pausa, inserir/apagar, compassos, desfazer/refazer, e "escrever tocando na pauta" (dedo ou caneta).
- **Instrumentos**: piano (2 pautas), violão, baixo, órgão, violino, violoncelo, flauta, clarinete, trompete, trombone e vozes. Cada um com timbre
  sintetizado próprio (decaem ou sustentam), nome na pauta, volume e remoção. Salvos no MusicXML (programa MIDI) e no MIDI exportado.
- **Escrever música inteira**: nova partitura de até 400 compassos, que cresce sozinha ao escrever; as teclas do piano escrevem com a duração
  escolhida, avançam e ligam notas que passam do compasso; salvamento automático e "Continuar de onde parei".
- **Regência**: gesto da mão direita animado (1 a 6 tempos, compassos compostos em 2/3/4, padrão genérico acima disso), com metrônomo ou
  seguindo a música tocando.
- **Servidor**: tela para configurar, testar e aprender a colocar no ar o servidor de leitura de PDF/foto.
- **Transpor**: por semitons, por tonalidade ou instrumento transpositor, com prévia; aplicar à partitura.
- **Teoria**: círculo das quintas interativo (gira, mostra os graus I IV V ii iii vi vii°), escalas (8 tipos), acordes, campo harmônico,
  tudo com piano e áudio.
- **Arquivos**: salvar projetos; exportar MusicXML, MIDI, WAV, PNG, PDF.

## Ainda não existe
Importar MIDI, exportar MP3, reconhecimento de escrita à mão, assistente de IA, gravações de instrumentos reais (o som é sintetizado),
pressão da caneta.

## Estrutura
- `core/`: lógica sem Android (modelo, MusicXML, transposição, teoria, edição, síntese, gravação da pauta). Testada com `core/src/test`.
- `app/`: interface Compose. O código de `core/` é compilado dentro do app.
- `omr-server/`: servidor Docker de leitura de PDF/foto (homr).

## Compilar
O GitHub Actions (`.github/workflows/build-apk.yml`) roda os testes do núcleo, gera o APK e publica em **Releases → Partitura - APK mais recente**.
Localmente: `gradle :app:assembleDebug` (Android SDK 35, JDK 17).
A assinatura de debug é fixa (`app/debug.keystore`), então novas versões instalam por cima da anterior.

## Licenças
Bravura (SIL OFL 1.1) em `app/src/main/assets`. O servidor OMR usa homr (AGPL-3.0).
