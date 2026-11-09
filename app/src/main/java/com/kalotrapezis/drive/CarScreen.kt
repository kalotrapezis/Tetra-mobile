package com.kalotrapezis.drive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Tetra Car (the user, 9 Nov: "our own Car UI in the app … AI, browser for youtube or radio, maps, messages and phone"; Android's
 * split screen "is horrible"): a head unit's home screen. Tetra's own pages side by side in panes (Full, 60/40, 50/50, three), a
 * dock at the bottom with AI Assist's mic in its middle, and Media: the Browser in Player mode, YouTube Music, YouTube or
 * Live24.gr. A page keeps running when its pane is given to another (the route, the music), until Tetra Car closes.
 */
class CarActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        TetraLook.load(this)
        CalendarIndex.dir = cacheDir; NotesStore.indexDir = cacheDir // the caches MainActivity names at its start
        if (PhoneLog.recent == null) PhoneLog.warm(this)
        if (ContactsPreload.all == null) ContactsPreload.warm(this)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = TetraLook.light
        WriterLog.i("Tetra Car opened")
        setContent { TetraTheme { CarScreen() } }
    }
    // the steering wheel's voice key, where the head unit passes it on: AI Assist listens
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_SEARCH) { carListen(this); return true }
        return super.onKeyDown(keyCode, event)
    }
}

/** AI Assist's sheet over the car's screen: it listens at once, answers, and goes. */
internal fun carListen(c: Context) = c.startActivity(Intent(c, AssistantSheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** Settings › App icons › Tetra Car: its launcher icon, also offered as the home screen (off until asked for, as the applets'). */
internal object CarIcon {
    private fun component(c: Context) = ComponentName(c.packageName, "${CarActivity::class.java.packageName}.AppCar")
    fun shown(c: Context) = c.packageManager.getComponentEnabledSetting(component(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    fun show(c: Context, on: Boolean) {
        c.packageManager.setComponentEnabledSetting(component(c),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        WriterLog.i("App icon ${if (on) "shown" else "hidden"}: Car")
    }
}

/** What a pane shows. Media is the Browser too (one Browser: its tabs and its sound), opened on its three tiles. */
internal enum class CarApp { Maps, Browser, Phone, Messages }

internal enum class CarLayout(val weights: List<Float>) { Full(listOf(1f)), Wide(listOf(0.6f, 0.4f)), Half(listOf(0.5f, 0.5f)), Three(listOf(0.5f, 0.25f, 0.25f)) }

/** The panes left to right, and the one the dock fills ([focus]); kept as "Wide|Maps,Browser|1". */
internal data class CarState(val layout: CarLayout = CarLayout.Wide, val panes: List<CarApp> = listOf(CarApp.Maps, CarApp.Browser), val focus: Int = 1) {
    /** Another layout: the pages shown stay in their order, the next ones fill a new pane, the last ones go. */
    fun laid(l: CarLayout): CarState {
        val apps = (panes.distinct() + CarApp.entries.filter { it !in panes }).take(l.weights.size)
        return CarState(l, apps, focus.coerceIn(apps.indices))
    }
    /** The dock's button: a page already shown is focused where it is, else it takes the focused pane. */
    fun put(app: CarApp): CarState = panes.indexOf(app).let { i -> if (i >= 0) copy(focus = i) else copy(panes = panes.toMutableList().also { it[focus] = app }) }
    fun focused(i: Int) = copy(focus = i.coerceIn(panes.indices))
    val shown get() = panes[focus]
    override fun toString() = "${layout.name}|${panes.joinToString(",") { it.name }}|$focus"
    companion object {
        fun parse(s: String?): CarState = runCatching {
            val (l, p, f) = s!!.split("|")
            CarState(CarLayout.valueOf(l), p.split(",").map(CarApp::valueOf), f.toInt()).let { if (it.panes.distinct().size != it.layout.weights.size) it.laid(it.layout) else it.focused(it.focus) }
        }.getOrDefault(CarState())
    }
}

/** Each pane's left edge and width in [width] pixels, [gap] between them; the last pane takes what rounding left. */
internal fun carSpans(width: Int, gap: Int, weights: List<Float>): List<Pair<Int, Int>> {
    val free = width - gap * (weights.size - 1)
    var x = 0
    return weights.mapIndexed { i, w -> val pw = if (i == weights.lastIndex) width - x else (free * w).roundToInt(); (x to pw).also { x += pw + gap } }
}

/** Live24.gr's tile: the first ♥ station of live24.gr, else Maps' ♪ when it is one, else the site's front page. */
internal fun carLive24(bookmarks: List<Bookmark>, mapsStation: String?): String {
    fun live24(url: String) = runCatching { java.net.URI(url).host.orEmpty().lowercase().let { it == "live24.gr" || it.endsWith(".live24.gr") } }.getOrDefault(false)
    return bookmarks.firstOrNull { live24(it.url) }?.url ?: mapsStation?.takeIf(::live24) ?: "https://www.live24.gr"
}

private fun carPlay(o: JSONObject) = "browser-play:" + o.put("player", true).put("at", System.currentTimeMillis())

/** A pane's own Back: the page in it hears Back only while its pane is the focused one. */
private class PaneBack(override val lifecycle: Lifecycle) : OnBackPressedDispatcherOwner {
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
}

@Composable
private fun CarScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("car", Context.MODE_PRIVATE) }
    var state by remember { mutableStateOf(CarState.parse(prefs.getString("state", null))) }
    fun set(s: CarState) { state = s; prefs.edit().putString("state", s.toString()).apply() }
    val opened = remember { mutableStateListOf<CarApp>().apply { addAll(state.panes) } } // pages once shown stay running
    LaunchedEffect(state.panes) { state.panes.forEach { if (it !in opened) opened += it } }
    var play by remember { mutableStateOf<String?>(null) }
    var tiles by remember { mutableStateOf(false) }
    var layouts by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val backs = remember { CarApp.entries.associateWith { PaneBack(lifecycle) } }
    // the home screen never closes on Back: the focused page goes back, else nothing
    BackHandler {
        when {
            apps -> apps = false
            layouts -> layouts = false
            tiles && state.shown == CarApp.Browser -> tiles = false
            else -> backs.getValue(state.shown).onBackPressedDispatcher.let { if (it.hasEnabledCallbacks()) it.onBackPressed() }
        }
    }
    fun media() {
        val wasShown = state.shown == CarApp.Browser
        set(state.put(CarApp.Browser))
        when {
            wasShown && !tiles -> tiles = true // Media again: back to the three
            BrowserMedia.title.isNotEmpty() && BrowserMedia.control != null -> { tiles = false; play = carPlay(JSONObject()) } // what plays now
            else -> tiles = true
        }
        WriterLog.i("Car: Media${if (tiles) " (tiles)" else ""}")
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            CarPanes(state, opened, Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
                focus = { app -> state.panes.indexOf(app).takeIf { it >= 0 && it != state.focus }?.let { set(state.focused(it)) } }) { app ->
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backs.getValue(app)) {
                    when (app) {
                        CarApp.Maps -> MapsScreen(MapsFiles.root(driveRoot()), null, back = {})
                        CarApp.Browser -> Box(Modifier.fillMaxSize()) {
                            BrowserScreen(back = {}, play = play, hide = {})
                            if (tiles) CarMediaTiles(Modifier.fillMaxSize()) { o -> play = carPlay(o); tiles = false }
                        }
                        CarApp.Phone -> {
                            val keypad = remember { mutableStateOf(false) }
                            val tab = remember { mutableStateOf(PhoneTab.Recents) }
                            val number = remember { mutableStateOf("") }
                            val missed = remember { mutableStateOf(0) }
                            val blocked = remember { mutableStateOf<String?>(null) }
                            PhoneApp(keypad, tab, number, missed, blocked, back = {})
                        }
                        CarApp.Messages -> SmsScreen(back = {})
                    }
                }
            }
            CarDock(state, layouts, setLayouts = { layouts = it }, layout = { set(state.laid(it)) },
                open = { set(state.put(it)); if (it == CarApp.Browser) tiles = false }, media = ::media, apps = { apps = true })
        }
        if (apps) CarApps { apps = false }
    }
}

/** The panes: every page once opened stays composed; the ones not shown wait off the screen, still running. */
@Composable
private fun CarPanes(state: CarState, opened: List<CarApp>, modifier: Modifier, focus: (CarApp) -> Unit, content: @Composable (CarApp) -> Unit) {
    val ring = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    Layout(modifier = modifier, content = {
        opened.forEach { app ->
            key(app) {
                val on = state.panes.size > 1 && state.shown == app
                Box(Modifier.clip(shape)
                    .pointerInput(app) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); focus(app) } }
                    .drawWithContent { drawContent(); if (on) drawRoundRect(ring, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(3.dp.toPx())) }) {
                    // the pages choose phone or tablet layouts by the screen's size: each is told its pane's
                    BoxWithConstraints {
                        val base = LocalConfiguration.current
                        val w = maxWidth.value.toInt(); val h = maxHeight.value.toInt()
                        val pane = remember(base, w, h) { Configuration(base).apply { screenWidthDp = w; screenHeightDp = h; smallestScreenWidthDp = minOf(w, h) } }
                        CompositionLocalProvider(LocalConfiguration provides pane) { content(app) }
                    }
                }
            }
        }
    }) { measurables, c ->
        val spans = carSpans(c.maxWidth, 8.dp.roundToPx(), state.layout.weights)
        val placeables = measurables.mapIndexed { i, m ->
            val at = state.panes.indexOf(opened[i])
            m.measure(Constraints.fixed((spans.getOrNull(at) ?: spans[0]).second, c.maxHeight))
        }
        layout(c.maxWidth, c.maxHeight) {
            placeables.forEachIndexed { i, p -> val at = state.panes.indexOf(opened[i]); if (at >= 0) p.place(spans[at].first, 0) else p.place(-2 * c.maxWidth, 0) }
        }
    }
}

@Composable
private fun CarDock(state: CarState, layouts: Boolean, setLayouts: (Boolean) -> Unit, layout: (CarLayout) -> Unit, open: (CarApp) -> Unit, media: () -> Unit, apps: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (layouts) { // the layouts' strip, with its way back (the user's sliding strips)
            IconButton({ setLayouts(false) }) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
            Spacer(Modifier.weight(1f))
            CarLayout.entries.forEach { l -> Box(Modifier.size(88.dp, 64.dp).clip(RoundedCornerShape(12.dp)).clickable { layout(l); setLayouts(false) }, contentAlignment = Alignment.Center) { LayoutGlyph(l, l == state.layout) } }
            Spacer(Modifier.weight(1f))
            return@Row
        }
        NowPlaying(Modifier.weight(1f), media)
        DockApp(R.drawable.maps_art, R.string.app_maps, CarApp.Maps in state.panes) { open(CarApp.Maps) }
        DockIcon(R.drawable.ic_play, R.string.car_media, Color(0xFFE53935), media)
        DockApp(R.drawable.phone_art, R.string.app_phone, CarApp.Phone in state.panes) { open(CarApp.Phone) }
        val context = LocalContext.current
        Box(Modifier.padding(horizontal = 12.dp).size(68.dp).clip(CircleShape).background(ASSISTANT_COLOR).clickable { carListen(context) }, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_mic), stringResource(R.string.app_assistant), Modifier.size(34.dp), tint = Color.White)
        }
        DockApp(R.drawable.sms_art, R.string.app_sms, CarApp.Messages in state.panes) { open(CarApp.Messages) }
        DockApp(R.drawable.browser_art, R.string.app_browser, CarApp.Browser in state.panes) { open(CarApp.Browser) }
        DockIcon(R.drawable.ic_view_grid, R.string.car_apps, MaterialTheme.colorScheme.secondary, apps)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.size(72.dp, 56.dp).clip(RoundedCornerShape(12.dp)).clickable { setLayouts(true) }, contentAlignment = Alignment.Center) { LayoutGlyph(state.layout, true) }
        }
    }
}

@Composable
private fun DockApp(art: Int, label: Int, shown: Boolean, tap: () -> Unit) = DockButton(label, shown, tap) { Image(painterResource(art), null, Modifier.size(44.dp)) }

@Composable
private fun DockIcon(icon: Int, label: Int, color: Color, tap: () -> Unit) = DockButton(label, false, tap) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(28.dp), tint = Color.White) }
}

@Composable
private fun DockButton(label: Int, shown: Boolean, tap: () -> Unit, art: @Composable () -> Unit) {
    Column(Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = tap).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        art()
        Text(stringResource(label), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(top = 2.dp).size(5.dp).clip(CircleShape).background(if (shown) MaterialTheme.colorScheme.primary else Color.Transparent)) // on the screen now
    }
}

/** A layout drawn as its panes. */
@Composable
private fun LayoutGlyph(l: CarLayout, on: Boolean) {
    val color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(48.dp, 30.dp)) {
        val gap = 3.dp.toPx()
        carSpans(size.width.roundToInt(), gap.roundToInt(), l.weights).forEach { (x, w) ->
            drawRoundRect(color, Offset(x.toFloat(), 0f), Size(w.toFloat(), size.height), CornerRadius(4.dp.toPx()), style = if (on) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2.dp.toPx()))
        }
    }
}

/** What the Browser plays, from any layout: ‹ ⏯ › and its name; the name opens Media. */
@Composable
private fun NowPlaying(modifier: Modifier, media: () -> Unit) {
    val now by produceState<Pair<String, Boolean>?>(null) {
        while (true) { value = BrowserMedia.title.takeIf { it.isNotEmpty() && BrowserMedia.control != null }?.let { it to BrowserMedia.playing }; delay(500) }
    }
    val (title, playing) = now ?: run { Spacer(modifier); return }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton({ BrowserMedia.act("prev") }) { Icon(painterResource(R.drawable.ic_skip_previous), null) }
        IconButton({ BrowserMedia.act("toggle") }) { Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), null, Modifier.size(32.dp)) }
        IconButton({ BrowserMedia.act("next") }) { Icon(painterResource(R.drawable.ic_skip_next), null) }
        Text(title, Modifier.weight(1f).clickable(onClick = media), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Media's three: each opens in Player mode; Live24.gr's station plays at once. */
@Composable
private fun CarMediaTiles(modifier: Modifier, pick: (JSONObject) -> Unit) {
    val context = LocalContext.current
    val live = remember {
        val p = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
        carLive24(BrowserRules.bookmarks(p.getString("bookmarks", null)), BrowserMedia.station(context)?.first)
    }
    val choices = listOf(
        Triple("YouTube Music", Color(0xFFC62828), JSONObject().put("url", "https://music.youtube.com").put("open", true)),
        Triple("YouTube", Color(0xFFE53935), JSONObject().put("url", "https://m.youtube.com").put("open", true)),
        Triple("Live24.gr", Color(0xFF1565C0), JSONObject().put("url", live)),
    )
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
        val tile: @Composable (Modifier, Triple<String, Color, JSONObject>) -> Unit = { m, (name, color, o) ->
            Box(m.clip(RoundedCornerShape(20.dp)).background(color).clickable { WriterLog.i("Car: Media › $name"); pick(o) }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_play), null, Modifier.size(40.dp), tint = Color.White)
                    Text(name, color = Color.White, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (maxWidth > maxHeight) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { choices.forEach { tile(Modifier.weight(1f).fillMaxSize(), it) } }
        else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) { choices.forEach { tile(Modifier.weight(1f).fillMaxWidth(), it) } }
    }
}

/** The dock's Apps: every app on the head unit; Android puts it beside Tetra Car where the unit allows, else in front. */
@Composable
private fun CarApps(close: () -> Unit) {
    val context = LocalContext.current
    val all by produceState(emptyList<Triple<ComponentName, String, ImageBitmap>>()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.name != "${CarActivity::class.java.packageName}.AppCar" }
                .map { r -> Triple(ComponentName(r.activityInfo.packageName, r.activityInfo.name), r.loadLabel(pm).toString(), r.loadIcon(pm).toBitmap(96, 96).asImageBitmap()) }
                .sortedBy { it.second.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
            Text(stringResource(R.string.car_apps), style = MaterialTheme.typography.titleLarge)
        }
        LazyVerticalGrid(GridCells.Adaptive(112.dp), Modifier.fillMaxSize().padding(8.dp)) {
            items(all, key = { it.first.flattenToString() }) { (component, label, icon) ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)) }
                        .onFailure { WriterLog.w("Car: ${component.flattenToShortString()} not opened", it) }
                    close()
                }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(icon, null, Modifier.size(56.dp))
                    Text(label, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
