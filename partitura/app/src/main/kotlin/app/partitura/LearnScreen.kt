package app.partitura

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.partitura.core.*

/** Aba "Aprender": método de piano por níveis, com exercício, partitura, teclado, mão isolada, loop, tempo, troca de tom e progresso salvo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LearnScreen(state: AppState, wide: Boolean) {
    val prog = state.progress
    state.progressRev // relê quando o progresso muda
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    var levelF by rememberSaveable { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Aprender piano", style = MaterialTheme.typography.headlineMedium)
        val all = Curriculum.piano
        val done = all.count { prog.map[it.id]?.done == true }
        val total = prog.map.values.sumOf { it.seconds } / 60
        Text("$done de ${all.size} exercícios concluídos · $total min de prática registrados neste aparelho", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (sel == null) RankingPanel(state)
        val next = Curriculum.next(prog)
        if (next != null) Panel {
            Text("Recomendado agora", style = MaterialTheme.typography.titleSmall)
            Text(next.name, style = MaterialTheme.typography.titleMedium)
            Text(next.objective, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { sel = next.id }) { Text("Abrir exercício") }
        }
        val s = sel?.let { Curriculum.byId(it) }
        if (s != null) LessonView(state, s, wide) { sel = null }
        else {
            Wrap {
                FilterChip(selected = levelF < 0, onClick = { levelF = -1 }, label = { Text("Todos") })
                Level.values().forEachIndexed { i, l -> FilterChip(selected = levelF == i, onClick = { levelF = i }, label = { Text(l.label) }) }
            }
            val shown = all.filter { levelF < 0 || it.level.ordinal == levelF }
            shown.groupBy { it.module }.forEach { (mod, ls) ->
                Text(mod, style = MaterialTheme.typography.titleMedium)
                ls.forEach { l ->
                    val e = prog.map[l.id]
                    val open = Curriculum.unlocked(l, prog)
                    OutlinedCard(onClick = { sel = l.id }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name, style = MaterialTheme.typography.bodyLarge)
                                Text(l.level.label + (if (!open) " · faça antes: " + l.prerequisites.joinToString { Curriculum.byId(it)?.name ?: it } else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(when { e?.done == true -> "✓ concluído"; e != null -> "${e.bestHit}% · ${e.attempts}×"; !open -> "bloqueado*"; else -> "" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("*Bloqueado é só uma recomendação de ordem: você pode abrir qualquer exercício.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LessonView(state: AppState, l: Lesson, wide: Boolean, onBack: () -> Unit) {
    val p = state.player
    var bpm by remember(l.id) { mutableIntStateOf(l.bpmStart) }
    var trans by remember(l.id) { mutableIntStateOf(0) }
    var hands by remember(l.id) { mutableIntStateOf(0) } // 0 ambas, 1 direita, 2 esquerda
    var loop by remember(l.id) { mutableStateOf(true) }
    var started by remember(l.id) { mutableLongStateOf(0L) }
    val sc = remember(l.id, trans) { l.score(trans) }
    OutlinedButton(onClick = { p.stop(); onBack() }) { Text("← Voltar à lista") }
    Panel {
        Text(l.name, style = MaterialTheme.typography.titleLarge)
        Text(l.level.label + " · " + l.module, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Objetivo: " + l.objective)
        Text("O que você precisa saber: " + l.knowledge, style = MaterialTheme.typography.bodySmall)
        Text(l.explanation, style = MaterialTheme.typography.bodyMedium)
    }
    Panel {
        Text("Partitura e teclado", style = MaterialTheme.typography.titleMedium)
        StaticScore(sc, 0, 230.dp, q = if (p.active) ({ p.posQ }) else null)
        val lit = pianoActiveFor(sc, 0, p)
        val first = 36; val last = 96
        PianoKeyboard(first, last, lit.keys, emptySet(), { m -> p.key(m) }, height = if (wide) 110.dp else 84.dp, roles = lit.mapValues { it.value shr 1 }, attack = lit.filterValues { (it and 1) == 1 }.keys)
        Stepper("Andamento (semínima = bpm)", "$bpm", { bpm = (bpm - 4).coerceAtLeast(30) }, { bpm = (bpm + 4).coerceAtMost(200) })
        Stepper("Tom (troca em semitons)", if (trans == 0) "original" else (if (trans > 0) "+$trans" else "$trans"), { trans = (trans - 1).coerceAtLeast(-6); p.stop() }, { trans = (trans + 1).coerceAtMost(6); p.stop() })
        Wrap {
            listOf("Duas mãos", "Só direita", "Só esquerda").forEachIndexed { i, n -> FilterChip(selected = hands == i, onClick = { hands = i }, label = { Text(n) }) }
            FilterChip(selected = loop, onClick = { loop = !loop }, label = { Text("Repetir") })
        }
        Wrap {
            Button(onClick = {
                val vols = when (hands) { 1 -> listOf(1.0, 0.0); 2 -> listOf(0.0, 1.0); else -> listOf(1.0, 1.0) }
                started = System.currentTimeMillis()
                p.start(sc, bpm, vols, 0.0, Double.MAX_VALUE, loop)
            }) { Text(if (p.active) "Reiniciar" else "Ouvir / acompanhar") }
            if (p.active) OutlinedButton(onClick = { p.stop() }) { Text("Parar") }
            OutlinedButton(onClick = { state.open(sc.copyDeep()); state.goStudio() }) { Text("Abrir no Estúdio") }
        }
        Text("Mão direita em roxo, esquerda em vermelho. Passo a passo e dedilhado abaixo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Panel {
        Text("Passo a passo", style = MaterialTheme.typography.titleMedium)
        l.steps.forEachIndexed { i, st -> Text("${i + 1}. $st") }
        Text("Dedilhado", style = MaterialTheme.typography.titleSmall)
        Text(l.fingering ?: "Sem dedilhado afirmado: este app só mostra a digitação padrão que tem certeza. Use a indicação do seu professor ou método.")
        Text("Erros comuns", style = MaterialTheme.typography.titleSmall)
        l.commonErrors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        Text("Andamento: comece em ${l.bpmStart} bpm e chegue a ${l.bpmTarget} bpm.", style = MaterialTheme.typography.bodySmall)
        Text("Critério de conclusão: ${l.completion}", style = MaterialTheme.typography.bodyMedium)
    }
    MicPanel(state, l, sc, bpm, hands)
    Panel {
        Text("Registrar tentativa", style = MaterialTheme.typography.titleMedium)
        Text("Sem microfone? Informe você mesmo quantos % saíram certos (autoavaliação). Esta tentativa não entra no ranking; só as medidas pelo microfone entram.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Wrap {
            listOf(60, 70, 80, 90, 95, 100).forEach { h ->
                OutlinedButton(onClick = {
                    val secs = if (started > 0) ((System.currentTimeMillis() - started) / 1000).toInt().coerceIn(0, 3600) else 0
                    state.progress.record(l, bpm, h, secs, System.currentTimeMillis()); state.saveProgress()
                    state.message = if (state.progress.map[l.id]?.done == true) "Exercício concluído!" else "Tentativa registrada: $h% a $bpm bpm."
                }) { Text("$h%") }
            }
        }
        val e = state.progress.map[l.id]
        state.progressRev
        if (e != null) Text("Melhor: ${e.bestHit}% · melhor andamento limpo: ${e.bestBpm} bpm · ${e.attempts} tentativa(s)" + if (e.done) " · concluído" else "", style = MaterialTheme.typography.bodySmall)
    }
}

/** Ranking pessoal: título, pontos totais e melhores exercícios. Fica só neste aparelho. */
@Composable
fun RankingPanel(state: AppState) {
    state.progressRev
    val r = state.ranking
    Panel {
        Text("Seu ranking (só neste aparelho)", style = MaterialTheme.typography.titleSmall)
        if (r.rows.isEmpty()) Text("Ainda sem pontos. Toque um exercício com «Gravar e avaliar» ou faça um quiz de leitura.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Text("${Ranking.title(r.total)} · ${r.total} pontos", style = MaterialTheme.typography.titleMedium)
            r.top(5).forEachIndexed { i, row -> Text("${i + 1}. ${row.title} — ${row.hit}% · ${row.points} pts", style = MaterialTheme.typography.bodySmall) }
            Text("Pontos = acerto × nível × (andamento tocado ÷ meta). Conta só a melhor tentativa de cada exercício.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Grava você tocando, ouve as notas e compara com o exercício: % de acerto, notas erradas/faltando, ritmo e andamento realmente tocado. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicPanel(state: AppState, l: Lesson, sc: Score, bpm: Int, hands: Int) {
    val ctx = LocalContext.current
    val p = state.player
    val rec = remember { MicRecorder() }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var recording by remember(l.id) { mutableStateOf(false) }
    var busy by remember(l.id) { mutableStateOf(false) }
    var res by remember(l.id) { mutableStateOf<Assess.Result?>(null) }
    var asked by remember(l.id) { mutableIntStateOf(bpm) }
    var pts by remember(l.id) { mutableIntStateOf(0) }
    var err by remember(l.id) { mutableStateOf<String?>(null) }
    DisposableEffect(l.id) { onDispose { if (rec.isRunning) rec.stop() } }

    fun startRec() { p.stop(); res = null; err = null; rec.start(); recording = true }
    fun finish() {
        val audio = rec.stop(); val sr = rec.rate; recording = false; busy = true
        val askedBpm = bpm; val hs = hands; val score = sc
        val profFile = java.io.File(ctx.filesDir, "keyboard.profile")
        Thread {
            try {
                if (audio.size < sr / 2) throw IllegalStateException("A gravação ficou curta demais (menos de meio segundo).")
                val profile = try { if (profFile.exists()) KeyboardProfile.fromBytes(profFile.readBytes()) else null } catch (_: Throwable) { null }
                val heard = Listener.hear(ctx, audio, sr, profile) { _, _ -> }
                val ev = Synth.events(score, askedBpm).filter { hs == 0 || (hs == 1 && it.part == 0) || (hs == 2 && it.part == 1) }
                val r = Assess.compare(ev, heard.notes)
                val secs = (audio.size / sr).coerceIn(0, 3600)
                main.post {
                    res = r; asked = askedBpm; busy = false
                    if (r.reliable) {
                        val played = r.achievedBpm(askedBpm)
                        val points = Ranking.points(r.hit, l.level.ordinal, played, l.bpmTarget)
                        pts = points
                        state.progress.record(l, played, r.hit, secs, System.currentTimeMillis()); state.saveProgress()
                        state.ranking.add(Ranking.Row(System.currentTimeMillis(), "ex", l.id, l.name, l.level.ordinal, r.hit, played, points)); state.saveRanking()
                    }
                }
            } catch (e: Throwable) { main.post { err = e.message ?: e.toString(); busy = false } }
        }.start()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startRec() else err = "Sem permissão do microfone não consigo ouvir você tocar." }

    Panel {
        Text("Tocar e avaliar pelo microfone", style = MaterialTheme.typography.titleMedium)
        Text("Toque o exercício no seu piano (a mão escolhida acima, no andamento $bpm bpm, um pouco mais devagar também vale). O app ouve, compara nota por nota com a partitura e dá a % de acerto. Não toque o som do app ao mesmo tempo: o microfone captaria os dois.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Wrap {
            if (!recording) Button(enabled = !busy, onClick = {
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRec() else permission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (res == null) "Gravar e avaliar" else "Gravar de novo") }
            else Button(onClick = { finish() }) { Text("Parar e avaliar") }
        }
        if (recording) Text("Gravando… toque agora e depois aperte «Parar e avaliar».", color = MaterialTheme.colorScheme.primary)
        if (busy) Text("Ouvindo e comparando…")
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        res?.let { r ->
            if (!r.reliable) Text(r.message, color = MaterialTheme.colorScheme.error)
            else {
                Text("${r.hit}% de acerto", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text(r.message)
                Text("Andamento: pedido $asked bpm · você tocou cerca de ${r.achievedBpm(asked)} bpm", style = MaterialTheme.typography.bodyMedium)
                if (r.rhythmMs > 0) Text("Regularidade: desvio médio de ${r.rhythmMs.toInt()} ms (${r.rhythmOk} de ${r.exact + r.octaveOff} notas dentro de 0,12 s)", style = MaterialTheme.typography.bodySmall)
                fun nm(m: Int) = Names.pitch(Spell.forMidi(m, 0))
                val miss = r.items.filter { it.status == "faltou" }.map { nm(it.midi) }
                val oct = r.items.filter { it.status == "oitava errada" }.map { nm(it.midi) }
                if (miss.isNotEmpty()) Text("Não ouvi: " + miss.take(14).joinToString(", ") + if (miss.size > 14) "…" else "", style = MaterialTheme.typography.bodySmall)
                if (oct.isNotEmpty()) Text("Oitava diferente: " + oct.take(14).joinToString(", "), style = MaterialTheme.typography.bodySmall)
                Text("+$pts pontos no ranking" + if (state.progress.map[l.id]?.done == true) " · exercício concluído!" else "", style = MaterialTheme.typography.titleSmall)
                Text("Limite honesto: o app ouve só as alturas e os tempos das notas. Dinâmica, pedal e dedilhado ele não avalia. Em acordes muito cheios ou piano desafinado a escuta pode errar.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
