# 提交给上游的改进与分歧清单

> 状态：**2026-11-14 首次提交，全部 pending** · 上游仓库 `aternosorg/thanos`（默认分支 `master`）
> 相关文件：[`upstream-divergences.md`](upstream-divergences.md)（行为对照）、
> [`upstream-expectations.json`](upstream-expectations.json)（门禁登记）、
> [`SPEC.md`](SPEC.md) WOS-12.5。
>
> 原则：**先证据、后改动**。凡是"上游行为与规范冲突"的地方，只有三种处置——改我们的实现、
> 改上游、或写进规范并登记为分歧。本文按这三类整理，并跟踪上游的接受情况。

---

## 一、已提交上游（4 项）

| 链接 | 类型 | 内容 | 依据强度 | 预期 |
|---|---|---|---|---|
| [thanos#28](https://github.com/aternosorg/thanos/pull/28) | PR | 不把 `session.lock` 复制进输出世界 | **强**（无 API 变更；上游 CI 8.4/8.5 绿） | 高 |
| [thanos#29](https://github.com/aternosorg/thanos/pull/29) | PR | Anvil 位置表溢出不再静默写坏文件（单 chunk ≤255 扇区、文件 ≤0xFFFFFF 扇区） | **强**（现有行为必产坏文件，无歧义） | 高 |
| [thanos#30](https://github.com/aternosorg/thanos/issues/30) | issue | legacy `data.Forced` 的 X/Z 高低 32 位疑似互换 | **中**（vanilla 语义明确；但需要上游确认真实世界夹具的来路） | 待确认 |
| [thanos#31](https://github.com/aternosorg/thanos/issues/31) | issue | `InhabitedTime` 取"第一个字节前缀命中"，嵌套同名 tag 会劫持判定 | **中**（可复现，但要上游决定是否接受结构化扫描的成本） | 待确认 |

### #30 的完整证据链

1. vanilla `ChunkPos.toLong(x, z)` = `(long)x & 0xFFFFFFFFL | ((long)z & 0xFFFFFFFFL) << 32`
   → **x 低 32 位、z 高 32 位**（`ChunkPos.getX` 取低位）。
   出处：Bukkit `mc-dev` 的 `net/minecraft/server/ChunkCoordIntPair.java`（1.8：
   `(long) i & 4294967295L | ((long) j & 4294967295L) << 32`）、任意 1.16 反编译版 `ChunkPos#toLong`。
2. `ForcedChunksSavedData` 用 `LongSet` 存 `ChunkPos.toLong()` 并存成 `LongArray`（键 `Forced`）；
   Forge 1.16 的 patch（`patches/minecraft/net/minecraft/world/ForcedChunksSaveData.java.patch`）
   只追加 `ForgeForced`，**不改** `Forced` 的编码 → **一个 long = 一个 chunk**。
3. 上游自己的夹具 `tests/Fixtures/legacy-forceload/data/chunks.dat` 里 `Forced` 恰好 25 个 long
   —— 与"1 long = 1 chunk"一致，与上游解析器的"每 long 拆高低 32 位"也一致，**但高低顺序相反**。
4. 影响：`ListPattern` 保留的是**转置后**的区块 → 真正被 forceload 的区块可能被删，无关区块被留。

> 这也是我们两个实现（Kotlin/Rust）曾经犯过的错——**错法不同**：我们错在"两个 long 拼一个坐标"，
> 上游错在"高低半互换"。`spec/vectors/forceload-forced` 现在把正确语义钉死。

### #31 的完整证据链

- `Chunk::findTag()` 在**整段解压数据**里搜 `pack("Cn", TYPE, strlen($key)) . $key` 并取**第一个**命中，
  然后读后面 `$maxContentLength` 字节 → 不感知 NBT 结构。
- 反例（root compound，`InhabitedTime = 0`）：`structures.InhabitedTime = 999999` 先序列化 →
  上游读到 `999999` 并保留该区块，正确结果应为删掉。
- 我们的实现（Kotlin `ChunkNbtScanner`、Rust `patterns::inhabited`）只走根 compound、按长度跳过 payload，
  只接受**根层**或 **`root.Level`**（≤1.17）的 tag；解析失败则安全保留。
- 复现向量：`spec/vectors/inhabited-nested/`。

---

## 二、我们更对的地方（有门禁证据）

| # | 行为 | 我们的做法 | 上游做法 | 证据 |
|---|---|---|---|---|
| 1 | `Forced` 打包 | 每 long 一个 chunk（x 低 / z 高） | 每 long 拆半但**高低互换** | `vectors/forceload-forced`（三家对比） |
| 2 | `InhabitedTime` 取值 | 只认根层 / `root.Level` | 第一个字节前缀命中 | `vectors/inhabited-nested` |
| 3 | 无法解析的 region | **原样保留**到输出 + 记错误（WOS-6.7） | 抛异常中止整个任务 | `vectors/errors-corrupt-region` |
| 4 | 无法解压的 chunk | 保留原字节 + 记错误（WOS-6.6） | 抛异常中止 | `vectors/corrupt-chunk` |
| 5 | `session.lock` | 不复制（WOS-7.4） | 复制到输出 | `vectors/world-basic` + #28 |
| 6 | region 位置表溢出 | 显式报错（Java/Rust 守卫） | 静默写坏 | #29 |
| 7 | 输入/输出重叠 | 写入前拒绝（WOS-10.1） | 无守卫 | `vectors/overlap-rejected` |
| 8 | 损坏数据下的可审计性 | `errors[]` + 报告 + `--strict` 退出码 | 整任务失败，无部分结果 | `vectors/errors-*` |
| 9 | 合并恢复 / 干跑 | `merge` + `--dry-run` + 差异清单 | 无（产品差异，见下） | `vectors/merge-*` |

---

## 三、分歧里"上游可能更对、值得借鉴"的部分

不是所有分歧都是"我们对"。以下记录上游更好的地方，以及我们为何暂时不改：

| 主题 | 上游做法 | 我们的做法 | 评价 | 处置 |
|---|---|---|---|---|
| 错误哲学 | 一个错误即整体中止（`SnapException`），绝不留半成品 | 收集错误继续，`--strict` 才非 0 | **各有道理**：无人值守备份有时需要"尽量产出 + 明示失败"；但对于"不可审计的半成品"，上游更保守 | 保留现状；WOS-9.4 已写明并强制 `--strict` 用于自动化；待上游反馈后重新评估 |
| 流式解压 | 2 KiB 分块流式读取，天然无 OOM，无上限 | 整块解压 + 64 MiB 上限，超限安全保留 | 上游内存模型更省；我们的上限在极端大 chunk 上会"多保留一份" | 属实现定义（WOS-11），不改 |
| 读取接口 | `getSerializedData()` 返回生成器，writer 边读边写 | Java/Rust 整块 buffer | 上游对大 region 更友好；我们换取了 2 GiB 守卫的简单性 | 属实现定义；若将来遇大 region 可在 Rust 侧改流式 |
| tag 缓存 | `Chunk::$chunkTagCache` 避免同一 chunk 重复扫描 | 每个 chunk 只查一次，无差异 | 目前无影响；若增加更多 tag 可借鉴 | 记录备查 |
| 空文件写入 | `McaWriter` 有 `writeEmptyFile` 开关 | "全空 region 不生成文件"是硬规则 | 上游更灵活；我们更贴合 MC 约定（WOS-6.4） | 保留现状，若用户需要可加开关 |
| 上游夹具 | 有真实世界 `Fixtures/world`（676 chunks）、`legacy-forceload` | 我们的向量是合成最小世界（确定性、可复算） | 各有利弊：上游真实、我们可复现 | 长期：可把上游夹具纳入我们的向量（需注明来源与许可） |

---

## 四、与规范的关系

| 条款 | 上游 | 我们 | 判定与登记 |
|---|---|---|---|
| WOS-4.3 `Forced` 打包 | 高低互换 | vanilla 语义 | 上游 `divergent`（#30）；门禁 `forceload-forced` = xfail |
| WOS-3.4 `InhabitedTime` 位置 | 字节前缀 | 结构化 | 上游 `divergent`（#31）；`inhabited-nested` = xfail |
| WOS-6.7 损坏 region | 中止 | 原样保留 | 上游 `divergent`；`errors-corrupt-region` = xfail |
| WOS-6.6 损坏 chunk | 中止 | 保留原字节 | 上游 `divergent`；`corrupt-chunk` = xfail |
| WOS-2.6 `CUSTOM` 压缩 | 抛不支持 | 安全保留 | 上游 `divergent`；`custom-compression` = xfail |
| WOS-2.4 LZ4 | 需 `ext-lz4` | 内置实现 | 环境差异；`compression-matrix` = xfail（门禁不装该扩展） |
| WOS-7.4 `session.lock` | 复制 | 跳过 | 上游 `divergent`；`world-basic` = xfail；已提 #28 |
| WOS-8 merge / WOS-9 报告 / WOS-9.5 dry-run / WOS-10.1 重叠守卫 / WOS-10.3 `--force` 符号链接 | 无此能力 | 有 | 门禁登记为 `not_applicable` |

> 上游若合并 #28/#29：`world-basic` 会变 PASS、`errors-*` 不变；届时把 `upstream-expectations.json`
> 里对应条目删掉即可 —— 若忘了删，门禁会报 **XPASS** 失败，强制更新登记表。

---

## 五、跟踪与后续动作

| 项目 | 链接 | 状态 | 上游回应后的动作 |
|---|---|---|---|
| `session.lock` | [PR #28](https://github.com/aternosorg/thanos/pull/28) | pending | 合并后：删除 expectations 中的 `world-basic` 条目，`upstream-divergences.md` D2 标记 resolved |
| 位置表守卫 | [PR #29](https://github.com/aternosorg/thanos/pull/29) | pending | 合并后：`upstream-divergences.md` 新增 "已对齐" 行 |
| `Forced` 打包 | [issue #30](https://github.com/aternosorg/thanos/issues/30) | pending（待确认夹具来路） | 若确认：上游改解析；我们同步把 `forceload-forced` 的 xfail 改为 PASS 检查 |
| `InhabitedTime` 结构 | [issue #31](https://github.com/aternosorg/thanos/issues/31) | pending（涉及性能取舍） | 若接受：`inhabited-nested` 转 PASS；若拒绝：把理由补进 `upstream-divergences.md` |
| 上游真实世界夹具 | — | 待评估 | 若引入我们的向量，需注明来源与 MIT 许可 |

**更新时间线**：2026-11-14 首次提交（4 项）。下一次会话只需看本表的状态列。
