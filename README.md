# Factory Gauge Order Fix

给机械动力（Create）工厂仪表补上**输入配方顺序性**的小补丁模组。

- 对应上游 issue：**[Creators-of-Create/Create#8898](https://github.com/Creators-of-Create/Create/issues/8898)**
  （*No way to create order-sensitive non-crafter recipes with factory gauges*，`status: confirmed`）
- 目标平台：Minecraft **1.21.1** / NeoForge **21.1.x** / Create **6.0.9+**（在 6.0.10、6.0.11 源码上核对）
- 详细的问题分析、根因链路、上游 PR 进展：见 **[docs/issue-8898.md](docs/issue-8898.md)**

一句话：原版仪表不记录“你按什么顺序连的线”，请求单和包裹里的物品顺序由哈希决定，
拆包时顺序信息又被丢掉；本模组让**连接顺序 → 请求顺序 → 落位顺序**整条链路保持一致。

---

## 1. 它改了什么（3 个注入点，只动顺序，不动数值）

| # | 注入点 | 行为 |
|---|---|---|
| 1 | `FactoryPanelBehaviour.<init>` / `disable()` | 连接表 `targetedBy` 由 `HashMap` 换成 `LinkedHashMap`，连接顺序被记录并可持久化 |
| 2 | `FactoryPanelBehaviour.tickRequests()` 调用 `LogisticsManager.findPackagersForRequest(...)` 处 | 按连接顺序重排本次请求的 `orderedStacks` |
| 3 | `PackagerBlockEntity.unwrapBox()` 调用 `UnpackingHandler.unpack(...)` 处 | 拆包前按包裹自带的 `orderedStacks` **就地**重排待插入列表 |

设计要点：

- 只做 `List` 重排，不增删条目 → 不会复制/吞掉物品；
- 第 3 点放在 `unwrapBox` 而不是默认拆包实现里，且 `hasCraftingInformation(ctx)` 为真时直接放行，
  因此**机械动力合成台的“合成排列”逻辑完全不受影响**；
- 就地排序（不是返回新 list）是必须的：`CrafterUnpackingHandler` 用“原 list 是否被消耗空”判定成功。

---

## 2. 构建

### 2.1 离线构建（已在本机验证成功，无需联网）

本机无法访问 `maven.neoforged.net`（详见第 5 节），因此提供了一条**不依赖 Gradle / 不联网**的构建路径：
直接复用一个已装好的 NeoForge 1.21.1 实例里的 jar 作为 classpath，用 MC 自带运行时里的 JDK 21 编译。

```powershell
cd factory-gauge-order
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-offline.ps1
# 产物：build/libs/factorygaugeorder-1.0.0.jar
```

脚本做的事：

1. 用 `%APPDATA%\.minecraft\runtime\java-runtime-delta\bin\javac.exe`（JDK 21）编译 `src/main/java`；
2. classpath 取自本机 `E:\Minecraft\.minecraft`：
   - `libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar`（MC 1.21.1，官方名）
   - `libraries\net\neoforged\neoforge\21.1.233\neoforge-21.1.233-{client,universal}.jar`
   - `libraries\net\neoforged\{fancymodloader,bus,mergetool}`、`org\spongepowered\mixin`、`guava`、`slf4j`
   - `versions\Mechanomania\mods\create-1.21.1-6.0.10.jar`（编译期依赖）
3. 展开 `neoforge.mods.toml` 里的 `${mod_id}` 等占位符，连同 mixin 配置一起打包。

> 路径写死在脚本顶部，换机器/换实例时改那几行即可。

### 2.2 标准 Gradle 构建（需要能访问 NeoForge maven）

### 前置

1. **JDK 21**（Minecraft 1.21.1 要求；JDK 17 编译不过）；
2. Gradle 由仓库自带的 wrapper 提供（`gradle-8.14.3`，首次运行会联网下载）；
3. 把整合包 `mods/` 里的 **Create 本体 jar** 放到 `libs/`（见 `libs/README.txt`），
   或者改用 `build.gradle` 里注释掉的官方 maven 依赖。

### 命令

```powershell
cd factory-gauge-order
.\gradlew.bat build          # 产物：build/libs/factorygaugeorder-1.0.0.jar
```

只编译不打包：

```powershell
.\gradlew.bat compileJava
```

开发环境试跑（需要 libs 里的 Create + NeoForge 依赖齐全）：

```powershell
.\gradlew.bat runClient
```

> 如果 `net.neoforged.moddev` 版本拉不下来，把 `build.gradle` 里的 `2.0.141`
> 改成你网络能拿到的 ModDevGradle 版本（Create 本体用的就是 2.0.141）。

---

## 3. 安装与验证

1. 把 `build/libs/factorygaugeorder-1.0.0.jar` 丢进 `mods/`（客户端 + 服务端都要，专用服务器只需要服务端逻辑也能生效）；
2. 启动后日志里应有：`Factory Gauge Order Fix loaded: ...`；
3. 验证顺序是否生效：
   - 3 个原料仪表按 A → B → C 连到输出仪表，触发一次请求；
   - 接收端用**原版 Crafter**（1.21）或一个对“第 N 个槽位放什么”敏感的容器；
   - 修复前：落位顺序每次可能不同（重进世界更明显）；修复后：与连线顺序一致，且重进世界不变。

---

## 4. 排错

| 现象 | 处理 |
|---|---|
| 启动崩溃，日志有 `Mixin apply failed` / `Invalid descriptor` / `Critical injection failure` | Create 版本与本模组定位的描述符不匹配。核对 `docs/issue-8898.md` 里引用的方法签名，或把日志发我按新版本调整 |
| 日志有 `No refmap loaded` 之类警告 | 正常。NeoForge 1.21 用官方名，本工程不生成 refmap |
| 装了没效果 | 确认是**工厂仪表**（不是红石请求器）的请求路径；确认 Create ≥ 6.0.9；确认客户端/服务端都装了 |

已知限制（跨网络原料、碎片化订单 >9 格、移动仪表会改顺序等）见
[docs/issue-8898.md 第 5 节](docs/issue-8898.md)。

---

## 5. 本机网络说明（重要）

排查过程中实测到的网络状况：

- `C:\Windows\System32\drivers\etc\hosts` 把 `github.com` / `api.github.com` / `raw.githubusercontent.com`
  等全部指向 `127.0.0.1`——这是 Watt Toolkit（SteamTools）的 GitHub 加速方案；加速未开启时直接连不上。
- shell（pwsh / curl / .NET）的 HTTPS 全部失败，根因是本机 schannel 报
  `SEC_E_NO_CREDENTIALS`；**纯 HTTP 可用**（`http://example.com` 返回 200）。
  Java（JSSE）不受影响，可以正常走 HTTPS——这是本机唯一可用的 TLS 栈。
- `maven.neoforged.net` 是本次真正的拦路虎：
  - 本地 DNS 把它解析到 `31.13.95.48`（Facebook 段，典型 DNS 污染）；
  - 用 DoH（`doh.pub`）查到真实地址是 `1755624453.rsc.cdn77.org` → `195.181.172.3/.6`；
  - 直连该 IP 并带 SNI 握手时被 **TCP reset**（SNI 阻断）。
  因此 Gradle 需要的 NeoForge 依赖在本机拿不到，`gradlew build` 走不通。
- 变通：`api.allorigins.win` 能取到 neoforged 的文本资源（POM/metadata），但无法承载整个依赖树。

**结论**：本机改用「复用已安装实例的 jar + 本地 JDK 21」的离线构建（第 2.1 节），已成功出包。
若要用 Gradle/CI 正常构建，需要一台能访问 `maven.neoforged.net` 的机器（或全局代理/VPN），
并安装 JDK 21（当前系统只有 JDK 17）。

### 本机实测结论

| 项目 | 结果 |
|---|---|
| `javac` 编译（JDK 21 / `--release 21`） | ✅ 通过，class 版本 65 |
| Mixin 目标描述符（对照 Create 6.0.10 字节码 `javap`） | ✅ `findPackagersForRequest`、`UnpackingHandler.unpack` 完全一致 |
| jar 结构（classes / mixins.json / neoforge.mods.toml 占位符展开） | ✅ 正确 |
| 游戏内运行验证 | ⏳ 未做（需要启动客户端实测） |

---

## 6. 目录结构

```
factory-gauge-order/
├── build.gradle / settings.gradle / gradle.properties   # 标准 Gradle 构建（联网机器用）
├── gradle/wrapper/、gradlew、gradlew.bat
├── build/libs/factorygaugeorder-1.0.0.jar   # 已构建好的模组（离线构建产物）
├── libs/README.txt                     # Gradle 构建时 Create jar 放这里
├── docs/issue-8898.md                  # 问题分析 / 根因 / 方案 / 限制
├── tools/                              # 离线构建与探测脚本
│   ├── build-offline.ps1               # ★ 本机可用的构建脚本
│   ├── HttpGet.java / SniProbe.java    # 绕过 schannel 抓取工具的调试小工具
│   └── *.ps1                           # 环境探测脚本
└── src/main/
    ├── java/dev/fgorder/
    │   ├── FGOrder.java                # @Mod 入口（只打日志，不注册内容）
    │   └── mixin/
    │       ├── FactoryPanelBehaviourMixin.java
    │       └── PackagerUnwrapMixin.java
    └── resources/
        ├── META-INF/neoforge.mods.toml
        └── factorygaugeorder.mixins.json
```

## 7. 许可与上游政策

- 本模组代码：MIT（见 [LICENSE](LICENSE)）。
- Create 仓库根目录 `AGENTS.md` 明确声明**不接受 LLM 参与的贡献**，并反对用 LLM 做代码解读。
  因此请**不要**把这里的代码整理成 PR 提给 Create；它只是独立的附属模组。
  若想推动上游，建议在 issue #8898 下用自然语言讨论思路，由维护者自行实现。

---

## 8. 参考与致谢（References）

本项目的**问题定义、行为分析和修复方向**来自 Create 仓库中的以下公开讨论。代码为独立实现，
未复制 Create 源码；下文标注了每一处参考具体被用在哪里。

### 8.1 主 issue

- **Creators-of-Create/Create#8898 — “No way to create order-sensitive non-crafter recipes with factory gauges”**
  - 作者：[@Vftdan](https://github.com/Vftdan)，2025-07-09，标签 `status: confirmed`，5 👍。
  - 本项目引用内容：问题描述（依赖顺序按“仪表坐标哈希”伪随机、包裹不保证同序）、期望行为、
    以及“Ars Nouveau 附魔装置”的用例。README 第 1 节与 [docs/issue-8898.md](docs/issue-8898.md)
    的现象、复现步骤、验收标准均以此为准。

### 8.2 该 issue 下的评论

- [@Tlotro](https://github.com/Tlotro)（2025-08-20）：
  指出 Create 自家的部署器（Deployer）也有顺序敏感场景——同一块木板当“零件”还是当“基底”，
  会分别得到齿轮与机壳。本项目把它作为“为什么只靠过滤器不够”的佐证写进了问题说明。
- [@Agni-web](https://github.com/Agni-web)（2026-05-27）：
  表达对该问题长期未修的关切，用来说明问题的影响面（非技术内容）。

### 8.3 相关 PR（思路参考，均未合并）

- **#10391 “Make factory gauges save order of connection”** — [@Daniel99j2](https://github.com/Daniel99j2)
  - 参考点：把 `targetedBy` 换成 `LinkedHashMap`、按连接顺序重排请求列表的方向。
  - 差异：该项目只在**单一网络**时排序；本项目改为在 `findPackagersForRequest` 调用处统一重排，
    并额外处理了拆包落位，因此多网络场景也不会退回哈希顺序。
- **#10188 “Fix Unpacking order & Vanilla Crafter”** — [@LIUKRAST](https://github.com/LIUKRAST)
  - 参考点：明确指出“**只有经过重打包机（Repackager）顺序才会被尊重**”，
    以及拆包侧应按 `orderedStacks` 处理。本项目的 `PackagerUnwrapMixin` 正是针对这一环节。
  - 差异：本项目不改 Create 的拆包实现，而是用 Mixin 在 `unwrapBox` 调用 `UnpackingHandler#unpack`
    前就地重排，因此对机械动力合成台的“合成排列”路径零影响。
- **#8729 “Clarify Factory Gauge restocking behavior”** — [@souldbminerr](https://github.com/souldbminerr)
  - 参考点：仅用于理解仪表“只补足缺口数量”的补货语义（背景资料）。

### 8.4 源码参照

- **Creators-of-Create/Create**，分支 `mc1.21.1/dev`，HEAD `7ff2760e4`（mod 6.0.11）——用于确认方法签名、
  行号与数据流（`FactoryPanelBehaviour#tickRequests`、`LogisticsManager#findPackagersForRequest`、
  `PackagerBlockEntity#unwrapBox`、`DefaultUnpackingHandler#unpack` 等）。
  本项目仅作行为参照，未复制其代码；编译期依赖的是整合包内的 Create 6.0.10 jar。

### 8.5 说明

以上 issue / PR 的**文字与代码版权归各自作者与 Create 项目所有**；本仓库只引用其公开描述与结论，
并按 MIT 许可发布自己的实现。若原作者认为引用方式不妥，请开 issue 联系修改。

---

## 9. AI 使用声明（AI Disclosure）

- 本仓库的代码、构建脚本与文档在 **AI 编程助手**辅助下完成：DeepSeek Harness 客户端，
  模型 `deepseek-flash`（会话内完成 issue 定位、源码核对、Mixin 编写、离线构建与文档撰写）。
- 人类作者负责：提出需求、决定方案取舍、执行游戏内实测与最终发布。
- 请注意：
  - AI 辅助代码**建议自行审阅**后再用于生产存档/服务器；
  - Mixin 依赖 Create 的内部方法描述符，Create 升级后可能失效（会以 `Mixin apply failed` 形式报错，
    不会静默改坏数据）；
  - 本项目**不是** Create 官方项目，也未向其仓库提交 AI 生成的 PR（Create 的 `AGENTS.md` 明确禁止）。
- 如果你要在此基础上继续开发，建议保留本节声明，或按你的实际情况更新。
