package com.kalotrapezis.drive

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI Assist on Home's widget itself (the user, 8 Nov: "I thought the TV would be the widget, not a new window below… no
 * text in so little space, just the AI's icon with three dots, or a cloud when it speaks, like a tamagotchi"): the mic
 * listens, the answer is heard, and the widget shows only what it is doing. An answer that needs a tap or a screen
 * (a call, choices, a card, a file, an app) goes on in AI Assist with the conversation, which is the same one.
 */
@Stable
internal class HomeTalk(private val c: Context, private val scope: CoroutineScope, private val reader: ReadAloud) {
    /** null: resting. */
    var phase by mutableStateOf<LivePhase?>(null); private set
    var hearing by mutableStateOf(false); private set
    var cue by mutableStateOf(TalkCue.None); private set
    var mood by mutableStateOf(TalkMood.Normal); private set
    private val stop = AtomicBoolean(false)
    private var job: Job? = null
    private var voice: AnswerVoice? = null

    /** The widget's mic: start; while listening, that is all; while thinking or speaking, stop. [sheet]: no key or no mic yet. */
    fun tap(sheet: () -> Unit) = when (phase) {
        null -> if (AssistantKey.get(c) == null || !hasMic(c)) sheet() else start()
        LivePhase.Listening -> stop.set(true)
        else -> end()
    }

    fun end() { cue = TalkCue.None; mood = TalkMood.Normal; stop.set(true); job?.cancel(); job = null; voice?.stop(); voice = null; reader.stop(); phase = null; hearing = false }

    private fun start() {
        job = scope.launch {
            try { turn(10_000) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { WriterLog.w("AI Assist widget: ${e.message}") }
            finally { phase = null; hearing = false; voice = null }
        }
    }

    private suspend fun turn(waitMs: Long) {
        stop.set(false); phase = LivePhase.Listening; cue = TalkCue.None; mood = TalkMood.Normal
        val pcm = withContext(Dispatchers.IO) { runCatching { listen(waitMs, { stop.get() }, { hearing = it }, AssistantPause.ms(c), AssistantAudio.mic(c)) }.getOrNull() }
        hearing = false
        if (pcm == null) return
        phase = LivePhase.Thinking
        val chat = withContext(Dispatchers.IO) { SharedChat.chat(c) }
        val text = withContext(Dispatchers.IO) { AssistantNet.transcribe(AssistantNet.key(c), pcm, AssistantWords.terms(c), chat.bill.part("dictation")) }
        if (text.isBlank()) return
        SharedChat.bubbles += Bubble(true, text)
        val speech = AnswerVoice(c, reader, chat.bill.part("voice"), scope).also { voice = it }
        val cut = Sentences()
        AssistantTools.beside = Beside.can(c)
        val said = StringBuilder()
        chat.onTool = { name -> TalkCue.ofTool(name).takeIf { it != TalkCue.None }?.let { found -> scope.launch { cue = found } } }
        val reply = withContext(Dispatchers.IO) { try { chat.ask(text) { words ->
            cut.push(words).forEach { speech.say(it) }
            said.append(words); val now = TalkCue.of(said); val feel = TalkMood.of(said); scope.launch { phase = LivePhase.Speaking; cue = now; mood = feel }
        } } finally { chat.onTool = null } }
        speech.say(cut.rest()); speech.finish()
        SharedChat.bubbles += Bubble(false, reply.text, reply.choices, reply.call, reply.images, audio = reply.audio, cards = reply.cards, confirm = reply.confirm)
        withContext(Dispatchers.IO) {
            SharedChat.at = System.currentTimeMillis()
            runCatching { AssistantChats.save(c, SharedChat.id, SharedChat.bubbles.map { it.json() }, chat.saved()) }
            SharedChat.save(SharedChat.file(c))
            AssistantAutoMemory.soon(c, chat.saved())
        }
        phase = LivePhase.Speaking
        speech.await(); voice = null
        if (needsScreen(reply)) { openAssistant(c); return }
        if (reply.text.trimEnd().endsWith('?')) turn(6_000) // a question waits for the answer, as the sheet does
    }

    companion object {
        fun needsScreen(r: AssistantReply) = r.call != null || r.confirm != null || r.audio != null || r.choices.isNotEmpty() || r.cards.isNotEmpty() ||
            r.images.isNotEmpty() || r.open != null || r.go != null
    }
}

/** Live's and the widget's player (8 Nov): 0 the record, 1 the cassette, 2 the TV; one choice for both. */
internal fun liveStyle(c: Context): Int = c.getSharedPreferences("assistant", Context.MODE_PRIVATE).let { it.getInt("liveStyle", if (it.getBoolean("cassette", false)) 1 else 0) }

/** The widget while it talks: the record or the cassette turning with it, or the small TV with the robot and its cues. */
@Composable internal fun TalkPlayer(talk: HomeTalk, phase: LivePhase, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val style = remember { liveStyle(c) }
    val level = if (talk.hearing) 0.7f else 0f
    when (style) {
        0 -> Vinyl(phase, level, modifier.aspectRatio(1f))
        1 -> Cassette(phase, level, modifier)
        else -> MiniTv(modifier) { TalkScreen(phase, talk.hearing, Modifier.fillMaxSize(), talk.cue, talk.mood, characterColor(AssistantVoices.id(c))) }
    }
}

@Composable internal fun rememberHomeTalk(): HomeTalk {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = rememberReadAloud()
    return remember { HomeTalk(c.applicationContext, scope, reader) }
}

/** What the answer is about, drawn beside the face while it is said (8 Nov, the user's sketch: a rain cloud for the weather). */
internal enum class TalkCue { None, Search, Rain, Snow, Cloud, Sun;
    companion object {
        private val RAIN = Regex("βροχ|μπόρ|καταιγ|ψιχάλ|rain|shower|storm|drizzle", RegexOption.IGNORE_CASE)
        private val SNOW = Regex("χιον|χιόν|snow|sleet", RegexOption.IGNORE_CASE)
        private val CLOUD = Regex("συννεφ|νεφώσ|cloud|overcast", RegexOption.IGNORE_CASE)
        private val SUN = Regex("ηλιοφάν|λιακάδ|αίθρι|ήλιο|sunny|sunshine|clear sk", RegexOption.IGNORE_CASE)
        private val SEARCHING = setOf("web_search", "youtube", "open_url", "media_play", "radio_play")
        /** A tool the model called: searching shows the magnifier. */
        fun ofTool(name: String) = if (name in SEARCHING) Search else None
        fun of(text: CharSequence) = when { RAIN.containsMatchIn(text) -> Rain; SNOW.containsMatchIn(text) -> Snow; CLOUD.containsMatchIn(text) -> Cloud; SUN.containsMatchIn(text) -> Sun; else -> None }
    }
}

/** The robot's colour by the voice chosen (8 Nov: "yellow, pink, white, green, blue… like different characters"); the phone's own voice keeps the orange. */
internal val CHARACTER_COLORS = listOf(Color(0xFFFFC93C), Color(0xFFF06292), Color(0xFFECEFF1), Color(0xFF66BB6A), Color(0xFF42A5F5))
internal fun characterColor(voice: String?): Color = if (voice.isNullOrEmpty()) ASSISTANT_COLOR else CHARACTER_COLORS[Math.floorMod(voice.hashCode(), CHARACTER_COLORS.size)]

/** How the weather feels, on the face (8 Nov: "it could tremble when cold, or be red with a hat when hot"). */
internal enum class TalkMood { Normal, Cold, Hot;
    companion object {
        private val COLD = Regex("κρύο|κρυώ|παγων|ψύχρα|παγετ|freez|cold|chilly|frost", RegexOption.IGNORE_CASE)
        private val HOT = Regex("ζέστη|ζεστ|καύσων|λίβα|scorch|heatwave|\\bhot\\b", RegexOption.IGNORE_CASE)
        private val DEGREES = Regex("(-?\\d{1,2})\\s*(°|βαθμ|degree)", RegexOption.IGNORE_CASE)
        /** Words first; else the first temperature said: under 8° cold, over 30° hot. */
        fun of(text: CharSequence): TalkMood = when {
            COLD.containsMatchIn(text) -> Cold
            HOT.containsMatchIn(text) -> Hot
            else -> DEGREES.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it < 8) Cold else if (it > 30) Hot else Normal } ?: Normal
        }
    }
}

/**
 * The robot on the screen, as the user drew it: a round face with two diamond eyes that blink; rings while it listens,
 * a thought cloud of three dots while it thinks, a moving mouth while it speaks, and the answer's [cue] up at the left.
 */
@Composable internal fun TalkScreen(phase: LivePhase, hearing: Boolean, modifier: Modifier = Modifier, cue: TalkCue = TalkCue.None, mood: TalkMood = TalkMood.Normal, color: Color = ASSISTANT_COLOR) {
    val loop = rememberInfiniteTransition(label = "talk")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "t")
    val blink by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "blink")
    val ink = color; val soft = Color(0xFFF4F1EA)
    Canvas(modifier) {
        val side = minOf(size.width, size.height)
        val r = side * 0.3f
        val shiver = if (mood == TalkMood.Cold) kotlin.math.sin(t * 40 * Math.PI).toFloat() * side * 0.012f else 0f // trembling
        val face = Offset(size.width * 0.62f + shiver, size.height * 0.58f)
        val skin = if (mood == TalkMood.Hot) Color(0xFFE53935) else if (mood == TalkMood.Cold) Color(0xFF64B5F6) else ink
        if (phase == LivePhase.Listening) for (k in 0 until 3) { // rings going out, stronger while a voice is heard
            val p = (t + k / 3f) % 1f
            drawCircle(ink.copy(alpha = (1 - p) * (if (hearing) 0.8f else 0.35f)), radius = r * (1.05f + p * 0.5f), center = face, style = Stroke(side * 0.02f))
        }
        drawCircle(skin.copy(alpha = if (mood == TalkMood.Hot) 0.45f else 0.18f), radius = r, center = face)
        drawCircle(skin, radius = r, center = face, style = Stroke(side * 0.025f))
        if (mood == TalkMood.Hot) { // a sun hat: the brim and the crown
            drawRoundRect(Color(0xFFFFE082), Offset(face.x - r * 1.15f, face.y - r * 0.98f), Size(r * 2.3f, r * 0.2f), CornerRadius(r * 0.1f))
            drawRoundRect(Color(0xFFFFE082), Offset(face.x - r * 0.6f, face.y - r * 1.45f), Size(r * 1.2f, r * 0.55f), CornerRadius(r * 0.25f))
            drawRect(Color(0xFFE53935), Offset(face.x - r * 0.6f, face.y - r * 1.05f), Size(r * 1.2f, r * 0.1f))
        }
        if (mood == TalkMood.Cold) for (k in 0 until 3) { // a breath of frost
            val p = (t + k / 3f) % 1f
            drawCircle(soft.copy(alpha = 0.6f * (1 - p)), radius = side * 0.015f * (1 + p), center = face + Offset(-r * (0.4f + p * 0.6f), r * 0.45f))
        }
        // the diamond eyes, shut for a moment now and then
        val shut = if (blink > 0.94f) 0.15f else 1f
        val look = if (phase == LivePhase.Thinking) Offset(-r * 0.12f, -r * 0.12f) else Offset.Zero // looking up at the thought
        listOf(-0.36f, 0.36f).forEach { k ->
            val e = face + Offset(r * k, -r * 0.12f) + look; val d = r * 0.16f
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(e.x, e.y - d * shut); lineTo(e.x + d, e.y); lineTo(e.x, e.y + d * shut); lineTo(e.x - d, e.y); close() }, soft)
        }
        // the mouth: a line, opening and closing while it speaks
        val open = if (phase == LivePhase.Speaking) (0.15f + 0.85f * kotlin.math.abs(kotlin.math.sin(t * 4 * Math.PI)).toFloat()) * r * 0.22f else r * 0.02f
        drawRoundRect(soft, Offset(face.x - r * 0.22f, face.y + r * 0.38f - open / 2), Size(r * 0.44f, open + side * 0.012f), CornerRadius(side * 0.02f))
        // up at the left: the thought, or what the answer is about
        val at = Offset(size.width * 0.24f, size.height * 0.22f); val w = side * 0.36f; val h = side * 0.18f
        when {
            phase == LivePhase.Thinking && cue == TalkCue.Search -> { // searching: a magnifier going round
                val a = t * 2 * Math.PI
                val c = at + Offset(kotlin.math.cos(a).toFloat() * w * 0.18f, kotlin.math.sin(a).toFloat() * h * 0.3f)
                val lens = h * 0.42f
                drawCircle(soft.copy(alpha = 0.25f), radius = lens, center = c)
                drawCircle(soft, radius = lens, center = c, style = Stroke(side * 0.022f))
                drawLine(soft, c + Offset(lens * 0.7f, lens * 0.7f), c + Offset(lens * 1.5f, lens * 1.5f), strokeWidth = side * 0.035f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            phase == LivePhase.Thinking -> {
                drawCircle(soft.copy(alpha = 0.9f), radius = side * 0.025f, center = Offset(size.width * 0.37f, size.height * 0.4f))
                drawCircle(soft.copy(alpha = 0.9f), radius = side * 0.04f, center = Offset(size.width * 0.32f, size.height * 0.33f))
                drawRoundRect(soft.copy(alpha = 0.95f), Offset(at.x - w / 2, at.y - h / 2), Size(w, h), CornerRadius(h / 2))
                for (k in 0 until 3) { // three dots, each bouncing in turn
                    val bounce = kotlin.math.sin(((t * 3 - k * 0.35f) % 3f).coerceIn(0f, 1f) * Math.PI).toFloat()
                    drawCircle(Color(0xFF3B3430), radius = side * 0.025f, center = Offset(at.x + (k - 1) * w * 0.26f, at.y - bounce * h * 0.2f))
                }
            }
            phase == LivePhase.Speaking && cue != TalkCue.None -> weather(cue, at, w, h, t, side)
        }
    }
}

/** The weather cue: a cloud with drops or flakes falling, a grey cloud, or a sun turning. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.weather(cue: TalkCue, at: Offset, w: Float, h: Float, t: Float, side: Float) {
    if (cue == TalkCue.Sun) {
        val c = at + Offset(0f, h * 0.3f); val r = h * 0.55f
        for (k in 0 until 8) { val a = (k / 8f + t / 8f) * 2 * Math.PI
            val dir = Offset(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
            drawLine(Color(0xFFFFC107), c + dir * (r * 1.25f), c + dir * (r * 1.7f), strokeWidth = side * 0.02f) }
        drawCircle(Color(0xFFFFC107), radius = r, center = c); return
    }
    val grey = if (cue == TalkCue.Cloud) Color(0xFFB0BEC5) else Color(0xFFE3E8EC)
    drawCircle(grey, radius = h * 0.42f, center = at + Offset(-w * 0.18f, 0f))
    drawCircle(grey, radius = h * 0.55f, center = at + Offset(w * 0.08f, -h * 0.12f))
    drawRoundRect(grey, Offset(at.x - w / 2, at.y), Size(w, h * 0.45f), CornerRadius(h * 0.22f))
    if (cue == TalkCue.Rain || cue == TalkCue.Snow) for (k in 0 until 4) { // falling, each a little after the other
        val fall = (t + k * 0.27f) % 1f
        val p = Offset(at.x - w * 0.3f + k * w * 0.2f, at.y + h * 0.6f + fall * h * 1.4f); val d = side * 0.022f
        val c = (if (cue == TalkCue.Rain) Color(0xFF64B5F6) else Color.White).copy(alpha = 1 - fall)
        if (cue == TalkCue.Rain) drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(p.x, p.y - d * 1.4f); lineTo(p.x + d, p.y); lineTo(p.x, p.y + d * 1.4f); lineTo(p.x - d, p.y); close() }, c)
        else drawCircle(c, radius = d, center = p)
    }
}

/** A small television for the widget: cabinet, glass with scan lines, two knobs; [screen] inside it. */
@Composable internal fun MiniTv(modifier: Modifier = Modifier, screen: @Composable () -> Unit) {
    val cabinet = Color(0xFF3B3430); val trim = Color(0xFF5A4E46)
    BoxWithConstraints(modifier.aspectRatio(1.3f).clip(RoundedCornerShape(18.dp)).background(cabinet)) {
        val knobs = maxWidth * 0.16f
        Box(Modifier.fillMaxSize().padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = knobs + 8.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF12201D))
            .drawWithContent {
                drawContent()
                var y = 0f
                while (y < size.height) { drawLine(Color.Black.copy(alpha = 0.16f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f); y += 5f }
                drawRect(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)), radius = size.maxDimension * 0.75f))
            }) { screen() }
        Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).fillMaxSize(0.12f).aspectRatio(0.4f)) {
            listOf(0.2f, 0.55f).forEach { k -> val c = Offset(size.width / 2, size.height * k)
                drawCircle(trim, radius = size.width * 0.45f, center = c); drawCircle(Color(0xFF2A2420), radius = size.width * 0.3f, center = c) }
            drawCircle(Color(0xFFE53935), radius = size.width * 0.12f, center = Offset(size.width / 2, size.height * 0.88f))
        }
    }
}
