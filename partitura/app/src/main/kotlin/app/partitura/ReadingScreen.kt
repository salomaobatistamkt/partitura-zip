package app.partitura

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.partitura.core.*

/** Aba "Leitura": quiz de 10 perguntas — nota na pauta (clave de Sol, de Fá ou as duas) e valor das figuras/pausas — com % de acerto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingScreen(state: AppState, wide: Boolean) {
    var kind by rememberSaveable { mutableIntStateOf(0) }   // 0 Sol, 1 Fá, 2 as duas, 3 valores
    var ledger by rememberSaveable { mutableStateOf(false) }
    var round by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Leitura de partitura", style = MaterialTheme.typography.headlineMedium)
        Text("Dez perguntas por rodada, no fim você vê a porcentagem de acertos. Cada rodada sorteia notas novas.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Wrap { listOf("Notas · clave de Sol", "Notas · clave de Fá", "Notas · as duas claves", "Valores de tempo").forEachIndexed { i, n -> FilterChip(selected = kind == i, onClick = { kind = i; round++ }, label = { Text(n) }) } }
        if (kind < 3) FilterChip(selected = ledger, onClick = { ledger = !ledger; round++ }, label = { Text("Incluir linhas suplementares (notas fora da pauta)") })
        RankingPanel(state)
        key(kind, ledger, round) {
            if (kind < 3) NoteQuiz(state, kind, ledger) { round++ } else DurationQuiz(state) { round++ }
        }
    }
}

private val NAMES = listOf('C', 'D', 'E', 'F', 'G', 'A', 'B')

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteQuiz(state: AppState, kind: Int, ledger: Boolean, again: () -> Unit) {
    val seed = remember { System.nanoTime() }
    val qs = remember {
        when (kind) {
            0 -> Reading.noteQuiz('G', 10, ledger, seed)
            1 -> Reading.noteQuiz('F', 10, ledger, seed)
            else -> (Reading.noteQuiz('G', 5, ledger, seed) + Reading.noteQuiz('F', 5, ledger, seed + 1)).shuffled(java.util.Random(seed))
        }
    }
    var i by remember { mutableIntStateOf(0) }
    var hits by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Char?>(null) }
    var saved by remember { mutableStateOf(false) }
    val title = listOf("Leitura de notas · clave de Sol", "Leitura de notas · clave de Fá", "Leitura de notas · as duas claves")[kind]
    if (i >= qs.size) {
        val pct = hits * 10
        LaunchedEffect(Unit) {
            if (!saved) {
                saved = true
                state.ranking.add(Ranking.Row(System.currentTimeMillis(), "leitura", "leitura-n$kind" + if (ledger) "-ls" else "", title + if (ledger) " (com linhas suplementares)" else "", 0, pct, 0, pct)); state.saveRanking()
            }
        }
        Panel {
            Text("$hits de ${qs.size} · $pct%", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(Reading.verdict(pct))
            Button(onClick = again) { Text("Nova rodada") }
        }
        return
    }
    val q = qs[i]
    Panel {
        Text("Pergunta ${i + 1} de ${qs.size}", style = MaterialTheme.typography.titleSmall)
        Text(if (q.clef == 'G') "Clave de Sol — que nota é esta?" else "Clave de Fá — que nota é esta?", style = MaterialTheme.typography.titleMedium)
        val sc = remember(i) { q.score() }
        StaticScore(sc, 0, 170.dp)
        Wrap {
            NAMES.forEach { c ->
                val chosen = picked
                val right = chosen != null && c == q.answer
                val wrong = chosen == c && c != q.answer
                (if (right) FilledTonalButton(onClick = {}) { Text(Reading.noteName(c)) }
                else OutlinedButton(enabled = chosen == null, onClick = { picked = c; if (c == q.answer) hits++ }) { Text(Reading.noteName(c) + if (wrong) " ✗" else "") })
            }
        }
        val chosen = picked
        if (chosen != null) {
            Text(if (chosen == q.answer) "Certo! É ${Reading.noteName(q.answer)}." else "Não foi dessa vez: a nota é ${Reading.noteName(q.answer)}.", color = if (chosen == q.answer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Wrap {
                OutlinedButton(onClick = { state.player.key(q.pitch.midi) }) { Text("Ouvir a nota") }
                Button(onClick = { picked = null; i++ }) { Text(if (i == qs.size - 1) "Ver resultado" else "Próxima") }
            }
        } else OutlinedButton(onClick = { state.player.key(q.pitch.midi) }) { Text("Ouvir a nota (dica)") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationQuiz(state: AppState, again: () -> Unit) {
    val seed = remember { System.nanoTime() }
    val qs = remember { Reading.durQuiz(10, seed) }
    var i by remember { mutableIntStateOf(0) }
    var hits by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Double?>(null) }
    var saved by remember { mutableStateOf(false) }
    if (i >= qs.size) {
        val pct = hits * 10
        LaunchedEffect(Unit) {
            if (!saved) { saved = true; state.ranking.add(Ranking.Row(System.currentTimeMillis(), "leitura", "leitura-valores", "Leitura de valores de tempo", 0, pct, 0, pct)); state.saveRanking() }
        }
        Panel {
            Text("$hits de ${qs.size} · $pct%", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(Reading.verdict(pct))
            Button(onClick = again) { Text("Nova rodada") }
        }
        return
    }
    val q = qs[i]
    val rest = q.title.startsWith("pausa de")
    val right = Reading.answer(q)
    Panel {
        Text("Pergunta ${i + 1} de ${qs.size}", style = MaterialTheme.typography.titleSmall)
        Text(if (rest) "Compasso de 4/4 (semínima = 1 tempo). Quanto dura a PAUSA deste compasso?" else "Compasso de 4/4 (semínima = 1 tempo). Quanto dura cada figura igual à primeira?", style = MaterialTheme.typography.titleMedium)
        val sc = remember(i) { Reading.durScore(q) }
        StaticScore(sc, 0, 170.dp)
        Wrap {
            Reading.DUR_OPTIONS.forEach { b ->
                val chosen = picked
                if (chosen != null && b == right) FilledTonalButton(onClick = {}) { Text(Reading.durLabel(b)) }
                else OutlinedButton(enabled = chosen == null, onClick = { picked = b; if (b == right) hits++ }) { Text(Reading.durLabel(b) + if (chosen == b) " ✗" else "") }
            }
        }
        val chosen = picked
        if (chosen != null) {
            Text(if (chosen == right) "Certo! ${Reading.durLabel(right)}." else "Não foi dessa vez: ${Reading.durLabel(right)}. Era ${if (rest) "uma " else "a figura: "}${q.title}.", color = if (chosen == right) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Button(onClick = { picked = null; i++ }) { Text(if (i == qs.size - 1) "Ver resultado" else "Próxima") }
        }
    }
}
