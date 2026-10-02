package com.shusheng.cobblemarket.client

import com.google.gson.JsonParser
import com.shusheng.cobblemarket.platform.modVersion
import com.shusheng.cobblemarket.screen.MarketEntryScreen
import net.minecraft.SharedConstants
import net.minecraft.client.MinecraftClient
import net.minecraft.text.ClickEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 「有新版本可用」提示（纯客户端，**Fabric / NeoForge 都有** —— 不依赖 Mod Menu）。
 *
 * **数据源**：我们官网的 `version.json`（发新版本时更新它即可，见 `docs/website_sync_plan.md`）。
 * ⚠ 2026-10-02 的教训：**别去接 Mod Menu 的 `modmenu` 入口点** —— 实测它会让 Mod Menu **自己**的
 * 更新检查抛 `ConcurrentModificationException`，连带打死**所有**模组的更新角标（完整证据链见记忆
 * `project-modmenu-update-check`）。自己查自己的，谁也不连累。
 *
 * **不骚扰**（用户 2026-10-02 拍板的口径）：
 * - 只在玩家**主动打开市场界面**时提示一次，发一条**金色聊天行**（末尾的下载入口可点），不弹窗、不挡屏
 * - **每次进入世界提一次**：进世界（含退出存档重进、重连服务器）后，第一次打开市场界面时提一次；
 *   同一个世界里不再重复（一局游戏不会因为你多开几次市场就刷屏）—— 不记忆「提过哪个版本」，
 *   要提醒到玩家真的更新为止（用户口径：否则设置里那个开关就没意义了）
 * - 设置面板里有开关（[ClientConfig.updateNotice]）：关掉后**立刻生效** —— 连查都不查，
 *   已经查到的结果也不再往外发（三条路径都要判：启动查 / 进世界补查 / 显示）
 *
 * ⚠ **隐私**：一次普通 GET，**不带任何玩家标识**（不发 UUID / 用户名，也不带任何参数），
 *   只带一个「是哪个模组在查」的 User-Agent，方便官网侧看使用量。
 * ⚠ **容错**：断网 / 超时 / 404 / JSON 坏了 / 版本号解析不出来 —— 一律静默当「没有更新」，
 *   不刷日志、不误报、更不该影响游戏。
 */
object UpdateNotice {

    /** 官网的版本清单（GitHub Pages）。结构 = 按 MC 版本分键，见网站仓库 `docs/version.json` */
    private const val VERSION_URL = "https://shushenga.github.io/CobbleMarket-Website/version.json"

    /** 清单那一条里没写 `download` 时的兜底：CurseForge 的文件页 */
    private const val FALLBACK_DOWNLOAD = "https://www.curseforge.com/minecraft/mc-mods/cobblemarket/files"

    private const val TIMEOUT_SECONDS = 5L

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build()
    }

    /** 后台线程查到的结果；null = 还没查完 / 没有更新 */
    @Volatile
    private var available: Available? = null

    /** 当前这个世界已经提示过（**每个世界只提一次**；退出存档重进/重连服务器会重置 —— 见类注释） */
    private var shownThisWorld = false

    /** 上一 tick 是否在世界里（用于检测「刚进世界」这个时刻） */
    private var inWorld = false

    /** 是否已经拿到过明确答复（成功 200 且解析完了）。false = 还没查成（断网/404 等）⇒ 进世界时可以再试一次 */
    @Volatile
    private var checked = false

    /** 防止同一时刻起两个查询线程 */
    @Volatile
    private var checking = false

    private data class Available(val version: String, val download: String)

    /**
     * 客户端初始化时调一次：起一个**守护线程**去查（不阻塞启动，也不拖慢进游戏）。
     * 只在开关打开、且拿得到自己的版本号时才查。
     */
    fun init() {
        if (!ClientConfig.updateNotice) return
        startCheck()
    }

    /**
     * 每客户端 tick 调（[CobbleMarketClient] 里挂的）：
     * - **刚进世界**那一下重置「本世界已提示」（退出存档重进、重连服务器都算），并补一次查询（上次没查成的话）
     * - 之后在本世界里**第一次打开市场界面**时把结果说出来，只说一次
     * ⚠ 判据用**市场入口界面**（[MarketEntryScreen]）—— 1.2.0 起那边有全界面白名单 `isMarketScreen`，
     *   本线（1.1.2）还没有，就用入口界面这一处；玩家不看市场就不会被打扰。
     */
    fun tick(client: MinecraftClient) {
        val player = client.player
        if (player == null) {
            inWorld = false
            return
        }
        if (!inWorld) {
            inWorld = true
            shownThisWorld = false
            // 启动时那次没查成（断网 / 官网 404 等）⇒ 进世界后再试一次（查成过就不再试）
            if (!checked && ClientConfig.updateNotice) startCheck()
        }
        if (shownThisWorld) return
        // ⚠⚠ **显示前必须再查一次开关**（2026-10-02 用户实测报的 bug）：开关以前只判了「查不查」，
        //   而**已经查到的结果**会一直留在内存里 —— 玩家关掉开关、退出存档重进（这会重置本世界标记），
        //   打开市场照样被提示。关掉就是关掉：查到的结果也不再往外发。
        if (!ClientConfig.updateNotice) return
        val info = available ?: return
        if (client.currentScreen !is MarketEntryScreen) return
        shownThisWorld = true
        player.sendMessage(message(info), false)
    }

    /** 起一个**守护线程**去查（不阻塞游戏）；同一时刻只允许一个查询在跑 */
    private fun startCheck() {
        val current = modVersion().takeIf { it.isNotBlank() } ?: return
        if (checking) return
        checking = true
        val mcVersion = SharedConstants.getGameVersion().name
        Thread({ check(current, mcVersion) }, "CobbleMarket Update Check").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 金色整句 + **末尾的下载入口单独可点**（点一下用浏览器打开下载页）。
     *
     * ⚠ 只让**链接那一段**可点、也只给那一段画下划线（用户 2026-10-02 两轮口径）：
     * 先是指出「点哪里，哪里的文字底下就该有下划线」，看过效果后定为**别整句都可点** ——
     * 整句画线像整句都是按钮。这与本模组既有可点文本的规矩一致（拍卖播报只把「[点击出价]」画线）。
     *
     * ⚠ 两段**平级**挂在 `Text.literal("")` 下，别让带色的那句当父节点（父样式会传染给子段，
     * 见项目约定「名字带色、陪衬文字不设色、段落平级」）。
     */
    private fun message(info: Available): Text {
        val link = Text.translatable("cobblemarket.update.available_link")
            .formatted(Formatting.GOLD, Formatting.UNDERLINE)
            .styled { it.withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, info.download)) }
        return Text.literal("")
            .append(Text.translatable("cobblemarket.update.available", info.version).formatted(Formatting.GOLD))
            .append(Text.literal(" "))
            .append(link)
    }

    /** 后台线程：拉清单 → 取本 MC 版本那一条 → 比版本号 → 存进 [available]；任何异常都吞掉 */
    private fun check(current: String, mcVersion: String) {
        try {
            val request = HttpRequest.newBuilder(URI.create(VERSION_URL))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("User-Agent", "CobbleMarket/$current")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return
            val node = JsonParser.parseString(response.body()).asJsonObject
                .getAsJsonObject(mcVersion) ?: return
            val latest = node.get("version")?.asString?.takeIf { it.isNotBlank() } ?: return
            // 拿到明确答复了（不管有没有更新）：以后不必再试
            checked = true
            if (!isNewer(latest, current)) return
            available = Available(
                latest,
                node.get("download")?.asString?.takeIf { it.isNotBlank() } ?: FALLBACK_DOWNLOAD
            )
        } catch (e: Exception) {
            // 静默：查更新失败不该在玩家日志里刷栈（没查成 ⇒ checked 仍是 false，进世界时会再试一次）
        } finally {
            checking = false
        }
    }

    /**
     * 版本比较：按 `.` 分段比数字，逐段取第一个不同处定大小（所以 `1.10.0 > 1.9.0`，不是字符串比较）。
     * 任一侧解析不出数字（如 `1.2.0-beta`）就返回 false —— **宁可漏报，也不误报**。
     */
    private fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest) ?: return false
        val b = numbers(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String): List<Int>? {
        val parts = version.trim().split('.')
        val nums = ArrayList<Int>(parts.size)
        for (part in parts) nums.add(part.toIntOrNull() ?: return null)
        return nums
    }
}
