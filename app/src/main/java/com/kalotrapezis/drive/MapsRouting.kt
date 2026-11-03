package com.kalotrapezis.drive

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.Profile
import com.graphhopper.jackson.Jackson
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.IntEncodedValue
import com.graphhopper.routing.ev.Orientation
import com.graphhopper.routing.ev.MaxSpeed
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.RoadClassLink
import com.graphhopper.routing.ev.TurnRestriction
import com.graphhopper.routing.weighting.DefaultTurnCostProvider
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.routing.weighting.custom.CustomWeighting
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.PMap
import com.graphhopper.util.Parameters
import com.graphhopper.util.Translation
import com.graphhopper.util.TranslationMap
import com.graphhopper.util.TurnCostsConfig
import java.io.File
import java.util.Locale

/** One leg of a route's spoken and shown directions; [at] is the index in [Route.points] where it begins. */
internal data class Step(val text: String, val sign: Int, val metres: Double, val at: Int, val street: String, val ref: String = "",
    val junction: Junction? = null, val lanes: Lanes? = null, val toward: String = "")

/** [roads]: what it mostly runs on — "motorway", "main" or "local" — for the choice between routes. */
internal data class Route(val mode: String, val lats: DoubleArray, val lngs: DoubleArray, val metres: Double, val millis: Long, val steps: List<Step>,
    val roads: String = "")

/**
 * Offline routing on the phone: the graph the computer built (tools/maps-greece.sh, tools/maps-graph.yml) opened read-only
 * from Tetra/.System/Maps/Shared/greece-gh with GraphHopper 10.2. GraphHopper turns a profile's custom model into Java bytecode with Janino
 * at run time, which Android cannot load, so the three custom models are written out here by hand (TetraWeightings) —
 * the same rules as GraphHopper's car.json, foot.json and bike.json, which the graph's shortcuts were made with.
 */
internal object MapsRouting {
    /**
     * A car's times as GraphHopper gives them, at the limits, are short of a real drive (the user, 21 Oct: Άγιος Λουκάς →
     * Χαλκίδα "is 30 min in reality, our map says 25"): a fifth more.
     */
    const val CAR_TIME = 1.2

    /** How far a search that keeps the car's heading may look before the shortcuts take over (on the computer it gives up on Athens–Thessaloniki in 0.8 s). */
    private const val MAX_HEADED_NODES = 300_000

    /** What turning back on the road costs a route (GraphHopper's 300 s had it say "turn back" all the way, 23 Oct). */
    private const val TURN_BACK_SECONDS = 1200

    /**
     * Main roads (the user, 24 Oct: "it always chooses the shorter path … even if that means a road that falls off a cliff"):
     * the car's way without car.json's pull towards the shorter one (distance_influence 90, a second and a half a kilometre),
     * a smaller road counting slower. A request hint on the car profile, so no new graph; the graph's shortcuts are made
     * for car.json, so it searches without them, up to [MAX_MAIN_NODES].
     */
    const val MAIN_ROADS = "tetra.main_roads"
    // shortcut: past this (a drive across Greece) the shortest way is taken, mostly on motorways anyway; a car_main CH profile in maps-graph.yml (a rebuild) would lift it
    private const val MAX_MAIN_NODES = 500_000

    /** How much slower a road of [c] counts on Main roads: the big ones as they are, a track ten times slower. */
    fun mainPriority(c: com.graphhopper.routing.ev.RoadClass): Double = when (c) {
        com.graphhopper.routing.ev.RoadClass.MOTORWAY, com.graphhopper.routing.ev.RoadClass.TRUNK,
        com.graphhopper.routing.ev.RoadClass.PRIMARY, com.graphhopper.routing.ev.RoadClass.SECONDARY -> 1.0
        com.graphhopper.routing.ev.RoadClass.TERTIARY -> 0.8
        com.graphhopper.routing.ev.RoadClass.RESIDENTIAL, com.graphhopper.routing.ev.RoadClass.LIVING_STREET -> 0.6
        com.graphhopper.routing.ev.RoadClass.UNCLASSIFIED -> 0.4
        com.graphhopper.routing.ev.RoadClass.TRACK -> 0.1
        else -> 0.3
    }

    /** maps-graph.yml's profiles, as Jackson reads them there: GraphHopper compares their hash with the graph's. */
    private val PROFILES = linkedMapOf(
        "car" to """{"name":"car","turn_costs":{"vehicle_types":["motorcar","motor_vehicle"]},"custom_model_files":["car.json"]}""",
        "foot" to """{"name":"foot","custom_model_files":["foot.json"]}""",
        "bike" to """{"name":"bike","custom_model_files":["bike.json"]}""",
        "wheelchair" to """{"name":"wheelchair","custom_model":{"priority":[{"if":"!foot_access || hike_rating >= 1","multiply_by":"0"},""" +
            """{"if":"road_class == STEPS","multiply_by":"0"},{"if":"mtb_rating > 1","multiply_by":"0"},{"else":"","multiply_by":"foot_priority"}],""" +
            """"speed":[{"if":"true","limit_to":"foot_average_speed"},{"if":"true","limit_to":"4"}]}}""",
    )

    /** The profiles [dir]'s graph was built with (properties.txt), in maps-graph.yml's order: an older graph lacks the newer ones. */
    fun profiles(dir: File): List<String> {
        val built = runCatching { File(dir, "properties.txt").readLines().firstOrNull { it.startsWith("profiles=") } }.getOrNull()
            ?.substringAfter("=")?.split(",")?.map { it.substringBefore("|") }.orEmpty()
        return PROFILES.keys.filter { it in built }.ifEmpty { listOf("car", "foot", "bike") }
    }

    /** The graphs open, the last used last: up to three, so a route across borders and the limits on its way open none again. */
    private val hoppers = LinkedHashMap<File, GraphHopper>(4, 0.75f, true)

    /** The graph in [dir], opened once and kept (opening maps the files, it reads nothing much). */
    @Synchronized fun open(dir: File): GraphHopper {
        hoppers[dir]?.let { return it }
        hoppers.entries.firstOrNull()?.takeIf { hoppers.size >= 3 }?.let { it.value.close(); hoppers.remove(it.key) }
        val names = profiles(dir)
        val json = """{"profiles":[${names.joinToString(",") { PROFILES.getValue(it) }}],"profiles_ch":[${names.joinToString(",") { "{\"profile\":\"$it\"}" }}],"profiles_lm":[]}"""
        val config = Jackson.newObjectMapper().readValue(json, GraphHopperConfig::class.java)
            .putObject("graph.location", dir.absolutePath)
            .putObject("graph.dataaccess.default_type", "MMAP_RO")
            .putObject("import.osm.ignored_highways", "")
        val gh = TetraHopper()
        gh.setAllowWrites(false) // no gh.lock in a synced folder
        gh.init(config)
        check(gh.load()) { "No routing graph in ${dir.name}." }
        hoppers[dir] = gh
        return gh
    }

    /** A route by [mode] (car, foot, bike), its directions in [w]'s language. */
    fun route(dir: File, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, mode: String, w: Words, heading: Double = Double.NaN,
              main: Boolean = false): Route =
        routes(dir, fromLat, fromLng, toLat, toLng, mode, w, heading, main).first()

    private val translations by lazy { TranslationMap().doImport() }
    private val translated = HashMap<String, Translation>()

    /**
     * GraphHopper's turn words in [lang], with Tetra's own over the lines it lacks (resources tetra/graphhopper/<lang>.txt,
     * GraphHopper's format: et, ga, lv and mt whole, a few lines of es, fi, hr, lt, nl, pt, ro, sl); English where neither has one.
     */
    @Synchronized fun translation(lang: String): Translation = translated.getOrPut(lang) {
        val base = translations.getWithFallBack(Locale.forLanguageTag(if (lang == "pt") "pt-PT" else lang)) // Tetra's Portuguese is Portugal's
        val ours = MapsRouting::class.java.getResourceAsStream("/tetra/graphhopper/$lang.txt") ?: return@getOrPut base
        val m = HashMap(base.asMap())
        ours.bufferedReader().useLines { ls -> ls.filter { '=' in it && !it.startsWith("#") }.forEach { m[it.substringBefore('=')] = it.substringAfter('=') } }
        val l = Locale.forLanguageTag(lang)
        object : Translation {
            override fun tr(key: String, vararg params: Any?) = m[key.lowercase()]?.takeIf { it.isNotEmpty() }?.let { String.format(Locale.ROOT, it, *params) } ?: key
            override fun asMap() = m
            override fun getLocale() = l
            override fun getLanguage() = lang
        }
    }

    /**
     * Up to three ways there, the fastest first (the user, 21 Oct, beside Google Maps: "can our map have alternative routs with
     * tolls or without, main roads over roral roads"): GraphHopper's alternatives on the same graph; for a car, none that
     * runs a tenth of its way on tracks.
     */
    fun routes(dir: File, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, mode: String, w: Words,
               heading: Double = Double.NaN, main: Boolean = false): List<Route> {
        val legs = MapsFiles.legs(dir, fromLat, fromLng, toLat, toLng, w) { g, aLat, aLng, bLat, bLng -> seconds(g, aLat, aLng, bLat, bLng, mode) }
        if (legs.size == 1) return direct(legs[0].dir, fromLat, fromLng, toLat, toLng, mode, w, heading, main)
        // across borders: each leg on its own country's roads, driven to the crossing and on from there, as one route
        val t0 = System.nanoTime()
        val parts = legs.mapIndexed { i, l -> direct(l.dir, l.fromLat, l.fromLng, l.toLat, l.toLng, mode, w, if (i == 0) heading else Double.NaN, main, alternatives = false).first() }
        WriterLog.i("Maps: a route across ${legs.size - 1} border(s), through ${legs.joinToString(" → ") { it.dir.name }}, its legs in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return listOf(join(parts))
    }

    /** How long from one point to the other in seconds, GraphHopper's (no line, no turns), or null when there is no way: to choose a border crossing. */
    fun seconds(dir: File, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, mode: String): Double? {
        val mode = if (mode == "wheelchair" && "wheelchair" !in profiles(dir)) "foot" else mode
        val req = GHRequest(fromLat, fromLng, toLat, toLng).setProfile(mode)
        req.hints.putObject(Parameters.Routing.INSTRUCTIONS, false).putObject(Parameters.Routing.CALC_POINTS, false)
        val r = open(dir).route(req)
        return if (r.hasErrors()) null else r.best.time / 1000.0
    }

    /** Routes one after the other as one: their lines joined at the shared point, the arrival said only at the end. */
    fun join(parts: List<Route>): Route {
        var offset = 0
        val steps = parts.flatMapIndexed { i, r ->
            (if (i < parts.lastIndex) r.steps.filter { it.sign != com.graphhopper.util.Instruction.FINISH } else r.steps)
                .map { it.copy(at = it.at + offset) }.also { offset += r.lats.size - 1 }
        }
        fun line(of: (Route) -> DoubleArray) = parts.flatMapIndexed { i, r -> of(r).asList().drop(if (i == 0) 0 else 1) }.toDoubleArray()
        return Route(parts[0].mode, line { it.lats }, line { it.lngs }, parts.sumOf { it.metres }, parts.sumOf { it.millis }, steps, parts.maxBy { it.metres }.roads)
    }

    /** [routes] on the one graph [dir]; without [alternatives], the fastest alone (a leg across a border). */
    private fun direct(dir: File, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, mode: String, w: Words,
                       heading: Double, main: Boolean, alternatives: Boolean = true): List<Route> {
        val gh = open(dir)
        val main = main && mode == "car"
        val tr = translation(w.lang); val locale = tr.locale
        val mode = if (mode == "wheelchair" && "wheelchair" !in profiles(dir)) "foot" else mode // a graph from before Rolling
        val req = GHRequest(fromLat, fromLng, toLat, toLng).setProfile(mode).setLocale(locale).setAlgorithm(if (alternatives) Parameters.Algorithms.ALT_ROUTE else "")
            .setPathDetails(listOf("street_name", "street_ref", "road_environment", "road_class"))
        req.hints.putObject("alternative_route.max_paths", 3)
        if (main) req.hints.putObject(MAIN_ROADS, true).putObject(Parameters.CH.DISABLE, true).putObject(Parameters.Routing.MAX_VISITED_NODES, MAX_MAIN_NODES)
        // a moving car goes on the way it faces (the user, 23 Oct: each new route said "turn back" for kilometres): one way,
        // without the graph's shortcuts, which take no heading; where that search grows too big, the shortcuts without it
        if (heading.isFinite()) {
            val ahead = GHRequest(fromLat, fromLng, toLat, toLng).setProfile(mode).setLocale(locale).setHeadings(listOf(heading, Double.NaN))
                .setAlgorithm(Parameters.Algorithms.ASTAR_BI).setPathDetails(req.pathDetails)
            ahead.hints.putObject(Parameters.CH.DISABLE, true).putObject(Parameters.Routing.MAX_VISITED_NODES, MAX_HEADED_NODES)
                .putObject(Parameters.Routing.HEADING_PENALTY, TURN_BACK_SECONDS).putObject(MAIN_ROADS, main)
            val r = gh.route(ahead)
            if (!r.hasErrors()) return listOf(MapsLanes.on(build(gh, r.best, tr, w, mode), dir.absoluteFile.parentFile, w))
        }
        val t0 = System.nanoTime()
        var rsp = gh.route(req)
        if (rsp.hasErrors()) rsp = gh.route(req.setAlgorithm(""))
        if (rsp.hasErrors() && main) { // too far to search without the shortcuts: today's way
            WriterLog.i("Maps: main roads too far to search (${rsp.errors.firstOrNull()?.message}), the shortest way instead")
            return direct(dir, fromLat, fromLng, toLat, toLng, mode, w, heading, false, alternatives)
        }
        val t1 = System.nanoTime() // alternatives not to be had here: the one way
        check(!rsp.hasErrors()) { words(rsp.errors.firstOrNull(), w) }
        val all = rsp.all.map { MapsLanes.on(build(gh, it, tr, w, mode), dir.absoluteFile.parentFile, w) }
        WriterLog.i("Maps: route found in ${(t1 - t0) / 1_000_000} ms${if (main) " on main roads" else ""}, its crossings and lanes in ${(System.nanoTime() - t1) / 1_000_000} ms")
        val usable = all.filter { r -> mode != "car" || r.roads != "track" }.ifEmpty { all }
        return usable.sortedBy { it.millis }
    }

    /** GraphHopper's error in the user's words (it said "Cannot find point 0: 37.9,23.55" for a spot at sea). */
    fun words(e: Throwable?, w: Words): String {
        val m = e?.message.orEmpty()
        return when {
            e is com.graphhopper.util.exceptions.PointNotFoundException || "Cannot find point" in m ->
                if ((e as? com.graphhopper.util.exceptions.PointNotFoundException)?.pointIndex == 0 || "point 0" in m) w.s(R.string.maps_error_not_near_you)
                else w.s(R.string.maps_error_destination_not_near)
            "Connection between locations not found" in m -> w.s(R.string.maps_error_no_way)
            else -> m.ifEmpty { w.s(R.string.maps_error_no_route) }
        }
    }

    private fun build(gh: GraphHopper, best: com.graphhopper.ResponsePath, tr: Translation, w: Words, mode: String): Route {
        val names = best.pathDetails["street_name"].orEmpty(); val refs = best.pathDetails["street_ref"].orEmpty()
        val environment = best.pathDetails["road_environment"].orEmpty()
        /** Whether a leg is mostly a ferry (GraphHopper 10 has no ferry instruction: it said "turn left" for 58 km of sea). */
        fun ferry(from: Int, to: Int) = environment.filter { it.value?.toString().equals("ferry", true) }
            .sumOf { maxOf(0, minOf(it.last, to) - maxOf(it.first, from)) } * 2 > (to - from)
        /** The road a leg [from]..[to] (point indexes) mostly runs on: its code and name, as "Α8 Ολυμπία Οδός". */
        fun mostly(from: Int, to: Int): String {
            fun longest(ds: List<com.graphhopper.util.details.PathDetail>) = ds.filter { it.last > from && it.first < to && (it.value as? String).orEmpty().isNotBlank() }
                .maxByOrNull { minOf(it.last, to) - maxOf(it.first, from) }?.value as? String
            return listOfNotNull(longest(refs)?.substringBefore(';'), longest(names)).distinct().joinToString(" ")
        }
        var at = 0
        val steps = best.instructions.map { ins ->
            var text = ins.getTurnDescription(tr)
            var street = ins.name.orEmpty(); var ref = (ins.extraInfoJSON["street_ref"] as? String).orEmpty()
            var toward = (ins.extraInfoJSON["street_destination"] as? String).orEmpty().ifEmpty { (ins.extraInfoJSON["street_destination_ref"] as? String).orEmpty() }
            if (ferry(at, at + ins.points.size())) {
                text = w.s(R.string.maps_take_ferry) + (if (street.isNotEmpty()) " ($street)" else "")
                street = w.s(R.string.maps_ferry)
            }
            // a turn onto a ramp with no name says where the ramp leads (the user, 21 Oct, on motorways: "Στρίψτε αριστερά" for 61 km)
            if (street.isEmpty() && ins.sign != com.graphhopper.util.Instruction.FINISH) {
                val road = mostly(at, at + ins.points.size())
                if (road.isNotEmpty() && ins.distance > 1000) {
                    text = w.s(R.string.maps_towards, text, road); toward = road
                    street = road.substringAfter(' ', road); ref = ref.ifEmpty { road.substringBefore(' ').takeIf { r -> r.any(Char::isDigit) }.orEmpty() }
                }
            }
            // a car's turn: its crossing, for the turn card's picture (the user, 23 Oct: Δράμα's five streets)
            val cross = if (mode == "car" && at > 0 && ins.sign !in setOf(0, 4, 5)) runCatching { junction(gh, best.points, at, at + ins.points.size(), ins.sign == 6,
                (ins as? com.graphhopper.util.RoundaboutInstruction)?.exitNumber ?: 0, when (ins.sign) { -8, -98 -> -1; 8 -> 1; else -> 0 }) }.getOrNull() else null
            Step(text.replaceFirstChar { it.uppercase() }, ins.sign, ins.distance, at, street, ref, cross, toward = toward).also { at += ins.points.size() }
        }
        val pts = best.points
        // what it runs on, by share of its length (a motorway has few points for many kilometres)
        val share = HashMap<String, Double>()
        best.pathDetails["road_class"].orEmpty().forEach { d ->
            var m = 0.0
            for (i in d.first until minOf(d.last, pts.size() - 1)) m += metresBetween(pts.getLat(i), pts.getLon(i), pts.getLat(i + 1), pts.getLon(i + 1))
            share.merge(d.value?.toString()?.lowercase().orEmpty(), m, Double::plus)
        }
        val total = share.values.sum().coerceAtLeast(1.0)
        fun part(vararg k: String) = (k.sumOf { share[it] ?: 0.0 } * 100 / total).toInt()
        val roads = when {
            part("track") >= 10 -> "track"
            part("motorway", "trunk") >= 25 -> "motorway"
            part("motorway", "trunk", "primary", "secondary") >= 50 -> "main"
            else -> "local"
        }
        val millis = if (mode == "car") (best.time * CAR_TIME).toLong() else best.time
        return Route(mode, DoubleArray(pts.size()) { pts.getLat(it) }, DoubleArray(pts.size()) { pts.getLon(it) }, best.distance, millis, steps, roads)
    }

    /**
     * The crossing where a route's turn is, at point [at] of [pts] (the next turn's at [next]), as the car arrives: the car
     * roads leaving it from the graph, each one's direction ~20 m out; a [roundabout]'s from its ring's middle, with the
     * [exit] GraphHopper says; a [uTurn] (−1 left, 1 right) back along the way in. Null where the turn is not at a crossing
     * the graph has.
     */
    private fun junction(gh: GraphHopper, pts: com.graphhopper.util.PointList, at: Int, next: Int, roundabout: Boolean, exit: Int, uTurn: Int): Junction? {
        val na = gh.baseGraph.nodeAccess; val access = gh.encodingManager.getBooleanEncodedValue("car_access")
        if (at < 1) return null
        val snap = gh.locationIndex.findClosest(pts.getLat(at), pts.getLon(at)) { it.get(access) || it.getReverse(access) }
        if (!snap.isValid) return null
        val node = if (snap.snappedPosition == com.graphhopper.storage.index.Snap.Position.TOWER) snap.closestNode
            else listOf(snap.closestEdge.baseNode, snap.closestEdge.adjNode).minBy { metresBetween(na.getLat(it), na.getLon(it), pts.getLat(at), pts.getLon(at)) }
        if (metresBetween(na.getLat(node), na.getLon(node), pts.getLat(at), pts.getLon(at)) > 15) return null
        return crossing(gh.baseGraph, gh.encodingManager, node, pts, at, next, roundabout, exit, uTurn)
    }

    /** [junction] at the graph's [node]: apart from finding the node, so a test can give it a small graph of its own. */
    internal fun crossing(g: com.graphhopper.storage.BaseGraph, em: com.graphhopper.routing.util.EncodingManager, node: Int,
                          pts: com.graphhopper.util.PointList, at: Int, next: Int, roundabout: Boolean, exit: Int = 0, uTurn: Int = 0): Junction? {
        val na = g.nodeAccess
        val access = em.getBooleanEncodedValue("car_access"); val ring = em.getBooleanEncodedValue("roundabout")
        val kind = if (em.hasEncodedValue(RoadClass.KEY)) em.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java) else null
        val ramp = if (em.hasEncodedValue(RoadClassLink.KEY)) em.getBooleanEncodedValue(RoadClassLink.KEY) else null
        fun car(e: EdgeIteratorState) = e.get(access) || e.getReverse(access)
        /** A road's size, for the picture's thickness: 2 a main road, 1 secondary or tertiary, 0 a street, −1 a ramp, −2 a service
         *  road or track — a car park's aisle, a driveway, a field's track: not a road the driver counts (the user, 3 Nov). */
        fun size(e: EdgeIteratorState): Int = if (ramp != null && e.get(ramp)) -1 else when (kind?.let { e.get(it) }) {
            RoadClass.MOTORWAY, RoadClass.TRUNK, RoadClass.PRIMARY -> 2
            RoadClass.SECONDARY, RoadClass.TERTIARY -> 1
            null, RoadClass.RESIDENTIAL, RoadClass.UNCLASSIFIED, RoadClass.LIVING_STREET, RoadClass.ROAD -> 0
            else -> -2
        }
        val explorer = g.createEdgeExplorer()
        fun apart(a: Double, b: Double) = Math.abs(((a - b) % 360 + 540) % 360 - 180)
        /** A car road at [node]: its first stretch, its point ~40 m along to draw, whether a car may leave by it ([out]) and
         *  come by it ([into]), its [size] and name; [to] the node at its other end, [length] in metres. */
        class Arm(val node: Int, val to: Int, val length: Double, val first: Double, val lat: Double, val lng: Double,
                  val out: Boolean, val into: Boolean, val size: Int, val name: String)
        fun arms(n: Int, skip: (EdgeIteratorState) -> Boolean): List<Arm> {
            val out = ArrayList<Arm>()
            val it = explorer.setBaseNode(n)
            while (it.next()) {
                if (!car(it) || skip(it)) continue
                val geo = it.fetchWayGeometry(com.graphhopper.util.FetchMode.ALL)
                if (geo.size() < 2) continue
                var k = 1
                while (k < geo.size() - 1 && metresBetween(geo.getLat(0), geo.getLon(0), geo.getLat(k), geo.getLon(k)) < 40) k++
                val first = bearing(geo.getLat(0), geo.getLon(0), geo.getLat(1), geo.getLon(1))
                // OpenStreetMap at times has two ways between the same two nodes (Δράμα's Καραϊσκάκη at Εθνικής Αμύνης): one road
                if (out.any { a -> a.to == it.adjNode && apart(a.first, first) < 5 }) continue
                out += Arm(n, it.adjNode, it.distance, first, geo.getLat(k), geo.getLon(k), it.get(access), it.getReverse(access), size(it), it.name.orEmpty())
            }
            return out
        }
        /** Whether [n] is a crossing: three roads or more meet there, not counting service roads and tracks. */
        fun crossingAt(n: Int) = arms(n) { false }.count { it.size > -2 } >= 3
        /** The way in, the way on, then each other road once (service roads and tracks not: only when the route takes them).
         *  Two arms within 30° are one road where both have its name, or within 20° where neither has a name, one only comes to
         *  the crossing and the other only leaves it: a dual carriageway's halves. A fork's two ways out, however close, are two;
         *  two one-way streets of their own names (Σταδίου and Πανεπιστημίου at Ομόνοια) too. */
        fun oneEach(d: List<Double>, arms: List<Arm>, inArm: Int, outArm: Int): List<Int> {
            val keep = arrayListOf(inArm, outArm)
            fun same(i: Int, j: Int) = (arms[i].name.isNotEmpty() && arms[i].name == arms[j].name && apart(d[i], d[j]) < 30) ||
                (arms[i].name == arms[j].name && apart(d[i], d[j]) < 20 && ((arms[i].out && !arms[i].into && arms[j].into && !arms[j].out) ||
                    (arms[i].into && !arms[i].out && arms[j].out && !arms[j].into)))
            for (i in d.indices) if (i !in keep && arms[i].size > -2 && keep.none { same(i, it) }) keep += i
            return keep
        }
        fun turned(b: Double, ref: Double) = ((b - ref) % 360 + 540) % 360 - 180
        val dirs: List<Double>; val sizes: List<Int>
        var then = emptyList<Double>(); var thenSizes = emptyList<Int>(); var thenTaken = -1
        // the crossing's nodes: this one and those a short link away that are crossings too — OpenStreetMap often draws one big
        // crossing as two or three small ones a few metres apart (Νικήσιανη's 5–6 streets: no node there has five roads). A link
        // of 12 m at most, or 20 m where it is one-way (a dual carriageway's side); never a bend or one along a road to the next
        // crossing 20 m on (the user, 3 Nov: a sharp left at three roads drawn with five)
        val group = LinkedHashSet<Int>()
        if (!roundabout) {
            val todo = ArrayDeque(listOf(node)); val links = g.createEdgeExplorer() // its own: crossingAt walks the other
            while (todo.isNotEmpty() && group.size < 8) {
                val n = todo.removeFirst(); if (!group.add(n)) continue
                val it = links.setBaseNode(n)
                while (it.next()) if (car(it) && size(it) > -2 && it.adjNode !in group &&
                    it.distance <= (if (it.get(access) != it.getReverse(access)) 20.0 else 12.0) &&
                    metresBetween(na.getLat(it.adjNode), na.getLon(it.adjNode), na.getLat(node), na.getLon(node)) <= 25 && crossingAt(it.adjNode)) todo += it.adjNode
            }
        }
        /** The roads at the route's own node: the way in, the way on, the rest; and where the route turns again at a crossing
         *  a short road on (a staggered crossing), that one's roads too. */
        fun one(): List<Pair<Double, Int>>? {
            // the way in and the way on are the roads the route itself runs along: its points next to the crossing lie on them
            val nLat = na.getLat(node); val nLng = na.getLon(node)
            val all = arms(node) { false }
            if (at + 1 >= pts.size()) return null
            val back = bearing(nLat, nLng, pts.getLat(at - 1), pts.getLon(at - 1)); val on = bearing(nLat, nLng, pts.getLat(at + 1), pts.getLon(at + 1))
            val inArm = all.indices.minByOrNull { apart(all[it].first, back) } ?: return null
            val outArm = all.indices.minBy { apart(all[it].first, on) }
            val ref = bearing(nLat, nLng, all[inArm].lat, all[inArm].lng) + 180 // the way in straight down
            val d = all.map { turned(bearing(nLat, nLng, it.lat, it.lng), ref) }
            if (inArm == outArm) { // back the way it came: drawn beside the way in, on the side it turns
                if (uTurn == 0) return null
                return listOf(-180.0 to all[inArm].size, (if (uTurn < 0) -179.0 else 179.0) to all[inArm].size) +
                    oneEach(d, all, inArm, inArm).drop(2).map { d[it] to all[it].size }
            }
            val kept = oneEach(d, all, inArm, outArm)
            if (kept.size < 3) return null
            // a staggered crossing: the way on is a short link to another crossing where the next turn is
            val link = all[outArm]; val b = link.to
            if (link.length <= 30 && next + 1 < pts.size() && b != node && crossingAt(b) &&
                metresBetween(na.getLat(b), na.getLon(b), pts.getLat(next), pts.getLon(next)) < 5) {
                val bLat = na.getLat(b); val bLng = na.getLon(b)
                val there = arms(b) { it.adjNode == node }
                val leave = bearing(bLat, bLng, pts.getLat(next + 1), pts.getLon(next + 1))
                val bOut = there.indices.minByOrNull { apart(there[it].first, leave) }
                if (bOut != null) {
                    val bd = there.map { turned(bearing(bLat, bLng, it.lat, it.lng), ref) }
                    val bKept = oneEach(bd, there, bOut, bOut).drop(1)
                    val order = bKept.sortedBy { bd[it] }
                    then = order.map { bd[it] }; thenSizes = order.map { there[it].size }; thenTaken = order.indexOf(bOut)
                }
            }
            return kept.map { d[it] to all[it].size }
        }
        /** The roads leaving a crossing of several nodes, seen from its middle; the way on is where the route leaves it. */
        fun many(): List<Pair<Double, Int>>? {
            val cLat = group.map { na.getLat(it) }.average(); val cLng = group.map { na.getLon(it) }.average()
            val all = group.flatMap { n -> arms(n) { it.adjNode in group } }
            // the route ~30 m from the middle before and after: which roads it comes in and goes on by
            fun leave(by: Int): Int? { var k = at; while (k + by in 0 until pts.size()) { k += by; if (metresBetween(cLat, cLng, pts.getLat(k), pts.getLon(k)) >= 30) return k }; return null }
            val j = leave(-1) ?: return null; val k = leave(1) ?: return null
            val far = all.map { bearing(cLat, cLng, it.lat, it.lng) }
            val inArm = far.indices.minByOrNull { apart(far[it], bearing(cLat, cLng, pts.getLat(j), pts.getLon(j))) } ?: return null
            val outArm = far.indices.minBy { apart(far[it], bearing(cLat, cLng, pts.getLat(k), pts.getLon(k))) }
            if (inArm == outArm) return null
            val ref = far[inArm] + 180
            val d = far.map { turned(it, ref) }
            return oneEach(d, all, inArm, outArm).map { d[it] to all[it].size }
        }
        if (!roundabout) {
            // the crossing as several nodes only where that makes five roads or more; else the turn's own node, exactly
            val d = (if (group.size > 1) many()?.takeIf { it.size >= 5 } else null) ?: one() ?: return null
            dirs = d.map { it.first }; sizes = d.map { it.second }
        } else { // a roundabout: round the ring from the way in, its middle, the roads leaving it, seen from there
            val nodes = LinkedHashSet<Int>(); val todo = ArrayDeque(listOf(node))
            while (todo.isNotEmpty() && nodes.size < 60) {
                val n = todo.removeFirst(); if (!nodes.add(n)) continue
                val it = explorer.setBaseNode(n); while (it.next()) if (it.get(ring) && it.adjNode !in nodes) todo += it.adjNode
            }
            if (nodes.size < 3 || next + 1 >= pts.size()) return null
            val cLat = nodes.map { na.getLat(it) }.average(); val cLng = nodes.map { na.getLon(it) }.average()
            val all = nodes.flatMap { n -> arms(n) { it.get(ring) } }
            // the way in at the entry node, the way out at the ring node where the next direction starts: the route runs on both
            val exitNode = nodes.minBy { metresBetween(na.getLat(it), na.getLon(it), pts.getLat(next), pts.getLon(next)) }
            fun road(n: Int, k: Int) = all.indices.filter { all[it].node == n }
                .minByOrNull { apart(all[it].first, bearing(na.getLat(n), na.getLon(n), pts.getLat(k), pts.getLon(k))) }
            val inArm = road(node, at - 1) ?: return null; val outArm = road(exitNode, next + 1) ?: return null
            if (inArm == outArm) return null
            val far = all.map { bearing(cLat, cLng, it.lat, it.lng) }
            val ref = far[inArm] + 180
            val d = far.map { turned(it, ref) }
            val kept = oneEach(d, all, inArm, outArm)
            dirs = kept.map { d[it] }; sizes = kept.map { all[it].size }
        }
        if (dirs.size < 3 && !(uTurn != 0 && dirs.size == 2)) return null
        val order = dirs.indices.sortedBy { dirs[it] }
        return Junction(order.map { dirs[it] }, order.indexOf(1), roundabout, order.map { sizes[it] } + thenSizes,
            if (roundabout) exit else 0, then, thenTaken)
    }

    /**
     * The speed limit of the road nearest [lat],[lng] in km/h, from OSM's maxspeed in the graph (the user, 21 Oct: "ξέρουμε και
     * όρια ταχύτητας; να το βάλουμε να κοκκινίζει όταν το περνάω;"); null where it is not known or no road is near.
     */
    fun limitAt(dir: File, lat: Double, lng: Double): Int? {
        val gh = open(dir)
        val em = gh.encodingManager
        val access = em.getBooleanEncodedValue("car_access"); val max = em.getDecimalEncodedValue("max_speed")
        val snap = gh.locationIndex.findClosest(lat, lng) { e -> e.get(access) || e.getReverse(access) }
        if (!snap.isValid || snap.queryDistance > 40) return null
        val e = snap.closestEdge
        return posted(e.get(max), e.getReverse(max))
    }

    /**
     * The sign's number from the graph's two directions of max_speed: GraphHopper keeps ∞ where OSM has no number
     * ("GR:urban", none tagged) and 150 for "none" or more — neither is a sign, so null, never a guess. It stores in 2 km/h
     * steps (a 25 comes back 26), and signs go by fives, so the nearest five.
     */
    fun posted(vararg kmh: Double): Int? =
        kmh.filter { it.isFinite() && it > 0 && it < MaxSpeed.UNLIMITED_SIGN_SPEED }.minOrNull()?.let { Math.round(it / 5).toInt() * 5 }

    private class TetraHopper : GraphHopper() {
        override fun createWeightingFactory() = WeightingFactory { profile, hints, noTurns -> weighting(profile, hints, noTurns) }

        private fun weighting(profile: Profile, request: PMap, noTurns: Boolean): Weighting {
            val em = encodingManager
            val hints = PMap(profile.hints).putAll(request)
            // as GraphHopper's DefaultWeightingFactory
            val turns = if (profile.hasTurnCosts() && !noTurns) {
                val o = if (em.hasEncodedValue(Orientation.KEY)) em.getDecimalEncodedValue(Orientation.KEY) else null
                val u = hints.getInt(Parameters.Routing.U_TURN_COSTS, profile.turnCostsConfig.uTurnCosts)
                DefaultTurnCostProvider(em.getTurnBooleanEncodedValue(TurnRestriction.key(profile.name)), o, baseGraph.baseGraph,
                    TurnCostsConfig(profile.turnCostsConfig).setUTurnCosts(u))
            } else TurnCostProvider.NO_TURN_COST_PROVIDER
            val heading = hints.getDouble(Parameters.Routing.HEADING_PENALTY, profile.customModel?.headingPenalty ?: Parameters.Routing.DEFAULT_HEADING_PENALTY)
            fun b(n: String) = em.getBooleanEncodedValue(n)
            fun d(n: String) = em.getDecimalEncodedValue(n)
            fun i(n: String) = em.getIntEncodedValue(n)
            fun EdgeIteratorState.v(e: BooleanEncodedValue, r: Boolean) = if (r) getReverse(e) else get(e)
            fun EdgeIteratorState.v(e: DecimalEncodedValue, r: Boolean) = if (r) getReverse(e) else get(e)
            fun EdgeIteratorState.v(e: IntEncodedValue, r: Boolean) = if (r) getReverse(e) else get(e)
            val p = when (profile.name) {
                "car" -> { // car.json, or Main roads ([MAIN_ROADS])
                    val access = b("car_access"); val speed = d("car_average_speed")
                    val road = em.getEnumEncodedValue(com.graphhopper.routing.ev.RoadClass.KEY, com.graphhopper.routing.ev.RoadClass::class.java)
                    if (hints.getBool(MAIN_ROADS, false)) CustomWeighting.Parameters({ e, r -> minOf(999.0, e.v(speed, r)) }, { speed.maxOrMaxStorableDecimal },
                        { e, r -> if (!e.v(access, r)) 0.0 else mainPriority(e.get(road)) }, { 1.0 }, 0.0, heading)
                    else CustomWeighting.Parameters({ e, r -> minOf(999.0, e.v(speed, r)) }, { speed.maxOrMaxStorableDecimal },
                        { e, r -> if (!e.v(access, r)) 0.0 else 1.0 }, { 1.0 }, 90.0, heading)
                }
                "foot" -> { // foot.json
                    val access = b("foot_access"); val speed = d("foot_average_speed"); val prio = d("foot_priority")
                    val hike = i("hike_rating"); val mtb = i("mtb_rating")
                    CustomWeighting.Parameters({ e, r -> minOf(999.0, e.v(speed, r)) }, { speed.maxOrMaxStorableDecimal },
                        { e, r ->
                            var v = 1.0
                            if (!e.v(access, r) || e.v(hike, r) >= 2) v *= 0.0
                            if (e.v(mtb, r) > 2) v *= 0.0 else v *= e.v(prio, r)
                            v
                        }, { prio.maxOrMaxStorableDecimal }, 0.0, heading)
                }
                "wheelchair" -> { // maps-graph.yml's inline model: foot.json without steps or rough paths, at most 4 km/h
                    val access = b("foot_access"); val speed = d("foot_average_speed"); val prio = d("foot_priority")
                    val hike = i("hike_rating"); val mtb = i("mtb_rating")
                    val road = em.getEnumEncodedValue(com.graphhopper.routing.ev.RoadClass.KEY, com.graphhopper.routing.ev.RoadClass::class.java)
                    CustomWeighting.Parameters({ e, r -> minOf(999.0, e.v(speed, r), 4.0) }, { minOf(speed.maxOrMaxStorableDecimal, 4.0) },
                        { e, r ->
                            var v = 1.0
                            if (!e.v(access, r) || e.v(hike, r) >= 1) v *= 0.0
                            if (e.get(road) == com.graphhopper.routing.ev.RoadClass.STEPS) v *= 0.0
                            if (e.v(mtb, r) > 1) v *= 0.0 else v *= e.v(prio, r)
                            v
                        }, { prio.maxOrMaxStorableDecimal }, 0.0, heading)
                }
                "bike" -> { // bike.json
                    val access = b("bike_access"); val speed = d("bike_average_speed"); val prio = d("bike_priority")
                    val hike = i("hike_rating"); val mtb = i("mtb_rating"); val round = b("roundabout")
                    CustomWeighting.Parameters({ e, r ->
                            var v = minOf(999.0, e.v(speed, r))
                            if (!e.v(access, r) && e.v(access, !r)) v = minOf(v, 5.0)
                            v
                        }, { speed.maxOrMaxStorableDecimal },
                        { e, r ->
                            var v = e.v(prio, r)
                            if (e.v(mtb, r) > 2) v *= 0.0
                            if (e.v(hike, r) > 1) v *= 0.0
                            val fwd = e.v(access, r); val back = e.v(access, !r)
                            if (!fwd && (!back || e.v(round, r))) v *= 0.0 else if (!fwd && back) v *= 0.2
                            v
                        }, { prio.maxOrMaxStorableDecimal }, 0.0, heading)
                }
                else -> throw IllegalArgumentException("No profile ${profile.name}")
            }
            return CustomWeighting(turns, p)
        }
    }
}
