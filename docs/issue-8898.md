# Create #8898：工厂仪表的输入配方顺序性缺失

> 仓库：`Creators-of-Create/Create`（机械动力）
> Issue：**[#8898 No way to create order-sensitive non-crafter recipes with factory gauges](https://github.com/Creators-of-Create/Create/issues/8898)**
> 状态：open，标签 `status: confirmed`，5 个 👍，2 条评论
> 报告人：Vftdan，2025-07-09
> 本文核对版本：本地完整克隆 `mc1.21.1/dev`，HEAD `7ff2760e4`（2026-10-07，mod 6.0.11）；问题在 6.0.9 / 6.0.10 / 6.0.11 上一致存在。

---

## 1. 问题是什么

工厂仪表（Factory Gauge）的“配方”是由若干**供货仪表**各供一种原料拼出来的。
玩家按 A → B → C 的顺序连了三根线，但仪表内部**不记录这个顺序**：

- 原料列表按“仪表坐标的哈希”排序，看起来是伪随机的；
- 打包出来的包裹里，物品格子顺序也不保证和请求顺序一致；
- 拆包写入目标容器时，又按包裹格子顺序写入。

结果：**任何“同一批原料、不同摆放位置会得到不同结果”的配方都没法用工厂仪表自动化**。
Issue 里给的典型场景是 Ars Nouveau 的附魔装置（主位 + 周围基座，放进哪个位置决定吃哪个材料）；
评论里 Tlotro 补充了 Create 自己的例子：用木板当“零件”还是当“基底”，部署器（Deployer）的
齿轮 / 机壳配方就会不同——只靠过滤器无法区分，必须靠顺序。

### 期望 vs 实际（issue 原文要点）

| | |
|---|---|
| 期望 | 仪表按“添加连接的顺序”保存依赖，和/或 UI 能调整顺序；请求生成的包裹也保持同样顺序（至少经过重打包机后保持） |
| 实际 | 依赖顺序是伪随机的——由依赖仪表的坐标哈希决定；包裹不保证同样的物品顺序 |

---

## 2. 根因：顺序在四个环节连续丢失

链路：**仪表 → 请求单（PackageOrder）→ 打包机 → 包裹 → 拆包写入容器**。

### 环节 1：仪表根本不记连接顺序

`FactoryPanelBehaviour`（`content/logistics/factoryBoard/FactoryPanelBehaviour.java`）

```java
public Map<FactoryPanelPosition, FactoryPanelConnection> targetedBy;   // L~96
...
this.targetedBy = new HashMap<>();        // 构造函数 L126
...
targetedBy = new HashMap<>();             // disable() L670
```

键是**仪表位置**（`FactoryPanelPosition`），`HashMap` 的迭代顺序由坐标哈希决定；
`addConnection()` 只是 `put`，玩家连接的先后从未被写入存档。所以“按连接顺序”这件事从一开始就没有数据基础。

### 环节 2：请求单在生成时被打乱

同一个类，`tickRequests()`（L404–507）：

```java
Map<UUID, Map<ItemStack, ItemStackConnections>> consolidated = new HashMap<>();          // L430
... new Object2ObjectOpenCustomHashMap<>(ItemStackLinkedSet.TYPE_AND_TAG) ...            // L440
Multimap<UUID, BigItemStack> toRequest = HashMultimap.create();                          // L447
...
for (Entry<UUID, Collection<BigItemStack>> entry : toRequest.asMap().entrySet()) {       // L483
    new PackageOrderWithCrafts(new PackageOrder(new ArrayList<>(entry.getValue())), ...) // L485
```

`HashMultimap` 每个 key 下的 value 集合是 `HashSet`，`BigItemStack`/`ItemStack` 都是哈希排序，
所以即使环节 1 修好了，交给 `PackageOrder` 的 `List` 仍然是哈希顺序。**这是 issue 里“包裹顺序也不保证”的直接原因。**

### 环节 3：每台打包机的请求队列是 HashSet

`LogisticsManager`（`content/logistics/packagerLink/LogisticsManager.java`）

```java
Multimap<PackagerBlockEntity, PackagingRequest> requests = HashMultimap.create();   // L106
```

`PackagingRequest` 是 record，`ItemStack` 用默认身份哈希，所以同一台打包机的请求顺序每次启动都可能不同。
`performPackageRequests()`（L201–218）把 `entry.getValue()` 直接 `new ArrayList<>(...)`，
`PackagerBlockEntity#attemptToSend()` 再按这个顺序**一格一格往包裹里塞**（L438–498，`insertItemStacked` 从 0 号格开始填）。
于是**包裹格子顺序 = 哈希顺序**。

### 环节 4：拆包时顺序信息被无视（真正让玩家看到问题的地方）

`PackagerBlockEntity#unwrapBox()`（L364–394）：

```java
List<ItemStack> items = ItemHelper.getNonEmptyStacks(contents);        // 包裹格子顺序
PackageOrderWithCrafts orderContext = PackageItem.getOrderContext(box);
UnpackingHandler toUse = handler != null ? handler : UnpackingHandler.DEFAULT;
boolean unpacked = toUse.unpack(level, target, targetState, facing, items, orderContext, simulate);
```

`DefaultUnpackingHandler#unpack()`（`impl/unpacking/DefaultUnpackingHandler.java` L40–41）里：

```java
for (ItemStack itemStack : items)
    ItemHandlerHelper.insertItemStacked(targetInv, itemStack.copy(), false);
```

`orderContext` 收下了，但**一次都没读**。全仓库里 `PackageOrderWithCrafts.orderedStacks`
除了“重打包机”和 `orderedStacksMatchOrderedRecipes()`（只做校验）之外没有任何消费者——
这正好解释了社区结论“**只有过了重打包机（Repackager）顺序才会被尊重**”：
`PackageRepackageHelper#repack()` 是唯一一处会用 `context.stacks()` 逐项定序写格子的代码（L66–127）。

顺带说明几个**没有**出问题的环节，便于排除干扰：

- `PackageOrder` / `PackageOrderWithCrafts` 是纯 record + `Codec.list(...)` / 流编解码，**序列化保序**；
- `PackageItem` 的 `ItemContainerContents` 只压缩空格、保持相对顺序；
- 包裹实体、蛙港（Frogport）/包裹港把包裹当成不透明 `ItemStack` 搬运，不重排；
- 机械动力合成台（Mechanical Crafter）**有**顺序支持：前提是仪表设置了“合成排列”
  （`activeCraftingArrangement` → `orderedCrafts`），此时 `CrafterUnpackingHandler` 会按配方图案逐位放。
  这条路径只覆盖“配方图案已知的合成台”，覆盖不到普通容器 / 原版 Crafter / 其他 mod 的多槽机器。

---

## 3. 上游进展（截至整理时）

| 编号 | 内容 | 状态 |
|---|---|---|
| **#8898** | 本 issue | open，`status: confirmed` |
| **#10391** | PR：`targetedBy` 改 `LinkedHashMap`，并在**单一网络**时按连接顺序排序请求 | open，未合并；`+16/−4` |
| **#10188** | PR：修复“拆包不尊重 orderedStacks” + 支持原版 Crafter | open，未合并 |
| #8729 | 只是补充 Ponder 文档说明“只补足缺口数量”，标的 `wrong target` | open |

也就是说：**上游已有两个半成品 PR，但都还开着，6.0.11 里一个都没进**。

> ⚠️ 注意：Create 仓库根目录的 `AGENTS.md` 明确声明“不接受 LLM 参与贡献”，并禁止用 LLM 做
> 代码理解/生成。因此本补丁模组**不要**整理成 PR 提给 Create；保持为独立附属模组即可。
> 同理，修复思路可以写在 issue 讨论里供维护者参考，但不要提交 AI 生成的代码补丁。

---

## 4. 方案

### 方案 A：不改代码的临时办法

1. **过一道重打包机**：请求 → 重打包机 → 目标。重打包机会按 `orderContext` 重排格子，
   这是目前唯一“官方已支持”的保序路径（但上游把 context 只挂在第一件包裹上，见限制）。
2. **合成台场景用“合成排列”**：仪表里通过 JEI 设定排列（`activeCraftingArrangement`），
   让 `CrafterUnpackingHandler` 按图案放料，不依赖连接顺序。
3. 单原料 / 顺序不敏感的配方不受影响。

### 方案 B：上游应该怎么修（给维护者的建议）

1. `targetedBy` → `LinkedHashMap`（成本极低，且顺带让 NBT 存档顺序稳定）；
2. `tickRequests()` 里按连接顺序重排每个网络的 `BigItemStack` 列表（或直接把 `HashMultimap` 换成有序实现并修掉内层哈希表）；
3. `findPackagersForRequest()` 的 `HashMultimap` 改有序实现（注意 Guava 的 `LinkedHashMultimap` 不是 `HashMultimap` 子类，不能直接替换返回值类型）；
4. `DefaultUnpackingHandler` 在读 `orderedStacks` 后按序插入（等价于 #10188 的核心修复）；
5. 让订单上下文跟着**每一件**包裹走，而不是只挂第一件。

### 方案 C：本目录的补丁模组（推荐，立即可用）

`factory-gauge-order/`，两个 Mixin、三个注入点，只改“顺序”，不改数值与判定：

| 注入点 | 作用 |
|---|---|
| `FactoryPanelBehaviour.<init>` / `disable` TAIL | 把 `targetedBy` 换成 `LinkedHashMap`，记录并持久化连接顺序 |
| `FactoryPanelBehaviour.tickRequests` 中 `LogisticsManager.findPackagersForRequest(...)` 调用 | 传入前按连接顺序重排 `PackageOrderWithCrafts.orderedStacks` |
| `DefaultUnpackingHandler.unpack` 入口（`items` 局部变量） | 按包裹里的 `orderedStacks` **就地**重排待插入列表 |

为什么选这三个点：

- 前两处是“让顺序**存在**”，第三处是“让顺序**生效**”；
- 第三处刻意放在 `DefaultUnpackingHandler` 而不是 `unwrapBox`：这样机械动力合成台
  （`CrafterUnpackingHandler`）的配方排列逻辑完全不受影响，只修“默认容器插入”这条路；
- 全部是“重排 List”，不新增/删除条目，因此不会造成刷物品或吞物品；
- 就地排序是必须的：`CrafterUnpackingHandler` 用“传入 list 是否被消耗空”判断成功，
  换成新 list 会让它误判失败。

---

## 5. 已知限制（务必看）

1. **订单上下文只挂在第一件包裹上**：`findPackagersForRequest()` 在第一个链路消耗后把 `context` 置空，
   `attemptToSend()` 也只给当次创建的包裹写 `PACKAGE_ORDER_DATA`。超过 9 格 / 跨多台打包机的碎片化订单，
   后续包裹没有顺序信息，无法重排。
2. **跨频率原料无法整体排序**：`tickRequests()` 是按网络（frequency）分别生成 `PackageOrder` 的，
   不同网络的原料天然分成不同包裹、走不同路线，不存在“一个全局顺序”。
3. **移动仪表会改顺序**：`moveTo()` 用 `remove` + `put` 重连，被移动的连接会跑到末尾；把仪表挪回去不会自动恢复原位。
4. **只针对 6.0.9+ / 1.21.1 分支**：Mixin 用描述符定位方法，Create 大改内部结构后需要重新核对。
5. **构建与验证状态**：本机无法访问 `maven.neoforged.net`（DNS 污染 + SNI 阻断），因此没有走 Gradle，
   而是复用本机已装 NeoForge 1.21.1 实例的 jar + Minecraft 自带 JDK 21 直接 `javac` 编译，
   产物 `build/libs/factorygaugeorder-1.0.0.jar` 已生成；Mixin 目标描述符已逐条对照 Create 6.0.10
   字节码（`javap`）验证一致。但**尚未进游戏实测**，首次测试请留意 `logs/latest.log` 里的 mixin 报错。

---

## 6. 复现与验证

复现（原版行为）：

1. 放一个“输出仪表”（设为要产出的物品）+ 3 个“原料仪表”，按固定顺序连到输出仪表；
2. 输出仪表设置目标数量，触发一次请求；
3. 目标端用原版 Crafter / 多槽机器接收并拆包；
4. 多试几次（或重进世界），原料落位顺序会变——依赖摆放位置的配方成功率随机。

验证（装本模组后）：

1. 同样连接顺序，落位顺序应与“你连线的顺序”一致，且重进世界后不变；
2. 日志里不应出现 `Mixin apply failed` / `Invalid descriptor`（见 README 排错）。
