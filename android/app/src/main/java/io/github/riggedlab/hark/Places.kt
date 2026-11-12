package io.github.riggedlab.hark

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.math.abs

/** 一个地点：第一次在这里定位时自动创建，用户可以起名字、和别的地点合并 */
data class Place(
    val id: Int,
    val name: String?,          // 用户起的名字
    val lat: Double,
    val lon: Double,
    val mergedInto: Int?,
    val auto: String? = null,   // 系统地址服务查到的短名字，比如小区名、大厦名
    val area: String? = null,   // 区 · 路，用来区分重名
) {
    /** 显示的名字：用户起的 > 自动查到的 > 经纬度 */
    val label: String get() = name ?: auto ?: "%.4f, %.4f".format(lat, lon)
}

/** 一次定位记录 */
data class Fix(val t: Long, val lat: Double, val lon: Double, val acc: Float, val place: Int)

/**
 * 地点：录音时每 5 分钟记一次手机位置，200 米以内算同一个地方。
 * 位置只存在手机上（files/places/），不上传。
 */
object Places {
    private const val TAG = "Places"
    const val SAME_PLACE_M = 200.0
    private const val MAX_ACCURACY_M = 1000f      // 误差比这还大的定位不要
    private const val MATCH_WINDOW_MS = 15 * 60_000L   // 找某个时刻的位置时，前后 15 分钟内的定位都算

    private val lock = Any()
    @Volatile private var places: List<Place>? = null
    @Volatile private var fixes: List<Fix>? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "places").apply { mkdirs() }
    private fun placesFile(ctx: Context) = File(dir(ctx), "places.json")
    private fun fixesFile(ctx: Context) = File(dir(ctx), "fixes.jsonl")

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun all(ctx: Context): List<Place> = synchronized(lock) {
        places ?: runCatching {
            val f = placesFile(ctx)
            if (!f.exists()) emptyList() else JSONArray(f.readText()).let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                    Place(o.getInt("id"), o.optString("name").takeIf { !o.isNull("name") && it.isNotBlank() },
                        o.getDouble("lat"), o.getDouble("lon"),
                        if (o.isNull("merged_into")) null else o.optInt("merged_into"),
                        o.optString("auto").takeIf { !o.isNull("auto") && it.isNotBlank() },
                        o.optString("area").takeIf { !o.isNull("area") && it.isNotBlank() })
                } }
            }
        }.getOrDefault(emptyList()).also { places = it }
    }

    private fun save(ctx: Context, list: List<Place>) {
        val a = JSONArray()
        list.forEach { p -> a.put(JSONObject().apply {
            put("id", p.id); put("name", p.name ?: JSONObject.NULL)
            put("lat", p.lat); put("lon", p.lon); put("merged_into", p.mergedInto ?: JSONObject.NULL)
            put("auto", p.auto ?: JSONObject.NULL); put("area", p.area ?: JSONObject.NULL)
        }) }
        val tmp = File(dir(ctx), "places.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(placesFile(ctx))
        places = list
    }

    fun fixes(ctx: Context): List<Fix> = synchronized(lock) {
        fixes ?: runCatching {
            val f = fixesFile(ctx)
            if (!f.exists()) emptyList() else f.readLines().mapNotNull { l ->
                runCatching { JSONObject(l).let {
                    Fix(it.getLong("t"), it.getDouble("lat"), it.getDouble("lon"), it.getDouble("acc").toFloat(), it.getInt("place"))
                } }.getOrNull()
            }
        }.getOrDefault(emptyList()).also { fixes = it }
    }

    /** 沿合并链找到最终的地点 */
    fun resolve(ctx: Context, id: Int?): Place? {
        val m = all(ctx).associateBy { it.id }
        var cur = id?.let { m[it] } ?: return null
        val seen = HashSet<Int>()
        while (cur.mergedInto != null && seen.add(cur.id)) cur = m[cur.mergedInto] ?: break
        return cur
    }

    /** 某个时刻在哪儿：取时间最接近的一次定位 */
    fun at(ctx: Context, time: LocalDateTime): Place? {
        val ms = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val f = fixes(ctx).minByOrNull { abs(it.t - ms) } ?: return null
        if (abs(f.t - ms) > MATCH_WINDOW_MS) return null
        return resolve(ctx, f.place)
    }

    /** 记下一次定位，归到 200 米内最近的地点；没有就新建一个 */
    fun record(ctx: Context, loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        synchronized(lock) {
            val list = all(ctx)
            val near = list.map { it to distance(it.lat, it.lon, loc.latitude, loc.longitude) }
                .filter { it.second <= SAME_PLACE_M }.minByOrNull { it.second }?.first
            val placeId = near?.id ?: run {
                val id = (list.maxOfOrNull { it.id } ?: 0) + 1
                val (auto, area) = geocode(ctx, loc.latitude, loc.longitude)
                save(ctx, list + Place(id, null, loc.latitude, loc.longitude, null, auto, area))
                id
            }
            val fix = Fix(loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 0f, placeId)
            fixesFile(ctx).appendText(JSONObject().apply {
                put("t", fix.t); put("lat", fix.lat); put("lon", fix.lon); put("acc", fix.acc.toDouble()); put("place", fix.place)
            }.toString() + "\n")
            fixes = fixes(ctx) + fix
        }
    }

    /**
     * 用系统自带的地址服务把经纬度换成名字（每个新地点只查一次）。
     * 查不到（没网、服务不可用）就返回 null，界面上先显示经纬度，之后 [backfill] 会再试。
     */
    fun geocode(ctx: Context, lat: Double, lon: Double): Pair<String?, String?> = runCatching {
        if (!android.location.Geocoder.isPresent()) return null to null
        @Suppress("DEPRECATION")
        val a = android.location.Geocoder(ctx, java.util.Locale.CHINA).getFromLocation(lat, lon, 1)?.firstOrNull()
            ?: return null to null
        shortName(a.featureName, a.thoroughfare, a.subLocality) to
            listOfNotNull(a.subLocality, a.thoroughfare).distinct().joinToString(" · ").ifBlank { null }
    }.onFailure { Log.w(TAG, "geocode failed: ${it.message}") }.getOrDefault(null to null)

    /** "某某路1605号某某小区" → "某某小区"；只有门牌号的，用"某某路1605号" */
    internal fun shortName(feature: String?, street: String?, district: String?): String? {
        val f = feature?.trim().orEmpty()
        val afterNo = f.substringAfterLast('号', "").trim()
        return when {
            afterNo.length >= 2 -> afterNo
            f.isNotEmpty() && !f.all { it.isDigit() } -> f
            !street.isNullOrBlank() -> street
            else -> district
        }
    }

    /** 补查之前没查到名字的地点（比如当时没网） */
    fun backfill(ctx: Context) {
        val missing = all(ctx).filter { it.auto == null }
        if (missing.isEmpty()) return
        val found = missing.associate { it.id to geocode(ctx, it.lat, it.lon) }.filterValues { it.first != null }
        if (found.isEmpty()) return
        synchronized(lock) {
            save(ctx, all(ctx).map { p -> found[p.id]?.let { (a, ar) -> p.copy(auto = a, area = ar) } ?: p })
        }
    }

    fun rename(ctx: Context, id: Int, name: String) = synchronized(lock) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(name = name.trim().ifBlank { null }) else it })
    }

    fun merge(ctx: Context, id: Int, into: Int?) = synchronized(lock) {
        // 不能合并成环
        var cur = into
        val seen = HashSet<Int>()
        while (cur != null && seen.add(cur)) {
            if (cur == id) return@synchronized
            cur = all(ctx).firstOrNull { it.id == cur }?.mergedInto
        }
        save(ctx, all(ctx).map { if (it.id == id) it.copy(mergedInto = into) else it })
    }

    /** 每个地点（合并后）去过的天数、记录到的时长（分钟，按定位次数 × 5 分钟估算）、最近一次 */
    data class Stats(val minutes: Int, val days: Int, val last: LocalDateTime?)

    fun stats(ctx: Context): Map<Int, Stats> {
        val byPlace = fixes(ctx).groupBy { resolve(ctx, it.place)?.id ?: it.place }
        return byPlace.mapValues { (_, fs) ->
            val times = fs.map { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.t), ZoneId.systemDefault()) }
            Stats(fs.size * 5, times.map { it.toLocalDate() }.distinct().size, times.maxOrNull())
        }
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0].toDouble()
    }

    private val exec = Executors.newSingleThreadExecutor()

    /** 取一次当前位置（异步），取到就记下来。没权限或者定位关了就什么都不做 */
    @SuppressLint("MissingPermission")
    fun requestFix(ctx: Context) {
        if (!hasPermission(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        // 只用网络定位（基站 + Wi-Fi），不开 GPS：几乎不耗电，误差几十到一两百米，分辨小区、公司够用
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> { Log.i(TAG, "网络定位不可用"); return }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, exec) { loc ->
                    (loc ?: lm.getLastKnownLocation(provider))?.let { record(ctx, it) }
                }
            } else {
                lm.getLastKnownLocation(provider)?.let { exec.execute { record(ctx, it) } }
            }
        }.onFailure { Log.w(TAG, "定位失败: ${it.message}") }
    }
}
