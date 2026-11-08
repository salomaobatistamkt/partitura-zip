package app.partitura

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.partitura.core.*

private val METERS = listOf(4 to 4, 3 to 4, 6 to 8, 2 to 4)
private val MODES = listOf("Piano completo (Sol + Fá)", "Só a melodia + cifras", "Melodia + cifras + acompanhamento novo")

/** Aba Ouvir: toca no microfone ou importa um áudio → notas → partitura. Tudo no aparelho, sem rede. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListenScreen(state: AppState) {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val rec = remember { MicRecorder() }
    var phase by remember { mutableIntStateOf(0) }            // 0 pronto, 1 gravando, 2 processando, 3 resultado
    var level by remember { mutableFloatStateOf(0f) }
    var secs by remember { mutableDoubleStateOf(0.0) }
    var prog by remember { mutableFloatStateOf(0f) }
    var progText by remember { mutableStateOf("") }
    var heard by remember { mutableStateOf<Heard?>(null) }
    var result by remember { mutableStateOf<Transcribed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val profFile = remember { java.io.File(ctx.filesDir, "keyboard.profile") }
    var profile by remember { mutableStateOf<KeyboardProfile?>(try { if (profFile.exists()) KeyboardProfile.fromBytes(profFile.readBytes()) else null } catch (_: Throwable) { null }) }
    var useProfile by rememberSaveable { mutableStateOf(true) }
    val calRec = remember { MicRecorder() }
    var calPhase by remember { mutableIntStateOf(0) }          // 0 parado, 1 gravando, 2 aprendendo
    var calPreset by rememberSaveable { mutableIntStateOf(1) }
    var calSecs by remember { mutableDoubleStateOf(0.0) }
    var calLevel by remember { mutableFloatStateOf(0f) }
    var calMsg by remember { mutableStateOf<String?>(null) }
    var calOk by remember { mutableStateOf(false) }

    var title by rememberSaveable { mutableStateOf("Gravação") }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var meter by rememberSaveable { mutableIntStateOf(0) }
    var tempoText by rememberSaveable { mutableStateOf("") }
    var keyAuto by rememberSaveable { mutableStateOf(true) }
    var fifths by rememberSaveable { mutableIntStateOf(0) }
    var minor by rememberSaveable { mutableStateOf(false) }

    fun build() {
        val h = heard ?: return
        try {
            val t = tempoText.trim().toDoubleOrNull()?.takeIf { it in 30.0..240.0 }
            val (b, bt) = METERS[meter]
            val r = ScoreFromNotes.build(h.notes, TranscribeOptions(
                title = title.ifBlank { "Gravação" }, tempo = t, beats = b, beatType = bt, mode = mode,
                fifths = if (keyAuto) null else fifths, minor = minor))
            result = r; error = null; phase = 3
        } catch (e: Throwable) { result = null; error = e.message ?: "Não consegui montar a partitura."; phase = 3 }
    }

    fun process(audio: FloatArray, sr: Int) {
        phase = 2; prog = 0f; progText = "Preparando…"; error = null; result = null
        Thread {
            try {
                if (audio.size < sr / 2) throw IllegalStateException("O áudio ficou curto demais (menos de meio segundo).")
                val h = Listener.hear(ctx, audio, sr, if (useProfile) profile else null) { p, t -> main.post { prog = p; progText = t } }
                main.post { heard = h; build() }
            } catch (e: Throwable) {
                main.post { error = e.message ?: e.toString(); phase = 3 }
            }
        }.start()
    }

    fun startRec() {
        rec.start(); phase = 1; error = null
    }

    fun startCal() { calRec.start(); calPhase = 1; calMsg = null; calOk = false }
    fun finishCal() {
        val a = calRec.stop(); val sr = calRec.rate
        val (name, lo, hi) = KeyboardProfile.PRESETS[calPreset]
        calPhase = 2
        Thread {
            val r: KeyboardCalib.Result? = try { KeyboardCalib.learn(a, sr, lo, hi, name) } catch (e: Throwable) { null }
            main.post {
                if (r?.profile != null) {
                    try { profFile.writeBytes(r.profile.toBytes()) } catch (_: Throwable) {}
                    profile = r.profile; calOk = true; useProfile = true
                }
                calMsg = r?.message ?: "Não consegui analisar essa gravação."
                calPhase = 0
            }
        }.start()
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRec() else error = "Sem permissão do microfone não consigo ouvir. Você pode liberar em Ajustes do Android → Apps → Compasso → Permissões."
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            phase = 2; prog = 0f; progText = "Lendo o arquivo…"
            Thread {
                try {
                    val (a, sr) = AudioFile.read(ctx, uri)
                    main.post { process(a, sr) }
                } catch (e: Throwable) { main.post { error = "Não consegui ler esse áudio: ${e.message ?: e.javaClass.simpleName}"; phase = 3 } }
            }.start()
        }
    }

    // atualiza o medidor enquanto grava
    LaunchedEffect(phase) {
        while (phase == 1) {
            level = rec.level; secs = rec.seconds
            rec.error?.let { error = it; phase = 3 }
            kotlinx.coroutines.delay(60)
        }
    }
    LaunchedEffect(calPhase) {
        while (calPhase == 1) { calLevel = calRec.level; calSecs = calRec.seconds; calRec.error?.let { calMsg = it; calPhase = 0 }; kotlinx.coroutines.delay(60) }
    }
    DisposableEffect(Unit) { onDispose { if (rec.isRunning) rec.stop(); if (calRec.isRunning) calRec.stop() } }
    val calPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCal() else calMsg = "Sem permissão do microfone não consigo ouvir o teclado."
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ouvir", style = MaterialTheme.typography.headlineSmall)
        Text("Toque algo e o app monta uma partitura inicial. Funciona sem internet: o modelo roda dentro do aparelho.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Panel {
            when (phase) {
                1 -> {
                    Text("Gravando… ${"%d:%02d".format(secs.toInt() / 60, secs.toInt() % 60)}", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("Toque uma voz de cada vez para melhor resultado; acordes também funcionam, com menos precisão.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { val a = rec.stop(); process(a, rec.rate) }) { Text("Parar e gerar partitura") }
                    OutlinedButton(onClick = { rec.stop(); phase = 0 }) { Text("Descartar") }
                }
                2 -> {
                    Text("Analisando o áudio…", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { prog.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(progText, style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRec()
                            else permission.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Text("Gravar") }
                        OutlinedButton(onClick = { picker.launch("audio/*") }) { Text("Importar áudio") }
                    }
                    Text("Importar aceita WAV, MP3, M4A, OGG e FLAC (até 10 minutos).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        Panel {
            Text("Calibrar o meu teclado", style = MaterialTheme.typography.titleMedium)
            val pr = profile
            Text(if (pr != null) "Teclado aprendido: ${pr.label.ifBlank { "${pr.keys} teclas" }} (${KeyboardProfile.noteName(pr.lowMidi)} a ${KeyboardProfile.noteName(pr.highMidi)})." else "Ainda não calibrado. A calibração ensina ao app o som de cada tecla do seu instrumento; depois, ao gravar, ele procura essas notas em vez de adivinhar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (calPhase == 0) {
                SectionTitle("Quantas teclas?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyboardProfile.PRESETS.forEachIndexed { i, (n, _, _) -> FilterChip(selected = calPreset == i, onClick = { calPreset = i }, label = { Text(n) }) }
                }
                Text("Notas escritas no padrão científico (Dó central = C4). Se o seu teclado mostra o Dó central como C3, o C1 dele é o C2 aqui; o que importa é tocar da primeira à última tecla.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Como fazer: aperte Gravar e toque cada tecla uma vez, em ordem, da mais grave à mais aguda, de semitom em semitom (brancas e pretas, sem pular). Segure cerca de 1 segundo e solte antes da próxima, sem pedal, em lugar silencioso, com o app perto do teclado.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startCal()
                    else calPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Gravar a calibração") }
                if (pr != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = useProfile, onCheckedChange = { useProfile = it })
                        Text("Usar meu teclado ao ouvir")
                    }
                    OutlinedButton(onClick = { profile = null; try { profFile.delete() } catch (_: Throwable) {} }) { Text("Apagar calibração") }
                }
            } else if (calPhase == 1) {
                val ks = KeyboardProfile.PRESETS[calPreset]
                Text("Gravando… ${"%d:%02d".format(calSecs.toInt() / 60, calSecs.toInt() % 60)} · toque de ${KeyboardProfile.noteName(ks.second)} até ${KeyboardProfile.noteName(ks.third)} (${ks.third - ks.second + 1} teclas)", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(progress = { calLevel.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { finishCal() }) { Text("Terminei: aprender as teclas") }
                OutlinedButton(onClick = { calRec.stop(); calPhase = 0 }) { Text("Descartar") }
            } else {
                Text("Aprendendo as teclas…", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            calMsg?.let { Text(it, color = if (calOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        }

        Panel {
            Text("Como montar a partitura", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SectionTitle("O que montar")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MODES.forEachIndexed { i, n -> FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(n) }) }
            }
            Text(when (mode) {
                0 -> "Escreve tudo o que foi ouvido em duas pautas, como no piano: mão direita na clave de Sol e mão esquerda na clave de Fá. Escolha esta quando a gravação for de piano (melodia + acompanhamento)."
                1 -> "Escreve só a linha principal (a nota mais aguda de cada instante), com as cifras estimadas. Boa para voz ou instrumento solo."
                else -> "Escreve a melodia e cria um acompanhamento de piano novo a partir das cifras (não é o que foi tocado)."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle("Compasso")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                METERS.forEachIndexed { i, (b, t) -> FilterChip(selected = meter == i, onClick = { meter = i }, label = { Text("$b/$t") }) }
            }
            OutlinedTextField(tempoText, { tempoText = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("Andamento (vazio = descobrir sozinho)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SectionTitle("Tom")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = keyAuto, onClick = { keyAuto = true }, label = { Text("Automático") })
                FilterChip(selected = !keyAuto && !minor, onClick = { keyAuto = false; minor = false }, label = { Text("Maior") })
                FilterChip(selected = !keyAuto && minor, onClick = { keyAuto = false; minor = true }, label = { Text("Menor") })
            }
            if (!keyAuto) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (-6..6).forEach { f ->
                    FilterChip(selected = fifths == f, onClick = { fifths = f },
                        label = { Text((if (minor) Names.minorKey(f) else Names.key(f)) + if (f > 0) " ${f}♯" else if (f < 0) " ${-f}♭" else "") })
                }
            }
            if (heard != null && phase == 3) Button(onClick = { build() }) { Text("Refazer com estas opções") }
        }

        val r = result
        if (phase == 3 && r != null) {
            Panel {
                Text("Partitura gerada", style = MaterialTheme.typography.titleMedium)
                val key = (if (r.minor) Names.minorKey(r.fifths) + " menor" else Names.key(r.fifths) + " maior")
                Text("${r.notes} notas · ${r.bars} compassos · ♩ = ${r.tempo.toInt()} · tom de $key")
                heard?.let { Text("Motor: ${it.engine}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                StaticScore(r.score, 0, 360.dp)
                Text("Isto é um rascunho: confira as notas no Estúdio. Ritmo e acordes são estimados e podem errar, principalmente com muitas notas ao mesmo tempo ou áudio com ruído.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { state.open(r.score.copyDeep(), title.ifBlank { "Gravação" }); state.goStudio() }) { Text("Abrir no Estúdio") }
                    OutlinedButton(onClick = { state.saveAs(title.ifBlank { "Gravação" } + ".musicxml", MusicXml.write(r.score).toByteArray()) }) { Text("Salvar MusicXML") }
                }
            }
        }
    }
}
