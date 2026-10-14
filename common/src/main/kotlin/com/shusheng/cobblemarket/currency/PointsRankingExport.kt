package com.shusheng.cobblemarket.currency

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * **余额排行的导出** —— 把全服余额写进存档目录，供**服主自己的工具**（全息板 / 告示牌 / 网站…）读取。
 *
 * 2026-10-14 用户提（起因：CobbleDollars 有这项能力）：RP 也该能像它那样把余额排行显示在服务器里。
 * 定位照它：**本模组只提供「文件 + 命令」，怎么显示是服主自己的事**（它也没有计分板、没有
 * PlaceholderAPI 集成 —— 那两样别去抄，本来就不存在）。
 *
 * ```
 * <存档>/cobblemarket_points/
 * ├─ players/<uuid>.json   { "name": …, "points": … }        ← 查单人（文件名就是 UUID）
 * └─ ranking.json          [ { "name": …, "uuid": …, "points": … }, … ]  ← 全部玩家，按余额降序
 * ```
 *
 * ⚠⭐ **这个格式发布后就是对外契约**：服主的全息板 / 牌子 / 网站都是照着它写的，
 *   改字段名或目录名等于让那些东西集体失效。加字段可以，改/删不行。
 *   （`uuid` 是**有意多给**的：外部工具按它认人，玩家改名后榜不会错位。）
 *
 * ⚠ **为什么只写变化的**：默认每 5 分钟扫一次内存里的余额表（几十微秒），**内容没变就一个字节都不写**。
 *   200 人的服上"每 5 分钟重写 200 个文件"是白费磁盘寿命，也让外部工具反复读到相同的旧数据。
 *   ⇒ 余额没动过的那些周期里，本功能对服务器**零开销**（只有一次 map 比较）。
 *
 * ⚠ **为什么同步写、不开后台线程**（2026-10-14 拍板，方案文档第六节的"待定"）：
 *   全量一份 + 变化的几个玩家，量级是几十 KB / 毫秒级；且内容不变时根本不写。
 *   换来的是没有「关服时后台线程还在写一半」「两次导出撞车」这类要额外兜的并发问题 ——
 *   本项目其余落盘（`PersistentState` / 各 CSV 账本）也全是同步的。
 *   （CobbleDollars 用单线程池是因为它那 5 分钟要写**每个玩家的主数据文件**，量级完全不同。）
 */
object PointsRankingExport {

    /**
     * 导出目录名（存档根目录下）。
     * ⚠ 改名 = 破坏对外契约（见类注释），别动。
     */
    private const val DIR_NAME = "cobblemarket_points"

    /** 榜上一行：余额 + **当时的**名字快照 */
    data class RankRow(val uuid: UUID, val name: String, val points: Long)

    /** 人可读优先：服主会直接打开这些文件看，外部工具解析不受缩进影响 */
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** tick 节流计数（照 `FinanceService.tick`） */
    private var tickCount = 0

    /** 上次成功导出的快照；**null = 本次启动还没导出过** ⇒ 首次必然全量写一次 */
    private var lastSnapshot: List<RankRow>? = null

    /** 导出目录只打一次日志（服主排障第一问就是"文件写哪了"） */
    private var loggedPath = false

    /** 名字解析不到的告警只发一次（解析不到是常态，别每 5 分钟刷一行） */
    private var warnedMissingNames = false

    /**
     * 服务器 tick 末（挂在 `CobbleMarket.onServerTickEnd`）：按配置的间隔到点导出一次。
     * ⚠ 节流在**本函数内**做，不依赖调用方 —— 与 `FinanceService` / 充值订单维护同一套写法。
     */
    fun tick(server: MinecraftServer) {
        val minutes = CobbleMarketConfig.pointsRankingExportMinutes
        if (++tickCount < minutes * 20 * 60) return
        tickCount = 0
        export(server, force = false)
    }

    /**
     * 当前排行（**实时算，不读 `ranking.json`**）：余额降序 → 名字 → UUID。
     *
     * ⚠ 给 `/market points top` 用。**不读导出文件**：那个文件最多落后一个导出周期，
     *   而命令是"现在看一眼"，实时算才是服主/玩家预期的数字（两者本来就是同一份数据）。
     * ⚠ 排序**必须是全序**（余额 → 名字 → UUID 三级）：只按余额排的话，同分玩家的先后
     *   取决于 map 的遍历顺序 ⇒ 每次都可能不同 ⇒ 导出侧会误判成"变了"而反复重写文件。
     */
    fun ranking(server: MinecraftServer): List<RankRow> = buildRanking(server)

    /**
     * 导出一次。
     *
     * @param force `true` = **无条件重写**（`/market points update` 用）—— 即使内容与上次导出
     *   完全相同也写一遍。⚠ 这一条是**有意**的：服主敲完命令要看的就是"文件刷新了"，
     *   走"只写变化"的话他那边 mtime 纹丝不动，只会以为功能坏了（静默失效是本项目最忌讳的一类）。
     *   `false`（定时器）= 内容没变就整个跳过。
     * @return 是否写盘成功（失败已记 error 日志，调用方只需给用户一句提示）
     */
    fun export(server: MinecraftServer, force: Boolean): Boolean = try {
        val entries = buildRanking(server)
        if (!force && entries == lastSnapshot) {
            true
        } else {
            // 首次导出（lastSnapshot == null）也要全量写：上次的每条都不一样，没有可比的对象
            write(server, entries, full = force || lastSnapshot == null)
            lastSnapshot = entries
            true
        }
    } catch (e: Exception) {
        CobbleMarket.LOGGER.error("Failed to export points ranking: {}", e.message)
        false
    }

    // ── 快照 ──

    /**
     * 余额表 + 名字 → 排好序的榜。
     *
     * 名字解析三级（**优先在线玩家**：改名后它是最新的，usercache 可能还没跟上）：
     * 1. 在线玩家表
     * 2. `UserCache`（离线玩家；`BanState.resolvePlayer` 的反向查法）
     * 3. **UUID 字符串兜底** —— 宁可在文件里看到一个 UUID，也不要让这个人从榜上凭空消失
     */
    private fun buildRanking(server: MinecraftServer): List<RankRow> {
        val balances = PointsState.get(server).allBalances()
        if (balances.isEmpty()) return emptyList()
        val online = server.playerManager.playerList.associate { it.uuid to it.name.string }
        val cache = server.userCache
        var unnamed = 0
        val rows = balances.map { (uuid, points) ->
            val name = online[uuid] ?: cache?.getByUuid(uuid)?.orElse(null)?.name
            if (name == null) unnamed++
            RankRow(uuid, name ?: uuid.toString(), points)
        }.sortedWith(
            compareByDescending<RankRow> { it.points }
                .thenBy { it.name.lowercase() }
                .thenBy { it.uuid }
        )
        if (unnamed > 0 && !warnedMissingNames) {
            warnedMissingNames = true
            CobbleMarket.LOGGER.warn(
                "Points ranking: {} player(s) could not be resolved to a name; their UUID is written instead",
                unnamed
            )
        }
        return rows
    }

    // ── 写盘 ──

    /**
     * 写盘。
     * @param full `true` = **连没变过的单人文件也重写**（强制导出 / 首次导出）
     */
    private fun write(server: MinecraftServer, entries: List<RankRow>, full: Boolean) {
        val root = server.getSavePath(WorldSavePath.ROOT).resolve(DIR_NAME)
        val playersDir = root.resolve("players")
        Files.createDirectories(playersDir)
        ensureReadme(root)
        if (!loggedPath) {
            loggedPath = true
            CobbleMarket.LOGGER.info("Points ranking exported to {}", root)
        }

        // 上一次导出里每个玩家是什么样（用于判断"这条有没有变"）；full 时不看它 = 全部重写
        val previous = if (full) emptyMap() else lastSnapshot.orEmpty().associateBy { it.uuid }

        // ranking.json：整份重写（它本身就是"全部玩家"这一份，没有按行增删的余地）
        val array = JsonArray()
        entries.forEach { e ->
            val obj = JsonObject()
            obj.addProperty("name", e.name)
            obj.addProperty("uuid", e.uuid.toString())
            obj.addProperty("points", e.points)
            array.add(obj)
        }
        writeAtomically(root.resolve("ranking.json"), gson.toJson(array))

        // players/<uuid>.json：**只写这条变了的**（一次导出通常只有几个玩家动过余额）
        val keep = HashSet<String>(entries.size)
        entries.forEach { e ->
            val fileName = "${e.uuid}.json"
            keep.add(fileName)
            if (previous[e.uuid] == e) return@forEach
            writeAtomically(playersDir.resolve(fileName), playerJson(e))
        }
        // 余额归零 / 已不在榜上的玩家：文件跟着删 —— 留着会与 ranking.json 打架
        // （外部工具读单人拿到的是过期数字）。⚠ 删不掉就算了：这只是便利文件，榜单以 ranking.json 为准
        playersDir.toFile().listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
            if (f.name !in keep) f.delete()
        }
    }

    /** 单玩家文件：**不重复写 uuid**（文件名就是它，再写一遍只会让每个文件多 36 字节） */
    private fun playerJson(e: RankRow): String {
        val obj = JsonObject()
        obj.addProperty("name", e.name)
        obj.addProperty("points", e.points)
        return gson.toJson(obj)
    }

    /**
     * 原子写：先写 `.tmp` 再 `Files.move` 覆盖。
     *
     * ⚠ **为什么不能直接截断重写**：这些文件就是给外部工具读的（全息板可能每秒读一次），
     *   直接写会让它有机会读到**半截 JSON**（解析失败 = 显示空白/报错）。
     *   先写临时文件再整体替换，读方永远只会看到完整的旧文件或完整的新文件。
     * ⚠ `move` 失败时**退回直接写**：Windows 上目标被别的进程占用时 move 会抛异常，
     *   那不该让整次导出失败（退化成非原子写，至少数据是新的）。
     */
    private fun writeAtomically(target: Path, content: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.writeString(
            tmp, content,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
        )
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.writeString(
                target, content,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
            )
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /** 目录说明（中英各一份，只写一次、不覆盖服主改过的版本 —— 照 `PointsAuditLogger.ensureReadme`） */
    private fun ensureReadme(root: Path) {
        val zh = root.resolve("README_zh_cn.txt")
        if (!Files.exists(zh)) {
            runCatching {
                Files.writeString(
                    zh,
                    """
                    CobbleMarket 余额排行导出目录说明
                    ==================================

                    本目录是模组**导出**的余额数据（只读快照，不是模组的存档；删掉会自动重建）。

                    ranking.json        —— 全部持有余额的玩家，按余额从高到低排序
                    players/<uuid>.json —— 单个玩家的余额（一个 UUID 一个文件，方便直接取某人的）

                    字段：
                      name   玩家名（**改名后会跟着更新**；查不到名字时这里写的是他的 UUID）
                      uuid   玩家 UUID（ranking.json 里有；players/ 下的文件名就是它）
                      points 余额

                    外部工具（全息板 / 告示牌 / 网站…）读 ranking.json 即可，
                    **按 uuid 认人**（玩家改名也不会错位）。

                    更新频率：默认每 5 分钟检查一次，**内容没变化时不写盘**。服主可用
                      /market points update
                    强制立刻刷新一次（也适合放进命令方块 + 时钟来自定节奏）。

                    ⚠ 文件是整体重写的：读取方万一遇到解析失败，重试一次即可。
                    """.trimIndent() + "\n"
                )
            }.onFailure { CobbleMarket.LOGGER.warn("Failed to write points ranking readme: {}", it.message) }
        }
        val en = root.resolve("README_en_us.txt")
        if (!Files.exists(en)) {
            runCatching {
                Files.writeString(
                    en,
                    """
                    CobbleMarket Balance Ranking Export
                    ===================================

                    This folder is data the mod **exports** (a read-only snapshot, not the mod's save
                    data; it is rebuilt automatically if deleted).

                    ranking.json         -- every player holding a balance, highest first
                    players/<uuid>.json  -- one player's balance (one file per UUID, handy for
                                            looking someone up directly)

                    Fields:
                      name   player name (**follows renames**; when no name could be resolved this is
                             the player's UUID instead)
                      uuid   player UUID (also the file name under players/)
                      points balance

                    External tools (holograms, signs, websites...) can just read ranking.json and
                    **key players by uuid**, so renames never scramble the board.

                    Refresh: checked every 5 minutes by default, and **nothing is written when nothing
                    changed**. Owners can force an immediate refresh with
                      /market points update
                    (also fine to run from a command block on a clock, to pick your own cadence).

                    Note: files are rewritten as a whole -- if a reader ever fails to parse one,
                    just retry.
                    """.trimIndent() + "\n"
                )
            }.onFailure { CobbleMarket.LOGGER.warn("Failed to write points ranking readme: {}", it.message) }
        }
    }
}
