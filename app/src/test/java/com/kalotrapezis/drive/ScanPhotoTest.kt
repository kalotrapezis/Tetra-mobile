package com.kalotrapezis.drive

import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto fix on a photographed page: test-documents/Scanner_test.pdf's first page (a real page image) laid on a
 * table in perspective, lit evenly, shadowed along one side, under a lamp, and as dark cream paper — then the same
 * steps the app takes (letters decide the cut, straighten, trim to paper, sharpen, gap fill) and a look at how much
 * paper each side lost. `SCAN_RENDERS=<dir> SCAN_LABEL=<name>` writes each case as a picture: the photo with the
 * outline (blue) and the final cut (red), and the page that came out.
 */
class ScanPhotoTest {
    private val page: BufferedImage by lazy {
        // the PDF's first page is a JPEG stream: cut it out between its start and end markers
        val bytes = File("../test-documents/Scanner_test.pdf").readBytes()
        val start = (0 until bytes.size - 2).first { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xD8.toByte() && bytes[it + 2] == 0xFF.toByte() }
        ImageIO.read(ByteArrayInputStream(bytes, start, bytes.size - start))
    }

    /** Where the photo's page is: a slight perspective, a hand's breadth of table round it. */
    private val corners = listOf(ScanPoint(150f, 140f), ScanPoint(1340f, 170f), ScanPoint(1370f, 1860f), ScanPoint(120f, 1830f))
    private val photoWidth = 1500
    private val photoHeight = 2000

    /** The page's ink, in the page's own 0..1 coordinates: what the reader would find. */
    private val ink: FloatArray by lazy {
        var left = page.width; var top = page.height; var right = 0; var bottom = 0
        for (y in 0 until page.height step 2) for (x in 0 until page.width step 2) if (luma(page.getRGB(x, y)) < 128) {
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
        }
        floatArrayOf(left / page.width.toFloat(), top / page.height.toFloat(), right / page.width.toFloat(), bottom / page.height.toFloat())
    }

    /** [dark]: whether the auto fix should choose Fix lighting for it. */
    private class Case(
        val name: String, val dark: Boolean, val light: (u: Float, v: Float) -> Float,
        val tint: FloatArray = floatArrayOf(1f, 1f, 1f), val generous: Float = 0f, val table: IntArray = intArrayOf(122, 92, 62),
    )

    private fun soft(edge: Float, width: Float, at: Float) = ((at - edge) / width + 0.5f).coerceIn(0f, 1f)

    private val cases = listOf(
        Case("even", false, { _, _ -> 0.8f }),
        // the phone's shadow over the bottom: dark paper along the edge, fading into the page
        Case("shadow-bottom", true, { _, v -> 0.8f - 0.36f * soft(0.86f, 0.10f, v) }),
        // a hand's shadow down the left side, a sharper fall
        Case("shadow-left", true, { u, _ -> 0.8f - 0.3f * (1f - soft(0.12f, 0.06f, u)) }),
        // a desk lamp at the top right: bright there, dim at the far corner
        Case("lamp", true, { u, v -> 0.95f - 0.4f * ((1f - u) + v) / 2f }),
        // cream paper in a dim room
        Case("cream-dark", true, { u, v -> 0.62f - 0.06f * (u + v) / 2f }, floatArrayOf(1f, 0.92f, 0.74f)),
        // evenly lit, but the outline kept 3% of table round the page: that band must still go
        Case("band", false, { _, _ -> 0.8f }, generous = 0.03f),
        // the same on a dark grey desk: no colour to tell it by, only the step at the paper's edge
        Case("band-grey", false, { _, _ -> 0.8f }, generous = 0.03f, table = intArrayOf(88, 88, 90)),
        // a band of table and the phone's shadow on the same side
        Case("band-shadow", true, { _, v -> 0.8f - 0.36f * soft(0.86f, 0.10f, v) }, generous = 0.03f),
    )

    private class Result(val light: IntArray, val lost: FloatArray, val inkKept: Boolean, val inkPainted: Float, val tableLeft: Float, val page: IntArray, val width: Int, val height: Int)

    private fun run(case: Case, label: String?): Result {
        val toPhoto = homography(listOf(ScanPoint(0f, 0f), ScanPoint(1f, 0f), ScanPoint(1f, 1f), ScanPoint(0f, 1f)), corners)
        val toPage = invert(toPhoto)
        val photo = IntArray(photoWidth * photoHeight)
        for (y in 0 until photoHeight) for (x in 0 until photoWidth) {
            val (u, v) = apply(toPage, x + 0.5, y + 0.5)
            photo[y * photoWidth + x] = if (u in 0.0..1.0 && v in 0.0..1.0) {
                val p = sample(page, u * page.width - 0.5, v * page.height - 0.5)
                val k = case.light(u.toFloat(), v.toFloat())
                rgb(((p shr 16 and 0xFF) * k * case.tint[0]).toInt(), ((p shr 8 and 0xFF) * k * case.tint[1]).toInt(), ((p and 0xFF) * k * case.tint[2]).toInt())
            } else {
                val grain = ((x * 7 + y * 13) % 11) - 5 // a little wood grain, so the table is not one flat colour
                rgb(case.table[0] + grain, case.table[1] + grain, case.table[2] + grain)
            }
        }
        // the outline: the page's own edge, or a few percent too generous
        val g = case.generous
        val outline = listOf(ScanPoint(-g, -g), ScanPoint(1 + g, -g), ScanPoint(1 + g, 1 + g), ScanPoint(-g, 1 + g))
            .map { apply(toPhoto, it.x.toDouble(), it.y.toDouble()).let { (x, y) -> ScanPoint((x / photoWidth).toFloat(), (y / photoHeight).toFloat()) } }
        val text = listOf(ScanPoint(ink[0], ink[1]), ScanPoint(ink[2], ink[1]), ScanPoint(ink[2], ink[3]), ScanPoint(ink[0], ink[3]))
            .map { apply(toPhoto, it.x.toDouble(), it.y.toDouble()).let { (x, y) -> ScanPoint((x / photoWidth).toFloat(), (y / photoHeight).toFloat()) } }
        val quad = ScanDetection.fitToLetters(DocumentQuad(outline[0], outline[1], outline[2], outline[3]), text)

        val photoImage = BufferedImage(photoWidth, photoHeight, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, photoWidth, photoHeight, photo, 0, photoWidth) }
        val fixed = autoFix(photoImage, quad)
        val (out, pw, ph) = Triple(fixed.page, fixed.width, fixed.height)
        val cut = fixed.cut
        val inkPainted = fixed.inkPainted

        // the final cut's corners, back on the page: how much paper went from each side
        val onPage = cut.map { apply(toPage, it.x.toDouble(), it.y.toDouble()).let { (u, v) -> ScanPoint(u.toFloat(), v.toFloat()) } }
        val lost = floatArrayOf(
            (onPage[0].x + onPage[3].x) / 2, (onPage[0].y + onPage[1].y) / 2,
            1 - (onPage[1].x + onPage[2].x) / 2, 1 - (onPage[2].y + onPage[3].y) / 2,
        )
        val inkKept = lost[0] < ink[0] && lost[1] < ink[1] && lost[2] < 1 - ink[2] && lost[3] < 1 - ink[3]
        // table left on the finished page: pixels of the table's colour along its outer 1%
        val rim = maxOf(2, minOf(pw, ph) / 100)
        var tableCount = 0; var rimCount = 0
        for (y in 0 until ph) for (x in 0 until pw) if (x < rim || y < rim || x >= pw - rim || y >= ph - rim) {
            rimCount++
            val p = out[y * pw + x]
            if (abs((p shr 16 and 0xFF) - case.table[0]) < 20 && abs((p shr 8 and 0xFF) - case.table[1]) < 20 && abs((p and 0xFF) - case.table[2]) < 20) tableCount++
        }
        val light = ScanFilters.paperLight(out, pw, ph)
        println("${case.name}: paper light ${light.toList()}, too dark ${ScanFilters.tooDark(light)}")
        println("${case.name}: trim ${fixed.trim.toList()} lost L %.3f T %.3f R %.3f B %.3f, ink kept $inkKept, ink painted over %.4f, table on rim %.3f".format(lost[0], lost[1], lost[2], lost[3], inkPainted, tableCount / rimCount.toFloat()))
        if (label != null) render(case.name, label, photoImage, outline, cut, out, pw, ph)
        return Result(light, lost, inkKept, inkPainted, tableCount / rimCount.toFloat(), out, pw, ph)
    }

    private class Fixed(val trim: IntArray, val page: IntArray, val width: Int, val height: Int, val cut: List<ScanPoint>, val inkPainted: Float)

    /** What autoFixScan does to [photo] given the outline [quad]: straighten (long edge 2000, as the viewer renders), trim, sharpen, fill. */
    private fun autoFix(photo: BufferedImage, quad: DocumentQuad): Fixed {
        val q = quad.points.map { ScanPoint(it.x * photo.width, it.y * photo.height) }
        val rawWidth = maxOf(dist(q[0], q[1]), dist(q[3], q[2]))
        val rawHeight = maxOf(dist(q[0], q[3]), dist(q[1], q[2]))
        val scale = minOf(1f, 2000f / maxOf(rawWidth, rawHeight))
        val w = (rawWidth * scale).toInt(); val h = (rawHeight * scale).toInt()
        val cropToPhoto = homography(listOf(ScanPoint(0f, 0f), ScanPoint(w.toFloat(), 0f), ScanPoint(w.toFloat(), h.toFloat()), ScanPoint(0f, h.toFloat())), q)
        val straight = IntArray(w * h) { i -> apply(cropToPhoto, i % w + 0.5, i / w + 0.5).let { (x, y) -> sample(photo, x - 0.5, y - 0.5) } }
        val trim = ScanDetection.trimToPaper(straight, w, h)
        val fw = w - trim[0] - trim[2]; val fh = h - trim[1] - trim[3]
        val kept = if (trim.all { it == 0 } || fw < w / 2 || fh < h / 2) intArrayOf(0, 0, 0, 0) else trim
        val pw = w - kept[0] - kept[2]; val ph = h - kept[1] - kept[3]
        val out = IntArray(pw * ph) { i -> straight[(i / pw + kept[1]) * w + i % pw + kept[0]] }
        ScanFilters.sharpen(out, pw, ph)
        val inkBefore = out.count { luma(it) < 90 }
        ScanDetection.fillPageGaps(out, pw, ph)
        val inkPainted = 1f - out.count { luma(it) < 90 } / inkBefore.toFloat()
        val cut = listOf(ScanPoint(kept[0].toFloat(), kept[1].toFloat()), ScanPoint((w - kept[2]).toFloat(), kept[1].toFloat()),
            ScanPoint((w - kept[2]).toFloat(), (h - kept[3]).toFloat()), ScanPoint(kept[0].toFloat(), (h - kept[3]).toFloat()))
            .map { apply(cropToPhoto, it.x.toDouble(), it.y.toDouble()).let { (x, y) -> ScanPoint(x.toFloat(), y.toFloat()) } }
        return Fixed(kept, out, pw, ph, cut, inkPainted)
    }

    @Test fun `the curved page photo keeps all its paper`() {
        // test-documents/Curved_page.jpg: a page turned 6° on a grey table, running off the frame at three corners
        val photo = ImageIO.read(File("../test-documents/Curved_page.jpg"))
        val corners = listOf(ScanPoint(2f, 130f), ScanPoint(1233f, 1f), ScanPoint(1416f, 1744f), ScanPoint(184f, 1874f))
            .map { ScanPoint(it.x / photo.width, it.y / photo.height) }
        val fixed = autoFix(photo, DocumentQuad(corners[0], corners[1], corners[2], corners[3]))
        println("curved: trim ${fixed.trim.toList()} of ${fixed.width}x${fixed.height}, ink painted over %.4f".format(fixed.inkPainted))
        System.getenv("SCAN_LABEL")?.let { render("curved", it, photo, corners, fixed.cut, fixed.page, fixed.width, fixed.height) }
        assertTrue("nothing taken past half a percent: ${fixed.trim.toList()}", fixed.trim.withIndex().all { (side, t) -> t <= (if (side % 2 == 0) fixed.width else fixed.height) / 200 })
        assertTrue("no letters painted out: ${fixed.inkPainted}", fixed.inkPainted < 0.002f)
    }

    private fun render(name: String, label: String, photo: BufferedImage, outline: List<ScanPoint>, cut: List<ScanPoint>, out: IntArray, w: Int, h: Int) {
        val dir = File(System.getenv("SCAN_RENDERS") ?: return).apply { mkdirs() }
        val scale = 900.0 / photo.height
        val pageScale = 900.0 / h
        val sheet = BufferedImage((photo.width * scale + w * pageScale + 30).toInt(), 920, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = Color(60, 60, 60); g.fillRect(0, 0, sheet.width, sheet.height)
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(photo, 10, 10, (photo.width * scale).toInt(), (photo.height * scale).toInt(), null)
        g.stroke = BasicStroke(2f)
        fun poly(points: List<ScanPoint>, color: Color, normalized: Boolean) {
            g.color = color
            val xs = points.map { (10 + (if (normalized) it.x * photo.width else it.x) * scale).toInt() }.toIntArray()
            val ys = points.map { (10 + (if (normalized) it.y * photo.height else it.y) * scale).toInt() }.toIntArray()
            g.drawPolygon(xs, ys, 4)
        }
        poly(outline, Color(40, 120, 255), true)
        poly(cut, Color.RED, false)
        val finished = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, w, h, out, 0, w) }
        g.drawImage(finished, (photo.width * scale + 20).toInt(), 10, (w * pageScale).toInt(), 900, null)
        g.dispose()
        ImageIO.write(sheet, "png", File(dir, "scan-$name-$label.png"))
    }

    @Test fun `a shadow or a dim room costs the page nothing past the letters' step in, and asks for Fix lighting`() {
        val label = System.getenv("SCAN_LABEL")
        val failures = cases.mapNotNull { case ->
            val result = run(case, label)
            val problems = buildList {
                if (!result.inkKept) add("text cut")
                if (result.inkPainted > 0.002f) add("ink painted over %.4f".format(result.inkPainted))
                // the letters step in 1.5%; the band cases start 3% outside, so a cut on the paper's edge is 0
                result.lost.forEachIndexed { side, lost -> if (lost > 0.02f) add("side $side lost %.3f".format(lost)) }
                if (result.tableLeft > 0.02f) add("table left on the rim %.3f".format(result.tableLeft))
                if (ScanFilters.tooDark(result.light) != case.dark) add("too dark should be ${case.dark}, paper light ${result.light.toList()}")
            }
            problems.takeIf { it.isNotEmpty() }?.let { "${case.name}: $it" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // --- a perspective transform from four points, and bilinear sampling: what Android's Matrix and Paint do ---

    private fun homography(from: List<ScanPoint>, to: List<ScanPoint>): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val (x, y) = from[i].x.toDouble() to from[i].y.toDouble()
            val (u, v) = to[i].x.toDouble() to to[i].y.toDouble()
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (c in 0 until 8) {
            val pivot = (c until 8).maxBy { abs(a[it][c]) }
            a[c] = a[pivot].also { a[pivot] = a[c] }
            for (r in 0 until 8) if (r != c) {
                val f = a[r][c] / a[c][c]
                for (k in c..8) a[r][k] -= f * a[c][k]
            }
        }
        return DoubleArray(9) { if (it == 8) 1.0 else a[it][8] / a[it][it] }
    }

    private fun invert(m: DoubleArray): DoubleArray {
        val (a, b, c) = Triple(m[0], m[1], m[2]); val (d, e, f) = Triple(m[3], m[4], m[5]); val (g, h, i) = Triple(m[6], m[7], m[8])
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return doubleArrayOf(e * i - f * h, c * h - b * i, b * f - c * e, f * g - d * i, a * i - c * g, c * d - a * f, d * h - e * g, b * g - a * h, a * e - b * d).map { it / det }.toDoubleArray()
    }

    private fun apply(m: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
        val w = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }

    private fun sample(image: BufferedImage, x: Double, y: Double): Int {
        val x0 = x.toInt().coerceIn(0, image.width - 1); val y0 = y.toInt().coerceIn(0, image.height - 1)
        val x1 = (x0 + 1).coerceAtMost(image.width - 1); val y1 = (y0 + 1).coerceAtMost(image.height - 1)
        val fx = (x - x0).coerceIn(0.0, 1.0); val fy = (y - y0).coerceIn(0.0, 1.0)
        val p = intArrayOf(image.getRGB(x0, y0), image.getRGB(x1, y0), image.getRGB(x0, y1), image.getRGB(x1, y1))
        fun ch(s: Int) = ((p[0] shr s and 0xFF) * (1 - fx) * (1 - fy) + (p[1] shr s and 0xFF) * fx * (1 - fy) + (p[2] shr s and 0xFF) * (1 - fx) * fy + (p[3] shr s and 0xFF) * fx * fy).toInt()
        return rgb(ch(16), ch(8), ch(0))
    }

    private fun dist(a: ScanPoint, b: ScanPoint) = kotlin.math.hypot(a.x - b.x, a.y - b.y)
    private fun luma(p: Int) = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
