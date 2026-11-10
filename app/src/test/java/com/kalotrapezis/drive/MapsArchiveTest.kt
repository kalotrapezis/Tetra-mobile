package com.kalotrapezis.drive

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** A map out as one .tetramap file and in on another device; a file that tries to write elsewhere or was changed is refused. */
class MapsArchiveTest {
    private fun root() = MapsFiles.root(Files.createTempDirectory("tetra").toFile()).apply { mkdirs() }

    private fun greece(root: File) {
        File(root, "greece.pmtiles").writeBytes(Random(1).nextBytes(300_000))
        File(root, "greece.json").writeText("""{"name":"Greece","built":"2026-10-21","bounds":[19.3,34.8,29.7,41.8],"drive":"right"}""")
        File(root, "greece-gh").mkdirs(); File(root, "greece-gh/edges").writeBytes(Random(2).nextBytes(70_000)); File(root, "greece-gh/properties").writeText("x=1\n")
        File(root, "greece-places.tsv").writeText("town\tΠύργος\t37.67\t21.44\n")
        File(root, "greece_macedonia-crossings.tsv").writeText("41.1\t22.5\tEvzoni\n")
        // not Greece's: another map, a sync's conflict copy, the user's own places and additions
        File(root, "macedonia.pmtiles").writeText("m"); File(root, "greece-places (2).tsv").writeText("old")
        File(root, "added-places.tsv").writeText("school\n"); File(root, "Places").mkdirs(); File(root, "Places/a.json").writeText("{}")
    }

    private fun entries(bytes: ByteArray): MutableList<Pair<String, ByteArray>> = ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
        generateSequence { z.nextEntry }.map { it.name to z.readBytes() }.toMutableList()
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { b ->
        ZipOutputStream(b).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
    }.toByteArray()

    private fun refused(root: File, bytes: ByteArray, why: String) {
        try { MapsArchive.import(root, ByteArrayInputStream(bytes)); fail("imported: $why") } catch (e: IllegalStateException) {
            assertTrue("$why: ${e.message}", e.message!!.contains(why))
        }
        assertTrue(root.list()!!.none { it.startsWith(".tetramap-") })
    }

    @Test fun roundTrip() {
        val phone = root(); greece(phone)
        val out = ByteArrayOutputStream()
        var last = 0f
        MapsArchive.export(phone, "greece", out) { last = it }
        assertEquals(1f, last)
        val bytes = out.toByteArray()
        assertEquals("tetramap.json", entries(bytes)[0].first)
        val m = MapsArchive.peek(ByteArrayInputStream(bytes))
        assertEquals("greece", m.id); assertEquals("Greece", m.name); assertEquals("2026-10-21", m.built); assertEquals(19.3, m.bounds!![0], 0.0)
        val paths = listOf("greece-gh/edges", "greece-gh/properties", "greece-places.tsv", "greece.json", "greece.pmtiles", "greece_macedonia-crossings.tsv")
        assertEquals(paths, m.files.map { it.path }.sorted())

        // the car has an older Greece, with a list the new one lacks: replaced whole; its own other things stay
        val car = root()
        File(car, "greece.pmtiles").writeText("old"); File(car, "greece-lanes.tsv").writeText("old"); File(car, "added-places.tsv").writeText("mine")
        assertEquals("greece", MapsArchive.import(car, ByteArrayInputStream(bytes)))
        paths.forEach { assertArrayEquals(it, File(phone, it).readBytes(), File(car, it).readBytes()) }
        assertFalse(File(car, "greece-lanes.tsv").exists())
        assertEquals("mine", File(car, "added-places.tsv").readText())
        assertFalse(File(car, "macedonia.pmtiles").exists()); assertFalse(File(car, "Places").exists())
        assertTrue(car.list()!!.none { it.startsWith(".") })
        assertEquals(listOf("greece"), MapsFiles.ids(car))
        assertEquals(File(phone, "greece.pmtiles").lastModified() / 2000, File(car, "greece.pmtiles").lastModified() / 2000) // the zip keeps 2 s
    }

    @Test fun refusesWhatIsNotTheMap() {
        val phone = root(); greece(phone)
        val good = ByteArrayOutputStream().also { MapsArchive.export(phone, "greece", it) }.toByteArray()
        val car = root(); File(car, "greece.pmtiles").writeText("old")
        fun manifest(e: List<Pair<String, ByteArray>>) = JSONObject(String(e[0].second))
        fun with(o: JSONObject, e: MutableList<Pair<String, ByteArray>>) = e.apply { set(0, "tetramap.json" to o.toString().toByteArray()) }

        // a path out of the Maps folder, in the manifest
        val slip = entries(good)
        val o = manifest(slip); o.getJSONArray("files").getJSONObject(0).put("path", "../evil.tsv")
        refused(car, zip(with(o, slip).apply { add("../evil.tsv" to "x".toByteArray()) }), "is not a file of map greece")
        // …and only in the zip, beside a good manifest
        refused(car, zip(entries(good).apply { add("../../evil.tsv" to "x".toByteArray()) }), "is not in the manifest")
        assertFalse(File(car.parentFile, "evil.tsv").exists()); assertFalse(File(car.parentFile.parentFile, "evil.tsv").exists())
        listOf("/etc/x.tsv", "greece-gh/../../x", "greece-gh\\x", "Places/a.json", "added-places.tsv", "macedonia.pmtiles", "greece-gh/a/b", "greece-gh/.hidden")
            .forEach { assertFalse(it, MapsArchive.allowed("greece", it)) }

        // a file changed on the way, its size the same
        val tampered = entries(good)
        val i = tampered.indexOfFirst { it.first == "greece-places.tsv" }
        tampered[i] = tampered[i].first to tampered[i].second.copyOf().also { it[0] = (it[0] + 1).toByte() }
        refused(car, zip(tampered), "is damaged")
        // a file missing, a newer format
        refused(car, zip(entries(good).filter { it.first != "greece.json" }), "greece.json is missing")
        refused(car, zip(with(manifest(entries(good)).put("format", 2), entries(good))), "format 2")
        // the old map is untouched by every refusal
        assertEquals("old", File(car, "greece.pmtiles").readText())
        assertEquals(listOf("greece.pmtiles"), car.list()!!.toList())
    }
}
