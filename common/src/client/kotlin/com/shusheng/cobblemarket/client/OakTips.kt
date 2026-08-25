package com.shusheng.cobblemarket.client

import com.google.gson.JsonParser
import net.minecraft.resource.ResourceManager
import net.minecraft.util.Identifier

/**
 * 大木博士知识点：客户端按当前语言从 assets/cobblemarket/oak_tips/<lang>.json 加载字符串数组，
 * 每次进入入口界面随机抽一句（调用方：MarketEntryScreen，init 时抽）。
 */
object OakTips {
    private var tips: List<String> = emptyList()
    private var loadedLang: String? = null

    /** 语言未变时跳过重复读取（每次进入口界面都会调用，避免重复 IO） */
    fun load(manager: ResourceManager, language: String) {
        if (loadedLang == language) return
        loadedLang = language
        tips = read(manager, language) ?: read(manager, "en_us") ?: emptyList()
    }

    private fun read(manager: ResourceManager, lang: String): List<String>? {
        val id = Identifier.of("cobblemarket", "oak_tips/$lang.json")
        return try {
            manager.getResource(id).get().inputStream.use { stream ->
                JsonParser.parseString(stream.readBytes().toString(Charsets.UTF_8)).asJsonArray
                    .map { it.asString }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 随机抽一句知识点；内容为空（资源缺失/解析失败）返回 null，调用方跳过渲染 */
    fun randomTip(): String? = if (tips.isEmpty()) null else tips.random()
}
