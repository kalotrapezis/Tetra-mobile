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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
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
            said.append(words); val now = TalkCue.of(said); val feel = TalkMood.of(said); scope.launch { phase = LivePhase.Speaking; if (now != TalkCue.None || cue.weather) cue = now.takeIf { it != TalkCue.None } ?: cue; mood = feel }
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
internal enum class TalkCue { None, Search, Rain, Snow, Cloud, Sun, Call, Contacts, Pill, Clock, Note, Calendar, Mail, Chat, Map, File;
    val weather get() = this == Rain || this == Snow || this == Cloud || this == Sun
    companion object {
        private val RAIN = Regex("βροχ|μπόρ|καταιγ|ψιχάλ|rain|shower|storm|drizzle", RegexOption.IGNORE_CASE)
        private val SNOW = Regex("χιον|χιόν|snow|sleet", RegexOption.IGNORE_CASE)
        private val CLOUD = Regex("συννεφ|νεφώσ|cloud|overcast", RegexOption.IGNORE_CASE)
        private val SUN = Regex("ηλιοφάν|λιακάδ|αίθρι|ήλιο|sunny|sunshine|clear sk", RegexOption.IGNORE_CASE)
        /** A tool the model called, as a card on the TV (8 Nov: "cards for each command: call, look in the address book…"). */
        fun ofTool(name: String) = if (name == "contact_call") Call else when (AssistantTools.APP_OF[name]) {
            "web", "browser" -> Search; "phone" -> Call; "contacts" -> Contacts; "pills" -> Pill; "timer" -> Clock; "notes" -> Note
            "calendar" -> Calendar; "mail" -> Mail; "sms" -> Chat; "maps", "photos" -> Map; "files" -> File; else -> None
        }
        fun of(text: CharSequence) = when { RAIN.containsMatchIn(text) -> Rain; SNOW.containsMatchIn(text) -> Snow; CLOUD.containsMatchIn(text) -> Cloud; SUN.containsMatchIn(text) -> Sun; else -> None }
    }
}

/** The app's mascot in pixels, 28 × 24 (Assets/robot-pixel.svg): D edge, O shade, Y shell, L shine, V visor, E eyes, H ear pads, W its voice, S the sparkle, G the glow. */
private val MASCOT = listOf(
    "............................",
    "...........DDD..............",
    "........DDDYYYDDD...........",
    ".......DYLYYYYYYYD..........",
    ".....DDYLLLYYYYYYYDD........",
    ".....DYLLLLLYYYYYYYD........",
    "....DYYYLLLYYYYYYYYYD.......",
    "....DYYYYLYYYYYYYYYOD.......",
    "..DODYYYVVVVVVVVVVYOODOD...W",
    "..OHOYYVVVVVVVVVVVVYOOHO...W",
    "..OHOYYVVEEVVVVEEVVYOOHO.W.W",
    "..OHOYYVEVVEVVEVVEVYOOHO.W.W",
    "..OHOYYVEVVEVVEVVEVYOOHO.W.W",
    "..OHOYYVVVVVVVVVVVVOOOHO...W",
    "..DODYYVVVVVVVVVVVVODDOD...W",
    "....DYYYVVVVVVVVVVOOD.......",
    ".....DYYYYYYYYYYYOOD........",
    ".....DDYYYYYYYYYOODD..S.....",
    ".......DOOOOOOOOOD....S.....",
    "........DDDOOODDD...SSHSS...",
    "...........DDD........S.....",
    "......................S.....",
    "........GGGGGGGGGG..........",
    "..........GGGGGG............",
)

/** The robot's colour by the voice chosen (8 Nov: "yellow, pink, white, green, blue… like different characters"); the phone's own voice keeps the mascot's yellow. */
internal val CHARACTER_COLORS = listOf(Color(0xFFFFC93C), Color(0xFFF06292), Color(0xFFECEFF1), Color(0xFF66BB6A), Color(0xFF42A5F5))
internal fun characterColor(voice: String?): Color = if (voice.isNullOrEmpty()) CHARACTER_COLORS[0] else CHARACTER_COLORS[Math.floorMod(voice.hashCode(), CHARACTER_COLORS.size)]

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
@Composable internal fun TalkScreen(phase: LivePhase, hearing: Boolean, modifier: Modifier = Modifier, cue: TalkCue = TalkCue.None, mood: TalkMood = TalkMood.Normal, color: Color = CHARACTER_COLORS[0]) {
    // a long think with nothing to show: the test card, PLEASE STAND BY (8 Nov: "put the please stand by in the waiting")
    var standby by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(phase, cue) { standby = false; if (phase == LivePhase.Thinking && cue == TalkCue.None) { kotlinx.coroutines.delay(STANDBY_MS); standby = true } }
    if (standby) return StandBy(modifier)
    val loop = rememberInfiniteTransition(label = "talk")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "t")
    val blink by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "blink")
    val ink = color; val soft = Color(0xFFF4F1EA)
    Canvas(modifier) {
        val side = minOf(size.width, size.height)
        val r = side * 0.36f
        val shiver = if (mood == TalkMood.Cold) kotlin.math.sin(t * 40 * Math.PI).toFloat() * side * 0.012f else 0f // trembling
        val face = Offset(size.width * 0.62f + shiver, size.height * 0.58f)
        val skin = if (mood == TalkMood.Hot) Color(0xFFE53935) else if (mood == TalkMood.Cold) Color(0xFF64B5F6) else ink
        if (phase == LivePhase.Listening) for (k in 0 until 3) { // rings going out, stronger while a voice is heard
            val p = (t + k / 3f) % 1f
            drawCircle(ink.copy(alpha = (1 - p) * (if (hearing) 0.8f else 0.35f)), radius = r * (1.05f + p * 0.5f), center = face, style = Stroke(side * 0.02f))
        }
        // the app's mascot in pixels (8 Nov: "put the app's mascot, not my sketch"), coloured by its voice, the weather on it
        val cell = side * 0.7f / MASCOT.size
        val top = Offset(face.x - cell * MASCOT[0].length / 2f, face.y - cell * MASCOT.size / 2f)
        val shell = mapOf('Y' to skin, 'L' to androidx.compose.ui.graphics.lerp(skin, Color.White, 0.5f), 'O' to androidx.compose.ui.graphics.lerp(skin, Color.Black, 0.18f),
            'D' to androidx.compose.ui.graphics.lerp(skin, Color.Black, 0.5f))
        val shut = blink > 0.94f
        MASCOT.forEachIndexed { y, row -> row.forEachIndexed { x, ch ->
            val c: Color? = when (ch) {
                'Y', 'L', 'O', 'D' -> shell[ch]
                'V' -> Color(0xFF3B2418)
                'E' -> if (shut) Color(0xFF3B2418) else Color(0xFFFFD86B)
                'H' -> Color(0xFFFFF1C2)
                // its voice: the waves go while it speaks, the outer one after the inner
                'W' -> if (phase == LivePhase.Speaking && kotlin.math.sin((t * 4 - (if (x > 25) 0.25f else 0f)) * 2 * Math.PI) > 0) soft else null
                'S' -> soft.copy(alpha = 0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(t * Math.PI)).toFloat()) // the sparkle twinkles
                'G' -> skin.copy(alpha = 0.35f)
                else -> null
            }
            if (c != null) drawRect(c, top + Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        } }
        if (mood == TalkMood.Hot) { // a sun hat over the helmet: the brim and the crown with its band
            drawRect(Color(0xFFFFE082), top + Offset(4 * cell, 1 * cell), Size(18 * cell, 2 * cell))
            drawRect(Color(0xFFFFE082), top + Offset(8 * cell, -3 * cell), Size(10 * cell, 4 * cell))
            drawRect(Color(0xFFE53935), top + Offset(8 * cell, 0f), Size(10 * cell, cell))
        }
        if (mood == TalkMood.Cold) for (k in 0 until 3) { // a breath of frost
            val p = (t + k / 3f) % 1f
            drawRect(soft.copy(alpha = 0.6f * (1 - p)), top + Offset((4 - p * 4) * cell, (16 + k) * cell), Size(cell, cell))
        }
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
            phase == LivePhase.Thinking && cue == TalkCue.None -> {
                drawCircle(soft.copy(alpha = 0.9f), radius = side * 0.025f, center = Offset(size.width * 0.37f, size.height * 0.4f))
                drawCircle(soft.copy(alpha = 0.9f), radius = side * 0.04f, center = Offset(size.width * 0.32f, size.height * 0.33f))
                drawRoundRect(soft.copy(alpha = 0.95f), Offset(at.x - w / 2, at.y - h / 2), Size(w, h), CornerRadius(h / 2))
                for (k in 0 until 3) { // three dots, each bouncing in turn
                    val bounce = kotlin.math.sin(((t * 3 - k * 0.35f) % 3f).coerceIn(0f, 1f) * Math.PI).toFloat()
                    drawCircle(Color(0xFF3B3430), radius = side * 0.025f, center = Offset(at.x + (k - 1) * w * 0.26f, at.y - bounce * h * 0.2f))
                }
            }
            (phase == LivePhase.Speaking || phase == LivePhase.Thinking) && cue.weather -> weather(cue, at, w, h, t, side)
            phase == LivePhase.Speaking || phase == LivePhase.Thinking -> if (cue != TalkCue.None) toolCard(cue, at, h, t, side)
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

/** A button in the TV's world: square, an ink border and a hard shadow, a monospace label (8 Nov: "a pixelated button to show the sources"). */
@Composable internal fun PixelButton(text: String, color: Color, icon: Int? = null, onClick: () -> Unit) {
    val ink = Color(0xFF2A2420)
    Box(Modifier.padding(end = 4.dp, bottom = 4.dp)) {
        Box(Modifier.matchParentSize().offset(3.dp, 3.dp).background(Color.Black.copy(alpha = 0.45f)))
        androidx.compose.foundation.layout.Row(Modifier.background(color).border(2.dp, ink).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            icon?.let { androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(it), contentDescription = null, Modifier.padding(end = 6.dp).size(16.dp), tint = ink) }
            androidx.compose.material3.Text(text, color = ink, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp))
        }
    }
}

internal const val STANDBY_MS = 3_500L

/** An old test card of our own: grey glass, a ring with its wedges and scale, the cross, and the words across it. */
@Composable internal fun StandBy(modifier: Modifier = Modifier) {
    val words = androidx.compose.ui.text.rememberTextMeasurer()
    val flicker by rememberInfiniteTransition(label = "standby").animateFloat(0.88f, 1f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "flicker")
    Canvas(modifier.background(Color(0xFFBDBDB4))) {
        val side = minOf(size.width, size.height); val c = center; val r = side * 0.42f
        val ink = Color(0xFF3A3A36).copy(alpha = flicker)
        drawLine(ink, Offset(0f, c.y), Offset(size.width, c.y), strokeWidth = 2f); drawLine(ink, Offset(c.x, 0f), Offset(c.x, size.height), strokeWidth = 2f)
        listOf(Color(0xFF8D8D85), Color(0xFF6F6F68), Color(0xFFA5A59D), Color(0xFF55554F)).forEachIndexed { i, g ->
            drawArc(g.copy(alpha = flicker), -60f + i * 90f, 32f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r)) }
        drawCircle(ink, radius = r, center = c, style = Stroke(side * 0.02f))
        for (k in 1..3) drawCircle(ink, radius = r * 0.08f * k, center = c, style = Stroke(1.5f))
        for (k in -4..4) drawLine(ink, Offset(c.x + k * r * 0.05f, c.y - r), Offset(c.x + k * r * 0.05f, c.y - r * 0.55f), strokeWidth = 1.2f)
        val label = words.measure("PLEASE STAND BY", androidx.compose.ui.text.TextStyle(color = Color(0xFFF5F5EE), fontSize = (side / 9f).toSp(),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Black, letterSpacing = (side / 40f).toSp(),
            shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), Offset(3f, 3f), 2f)))
        drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - label.size.height / 2f))
    }
}

/** A tool's card, up at the left: a phone, the address book, a pill, a clock, a note, a calendar page, a letter, a speech bubble, a pin, a file. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.toolCard(cue: TalkCue, at: Offset, h: Float, t: Float, side: Float) {
    val paper = Color(0xFFF4F1EA); val ink = Color(0xFF3B3430); val s = h * 0.9f
    val bob = kotlin.math.sin(t * 2 * Math.PI).toFloat() * h * 0.08f
    val o = at + Offset(0f, bob); val stroke = Stroke(side * 0.018f)
    when (cue) {
        TalkCue.Call -> { // a handset, ringing
            val tilt = kotlin.math.sin(t * 12 * Math.PI).toFloat() * 12f
            rotate(tilt, o) {
                drawRoundRect(Color(0xFF66BB6A), Offset(o.x - s * 0.7f, o.y - s * 0.18f), Size(s * 1.4f, s * 0.36f), CornerRadius(s * 0.18f))
                drawRoundRect(Color(0xFF66BB6A), Offset(o.x - s * 0.8f, o.y - s * 0.1f), Size(s * 0.4f, s * 0.5f), CornerRadius(s * 0.12f))
                drawRoundRect(Color(0xFF66BB6A), Offset(o.x + s * 0.4f, o.y - s * 0.1f), Size(s * 0.4f, s * 0.5f), CornerRadius(s * 0.12f))
            }
        }
        TalkCue.Contacts -> { // the address book, pages turning
            drawRoundRect(Color(0xFF8D6E63), Offset(o.x - s * 0.5f, o.y - s * 0.6f), Size(s, s * 1.2f), CornerRadius(s * 0.08f))
            drawRect(paper, Offset(o.x - s * 0.36f, o.y - s * 0.5f), Size(s * 0.78f, s))
            drawCircle(ink, radius = s * 0.14f, center = o + Offset(0f, -s * 0.18f))
            drawRoundRect(ink, Offset(o.x - s * 0.24f, o.y + s * 0.02f), Size(s * 0.48f, s * 0.22f), CornerRadius(s * 0.1f))
            val page = (t * 2) % 1f
            drawRect(paper.copy(alpha = 0.8f), Offset(o.x - s * 0.36f, o.y - s * 0.5f), Size(s * 0.78f * (1 - page), s))
        }
        TalkCue.Pill -> { // a capsule hopping, and spinning when it lands
            val hop = kotlin.math.abs(kotlin.math.sin(t * 2 * Math.PI)).toFloat() * h * 0.5f
            val p = at + Offset(0f, -hop)
            rotate(if (hop < h * 0.05f) t * 360f else 30f, p) {
                drawRoundRect(Color(0xFFF06292), Offset(p.x - s * 0.6f, p.y - s * 0.22f), Size(s * 0.6f, s * 0.44f), CornerRadius(s * 0.22f, s * 0.22f))
                drawRoundRect(paper, Offset(p.x, p.y - s * 0.22f), Size(s * 0.6f, s * 0.44f), CornerRadius(s * 0.22f, s * 0.22f))
                drawRect(Color(0xFFF06292), Offset(p.x - s * 0.1f, p.y - s * 0.22f), Size(s * 0.1f, s * 0.44f))
                drawRect(paper, Offset(p.x, p.y - s * 0.22f), Size(s * 0.1f, s * 0.44f))
            }
        }
        TalkCue.Clock -> { // a clock, its hand going round
            drawCircle(paper, radius = s * 0.55f, center = o); drawCircle(ink, radius = s * 0.55f, center = o, style = stroke)
            val a = t * 2 * Math.PI - Math.PI / 2
            drawLine(ink, o, o + Offset(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat()) * (s * 0.42f), strokeWidth = side * 0.02f)
            drawLine(ink, o, o + Offset(0f, -s * 0.28f), strokeWidth = side * 0.026f)
        }
        TalkCue.Note -> { // a sticky note being written
            drawRect(Color(0xFFFFF176), Offset(o.x - s * 0.5f, o.y - s * 0.5f), Size(s, s))
            val written = (t * 3).toInt() % 4
            for (k in 0 until written) drawLine(ink, Offset(o.x - s * 0.35f, o.y - s * 0.25f + k * s * 0.22f), Offset(o.x + s * 0.3f, o.y - s * 0.25f + k * s * 0.22f), strokeWidth = side * 0.012f)
        }
        TalkCue.Calendar -> { // a calendar page
            drawRect(paper, Offset(o.x - s * 0.5f, o.y - s * 0.45f), Size(s, s * 0.95f))
            drawRect(Color(0xFFE91E63), Offset(o.x - s * 0.5f, o.y - s * 0.45f), Size(s, s * 0.25f))
            for (k in 0 until 6) drawRect(ink, Offset(o.x - s * 0.38f + (k % 3) * s * 0.28f, o.y - s * 0.08f + (k / 3) * s * 0.26f), Size(s * 0.16f, s * 0.14f))
        }
        TalkCue.Mail -> { // a letter flying in
            val fly = ((t * 1.5f) % 1f)
            val m = o + Offset((1 - fly) * h * 0.6f, 0f)
            drawRect(paper, Offset(m.x - s * 0.6f, m.y - s * 0.38f), Size(s * 1.2f, s * 0.76f))
            drawLine(ink, Offset(m.x - s * 0.6f, m.y - s * 0.38f), Offset(m.x, m.y + s * 0.05f), strokeWidth = side * 0.012f)
            drawLine(ink, Offset(m.x + s * 0.6f, m.y - s * 0.38f), Offset(m.x, m.y + s * 0.05f), strokeWidth = side * 0.012f)
        }
        TalkCue.Chat -> { // a speech bubble, typing
            drawRoundRect(Color(0xFF81D4FA), Offset(o.x - s * 0.65f, o.y - s * 0.4f), Size(s * 1.3f, s * 0.7f), CornerRadius(s * 0.3f))
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(o.x - s * 0.3f, o.y + s * 0.25f); lineTo(o.x - s * 0.5f, o.y + s * 0.55f); lineTo(o.x - s * 0.05f, o.y + s * 0.28f); close() }, Color(0xFF81D4FA))
            for (k in 0 until 3) { val on = ((t * 3).toInt() % 3) >= k; drawCircle(if (on) ink else ink.copy(alpha = 0.3f), radius = s * 0.07f, center = o + Offset((k - 1) * s * 0.3f, -s * 0.05f)) }
        }
        TalkCue.Map -> { // a pin dropping on the map
            val drop = (1 - ((t * 1.5f) % 1f).coerceAtMost(0.5f) * 2) * h * 0.5f
            drawOval(Color(0xFF66BB6A).copy(alpha = 0.6f), Offset(o.x - s * 0.5f, o.y + s * 0.3f), Size(s, s * 0.3f))
            drawCircle(Color(0xFFE53935), radius = s * 0.28f, center = o + Offset(0f, -s * 0.2f - drop))
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(o.x - s * 0.24f, o.y - s * 0.08f - drop); lineTo(o.x, o.y + s * 0.42f - drop); lineTo(o.x + s * 0.24f, o.y - s * 0.08f - drop); close() }, Color(0xFFE53935))
            drawCircle(paper, radius = s * 0.1f, center = o + Offset(0f, -s * 0.2f - drop))
        }
        TalkCue.File -> { // a page with its corner folded
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(o.x - s * 0.4f, o.y - s * 0.55f); lineTo(o.x + s * 0.15f, o.y - s * 0.55f); lineTo(o.x + s * 0.4f, o.y - s * 0.3f); lineTo(o.x + s * 0.4f, o.y + s * 0.55f); lineTo(o.x - s * 0.4f, o.y + s * 0.55f); close() }, paper)
            for (k in 0 until 3) drawLine(ink, Offset(o.x - s * 0.25f, o.y - s * 0.1f + k * s * 0.2f), Offset(o.x + s * 0.25f, o.y - s * 0.1f + k * s * 0.2f), strokeWidth = side * 0.012f)
        }
        else -> Unit
    }
}
