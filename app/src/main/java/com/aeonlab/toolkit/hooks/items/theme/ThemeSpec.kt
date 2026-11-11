package com.aeonlab.toolkit.hooks.items.theme

import android.content.Context
import android.os.Build
import android.util.Base64
import com.aeonlab.toolkit.BuildConfig
import com.aeonlab.toolkit.utils.HLog
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * 主题文件（.hctheme / .json）的读写。
 *
 * 落地格式刻意做成「MDWechat 配置项的超集」：
 * - 我们自己的字段放在 items.* 下，语义与 MDWechat 的 HookConfig 一一对应；
 * - 顶层同时写出 mdwechat 兼容块（colorPrimary / isHookAvatar / isHookHideActionBar），
 *   方便把一个主题交给仍在使用 MDWechat 配置语义的工具；
 * - 图片一律 base64 内联，主题文件自包含，可直接分享。
 */
object ThemeSpec {
    const val FORMAT = "hchat-theme"
    const val SCHEMA_VERSION = 1
    const val FILE_EXTENSION = ".hctheme.json"
    const val MIME_JSON = "application/json"

    private const val TAG = "[AeonLab:ThemeSpec]"

    sealed class ImportOutcome {
        data class Success(
            val snapshot: ThemeSnapshot,
            val applied: List<String>,
            val warnings: List<String>
        ) : ImportOutcome()

        data class Failure(val reason: String) : ImportOutcome()
    }

    fun exportJson(context: Context): String {
        val snapshot = ThemeSettings.snapshot(context)
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("name", snapshot.name.ifBlank { "未命名主题" })
        root.put("author", snapshot.author)
        root.put("note", snapshot.note)
        root.put("createdAt", timestamp())
        root.put(
            "module",
            JSONObject()
                .put("name", "Hchat")
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
        )
        root.put(
            "host",
            JSONObject()
                .put("package", "com.tencent.mm")
                .put("android", Build.VERSION.RELEASE ?: "")
        )
        root.put("items", itemsToJson(snapshot))
        root.put("mdwechat", mdwechatBlock(snapshot))
        return root.toString(2)
    }

    fun importJson(context: Context, text: String, sourceName: String = ""): ImportOutcome {
        return when (val parsed = parseJson(context, text)) {
            is ImportOutcome.Success -> {
                ThemeSettings.write(context, parsed.snapshot.copy(enabled = true))
                ThemeSync.applyRoundAvatar(context, parsed.snapshot.roundAvatar)
                parsed
            }

            is ImportOutcome.Failure -> parsed
        }
    }

    /** 只解析、不写设置，供 zip 扫描时先读配置用。 */
    fun parseJson(context: Context, text: String): ImportOutcome {
        if (text.isBlank()) return ImportOutcome.Failure("主题文件是空的，没有可导入的内容")
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return ImportOutcome.Failure("不是合法的 JSON 主题文件，请确认导入的是 Hchat 导出的主题文件（.hctheme.json）")
        val format = root.optString("format", "").trim()
        val schema = root.optInt("schemaVersion", 0)
        if (format.isEmpty()) {
            val mapped = fromMdwechatJson(root)
                ?: return ImportOutcome.Failure("缺少 format 标记，且没有可识别的 MDWechat 主题字段（colorPrimary / isHookAvatar / isHookHideActionBar）")
            return ImportOutcome.Success(
                mapped.first.copy(enabled = true),
                listOf("主题开关") + mapped.second,
                emptyList()
            )
        }
        if (format != FORMAT) {
            return ImportOutcome.Failure("主题格式标记是「$format」，本模块只认「$FORMAT」")
        }
        if (schema > SCHEMA_VERSION) {
            return ImportOutcome.Failure("主题文件 schemaVersion=$schema，高于本模块支持的 $SCHEMA_VERSION，请升级模块后再导入")
        }
        val items = root.optJSONObject("items")
            ?: return ImportOutcome.Failure("主题文件缺少 items 节点，没有可导入的主题项")
        val warnings = ArrayList<String>()
        val snapshot = itemsFromJson(context, items, root, warnings)
        if (snapshot.isEmpty()) {
            return ImportOutcome.Failure("主题文件里没有可导入的主题项（所有字段都是空值）")
        }
        return ImportOutcome.Success(snapshot.copy(enabled = true), describeApplied(snapshot), warnings)
    }

    /** 兼容导入手机里现成的主题包（zip / 改名成 .txt 的 zip）：找 JSON 配置 + 按文件名认图片。 */
    fun importArchive(context: Context, bytes: ByteArray, sourceName: String = ""): ImportOutcome {
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
            if (text.trimStart().startsWith("{")) return importJson(context, text, sourceName)
            return ImportOutcome.Failure("既不是 zip 主题包，也不是 JSON 主题文件（文件头不是 PK，内容也不是 JSON）")
        }
        val warnings = ArrayList<String>()
        var config: ThemeSnapshot? = null
        val images = LinkedHashMap<String, ByteArray>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                var totalRead = 0L
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        val data = zip.readBytes()
                        totalRead += data.size
                        if (totalRead > MAX_ARCHIVE_BYTES) {
                            throw IllegalStateException("压缩包展开后超过 ${MAX_ARCHIVE_BYTES / 1024 / 1024}MB，已停止读取")
                        }
                        when {
                            name.endsWith(".json", ignoreCase = true) && config == null -> {
                                val parsed = runCatching {
                                    parseJson(context, String(data, Charsets.UTF_8))
                                }.getOrNull()
                                if (parsed is ImportOutcome.Success) config = parsed.snapshot
                            }

                            name.endsWith(".png", ignoreCase = true) ||
                                name.endsWith(".jpg", ignoreCase = true) ||
                                name.endsWith(".jpeg", ignoreCase = true) ||
                                name.endsWith(".webp", ignoreCase = true) -> {
                                if (!name.contains(".9.")) images[name] = data
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }.onFailure {
            return ImportOutcome.Failure("读取 zip 主题包失败：${it.message ?: it.javaClass.simpleName}")
        }
        // 只有图片、没有配置的主题包：保留当前主题设置，只替换识别到的图片
        val base = config ?: ThemeSettings.snapshot(context)
        val slotMapping = mapImages(context, images, warnings)
        val merged = base.copy(
            enabled = true,
            tabIcons = base.tabIcons.mapIndexed { index, path ->
                slotMapping[ThemeImageStore.slotForTabIcon(index)] ?: path
            },
            backgroundSplash = slotMapping[ThemeImageStore.SLOT_SPLASH] ?: base.backgroundSplash,
            backgroundHome = slotMapping[ThemeImageStore.SLOT_HOME] ?: base.backgroundHome,
            backgroundContacts = slotMapping[ThemeImageStore.SLOT_CONTACTS] ?: base.backgroundContacts,
            backgroundDiscover = slotMapping[ThemeImageStore.SLOT_DISCOVER] ?: base.backgroundDiscover,
            backgroundMe = slotMapping[ThemeImageStore.SLOT_ME] ?: base.backgroundMe
        )
        if (config == null && slotMapping.isEmpty()) {
            return ImportOutcome.Failure("压缩包里没有可识别的主题配置或图片（只认识 JSON 配置和 png/jpg 背景图）")
        }
        if (config == null) {
            warnings.add("压缩包里没有主题配置 JSON，只按文件名导入图片")
        }
        val finalSnapshot = merged.copy(enabled = true)
        val applied = ArrayList<String>()
        applied.addAll(describeApplied(finalSnapshot))
        if (config != null) {
            applied.addAll(paraDescribe(config!!))
            ThemeSettings.write(context, finalSnapshot)
            ThemeSync.applyRoundAvatar(context, finalSnapshot.roundAvatar)
        } else {
            ThemeSettings.write(context, finalSnapshot)
        }
        if (applied.isEmpty()) {
            return ImportOutcome.Failure("主题包解析完成，但没有可以落到设置里的字段")
        }
        return ImportOutcome.Success(finalSnapshot, applied, warnings)
    }

    private fun mapImages(
        context: Context,
        images: Map<String, ByteArray>,
        warnings: MutableList<String>
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        images.forEach { (name, data) ->
            val lower = name.lowercase(Locale.ROOT)
            val slot = when {
                lower.contains("tab_icon") || lower.contains("tabicon") -> tabSlotFor(lower)
                lower.contains("splash") || lower.contains("launch") -> ThemeImageStore.SLOT_SPLASH
                lower.contains("home") || lower.contains("main") || lower.contains("conversation") ||
                    name.contains("主页") || name.contains("首页") -> ThemeImageStore.SLOT_HOME
                lower.contains("contact") || name.contains("通讯录") -> ThemeImageStore.SLOT_CONTACTS
                lower.contains("discover") || name.contains("发现") -> ThemeImageStore.SLOT_DISCOVER
                lower.contains("mine") || lower.contains("me_") || lower.contains("setting") ||
                    name.contains("我") -> ThemeImageStore.SLOT_ME
                else -> null
            }
            if (slot == null) {
                warnings.add("跳过未识别的图片：$name")
                return@forEach
            }
            if (result.containsKey(slot)) return@forEach
            val saved = ThemeImageStore.saveFromBytes(context, slot, data)
            if (saved != null) result[slot] = saved else warnings.add("图片解码失败：$name")
        }
        return result
    }

    private fun tabSlotFor(lowerName: String): String {
        val index = when {
            lowerName.contains("0") || lowerName.contains("wechat") -> 0
            lowerName.contains("1") || lowerName.contains("contact") -> 1
            lowerName.contains("2") || lowerName.contains("discover") -> 2
            else -> 3
        }
        return ThemeImageStore.slotForTabIcon(index)
    }

    private fun describeApplied(snapshot: ThemeSnapshot): List<String> {
        val applied = ArrayList<String>()
        applied.add("主题开关")
        if (snapshot.roundAvatar) applied.add("圆形头像")
        if (snapshot.actionBarHide) applied.add("隐藏 ActionBar")
        if (snapshot.tabEnabled) applied.add("底栏样式")
        if (snapshot.tabIcons.any { it.isNotBlank() }) applied.add("底栏图标")
        if (snapshot.backgroundPaths().any { it.isNotBlank() }) applied.add("页面背景图片")
        if (snapshot.actionBarBackgroundColor.isNotBlank() ||
            snapshot.actionBarTitleColor.isNotBlank() ||
            snapshot.actionBarSubtitleColor.isNotBlank()
        ) {
            applied.add("标题栏颜色")
        }
        return applied
    }

    private fun paraDescribe(snapshot: ThemeSnapshot): List<String> {
        val applied = ArrayList<String>()
        if (snapshot.tabBackgroundColor.isNotBlank() ||
            snapshot.tabTextColor.isNotBlank() ||
            snapshot.tabSelectedTextColor.isNotBlank()
        ) {
            applied.add("底栏颜色")
        }
        return applied
    }

    private fun itemsToJson(snapshot: ThemeSnapshot): JSONObject {
        val items = JSONObject()
        items.put(
            "tab",
            JSONObject()
                .put("enabled", snapshot.tabEnabled)
                .put("hideNative", snapshot.tabHideNative)
                .put("backgroundColor", snapshot.tabBackgroundColor)
                .put("textColor", snapshot.tabTextColor)
                .put("selectedTextColor", snapshot.tabSelectedTextColor)
                .put(
                    "icons",
                    JSONObject().apply {
                        TAB_SLOT_NAMES.forEachIndexed { index, name ->
                            val path = snapshot.tabIcons.getOrElse(index) { "" }
                            imageToJson(path)?.let { put(name, it) }
                        }
                    }
                )
        )
        items.put("avatar", JSONObject().put("round", snapshot.roundAvatar))
        items.put(
            "actionBar",
            JSONObject()
                .put("hide", snapshot.actionBarHide)
                .put("backgroundColor", snapshot.actionBarBackgroundColor)
                .put("titleColor", snapshot.actionBarTitleColor)
                .put("subtitleColor", snapshot.actionBarSubtitleColor)
        )
        items.put(
            "background",
            JSONObject()
                .put("enabled", snapshot.backgroundEnabled)
                .apply {
                    put("splash", imageToJson(snapshot.backgroundSplash) ?: "")
                    put("home", imageToJson(snapshot.backgroundHome) ?: "")
                    put("contacts", imageToJson(snapshot.backgroundContacts) ?: "")
                    put("discover", imageToJson(snapshot.backgroundDiscover) ?: "")
                    put("me", imageToJson(snapshot.backgroundMe) ?: "")
                }
        )
        return items
    }

    private fun imageToJson(path: String?): JSONObject? {
        val bytes = ThemeImageStore.readBytes(path) ?: return null
        return JSONObject()
            .put("mime", "image/png")
            .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun itemsFromJson(
        context: Context,
        items: JSONObject,
        root: JSONObject,
        warnings: MutableList<String>
    ): ThemeSnapshot {
        val tab = items.optJSONObject("tab")
        val avatar = items.optJSONObject("avatar")
        val actionBar = items.optJSONObject("actionBar")
        val background = items.optJSONObject("background")
        val icons = tab?.optJSONObject("icons")
        val tabIcons = TAB_SLOT_NAMES.mapIndexed { index, name ->
            imageFromJson(context, icons?.opt(name), ThemeImageStore.slotForTabIcon(index), warnings)
        }
        return ThemeSnapshot(
            enabled = true,
            name = root.optString("name", ""),
            author = root.optString("author", ""),
            note = root.optString("note", ""),
            tabEnabled = tab?.optBoolean("enabled", true) ?: true,
            tabHideNative = tab?.optBoolean("hideNative", false) ?: false,
            tabBackgroundColor = ThemeSettings.normalizeColor(tab?.optString("backgroundColor", "")),
            tabTextColor = ThemeSettings.normalizeColor(tab?.optString("textColor", "")),
            tabSelectedTextColor = ThemeSettings.normalizeColor(tab?.optString("selectedTextColor", "")),
            tabIcons = tabIcons,
            roundAvatar = avatar?.optBoolean("round", false) ?: false,
            actionBarHide = actionBar?.optBoolean("hide", false) ?: false,
            actionBarBackgroundColor = ThemeSettings.normalizeColor(actionBar?.optString("backgroundColor", "")),
            actionBarTitleColor = ThemeSettings.normalizeColor(actionBar?.optString("titleColor", "")),
            actionBarSubtitleColor = ThemeSettings.normalizeColor(actionBar?.optString("subtitleColor", "")),
            backgroundEnabled = background?.optBoolean("enabled", true) ?: true,
            backgroundSplash = imageFromJson(context, background?.opt("splash"), ThemeImageStore.SLOT_SPLASH, warnings),
            backgroundHome = imageFromJson(context, background?.opt("home"), ThemeImageStore.SLOT_HOME, warnings),
            backgroundContacts = imageFromJson(context, background?.opt("contacts"), ThemeImageStore.SLOT_CONTACTS, warnings),
            backgroundDiscover = imageFromJson(context, background?.opt("discover"), ThemeImageStore.SLOT_DISCOVER, warnings),
            backgroundMe = imageFromJson(context, background?.opt("me"), ThemeImageStore.SLOT_ME, warnings)
        )
    }

    private fun imageFromJson(
        context: Context,
        value: Any?,
        slot: String,
        warnings: MutableList<String>
    ): String {
        val encoded = when (value) {
            null -> return ""
            is JSONObject -> value.optString("base64", "")
            is String -> value
            else -> return ""
        }
        if (encoded.isBlank()) return ""
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            warnings.add("$slot 图片 base64 解码失败")
            return ""
        }
        val saved = ThemeImageStore.saveFromBytes(context, slot, bytes)
        if (saved == null) warnings.add("$slot 图片写入失败")
        return saved.orEmpty()
    }

    /** MDWechat 的配置语义（SharedPreferences 键）映射到本模块字段。 */
    private fun fromMdwechatJson(root: JSONObject): Pair<ThemeSnapshot, List<String>>? {
        val applied = ArrayList<String>()
        var snapshot = ThemeSnapshot(enabled = true)
        val primary = optColor(root, "colorPrimary") ?: optColor(root, "color_primary")
        if (primary != null) {
            snapshot = snapshot.copy(actionBarBackgroundColor = primary)
            applied.add("主色（colorPrimary → 标题栏背景色）")
        }
        if (root.optBoolean("isHookAvatar", root.optBoolean("is_hook_avatar", false))) {
            snapshot = snapshot.copy(roundAvatar = true)
            applied.add("圆形头像（isHookAvatar）")
        }
        if (root.optBoolean("isHookHideActionBar", root.optBoolean("is_hook_hide_actionbar", false))) {
            snapshot = snapshot.copy(actionBarHide = true)
            applied.add("隐藏 ActionBar（isHookHideActionBar）")
        }
        val background = root.optJSONObject("backgrounds")
        if (background != null) {
            applied.add("页面背景图片（backgrounds）")
        }
        if (applied.isEmpty()) return null
        return snapshot to applied
    }

    private fun optColor(root: JSONObject, key: String): String? {
        if (!root.has(key)) return null
        val raw = root.opt(key)
        if (raw is Number) return String.format(Locale.ROOT, "#%08X", raw.toInt())
        return ThemeSettings.normalizeColor(raw as? String).ifEmpty { null }
    }

    private fun mdwechatBlock(snapshot: ThemeSnapshot): JSONObject {
        val block = JSONObject()
        val primary = ThemeSettings.parseColor(snapshot.actionBarBackgroundColor)
        if (primary != null) block.put("colorPrimary", primary)
        block.put("isHookAvatar", snapshot.roundAvatar)
        block.put("isHookHideActionBar", snapshot.actionBarHide)
        block.put("isHookTab", snapshot.tabEnabled)
        block.put(
            "backgroundFiles",
            JSONObject().apply {
                put("splash", snapshot.backgroundSplash.isNotBlank())
                put("conversation", snapshot.backgroundHome.isNotBlank())
                put("contact", snapshot.backgroundContacts.isNotBlank())
                put("discover", snapshot.backgroundDiscover.isNotBlank())
                put("settings", snapshot.backgroundMe.isNotBlank())
            }
        )
        return block
    }

    private fun timestamp(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
        format.timeZone = TimeZone.getDefault()
        return format.format(Date())
    }

    private val TAB_SLOT_NAMES = listOf("wechat", "contacts", "discover", "me")
    private const val MAX_ARCHIVE_BYTES = 160L * 1024L * 1024L
}
