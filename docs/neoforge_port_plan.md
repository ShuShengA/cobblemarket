# NeoForge 移植方案（Architectury 三模块）

> 2026-08-24 定稿待拍板。背景：Connector 兼容方案已验证可行（beta.16 上全流程通过），
> 但玩家门槛/依赖链风险/分发可见性三方面不理想，决定做原生双平台移植。
> 基线：d03ea43（1.0.0 分支，含 Connector 测试发现的两个 bug 修复）。

## 一、现状耦合面（调研结论）

- 72 个 Kotlin 文件：main 39 + client 33；其中 37 个引用 `net.fabricmc`
- **网络层最大**：ServerPlayNetworking 10 文件、ClientPlayNetworking 20 文件。
  Payload 定义全部基于原版 `CustomPayload.Id` + `PacketCodec`（跨平台通用），
  只有「收发/注册」四个入口是 fabric 特有 → 桥接封装后机械替换
- 事件：ServerTickEvents（3）、ServerLifecycleEvents（1）、ClientTickEvents（1）
- 按键：KeyBindingHelper 1 文件（K 键）
- FabricLoader：3 处，全是 `configDir` 取配置目录
- 客户端入口：`CobbleMarketClient : ClientModInitializer`；服务端入口 `CobbleMarket`
- Mixin：仅 1 个 `ScreenMixin`（client 端）
- Smartphone app 注册：4 文件（OpenMarketAction 等），neoforge 版 API 待查证
- 资源：lang/textures 通用；fabric.mod.json 平台专属

## 二、三模块结构

```
根
├── settings.gradle        include common/fabric/neoforge
├── build.gradle           architectury-plugin 根配置（版本 1.21.1 可用版本）
├── common/                业务代码全量迁移（~69 文件）
│   ├── build.gradle       architectury common 变体 + kotlin
│   └── src/.../kotlin + resources/lang + textures
├── fabric/
│   ├── build.gradle       fabric-loom + kotlin + FLK
│   └── 入口类（Main/Client）+ 平台实现 + fabric.mod.json + mixins.json
└── neoforge/
    ├── build.gradle       architectury-loom（neoforge 变体）+ kotlin + KFF
    └── 入口类 + 平台实现 + neoforge.mods.toml
```

产物：`cobblemarket-fabric-1.0.0.jar` + `cobblemarket-neoforge-1.0.0.jar`

## 三、平台抽象接口（common 定义，@ExpectPlatform 两端实现）

> ✅ 步骤 4 已完成（2026-08-24），fabric 游戏内回归验证通过。实测以顶层函数 + architectury
> `@ExpectPlatform` 字节码 transform 实现（Kotlin 2.4 已移除 expect/actual 的 JVM 支持，不可用）。
> 规则：common 桩方法体恒 throw；平台模块在 `<本包>.<platform>` 子包提供
> `〈桩文件名〉KtImpl` 顶层函数（必须 `@file:JvmName("XxxKtImpl")` 覆盖宿主类名，
> transform 按「桩类名 + Impl」查找）。字节码转发已验证。

| 接口 | 职责 | fabric 实现 | neoforge 实现 |
|---|---|---|---|
| `configDir` / `isModLoaded` | 环境 | FabricLoader | FMLPaths.CONFIGDIR / ModList |
| `registerC2S` / `registerS2C` / `registerS2CType` / `sendToPlayer` / `sendToServer` | 网络（56 C2S + 25 S2C + 210/76 send 已全部替换） | PayloadTypeRegistry + Networking | IPayloadRegistrar / PacketDistributor |
| `onServerStarting/Started/Stopped` / `onServerTickEnd` / `onPlayerJoin` / `onPlayerDisconnect` / `registerCommands` | 服务端事件 + 命令 | LifecycleEvents + TickEvents + ConnectionEvents + CommandRegistrationCallback | ServerStartedEvent/StoppingEvent/TickEvent.Post + RegisterCommandsEvent |
| `onClientTick` / `registerKeyBinding` / `registerHudRender` | 客户端 | ClientTickEvents + KeyBindingHelper + HudRenderCallback | ClientTickEvent.Post + RegisterKeyMappingsEvent + RegisterGuiLayersEvent |
| `cobecoAvailable/GetBalance/Remove/Add` | cobeco 货币（CurrencyHandler 已全走桥接，common 零 cobeco 引用） | cobeco jar 真实实现 | **恒不可用**（返回 false/null，货币自动降级） |
| Smartphone app 注册 | **无需桥接**：neoforge 版 jar 已查证含相同 API 类（SmartphoneAction/SmartphoneActionRegistry），common 直接保留引用 | — | — |

### 步骤 4 实测发现的坑（构建/打包层）

1. **neoforge 不支持 `splitEnvironmentSourceSets`**：loom 报 "Using Forge with split jars is not supported"。
   neoforge 模块单源集，客户端实现类也放 main 源集（步骤 6 注意）。
2. **shadowJar 丢 client 源集类**：loom 1.11 的 client 输出是独立目录，shadow 插件只收 main 输出
   → fabric 模块 shadowJar 显式 `from(sourceSets.client.output)`（客户端入口类曾因此 ClassNotFound）。
3. **S2C 类型注册只做一次**：playS2C 注册表是进程级静态单例，服务端 `registerS2CType` 注册后
   客户端桥接内**不得**再注册（单机同进程会抛 "already registered"）。
4. **旧 mixins.json 残留覆盖**：骨架阶段 fabric/src/client/resources 留有旧 mixins.json（空 client
   数组），shadow 合并时覆盖 common 的正确版本 → ScreenMixin 静默失效（界面内庆祝动画消失）。
   已删除，mixin 配置唯一来源 = common。
5. `mappings loom.layered {}` 写法（yarn + yarn-mappings-patch-neoforge 双层合成单一依赖）。

### 步骤 6 实测发现的坑（neoforge 平台实现，2026-08-24）

6. **网络协商要求双端注册集合完全一致**：`NetworkComponentNegotiator` 规定任何一端缺少另一端注册的
   非 optional payload（同 id + 同流向）直接断连。common 的 56 个 C2S 由服务端注册 playToServer，
   neoforge 客户端也必须空注册同名类型才能进服（fabric 无此机制：发送前不校验本地注册）。
   落地：`neoforge/.../platform/neoforge/C2SClientRegistration.kt` 硬编码 56 个 (ID, CODEC) 清单，
   客户端入口调用。**新增 C2S payload 必须同步此清单**，漏加会在进服协商时直接断连（fail-fast，易发现）。
7. **单机双端同进程会重复注册同 id payload**（NetworkRegistry 按 protocol+id 判重，抛
   "already registered"）。去重设计：带业务 handler 的注册（服务端 C2S、客户端 S2C）用
   `EventPriority.HIGH` 先注册；空注册（服务端 S2C 类型、客户端 C2S 清单）用 NORMAL 晚到，
   经 `NeoForgePlatform.markPayloadRegistered(id)` 检查后跳过。专用服务器/专用客户端只有一侧逻辑，照常注册。
8. **FMLJavaModLoadingContext 在 21.1 已移除**（ModLoadingContext 的 activeContainer 在 mod 类加载后
   即清空）：获取 mod event bus 的唯一可靠途径是 `@Mod` 构造器注入 `IEventBus`（只允许一个 public
   构造器）+ 模块内静态持有（`NeoForgePlatform.modBus`）。客户端初始化因此放在 @Mod 构造器内
   （`FMLEnvironment.dist.isClient` 判断），而非 FMLClientSetupEvent。
9. **mixin refmap 无需处理**（查证结论）：loom 1.11 不解析 neoforge.mods.toml 的 [[mixins]] 段，
   也不会重映射 shadowBundle 里 common 的 refmap；但 refmap 的 `named:intermediary` 形态被
   neoforge 21.1 运行时原生支持——Cobblemon neoforge 版（同构 yarn+三模块项目）的 refmap 正是
   此形态且生产可用，architectury-neoforge 官方 jar 亦同。remapJar 只重映射 .class（yarn → official），
   最终 jar 内所有签名已是 official 名，与运行时匹配。
10. neoforge 模块单源集：`ClientBridgeKtImpl.kt`、`C2SClientRegistration.kt`、客户端入口逻辑都在
    main 源集（见坑 1），专用服务器不会执行其代码（入口处 dist 判断）。

## 四、依赖双平台映射

| 依赖 | fabric | neoforge |
|---|---|---|
| Cobblemon | 现有 Modrinth fabric ID | neoforge 版 ID（开工时查） |
| CobbleDollars | 现有 compileOnly | 2.0.0+Beta-6.1 compileOnly（已查证存在） |
| Cobblemon Economy | 现有 compileOnly | ❌ 不依赖 |
| Smartphone | 现有 compileOnly | neoforge 版（已查证存在，ID 开工时查） |
| Kotlin 加载器 | FLK（现有） | Kotlin for Forge（KFF，ID 开工时查） |

## 五、Mixin

`ScreenMixin` 单文件：fabric 在 fabric.mod.json 声明、neoforge 在 neoforge.mods.toml 声明；
两端 mixin 机制均原生支持，仅 json 配置与注入方式微调。

## 六、实施顺序（每步可验证、可回退）

1. 从 d03ea43 开分支 `1.0.0-neoforge-port`
2. 三模块骨架 + 空 fabric 模块构建跑通
3. 代码全量迁移 common，fabric 端编译修复 → **fabric 游戏内回归验证**（改造没破坏原功能）
4. 平台抽象逐一替换：网络桥（30 文件机械替换）→ 事件 → 按键 → 杂项
5. fabric 端最终验证 + 提交
6. neoforge 模块搭建至构建通过 ✅ 2026-08-24（含坑 6~10）
7. neoforge 游戏内验证（原生 neoforge 实例，非 Connector）
8. 双平台验收清单逐项过

## 七、验收清单（两端各过一遍）

启动进服 → K 键开市场 → 挂单/购买（双账号）→ 求购单全流程 → 拍卖 → 历史/CSV → 管理员面板 → 重启持久化 → log 无红字 → 满页挂单 F3 帧率

## 八、风险点

1. neoforge 版 Smartphone API 差异（可降级：先不带 app 入口）
2. Cobblemon neoforge 版类名一致性（Cobblemon 内部跨平台 common，构建期即可暴露，风险低）
3. FLK/KFF 的 Kotlin 版本必须一致（否则运行时类加载冲突）
4. 发布说明需醒目提醒 fabric 玩家：删旧 `cobblemarket-1.0.0.jar` 再放新 `cobblemarket-fabric-1.0.0.jar`（同名 mod id 并存会崩）

## 九、放弃的路线

- Sinytra Connector 兼容：已验证可跑（2.0.0-beta.16+1.21.1），保留为文档级备选说明，不再作为主线
- 独立 neoforge 仓库（单平台复制维护）：双份代码长期同步成本高，放弃
