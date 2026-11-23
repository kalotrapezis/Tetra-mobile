package com.kalotrapezis.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Share with Tetra Auto SE (the head unit's own app) over the car link: the phone's Send to car keeps the files in its Tetra › To car;
 * the car takes them as it takes the places (CarMaps' "@share/"), and the phone forgets them. A file of the car's (a USB stick's) goes
 * to the phone's Tetra › From car.
 */
internal object CarShare {
    const val PREFIX = "@share/"
    /** The phone's Tetra › From car: the car's files (SyncServer's PUT /car/send). */
    const val FROM_CAR = "From car"
    /** The phone's To car. */
    fun dir(): File = File(driveRoot(), "To car")

    /** On the phone: Send to car while the car's own Tetra Car is linked. */
    fun send(c: Context, uris: List<Uri>) = Thread({
        val d = dir().apply { mkdirs() }
        for (uri in uris) runCatching {
            File(d, name(c, uri)).outputStream().use { out -> c.contentResolver.openInputStream(uri)!!.use { it.copyTo(out, 1 shl 16) } }
        }.onFailure { WriterLog.w("Car share: not kept for the car (${it.message})") }
        WriterLog.i("Car share: ${uris.size} for the car")
        CarWire.tell(c, JSONObject().put("type", "share")) // the car takes them now
    }, "car-share").start()

    fun name(c: Context, uri: Uri) = safeName(c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "file").takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "file"

    fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
        .trim().trimStart('.').take(120).ifEmpty { "file" }

    /** On the phone: the car has [names] now, off To car. */
    fun taken(names: JSONArray) {
        val d = dir().canonicalFile
        for (i in 0 until names.length()) File(d, names.getString(i)).canonicalFile.takeIf { it.parentFile == d && it.isFile }?.delete()
    }

    fun list(): List<File> = dir().listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.sortedByDescending { it.lastModified() }

    /** A file from the phone, opened: an app is offered for installing (a Tetra Car update), the rest by its kind. */
    fun open(c: Context, f: File) {
        val type = if (f.name.endsWith(".apk", true)) "application/vnd.android.package-archive"
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        runCatching {
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(c, "${c.packageName}.files", f), type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { WriterLog.w("Car share: ${f.name} not opened (${it.message})"); Toast.makeText(c, c.getString(R.string.car_share_no_app, f.name), Toast.LENGTH_LONG).show() }
    }
}

/** Send to car: Tetra in the share menu; what is shared goes to Tetra Car SE while it is linked (CarWire). */
class CarShareActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = @Suppress("DEPRECATION") (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)))
        if (uris.isNotEmpty() && CarWire.alive()) { CarShare.send(this, uris); Toast.makeText(this, resources.getQuantityString(R.plurals.auto_sending, uris.size, uris.size), Toast.LENGTH_SHORT).show() }
        else Toast.makeText(this, R.string.auto_no_car, Toast.LENGTH_LONG).show()
        finish()
    }
}
