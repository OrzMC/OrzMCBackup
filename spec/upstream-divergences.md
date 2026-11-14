# 与上游 aternos/thanos 的差异对照

> 状态：参考文档（随实现更新）· 最后更新：2026-10
> 上游仓库：`aternosorg/thanos`（PHP，MIT），在父仓库里以子模块 `tools/thanos` 挂载，**只读引用、不本地修改**。
> 本文记录：上游现状、与本项目的功能对照、以及对照中发现并已修复/有意保留的差异。

---

## 1. 上游现状

| 项 | 结果 |
|---|---|
| 最新提交 | `56772da`（2026-04-26，`Merge pull request #26 … phpunit-12.5.22`） |
| 性质 | 仅为 dependabot 依赖升级；**自 2026-04 起无功能/行为变更** |
| 本地指针 | `tools/thanos` 的 `master` 与 `origin/master` 一致 |
| 代码规模 | `src/` 约 2,600 行 PHP，119 个测试方法 |

结论：上游已冻结，功能对照以 2026-04 版本为准。本项目的 Rust 对齐实现 `tools/rust-thanos` 仍在活跃维护。

---

## 2. 功能对照

| 能力 | 上游 | 本项目 | 说明 |
|---|---|---|---|
| InhabitedTime 阈值（严格 `>`） | ✅ | ✅ | 语义一致；两边都用"字节前缀搜索"取第一个 `InhabitedTime` LONG |
| 无 `InhabitedTime` tag / 空数据 | 按 `removeUnknown` 决定保留 | 同 | 一致 |
| 外部压缩区块（`External*`，method 为负） | 按 `removeUnknown` 决定保留 | 同 | 一致 |
| legacy `data/chunks.dat` 的 `data.Forced` | ✅（每个 long 一个 chunk） | ✅（**已修复**，见 §3） | 修复前本项目把两个 long 拼成一个坐标 |
| `data.tickets[].chunk_pos`（`minecraft:forced`） | ✅ | ✅ | 一致 |
| 文件探测顺序 | `data/minecraft/chunk_tickets.dat` → `data/chunks.dat`，取第一个存在的 | 同 | 一致 |
| 压缩格式 | GZIP / ZLIB / RAW / LZ4；`CUSTOM(127)` 抛不支持 | 全部 + `CUSTOM` 原字节透传 | 本项目更宽 |
| region / entities / poi 锁步 | ✅ | ✅ | 一致 |
| `.mcc` 外部区块文件 | ✅（region 目录剩余文件整体复制） | ✅（嵌套/非 `.mca` 视为杂项） | 一致 |
| 杂项文件（`level.dat`/`players`/`data`/`datapacks`） | 全量递归复制（含 `session.lock`） | 复制但**跳过 `session.lock`** | 有意差异 |
| 维度发现 | 任意含 `region/` 的目录，递归 | 同 + 显式 `dimensions/` 与杂项父目录发现 | 一致（本项目更显式） |
| 并行 | Taskmaster，默认 4 worker | `--parallelism`（维度串行、region 并行） | 一致 |
| 保留模式注入 | `ChunkPatternInterface` + 维度/世界级工厂 | `FilterOptions.extraPatterns` + `ChunkPattern` | 本项目为后补（见 §4） |
| `RangePattern` | ✅ 公开 API | ✅ 已恢复 | 见 §4 |
| CLI 参数 | 无（仅 `world [output]`） | 15+ 选项 | 本项目更丰富 |
| 默认阈值 / 默认落盘 | 阈值 `0`；无输出参数时**替换原世界** | 阈值 `300`；只有 `--in-place` 才替换 | 本项目默认更保守 |
| 报告（JSON/CSV）、dry-run、ZIP、`--strict`、`--no-fsync` | ❌ | ✅ | 本项目独有 |
| merge（优化备份 + 全量备份的槽位级合并） | ❌ | ✅ + `--dry-run`/差异清单 | 本项目独有 |
| 2 GiB region 守卫 / 解压上限 / 输入输出重叠守卫 | ❌ | ✅ | 本项目独有（安全） |
| 损坏数据策略 | 任一错误 → 中止整个任务（`SnapException`） | 收集错误继续；`--strict` 才非 0 | 有意差异，见 §5 |

---

## 3. 已修复：legacy `Forced` 的打包解析

**修复前**（`NbtForceLoader` / rust-thanos `forceload.rs`）：把 LongArray 里的**两个连续 long 拼成一个坐标**。

**证据链**

1. vanilla `ChunkPos.toLong(x, z)` = `(long)x & 0xFFFFFFFFL | ((long)z & 0xFFFFFFFFL) << 32`，
   即 **x 在低 32 位、z 在高 32 位**（`ChunkPos.getX` 取低位、`getZ` 取高位）。
2. Minecraft 的 `ForcedChunksSavedData` 用 `LongSet`（元素为 `ChunkPos.toLong`）存盘为 `LongArray`，
   键名 `Forced`；Forge 1.16 的 `patches/minecraft/net/minecraft/world/ForcedChunksSaveData.java.patch`
   只是在 vanilla 的基础上**追加** `ForgeForced` 的自定义 ticket，`Forced` 的编码未改。
   ⇒ **一个 long = 一个 chunk**。
3. 上游夹具 `tools/thanos/tests/Fixtures/legacy-forceload/data/chunks.dat` 里 `Forced` 有 25 个 long
   （奇数 ⇒ 不可能是"两个 long 一个 chunk"），值形如 `0x200000005`。

**修复**：每个 long 拆成 `x = 低 32 位`、`z = 高 32 位`，不再两两配对；补了"25 个 long → 25 个 chunk"
与负坐标/符号扩展的回归测试（Kotlin 与 Rust 两侧同步修复）。

> 上游的同一处实现是"每个 long 拆两个 32 位，但取高位当 x"（按 vanilla 打包等于轴互换）。
> 本项目按 vanilla 语义实现，并在测试注释中记录该差异。

**影响面**：仅"旧版/Forge 世界 + `chunks.dat` 里存在 `Forced`"的场景。修复前会把**错误的坐标**
当成强制加载区——真正被 `/forceload` 的区块可能被删除，而无关区块被保留。

---

## 4. 已补齐：`RangePattern` 与自定义保留模式

- 恢复 `RangePattern(startX, startZ, endX, endZ)`：全局区块坐标闭区间，角点顺序无关
  （与上游语义一致）。
- `FilterOptions.extraPatterns: List<ChunkPattern>`：追加在
  `ListPattern(forced) + InhabitedTimePattern(threshold)` 之后，任一命中即保留。
  Builder 侧提供 `addPattern(...)`。
- 自定义模式只需实现 `ChunkPattern.matches(entry: McaEntry): Boolean`。

---

## 5. 有意保留的差异（不回退到上游行为）

| 差异 | 上游 | 本项目 | 理由 |
|---|---|---|---|
| 错误处理 | 首个错误即中止，无输出 | 收集错误继续，`--strict` 升级退出码 | 备份场景需要"尽量备份下来 + 报告"，但**自动化必须加 `--strict`**（README 已警告） |
| `session.lock` | 复制到输出 | 跳过（merge 还会主动删除） | 避免把过期世界锁带到备份/恢复结果里 |
| 0 字节 region | 静默跳过并原样复制 | 记 `MCA` 错误、不复制 | 让损坏可见 |
| InhabitedTime 读失败 | 抛错并中止整个任务 | **保留原始区块**并记错误 | 备份宁多勿少 |
| 2 GiB region / 解压炸弹 / 重叠目录 | 无守卫 | 有守卫 | 防止不可逆的数据破坏 |

**已知差异待评估**：孤儿 `entities`/`poi`（`.mca` 存在但同名 region 文件不存在）——上游会原样复制，
本项目视为"保留目录内由引擎重写"而丢弃。当前判断是"没有地形就没有实体"更符合优化语义，属有意行为；
若将来遇到真实丢数据案例再调整。

---

## 6. 潜在增强（未实现，记录备查）

1. **Forge `ForgeForced`（模组 ticket）**：Forge 1.16+ 在 `chunks.dat` 里另写 `ForgeForced`
   （`ModForced[]` → `Blocks`/`Entities` 列表）。上游与本项目都**没有**解析它 ⇒ 模组通过
   `ForgeChunkManager` 强制加载的区块不在保护范围内（仅依赖 `Forced` 与 vanilla ticket）。
   若目标世界跑 Forge 模组（如区块加载器），值得补上。
2. **模式注入的工厂形态**：上游支持"基于世界数据动态生成 pattern"的工厂
   （`DimensionPatternFactoryInterface` / `WorldPatternFactoryInterface`）。本项目目前只支持
   静态注入 `extraPatterns`；动态需求可用"先读数据再构造 pattern"绕开。
3. **跨区块特征的切面**：上游 README 的 Known issues（aternosorg/thanos#20）与本项目一致 ——
   `InhabitedTime` 阈值裁剪会切断跨区块的树/结构。上游未解决；本项目的缓解方案（槽位时间戳保留、
   邻居膨胀）属于待定需求，未实现。
