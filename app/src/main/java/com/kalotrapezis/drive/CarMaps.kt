package com.kalotrapezis.drive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File

/**
 * Tetra Auto SE's maps, from the phone (the user, 13 Nov: "a guide in the first run to download the map info from the phone … or
 * add or update the maps from the phone if there are more countries on the phone"). The phone lists what its Maps folder has
 * (/car/maps) and gives each file (GET /car/map) to the paired unit only, over Tetra Car's own pinned link (CarWire); the unit
 * takes what it does not have or has older — a country more, a newer map, its routing graph, the saved places — over the
 * hotspot's Wi-Fi, never the phone's mobile data being needed for it. Only on the car's own build (BuildConfig .se).
 */
internal object CarMaps {
    /** Tetra Auto SE: Tetra for Android built for a head unit (the car module). */
    val edition: Boolean get() = BuildConfig.APPLICATION_ID.endsWith(".se")

    private fun root() = MapsFiles.root(driveRoot())

    // ---- the phone ----

    /** Every file under the phone's Maps folder: path → {size, modified}. */
    fun list(): JSONObject = JSONObject().apply {
        val root = root()
        root.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }
            .forEach { put(it.relativeTo(root).invariantSeparatorsPath, JSONObject().put("size", it.length()).put("modified", it.lastModified())) }
    }

    /** A path the unit asked for, if it is a file inside the Maps folder (no way out of it). */
    fun file(path: String): File? {
        val root = root().canonicalFile
        val f = File(root, path).canonicalFile
        return f.takeIf { it.isFile && it.path.startsWith(root.path + File.separator) }
    }

    // ---- the unit ----

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** The phone has [files] this unit lacks or has older: [countries] among them, [bytes] in all; [first]: the unit has no map yet. */
        data class Offer(val files: List<Pair<String, JSONObject>>, val countries: List<String>, val bytes: Long, val first: Boolean) : State
        data class Moving(val done: Long, val total: Long, val name: String) : State
        data class Failed(val why: String) : State
    }
    val state = mutableStateOf<State>(State.Idle)
    /** Bumped when maps came: Maps draws them again. */
    val version = mutableIntStateOf(0)
    private val main = Handler(Looper.getMainLooper())
    /** "Not now" for this offer: asked again when the phone has something else. */
    private var declined: Set<String> = emptySet()

    fun haveMap() = MapsFiles.ids(root()).isNotEmpty()

    fun storageOk(c: Context) = storageAllowed(c)

    /** Asks the phone what maps it has (on a thread of its own); an offer when it has more or newer. */
    fun check(c: Context) {
        if (state.value is State.Moving || state.value is State.Checking) return
        state.value = State.Checking
        Thread {
            val next: State = runCatching {
                val theirs = CarWire.maps(c.applicationContext)
                val root = root()
                val want = theirs.keys().asSequence().map { it to theirs.getJSONObject(it) }.filter { (path, o) ->
                    val mine = File(root, path)
                    !mine.isFile || mine.length() != o.optLong("size") || mine.lastModified() < o.optLong("modified") - 2000
                }.sortedBy { it.first }.toList()
                if (want.isEmpty() || want.map { it.first }.toSet() == declined) State.Idle
                else State.Offer(want, want.map { it.first }.filter { it.endsWith(".pmtiles") }.map { it.removeSuffix(".pmtiles").replaceFirstChar(Char::uppercase) },
                    want.sumOf { it.second.optLong("size") }, !haveMap())
            }.getOrElse { State.Failed(it.message ?: "") }
            main.post { state.value = next }
        }.start()
    }

    fun decline(o: State.Offer) { declined = o.files.map { it.first }.toSet(); state.value = State.Idle }

    /** The offered files from the phone, one after another, each kept only once it has all arrived. */
    fun download(c: Context, o: State.Offer) {
        val root = root()
        val free = runCatching { StatFs(root.apply { mkdirs() }.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < o.bytes + (200L shl 20)) { state.value = State.Failed(c.getString(R.string.car_maps_no_space, o.bytes shr 20, free shr 20)); return }
        state.value = State.Moving(0, o.bytes, "")
        WriterLog.i("Car maps: ${o.files.size} files from the phone, ${o.bytes shr 20} MB")
        Thread {
            var before = 0L
            val failed = runCatching {
                for ((path, info) in o.files) {
                    val f = File(root, path).also { it.parentFile?.mkdirs() }
                    val part = File(f.parentFile, ".${f.name}.part")
                    var last = 0L
                    part.outputStream().buffered(1 shl 16).use { out ->
                        CarWire.download(c.applicationContext, path, out) { got ->
                            if (got - last > (4L shl 20)) { last = got; val at = before + got; main.post { state.value = State.Moving(at, o.bytes, path) } }
                        }
                    }
                    check(part.length() == info.optLong("size")) { "$path came incomplete." }
                    f.delete(); check(part.renameTo(f)) { "$path not kept." }
                    f.setLastModified(info.optLong("modified"))
                    before += info.optLong("size")
                }
            }.exceptionOrNull()
            WriterLog.i("Car maps: ${if (failed == null) "all came" else "stopped (${failed.message})"}")
            main.post {
                version.intValue++
                state.value = if (failed == null) State.Idle else State.Failed(failed.message ?: "")
            }
        }.start()
    }
}

/**
 * The guide over Tetra Car's map, on the car's build only: with no map yet, the first run's three steps — Tetra's storage, the
 * phone paired, the maps from it; after that a line when the phone has more countries or newer maps.
 */
@Composable
internal fun CarMapsGuide(modifier: Modifier = Modifier, pair: () -> Unit) {
    if (!CarMaps.edition) return
    val context = LocalContext.current
    val state by CarMaps.state
    val reached by CarWire.reached
    var storage by remember { mutableStateOf(CarMaps.storageOk(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { storage = CarMaps.storageOk(context) }
    val paired = CarWire.link(context) != null
    LaunchedEffect(reached, storage) { if (reached && storage) CarMaps.check(context) }
    val first = !CarMaps.haveMap()
    val s = state
    if (!first && (s is CarMaps.State.Idle || s is CarMaps.State.Checking)) return // nothing to say over a map that is there
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = if (first) Alignment.Center else Alignment.TopCenter) {
        Surface(Modifier.widthIn(max = 620.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_map), null, Modifier.size(32.dp), tint = MAPS_COLOR)
                    Text(stringResource(if (first) R.string.car_maps_title else R.string.car_maps_update_title), style = MaterialTheme.typography.headlineSmall)
                }
                if (first) {
                    Text(stringResource(R.string.car_maps_intro), style = MaterialTheme.typography.bodyLarge)
                    Step(1, stringResource(R.string.car_maps_step_storage), storage) {
                        if (Build.VERSION.SDK_INT >= 30) context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                        else ask.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                    Step(2, stringResource(R.string.car_maps_step_pair), paired && reached, pair)
                }
                when (s) {
                    is CarMaps.State.Offer -> {
                        Text(stringResource(R.string.car_maps_offer, s.countries.ifEmpty { listOf(stringResource(R.string.car_maps_updates)) }.joinToString(", "), s.bytes shr 20),
                            style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button({ CarMaps.download(context, s) }) { Text(stringResource(if (s.first) R.string.car_maps_download else R.string.car_maps_update)) }
                            if (!s.first) TextButton({ CarMaps.decline(s) }) { Text(stringResource(R.string.car_maps_not_now)) }
                        }
                    }
                    is CarMaps.State.Moving -> {
                        Text(stringResource(R.string.car_maps_moving, s.done shr 20, s.total shr 20), style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, Modifier.fillMaxWidth())
                    }
                    is CarMaps.State.Failed -> {
                        Text(stringResource(R.string.car_maps_failed, s.why), color = MaterialTheme.colorScheme.error)
                        TextButton({ CarMaps.check(context) }) { Text(stringResource(R.string.car_maps_again)) }
                    }
                    CarMaps.State.Checking -> Text(stringResource(R.string.car_maps_checking))
                    CarMaps.State.Idle -> if (first && storage && paired && reached) Text(stringResource(R.string.car_maps_phone_none))
                }
            }
        }
    }
}

/** One step of the guide: its number (a tick when done) and what to do, a tap to do it. */
@Composable
private fun Step(n: Int, text: String, done: Boolean, act: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp).background(if (done) MAPS_COLOR else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else "$n", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White)
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (!done) Button(act) { Text(stringResource(R.string.car_maps_do)) }
    }
}

/** Whether Tetra may use its folder: all files access (Android 11 and later), the storage permission on Android 10 (Tetra Auto SE's head units). */
internal fun storageAllowed(c: Context) = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/**
 * Tetra Auto SE's size on the car's screen (the user, 13 Nov: "the arrow … and the UI elements big enough … scaling settings in
 * 25% increments"): a head unit says 160 dpi, so Tetra drew at a phone's size there — the arrow a dot, the names tiny. Every
 * screen of Tetra is drawn this much bigger (AppLocale.wrap sets the density), the map's names and arrow with it, as Android
 * itself does on a denser screen. 150 % until chosen; 1 on the phone, which is never scaled.
 */
internal object CarSize {
    val STEPS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f)
    fun of(c: Context): Float = if (!CarMaps.edition) 1f else c.getSharedPreferences("car", Context.MODE_PRIVATE).getFloat("screen_scale", 1.5f)
    fun set(c: Context, v: Float) = c.getSharedPreferences("car", Context.MODE_PRIVATE).edit().putFloat("screen_scale", v).commit()
}
