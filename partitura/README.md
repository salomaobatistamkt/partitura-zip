# Compasso (Android, Kotlin + Jetpack Compose)

Aplicativo de partituras para celular e tablet. Funciona offline.

## Novidades da v0.16
- **Letra do PDF por sílabas, corrigida:** o leitor só procurava a letra abaixo de cada pauta e perdia a linha de texto entre duas pautas do coro (soprano/contralto) e o texto acima da primeira pauta. Agora ele atribui a mesma linha às pautas do mesmo grupo. Nos 14 PDFs vetoriais de teste, as notas com sílaba foram de ~1.800 para ~6.800 (a maior parte das notas sem letra são pausas, vocalises, pautas em uníssono ou piano). Cada sílaba fica numa nota, marcada como início/meio/fim de palavra. Só o verso 1 é lido; se houver mais versos, o app avisa.
- **Limite real:** PDF «Microsoft Print to PDF» ou escaneado traz o texto como desenho/imagem, sem texto pesquisável. O app não tem OCR no aparelho; nesses casos avisa que a letra não foi lida (o Hino de Louvor é assim).
- **Menos texto solto:** explicações longas ficam em seções recolhidas («Tons, semitons e dedilhado», «Passo a passo…», «Como calibrar»), que abrem com animação.
- **Animações:** troca de tela com fade, menu lateral que recolhe suavemente, teclas do piano com degradê, cantos arredondados e brilho que acende e apaga ao tocar.
- Não testado no tablet.

## Novidades da v0.16
- **Letra do PDF por sílabas, corrigida:** o leitor só procurava o texto «abaixo» de cada pauta e perdia a linha de letra que fica entre duas pautas do coro (soprano/contralto) ou acima da primeira pauta. Em 14 PDFs digitais de coro, as notas com sílaba foram de ~1.800 para ~6.800 (a maioria das notas cantadas; pausas e vocalises ficam sem letra). Cada nota recebe a sílaba e a posição (início, meio, fim de palavra). O resultado avisa quantas sílabas foram lidas e se há outros versos que ainda não são lidos.
- **Limite importante:** PDF gerado por «Imprimir como PDF» (letras e notas viradas em desenho, sem texto) e PDF escaneado não têm texto para ler; o app avisa que a letra NÃO foi lida. Falta um reconhecimento de caracteres para esses casos.
- **Tela mais limpa:** explicações, passo a passo, dedilhado e erros comuns ficam em blocos que abrem e fecham com animação.
- **Teclado:** tecla afunda ao tocar, brilho que apaga aos poucos, bordas, sombra e reflexo nas pretas.

## Novidades da v0.15
- **Identificar acorde (Instrumentos → Piano e Violão):** toque nas teclas (ou numa casa de cada corda) e o app anota as notas e diz o acorde (ex.: Dó–Mi–Sol = C; Mi–Sol–Dó = C/E, 1ª inversão), listando também as outras leituras possíveis. Precisa de 3 notas diferentes.
- **Gravar e avaliar (Aprender):** em cada exercício, o microfone ouve você tocar e o app compara nota por nota com a partitura: % de acerto, notas que faltaram ou vieram a mais, oitava errada, regularidade do ritmo e andamento realmente tocado. Se ouvir poucas notas, não dá nota. Só alturas e tempos são avaliados (não dinâmica, pedal nem dedilhado).
- **Ranking pessoal (só neste aparelho):** pontos = acerto × nível × (andamento tocado ÷ meta); conta a melhor tentativa de cada exercício. Não há ranking online.
- **Nova aba Leitura:** 10 perguntas por rodada, com % no fim: nota na clave de Sol, na de Fá, nas duas (com ou sem linhas suplementares) e valor das figuras e pausas (1 tempo, ½, ¼, 1½…).
- **Mais conteúdo:** arpejos de menor, diminuto, aumentado, 7, 7M, m7, add9 e 9, mais inversões; arpejo diatônico (Dó maior e outros tons); preenchimentos entre os acordes de I–V–vi–IV em 12 tons (escala subindo, descendo, aproximação cromática) e frase de ligação 1–5–8–9–10.
- **Removido:** a seção de flauta doce.
- Limites: preenchimentos e frases foram montados por regras de teoria, não copiados de um método publicado; sem dedilhado afirmado onde não há certeza. O CI só prova compilação e testes do núcleo; microfone, telas e desempenho **não foram testados no tablet**.

## Novidades da v0.13
- **Nova aba Instrumentos (Aprender):** *Piano* (escalas maiores/menores/modos com mão direita roxa e esquerda vermelha no teclado, tons e semitons, digitação só onde é segura; dicionário de 15 tipos de acorde com inversões; toque numa tecla e veja nome e frequência), *Violão* (braço com 19 casas e todas as notas, escolha uma nota e veja onde ela está; dicionário de acordes com formas geradas por cálculo, não digitadas), *Flauta doce* soprano (dedilhado barroco, Dó/Ré normal e oitavado).
- **Exercícios da sua música:** a partir do tom e das cifras da partitura aberta, o app gera 8 exercícios de piano em duas claves (escala, oitavas, cinco dedos, saltos de oitava, arpejos, acompanhamento, baixo de Alberti, cadência). Abrem no Estúdio.
- **Cores na execução:** melodia amarela, mão direita roxa, mão esquerda vermelha (partitura e teclado). Notas repetidas agora apagam um instante antes de acabar, então o mesmo Fá tocado duas vezes pisca duas vezes.
- **Regente:** o boneco foi removido; ficam o traçado do gesto, o metrônomo e a contagem.
- Limites: digitações de escala só para C, G, D, A, E, F (maiores) e Lá, Mi, Ré (menores naturais); dedilhado da flauta nas notas agudas varia entre modelos; formas de acorde do violão sem indicação de dedos; nada disso foi testado no tablet.

## Novidades da v0.12
- **Regente refeito, corpo inteiro (Regência):** pernas, quadril, tronco, ombros, cotovelos, punhos, mão e dedos. O movimento nasce do gesto da mão direita e passa por uma cadeia com atraso (punho → cotovelo → ombro → tronco → quadril), então o corpo "segue" a mão: o tronco inclina para a frente no tempo forte, o quadril desce e os joelhos dobram, o peso muda de pé. Mão esquerda relaxada ao lado do corpo, ou espelhada em "Duas mãos". Vistas de costas / lado / frente (arraste para girar). Conferido por imagem (Java2D) e por teste de valores; **não testado no tablet**, e os padrões de regência continuam sem revisão de um regente de verdade.
- **Calibrar o meu teclado (Ouvir):** você toca uma tecla por vez, da primeira à última (49/61/76/88 teclas), e o app aprende o som de cada tecla. Depois, ao gravar, ele decompõe o áudio nesses moldes. Em teste sintético (piano de SoundFont) acertou mais notas que o motor genérico e sem inventar tantas; **ainda não foi medido com o seu teclado real**.

## Novidades da v0.11
- **Cursor amarelo**: a tela e o som agora contam o tamanho de cada compasso do mesmo jeito (antes divergiam quando uma pauta tinha compasso com soma diferente ou vazio), então o cursor não adianta mais.
- **Letra e dinâmicas** ficam abaixo das notas e hastes mais baixas; o espaço entre pautas cresce quando precisa. Rodapé (©, direitos reservados) não vira mais letra.
- **Título e créditos** (letra/música/arranjo, 'SCTB'…) lidos do PDF e mostrados no topo; salvos no MusicXML.
- **Regência**: boneco 3D (costas, lado, frente; arraste para girar; opção de duas mãos).
- **Desempenho**: o realce das notas tocando e do teclado não varre mais a música toda a cada quadro.
- **Gravação**: grade de colcheias quando o ritmo permite e pausas curtíssimas viram ligadura (partitura mais limpa).
- **Limites honestos**: o boneco 3D, o cursor e o desempenho não foram testados em aparelho; a gravação continua dependendo da qualidade do áudio e do andamento detectado; os padrões de regência não foram revisados por um regente.

## Novidades da v0.10
- **Som com instrumentos reais**: piano, coro (aah/ooh), cordas, órgão, flauta etc. tocam por amostras de um SoundFont livre (GeneralUser GS, licença própria que permite uso em software; ver `app/src/main/assets/GENERALUSER-GS-LICENSE.txt`). Em Reprodução dá para voltar ao som sintetizado. A exportação WAV/M4A usa o mesmo som.
- **Regência com mão animada**: antebraço, palma, dedos e polegar seguem o gesto e "afundam" no tempo; opção de duas mãos espelhadas.
- Quando o PDF não traz o andamento escrito, o app avisa e usa 80 bpm (ajustável).
- **Limites honestos**: o som real não foi ouvido num aparelho (só medido em computador); repetições com 3 estrofes não escritas no PDF (ex.: Deus de Vivos toca 3 vezes no seu áudio) ainda tocam 2 vezes.

## Novidades da v0.9
- **Leitura de PDF mais precisa**: repetições, casas 1./2., mudança de tonalidade/compasso, dinâmicas (pp–ff, mf, mp), crescendo/diminuendo, andamento, rit., fermata; pautas vazias ocultas alinhadas; duas vozes por pauta.
- **Ouvir**: modo padrão com clave de Sol e Fá (arranjo completo de piano) ou só melodia.
- **Mixer**: soprano, contralto, tenor e baixo (volume, mudo, solo), timbre por trilha, som menos estridente.
- **Exportar áudio**: WAV e M4A (AAC) para apresentações. MP3 não é gerado (sem codificador nativo no Android).
- Layout respeita a barra de status do tablet.
- **Limites honestos**: PDFs sem fontes musicais (glifos vetorizados, ex. "Hino de Louvor") não são lidos; clave de tenor 8vb e fermata na reprodução ainda não; interface Android e exportação M4A não foram testadas em aparelho (o CI só prova compilação e testes do núcleo). SoundFont e importação MIDI continuam pendentes.

## Novidades da v0.7
- **Ler** (nova aba): abre PDF ou imagem de partitura e converte em notas editáveis, 100% no aparelho (sem servidor). Leitor próprio em Kotlin (`core/Omr.kt`): pautas, claves de Sol/Fá, armadura, fórmula de compasso, notas cheias/vazias, pausas, pontos, acidentes, colcheias/barras, sistemas de 1–2 pautas.
- **Limites honestos**: funciona bem com partitura impressa nítida (PDF digital). Fotos tortas ou com sombra: modo "é uma foto" ajuda, mas não corrige inclinação. Ainda não lê tercinas, ligaduras, letras, dinâmicas, claves de Dó nem várias vozes numa pauta. Acordes de semibreves empilhadas e outras fontes musicais (além de Bravura/Leland) são menos precisos. Sempre confira no Estúdio.

## Novidades da v0.6

- **Ouvir** (nova aba): grave no microfone ou importe um áudio (WAV, MP3, M4A, OGG, FLAC) e o app monta uma partitura inicial: melodia, cifras e, se quiser, acompanhamento de piano. Tudo no aparelho, sem internet.
- Motor: rede neural Basic Pitch (Spotify, Apache-2.0, 204 KB, em `app/src/main/assets/nmp.tflite`) via TensorFlow Lite. Se o modelo não carregar, entra o motor espectral próprio (`core/Dsp.kt`).
- Criatividade: escolha o tom e digite a sequência de acordes antes de gerar; a melodia segue os acordes e dá para editar depois.
- Limites honestos: testado em áudio sintético (notas isoladas F1 ≈ 0,93–0,97; vários sons juntos ≈ 0,70–0,80). Áudio real de microfone, ruído e muitos instrumentos juntos ainda não foram medidos e podem errar mais.

## Novidades da v0.5
- **Criatividade** (aba nova): cole uma letra, escolha tonalidade (maior/menor), compasso (14 fórmulas), andamento e ritmo. O app separa as sílabas
  (português), distribui em tempos fortes/fracos, cria melodia com cadência por verso e escolhe os acordes; a letra sai embaixo das notas e as cifras em cima.
  Depois: arranjo de piano (6 estilos), vozes extras (coral contralto/tenor/baixo sem quintas ou oitavas paralelas, segunda voz em terças ou sextas,
  linha de baixo) e edição de cada acorde. "Variação" gera outra versão. São regras musicais fixas, não IA generativa nem rede.
- **Estúdio**: duplicar, inserir, copiar, colar e limpar compasso, oitava do compasso, sílaba por nota (a-le-gri) e acorde digitado (Am7, B♭/D).
- **Compassos**: 1 a 16 tempos com 2, 4, 8 ou 16; criar com número de compassos digitado; vários instrumentos e vozes (contralto, tenor…) na criação.
- **Regência**: padrões revistos, com variantes para compassos irregulares (5 = 3+2 ou 2+3 etc.).
- **Servidor privado**: escondido, só abre pelos Ajustes e com o bloqueio do tablet; pareamento por código, chave guardada no cofre do Android.
- Menu lateral recolhível ou escondido.

## O que o app faz hoje (v0.5)
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
