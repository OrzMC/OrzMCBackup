# 允许的实现分歧（divergences）

> 规则：任何**可观察行为**上的实现差异都必须在此登记，并在 `SPEC.md` 对应条款标注 `divergent`。
> 未登记的分歧即缺陷；`conformance` 会对登记项做 expected-fail 处理，其余必须一致。
>
> **上游 PHP 也在门禁内**（WOS-12.5）：它在 `spec/upstream-expectations.json` 里逐条登记
> `expected_failures`（对应本表 D1–D6）或 `not_applicable`（缺该特性）。**未登记的不一致 = 构建失败**
> —— 这正是"发现不一致就说明规范需要对齐"的机制：要么改实现，要么改规范并登记。

| # | 条款 | 实现 | 差异 | 理由 | 检测方式 |
|---|---|---|---|---|---|
| D1 | WOS-9.4 | upstream | 出错即中止整个任务（`SnapException`），不产出部分结果 | 上游把一致性放在"不做半成品"上 | upstream adapter expected-fail |
| D2 | WOS-7.4 | upstream | 会把 `session.lock` 复制到输出 | 上游按"整目录复制"实现 | manifest 文件集合 expected-fail |
| D3 | WOS-6.6 | upstream | 无 2 GiB region 守卫（3 B 偏移溢出会写坏头部） | 上游未实现 | 无向量（超限场景过大，仅代码审查） |
| D4 | WOS-10.2 | upstream | 无解压上限（流式读取，不存在 OOM 风险） | 实现方式不同 | 无向量 |
| D5 | WOS-6.6 | java, rust | 对"孤儿 entities/poi（无同名 region）"丢弃而非原样复制 | 语义上"无地形即无实体"；待真实案例再评估 | 待补向量（S3 后评估） |
| D6 | WOS-5.4 | upstream | 无"注入自定义模式"的等价 API（用 pattern 工厂替代） | 语言/库形态差异 | 不纳入比较（属 §11 实现定义） |

## 候选分歧（未决，先记录）

| # | 说明 | 决策所需信息 |
|---|---|---|
| C1 | `ForgeForced`（Forge `ForgeChunkManager` 写出的模组 ticket）三份实现都未解析 | 是否有目标世界使用模组区块加载器 |
| C2 | `java` 与 `rust` 的 CLI 选项集合不完全相同（如 `--keep-range` 仅 rust） | 是否需要统一 CLI 表面（属 §11，可不统一） |
