package io.github.riggedlab.hark

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class Speaker(
    val id: String,
    val name: String?,
    val mergedInto: String?,
    val firstSeen: String?,
    val sample: String?,
) {
    val defaultName: String
        get() = if (id.startsWith("pspk_")) {
            "新说话人 #" + id.removePrefix("pspk_").trimStart('0').ifEmpty { "1" }
        } else {
            "未命名 #" + id.removePrefix("spk_").trimStart('0').ifEmpty { "1" }
        }
}

/** 只填缺失字段，null 也可能是用户明确清空的新编辑，不能覆盖。 */
internal fun mergeLegacySpeakers(
    legacy: Map<String, Map<String, Any?>>,
    mac: Map<String, Map<String, Any?>>,
    phone: Map<String, Map<String, Any?>>,
): Pair<Map<String, Map<String, Any?>>, Map<String, Map<String, Any?>>> {
    fun merge(current: Map<String, Map<String, Any?>>, fields: List<String>, nonempty: Boolean)
        : Map<String, Map<String, Any?>> {
        val result = current.mapValues { it.value.toMutableMap() }.toMutableMap()
        for ((id, old) in legacy) {
            val item = result.getOrPut(id) { mutableMapOf() }
            for (field in fields) {
                val value = old[field] ?: continue
                if (field !in item && (!nonempty || value is String && value.isNotBlank())) item[field] = value
            }
        }
        return result
    }
    return merge(mac, listOf("first_seen", "sample"), false) to
        merge(phone, listOf("name", "merged_into"), true)
}

/** 接收远端 Mac 名册时保留本地已有的非空历史元信息，避免旧库升级初期的空列冲掉迁移结果。 */
internal fun mergeMacRoster(
    incoming: Map<String, Map<String, Any?>>,
    existing: Map<String, Map<String, Any?>>,
): Map<String, Map<String, Any?>> {
    fun hasValue(v: Any?) = v != null && (v !is String || v.isNotBlank())
    val out = existing.mapValues { it.value.toMutableMap() }.toMutableMap()
    for ((id, inc) in incoming) {
        val cur = out.getOrPut(id) { mutableMapOf() }
        for ((k, v) in inc) {
            if (hasValue(v) || !hasValue(cur[k])) cur[k] = v
        }
    }
    return out
}

/** Mac 管首次出现信息，手机管名字和合并；编辑先落本机，再后台重试上传。 */
object Speakers {
    private val edits = CoroutineScope(Dispatchers.IO)
    private fun read(ctx: Context, slot: String): JSONObject =
        File(ctx.filesDir, "$slot.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
            ?: JSONObject().put("items", JSONObject())

    private fun toRosterMap(value: JSONObject): Map<String, Map<String, Any?>> {
        val items = value.getJSONObject("items")
        return items.keys().asSequence().associateWith { id ->
            val item = items.getJSONObject(id)
            item.keys().asSequence().associateWith { field -> item.get(field).takeUnless { item.isNull(field) } }
        }
    }

    @Synchronized
    fun migrateLegacy(ctx: Context) {
        val legacy = File(ctx.filesDir, "speakers.json")
        if (!legacy.exists()) return
        val (mac, phone) = mergeLegacySpeakers(toRosterMap(JSONObject(legacy.readText())),
            toRosterMap(read(ctx, "speakers_mac")), toRosterMap(read(ctx, "speakers_phone")))
        for ((slot, items) in listOf("speakers_mac" to mac, "speakers_phone" to phone)) {
            Sync.writeFile(File(ctx.filesDir, "$slot.json"), JSONObject().put("items", JSONObject(items)).toString())
        }
        val p = ctx.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
        check(p.edit().putBoolean("speaker_edits_dirty", true)
            .putLong("speaker_edits_revision", p.getLong("speaker_edits_revision", 0) + 1).commit()) {
            "迁移说话人同步标记失败"
        }
        check(legacy.delete()) { "删除旧说话人文件失败" }
    }

    private fun JSONObject.text(key: String) = optString(key).takeIf { !isNull(key) && it.isNotBlank() }

    @Synchronized
    fun all(ctx: Context): Map<String, Speaker> {
        migrateLegacy(ctx)
        Voiceprint.migrateSchemaFast(ctx)
        val mac = read(ctx, "speakers_mac").getJSONObject("items")
        val local = read(ctx, "speakers_local").optJSONObject("items") ?: JSONObject()
        val phone = read(ctx, "speakers_phone").getJSONObject("items")
        val ids = LinkedHashSet<String>().apply {
            mac.keys().forEach { add(it) }
            local.keys().forEach { if (it.startsWith("pspk_") || !mac.has(it)) add(it) }
            phone.keys().forEach { add(it) }
        }
        return ids.associateWith { id ->
            val meta = mac.optJSONObject(id) ?: local.optJSONObject(id) ?: JSONObject()
            val localMeta = local.optJSONObject(id)
            val edit = phone.optJSONObject(id) ?: JSONObject()
            Speaker(
                id = id,
                name = edit.text("name"),
                mergedInto = edit.text("merged_into"),
                firstSeen = meta.text("first_seen") ?: localMeta?.text("first_seen"),
                sample = meta.text("sample") ?: localMeta?.text("sample"),
            )
        }
    }

    fun resolve(ctx: Context, id: String?): Speaker? {
        val m = all(ctx)
        var cur = id?.let { m[it] } ?: return null
        val seen = HashSet<String>()
        while (cur.mergedInto != null && seen.add(cur.id)) cur = m[cur.mergedInto] ?: break
        return cur
    }

    fun displayName(ctx: Context, id: String?): String? =
        id?.let { resolve(ctx, it)?.let { s -> s.name ?: s.defaultName } ?: it }

    fun edit(ctx: Context, id: String, name: String? = null, mergeInto: String? = null, unmerge: Boolean = false): Boolean {
        synchronized(this) {
            val known = all(ctx)
            if (id !in known) return false
            if (mergeInto != null) {
                val seen = hashSetOf(id)
                var target: String? = mergeInto
                while (target != null) {
                    if (!seen.add(target) || target !in known) return false
                    target = known[target]?.mergedInto
                }
            }
            val body = read(ctx, "speakers_phone")
            val items = body.getJSONObject("items")
            val item = items.optJSONObject(id) ?: JSONObject()
            if (name != null) item.put("name", name.trim().take(40).ifBlank { null } ?: JSONObject.NULL)
            if (mergeInto != null) item.put("merged_into", mergeInto)
            if (unmerge) item.put("merged_into", JSONObject.NULL)
            items.put(id, item)
            Sync.writeFile(File(ctx.filesDir, "speakers_phone.json"), body.toString())
            val p = ctx.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
            p.edit().putBoolean("speaker_edits_dirty", true)
                .putLong("speaker_edits_revision", p.getLong("speaker_edits_revision", 0) + 1).commit()
        }
        edits.launch { Sync.pushSpeakerEdits(ctx.applicationContext) }
        return true
    }

    @Synchronized
    fun pending(ctx: Context): Pair<Long, String>? {
        migrateLegacy(ctx)
        val p = ctx.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
        if (!p.getBoolean("speaker_edits_dirty", false)) return null
        return p.getLong("speaker_edits_revision", 0) to read(ctx, "speakers_phone").toString()
    }

    @Synchronized
    fun acknowledge(ctx: Context, revision: Long, ver: Int) {
        val p = ctx.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
        if (p.getLong("speaker_edits_revision", 0) == revision)
            p.edit().putBoolean("speaker_edits_dirty", false).putInt("sp_ver", ver).commit()
    }

    @Synchronized
    fun receive(ctx: Context, slot: String, ver: Int, value: JSONObject) {
        migrateLegacy(ctx)
        require(value.optJSONObject("items") != null) { "说话人名册格式错误" }
        val p = ctx.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
        if (slot == "speakers_phone" && (p.getBoolean("speaker_edits_dirty", false) || ver < p.getInt("sp_ver", 0))) return
        val toSave = if (slot == "speakers_mac") {
            val merged = mergeMacRoster(toRosterMap(value), toRosterMap(read(ctx, "speakers_mac")))
            JSONObject().put("items", JSONObject(merged))
        } else value
        Sync.writeFile(File(ctx.filesDir, "$slot.json"), toSave.toString())
        p.edit().putInt(if (slot == "speakers_mac") "sm_ver" else "sp_ver", ver).commit()
    }
}
