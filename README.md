# Factory Gauge Order Fix

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-informational)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-orange)](https://neoforged.net/)
[![Create](https://img.shields.io/badge/Create-6.0.9%2B-blue)](https://github.com/Creators-of-Create/Create)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

一个面向 [Create（机械动力）](https://github.com/Creators-of-Create/Create) 的小型附属模组：
让**工厂仪表（Factory Gauge）**的输入配方重新具备**顺序性**。

> 修复上游问题：[Creators-of-Create/Create#8898](https://github.com/Creators-of-Create/Create/issues/8898)
> *No way to create order-sensitive non-crafter recipes with factory gauges*

---

## 问题背景

工厂仪表的“配方”由若干供货仪表各供一种原料拼成。原版实现中：

- 仪表不记录玩家添加连接的先后顺序（`targetedBy` 是 `HashMap`）；
- 请求单与包裹内的物品顺序由哈希决定，每次启动都可能不同；
- 拆包写入目标容器时，又按包裹格子顺序插入。

因此，任何**同批原料、摆放位置不同则结果不同**的配方（例如 Ars Nouveau 附魔装置、
部署器的齿轮/机壳配方）无法用工厂仪表自动化。

根因的逐环节分析见 [docs/issue-8898.md](docs/issue-8898.md)。

## 修复内容

本模组只调整“顺序”，不改变数量、不增删条目，不会复制或吞掉物品。

| # | 位置 | 行为 |
|---|---|---|
| 1 | `FactoryPanelBehaviour` 构造函数 / `disable()` | 连接表 `targetedBy` 改为 `LinkedHashMap`，记录并持久化连接顺序 |
| 2 | `FactoryPanelBehaviour#tickRequests` | 下发请求前，按连接顺序重排 `PackageOrderWithCrafts.orderedStacks` |
| 3 | `PackagerBlockEntity#unwrapBox` | 拆包前按包裹自带的 `orderedStacks` **就地**重排待插入列表 |

第 3 点刻意跳过带配方图案（`orderedCrafts`）的订单，因此机械动力合成台的“合成排列”
逻辑完全不受影响。

## 兼容性

| 项目 | 要求 |
|---|---|
| Minecraft | 1.21.1 |
| 加载器 | NeoForge 21.1.x |
| Create | 6.0.9 及以上 |
| 安装位置 | 客户端**与**服务端都需安装 |

- 不注册任何方块/物品，可随时加入或移除，不需要新建存档；
- 对其他模组无硬依赖。

## 安装

1. 从 [Releases](https://github.com/WOCOWZj/factory-gauge-order/releases) 下载 `factorygaugeorder-<version>.jar`；
2. 放入 `mods/` 目录（客户端与服务端各一份）；
3. 启动后日志出现 `Factory Gauge Order Fix loaded` 即生效。

## 验证

1. 用三个原料仪表按 A → B → C 的顺序连到输出仪表，并设置目标数量；
2. 让接收端使用原版 Crafter 或对槽位敏感的容器；
3. 修复后：落位顺序应与连线顺序一致，且重进世界后不变。

## 已知限制

- **订单上下文只挂在第一件包裹上**：超过 9 格或跨多台打包机的碎片化订单，后续包裹没有顺序信息，无法重排；
- **跨网络原料无法整体排序**：不同频率的原料天然分成不同包裹、走不同路线，不存在全局顺序；
- **移动仪表会改变顺序**：`moveTo()` 重连会把该连接移到末尾；
- **依赖 Create 内部方法描述符**：Create 升级后可能失效，届时日志会报 `Mixin apply failed`（不会静默改坏数据）。

## 构建

### Gradle（标准方式）

```bash
# 1. 把整合包里的 Create 1.21.1 jar 放到 libs/（编译期依赖，不会被打包）
# 2. 构建
./gradlew build
# 产物：build/libs/factorygaugeorder-<version>.jar
```

也可以改用 Create 官方 maven 依赖（见 `build.gradle` 中注释掉的那行）。需要 JDK 21。

### 离线构建（无法访问 NeoForge maven 时）

仓库附带 `tools/build-offline.ps1`：复用已安装的 NeoForge 1.21.1 实例里的 jar 与
Minecraft 自带运行时中的 JDK 21，直接 `javac` 编译并打包。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-offline.ps1 `
    -MinecraftHome 'C:\Users\<you>\AppData\Roaming\.minecraft'
```

## 参考与致谢

本项目的**问题定义与修复方向**来自 Create 仓库的公开讨论，代码为独立实现：

- [**#8898**](https://github.com/Creators-of-Create/Create/issues/8898) — 问题描述、期望行为与用例，
  作者 [@Vftdan](https://github.com/Vftdan)；
  - 评论 [@Tlotro](https://github.com/Tlotro)：部署器“零件/基底”的顺序敏感例子；
  - 评论 [@Agni-web](https://github.com/Agni-web)：问题影响面。
- [**#10391**](https://github.com/Creators-of-Create/Create/pull/10391) — [@Daniel99j2](https://github.com/Daniel99j2)：
  `LinkedHashMap` 保存连接顺序、按顺序下发请求的思路（本项目扩展到多网络并补齐拆包环节）。
- [**#10188**](https://github.com/Creators-of-Create/Create/pull/10188) — [@LIUKRAST](https://github.com/LIUKRAST)：
  指出“只有经过重打包机顺序才会被尊重”，以及拆包侧应按 `orderedStacks` 处理。
- [**#8729**](https://github.com/Creators-of-Create/Create/pull/8729) — [@souldbminerr](https://github.com/souldbminerr)：
  仪表“只补足缺口数量”的补货语义（背景参考）。
- [Creators-of-Create/Create](https://github.com/Creators-of-Create/Create) 源码（`mc1.21.1/dev`）：
  用于核对方法签名与数据流，未复制其代码。

以上 issue / PR 的文字与代码版权归各自作者与 Create 项目所有。

## AI 使用声明

本仓库的代码、构建脚本与文档在 AI 编程助手（DeepSeek Harness，模型 `deepseek-flash`）辅助下完成，
由人类作者负责需求、方案取舍、游戏内实测与发布。AI 辅助代码建议自行审阅后再用于生产存档或服务器。

本项目不是 Create 官方项目，也未向其仓库提交 AI 生成的 PR。

## 许可证

[MIT](LICENSE)
