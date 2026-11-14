# OrzMCBackup 行为规范（WOS）

> **状态**：v0.2（条款已落地；`forceload-forced` / `decision-inhabited` 两个向量已生效并通过 java+rust，其余向量见末尾进度表）
> **规范性**：本文是**唯一行为真值**。`java/`、`rust/` 的实现必须满足本文件条款；允许的分歧只能登记在
> [`divergences.md`](divergences.md)，与官方 PHP 的对照见 [`upstream-divergences.md`](upstream-divergences.md)。
> **关键词**：`MUST`（必须）/ `MUST NOT`（禁止）/ `SHOULD`（应当）/ `MAY`（可以），按 RFC 2119 解释。

条款 ID 规则：`WOS-<章>.<款>`。每条给出 `conformance:`（对应测试向量）与 `impl:`（三份实现的位置，
`absent` 表示该实现没有此能力，`divergent` 表示已知分歧，须在 divergences.md 有对应行）。

---

## 1. 术语与粒度（WOS-1）

| 术语 | 定义 |
|---|---|
| block | 1×1×1 方块。**不是**任何裁剪的单位 |
| section | 16×16×16 = 4,096 方块。持有 `block_states`/`biomes`/`BlockLight`/`SkyLight` |
| chunk（区块） | 16×16 × 全高（1.18+ 主世界 384 格 = 24 sections）。持有 `InhabitedTime`、`Heightmaps`、`structures` 等 |
| slot（槽位） | `.mca` 头部位置表的一个表项（0..1023），**恰好对应一个 chunk** |
| region 文件 | `.mca`，32×32 chunks = 512×512 方块足迹 |

- **WOS-1.1** 裁剪与合并的单位 `MUST` 是 chunk（= slot）。`MUST NOT` 在 section 或 block 粒度上裁剪或合并。
  `impl:` java `world/DimensionProcessor.kt` / rust `world/optimizer.rs`
- **WOS-1.2** 槽位索引 `MUST` 为 `index = (chunkX & 31) + (chunkZ & 31) * 32`；region 文件名 `MUST` 匹配
  `r\.(-?\d+)\.(-?\d+)\.mca$`，`regionX = chunkX >> 5`。

## 2. `.mca` 格式（WOS-2）

- **WOS-2.1** 文件头 `MUST` 为 8 KiB：前 4 KiB 位置表（1024 × 4 B：3 B 起始扇区 + 1 B 扇区数），
  后 4 KiB 时间戳表（1024 × 4 B，Unix 秒）。
- **WOS-2.2** 空槽位 `MUST` 以「起始扇区 = 0」表示；引擎读到的空槽位不得视为 chunk。
- **WOS-2.3** 每个 chunk payload `MUST` 为 `4 B 长度 + 1 B 压缩方法 + 数据`，从 4 KiB 扇区边界开始。
- **WOS-2.4** 压缩方法码表：`1=GZIP`、`2=ZLIB`、`3=RAW`、`4=LZ4`、`127=CUSTOM`（后跟 2 B 名称长度 + 名称）、
  `-127..-124 = 外部存储（EXT_*）`，其数据在 `.mcc` 文件中。
- **WOS-2.5** 读取外部存储区块时 `MUST` 不做内联解压；保留与否由 WOS-5.3 决定，但**字节必须原样保留**。
- **WOS-2.6** `CUSTOM` 压缩 `MUST` 走安全保留（无法解压 ≠ 可删除）。
  `impl:` java `mca/McaEntry.kt` / rust `mca/entry.rs` / upstream `Mca/Entry/McaEntry.php`（`divergent`：上游抛不支持）

## 3. chunk 数据的使用（WOS-3）

- **WOS-3.1** `InhabitedTime` `MUST` 为 chunk 根 compound 上的 `LONG`（tag id 4），单位 game tick。
- **WOS-3.2** 阈值比较 `MUST` 为**严格大于**（`InhabitedTime > threshold`）；因此 `threshold = 0` 会移除
  所有从未被 tick 过的区块。
- **WOS-3.3** 缺失 `InhabitedTime`、解压失败或数据为空：`MUST NOT` 视为"可删除"，除非 `removeUnknown`
  为真；`removeUnknown = false` 时 `MUST` 保留。
- **WOS-3.4** 查找 `InhabitedTime` `MAY` 采用字节前缀搜索以省去完整 NBT 解析，但**读到的值若无法解释，
  `MUST` 走安全保留**（等价于 WOS-3.3）。
  `impl:` java `patterns/InhabitedTimePattern.kt` / rust `patterns/inhabited.rs` / upstream `Pattern/InhabitedTimePattern.php`

## 4. 强制加载区块（WOS-4）

- **WOS-4.1** 探测顺序 `MUST` 为：`data/minecraft/chunk_tickets.dat` → `data/chunks.dat`，
  **取第一个存在的文件**（不合并两个文件）。
- **WOS-4.2** 单个文件内，`data.Forced`（LongArray）与 `data.tickets[].chunk_pos`（IntArray[2]，且
  `type == "minecraft:forced"`）`MUST` 取并集。
- **WOS-4.3** `data.Forced` `MUST` 按 vanilla `ChunkPos.toLong(x, z)` 解释：**每个 long 一个 chunk，
  x 为低 32 位、z 为高 32 位**（`MUST NOT` 把两个连续 long 拼成一个坐标）。
  `conformance: forceload/forced-packing`
  `impl:` java `world/NbtForceLoader.kt` / rust `world/forceload.rs` / upstream `divergent`（取高 32 位当 x）
- **WOS-4.4** 解析失败 `MUST` 视为"没有强制加载区块"（保留空列表），`strict` 模式下 `MAY` 记录错误。
- **WOS-4.5** 强制加载区块 `MUST` 无条件保留（不参与阈值判定）。

## 5. 保留决策（WOS-5）

- **WOS-5.1** 保留判据为各模式的**并集（OR）**：
  `keep = forced ∨ inhabited(threshold) ∨ extraPatterns…`，任一命中即保留，顺序不改变结果。
- **WOS-5.2** `removeUnknown` `MUST` 只影响"未知/外部存储/无法解析"的区块，`MUST NOT` 影响正常区块。
- **WOS-5.3** 外部存储区块（`EXT_*`）：`removeUnknown = true` 时删除，否则保留（WOS-2.5）。
- **WOS-5.4** 引擎 `MUST` 支持注入自定义保留模式（库 API），默认不启用任何额外模式。

## 6. 写入语义（WOS-6）

- **WOS-6.1** 保留区块的 payload `MUST` 逐字节原样写出（不重压缩、不重编码 NBT、不清洗）。
- **WOS-6.2** 槽位时间戳 `MUST` 沿用源 chunk 的时间戳（不得替换为"当前时间"）。
- **WOS-6.3** 输出 chunk `MUST` 从 4 KiB 扇区边界开始并重算位置表；`MUST` 保持 ≤ 2 GiB
  （3 B 偏移上限），超限 `MUST` 报错而不是写坏头部。
- **WOS-6.4** 若某 region 的所有槽位均被删除，`MUST NOT` 生成空 `.mca` 文件（惰性写入）。
- **WOS-6.5** entities / poi `MUST` 与 region **同槽位锁步**：保留 chunk 时写其 entities/poi（若有），
  删除时一并删除；`MUST NOT` 写入与 region 不对应的 entities/poi 槽位。
- **WOS-6.6** 损坏数据 `MUST` 遵循"宁多勿少"：无法解析的 chunk 保留原字节并记录错误，`MUST NOT`
  静默丢弃。
- **WOS-6.7** 无法解析的 **region 文件**（如头部不足 8 KiB / 读取失败）在非原地模式下 `MUST` 被
  **原样复制**到输出（连同其同名 entities/poi 兄弟文件），并记录错误；`MUST NOT` 因为解析失败而
  让该文件从备份中消失。`conformance: errors-corrupt-region`
  `impl:` java `world/DimensionProcessor.kt`（`preserveUnprocessable`）/ rust `world/optimizer.rs`
  （`preserve_unprocessable`）/ upstream `absent`（上游直接中止整个任务）
  `impl:` java `mca/McaWriter.kt` + `world/DimensionProcessor.kt` / rust `mca/writer.rs` +
  `world/optimizer.rs` / upstream `Mca/McaWriter.php`（`divergent`：无 2 GiB 守卫、错误即中止）

## 7. 目录与杂项（WOS-7）

- **WOS-7.1** 维度目录 `MUST` 定义为「包含 `region/` 子目录的目录」，`MUST` 递归发现（兼容
  `DIM-1`/`DIM1`、`world_nether`、以及 26.1+ 的 `dimensions/minecraft/<dim>/`）。
- **WOS-7.2** 世界级杂项文件（`level.dat`、`players/`、`data/`、`datapacks/`、`generated/` 等）
  `MUST` 被复制到输出；若这些文件位于维度的祖先目录（26.1+ 结构），`MUST` 单独发现并复制。
- **WOS-7.3** `region/`、`entities/`、`poi/` 下的**顶层 `.mca`** 由引擎重写；这些目录下的
  **非 `.mca` 文件与嵌套 `.mca`** `MUST` 作为杂项原样复制（含外部存储的 `.mcc`）。
- **WOS-7.4** `session.lock` `MUST NOT` 被复制到输出；原地替换与合并输出中的 `session.lock` `MUST` 被删除。
- **WOS-7.5** 符号链接：发现/计数/复制 `MUST` 跟随链接目标（如 `world → 真实目录`）；删除操作
  `MUST NOT` 跟随（避免穿链接删除源数据）。

## 8. 合并（merge）（WOS-8）

- **WOS-8.1** base/patch/output 三者 `MUST` 互不重叠（含符号链接别名）；重叠时 `MUST` 在写入前拒绝。
- **WOS-8.2** 槽位来源 `MUST` 为：`patch 有 → patch`、否则 `base 有 → base`、否则空。
  patch 来源优先，**不做时间戳择优**。
- **WOS-8.3** entities/poi `MUST` 与来源锁步：来源 patch 时只取 patch 的 entities/poi（缺失即为无），
  `MUST NOT` 回退 base 的同槽位 entities/poi。
- **WOS-8.4** 合并后全空的 entities/poi 文件 `MUST NOT` 生成（并移除 base 复制的残留）。
- **WOS-8.5** 仅 base 存在的 region `MUST` 原样复制（含其 entities/poi）；仅 patch 存在的 region
  `MUST` 连同同名 entities/poi 兄弟文件一起复制。
- **WOS-8.6** patch 的 region 无法读取时 `MUST` 逐字节回退 base 对应 region 与 entities/poi。
- **WOS-8.7** 杂项文件（`level.dat` 等）`MUST` 采用 patch 版本覆盖。
- **WOS-8.8** 并行度 `MUST NOT` 影响输出字节（同一请求的输出逐字节确定）。
  `impl:` java `world/WorldMerger.kt` / rust `world/merger.rs` / upstream `absent`

## 9. 报告、进度与退出码（WOS-9）

- **WOS-9.1** `processedChunks` 语义为**访问过的区块总数**（保留 + 删除），保留数 = `processed − removed`。
- **WOS-9.2** merge 报告字段：`mergedRegions`、`patchSlots`、`baseSlots`（回填数，不是删除数）、
  `copiedFiles`、`overlayFiles`、`linkedEntities`、`linkedPoi`、`errors`。
- **WOS-9.3** 错误必须以 `{path, kind, message}` 结构收集；`kind` `MUST` 取自 `spec.yaml` 的枚举。
- **WOS-9.4** 退出码：`backup` 在无 `--strict` 时即便有错误也返回 0；`--strict` 且存在错误返回 1。
  `merge` 在**存在任何错误**时返回 1。
- **WOS-9.5** `--dry-run` `MUST NOT` 创建、修改或删除任何输出路径。

## 10. 安全（WOS-10）

- **WOS-10.1** 输入与输出重叠（相同/嵌套/符号链接别名）`MUST` 在任何写入之前被拒绝，并且
  `MUST` 早于**任何交互提示与目录删除**（`--force` / "覆盖吗？" 都不得发生）。
  `conformance: overlap-rejected`
  `impl:` java `world/OverlapGuard.kt`（run 内最先执行）/ rust `world/output.rs` + `main.rs`（CLI 入口先守卫）
- **WOS-10.2** 解压后数据量 `MUST` 有上限（当前 64 MiB/ chunk），超限走安全保留 + 记错误。
- **WOS-10.3** 输出目录的删除（`--force`）`MUST NOT` 跟随符号链接。

## 11. 实现定义（不参与一致性比较）

`MAY` 自由实现、且**不得**作为一致性差异：region 扇区分配顺序与文件内布局、ZIP 归档命名与压缩级别、
并行调度与线程数、进度/日志文案、临时目录命名、库 API 形态、CLI 选项集合（各实现可有额外选项）。

## 12. 一致性测试（WOS-12）

- **WOS-12.1** 一致性以「**manifest 等价**」判定（见 `schema/manifest.schema.json`）：
  严格比较**文件集合**、`region/`/`entities/`/`poi/` 下每个 `.mca` 的**槽位占用集合**、每个槽位的
  **payload SHA-256 与时间戳**、
  杂项文件 SHA-256、报告计数与错误 `kind` 集合；**无法解析的 `.mca`（< 8 KiB 头）按整文件 SHA-256 比较**
  （对应 WOS-6.7 的"原样保留"）。忽略 §11 项。
- **WOS-12.2** 期望清单 `MUST` 依据条款手工推导（`tools/make_vectors.py`，含逐条推导说明与
  provenance），`MUST NOT` 由任一被测实现的输出直接充当期望值；实际清单由中立读取器
  `tools/manifest.py` 从输出目录派生（实现不产 manifest）。比较由 `tools/compare.py` 完成。
- **WOS-12.3** 向量以仓库内 `spec/vectors/<name>/{params.json,input/,expected/manifest.json}` 组织，
  `MUST` 可通过 `scripts/conformance.sh` 复现。
- **WOS-12.4** conformance 失败 `MUST` 阻断合并（CI 门禁）。

---

## 一致性进度（v0.1）

| 向量目录 | 覆盖条款 | 状态 |
|---|---|---|
| `vectors/forceload-forced/` | WOS-4.1/4.2/4.3/4.5 | ✅ 已落地（java+rust PASS；旧的双 long 解析会红） |
| `vectors/decision-inhabited/` | WOS-3.1/3.2/3.3、WOS-5.3 | ✅ 已落地（严格 `>`、缺 tag、外部压缩） |
| `vectors/world-basic/` | WOS-6.4/6.5、WOS-7.3/7.4 | ✅ 已落地 |
| `vectors/world-26-1/` | WOS-7.1/7.2 | 待补（S3 第二批） |
| `vectors/merge-basic/` | WOS-8.2/8.3/8.4/8.5/8.7 | ✅ 已落地 |
| `vectors/errors-corrupt-region/` | WOS-6.7（原样保留损坏 region）、WOS-9.3/9.4 | ✅ 已落地 |
| `vectors/cli-dry-run/` | WOS-9.5 | ✅ 已落地 |
| `vectors/compression-matrix/` | WOS-2.4、WOS-6.1（原字节透传） | ✅ 已落地 |
| `vectors/threshold-zero/` | WOS-3.2（`-t 0` 边界） | ✅ 已落地 |
| `vectors/remove-unknown/` | WOS-5.2/5.3 | ✅ 已落地 |
| `vectors/merge-parallel/` | WOS-8.8（并行不影响字节） | ✅ 已落地 |
| `vectors/merge-corrupt-patch/` | WOS-8.6（回退 base） | ✅ 已落地 |
| `vectors/overlap-rejected/` | WOS-10.1 | ✅ 已落地 |

## 条款覆盖（机器生成）

```bash
python3 spec/tools/coverage.py            # 条款 ↔ 向量 矩阵（report-only）
python3 spec/tools/coverage.py --strict   # 有未覆盖条款即退出 1（未来可接 CI）
```

当前：**53 条条款，36 条被向量覆盖**。未覆盖的 17 条分三类：

| 类别 | 条款 | 说明 |
|---|---|---|
| 待补向量 | WOS-2.5/2.6（外部存储 `.mcc`、`CUSTOM` 压缩）、WOS-3.4（tag 读取歧义）、WOS-4.4（强制加载解析失败）、WOS-5.1/5.4（模式组合/注入）、WOS-6.6（单 chunk 损坏）、WOS-7.5（符号链接）、WOS-9.1/9.2（报告字段语义）、WOS-10.2/10.3（解压上限/删除不跟链接） | 需要专门构造的夹具，列入 S3 后续 |
| 元条款 | WOS-12.1–12.4 | 描述一致性方法本身，由 `tools/*` 与 CI 实现，不是行为断言 |
| 结构性 | WOS-8.1（merge 重叠） | 与 WOS-10.1 同源，`overlap-rejected` 已覆盖 backup 侧；merge 侧可复用同一向量扩展 |

## 变更流程

1. 任何可观察行为变更：**先**提本文件的条款 PR（含受影响向量）；
2. 合并后再改 `java/`、`rust/`（PR 描述引用 `WOS-###`）；
3. `scripts/conformance.sh java|rust|upstream` 全绿后方可合并；
4. 无法对齐的行为只能进 [`divergences.md`](divergences.md)，并注明检测向量。
