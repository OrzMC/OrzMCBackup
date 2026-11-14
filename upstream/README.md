# upstream/ — 官方 PHP 实现的只读关联

本目录只存放**参考实现**，不参与本仓库的构建与发布，也不接受本地修改。

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/aternosorg/thanos |
| 形态 | Git submodule（嵌套：父仓库 → `backup` → `upstream/thanos`） |
| 许可 | MIT（见 `thanos/LICENSE`） |
| 当前钉住的提交 | `56772da`（2026-04-26，仅 dependabot 依赖升级） |
| 角色 | **reference-only**：行为对照来源，不是可交付实现 |

## 为什么放 submodule

我们的规范需要不断用上游实现来补充/印证（例如 `data.Forced` 的打包方式、模式组合语义、
压缩方法码表）。把上游源码固定在仓库内，可以让：

1. `spec/upstream-divergences.md` 里的每一条差异都能直接对照到**具体提交**的源码行；
2. CI（`conformance` 的 upstream job）在需要时可直接跑 PHP 实现做参考比对；
3. 上游若真有新提交，只需一次 `git submodule update --remote upstream/thanos` 的评审式升级。

## 使用方式

```bash
# 父仓库首次检出（递归初始化嵌套子模块）
git clone --recurse-submodules https://github.com/OrzMC/OrzMC.git
# 已检出但为空
git submodule update --init --recursive
```

## 参与一致性门禁（WOS-12.5）

上游实现会被 `scripts/conformance.sh upstream` 用同一批向量校验（见 `../spec/SPEC.md` §12）：

```bash
composer install --working-dir=upstream/thanos          # 一次性：安装 aternos/nbt、taskmaster、io
./scripts/conformance.sh upstream                        # 跑全部向量；本地无 php 时自动跳过
CONFORMANCE_REQUIRE_UPSTREAM=1 ./scripts/conformance.sh all   # CI 用法：缺 php/依赖即失败
```

- 驱动脚本：`../spec/tools/upstream_adapter.php`（上游 CLI 固定 `-t 0` 且无选项，故直接调库）
- 结果处理：`../spec/upstream-expectations.json` 登记 `not_applicable` / `expected_failures`；
  **未登记的不一致直接判失败**，登记了却通过报 `XPASS` 失败
- 每一条 `expected_failures` 都必须在 `../spec/upstream-divergences.md` 有对应说明

## 升级流程（评审式）

1. 在 `upstream/thanos` 中 `git fetch && git log --oneline HEAD..origin/master`；
2. 若有新提交：`git -C upstream/thanos checkout <new-sha>`，在父仓库 `git add upstream/thanos`；
3. 更新本文件的"当前钉住的提交"，跑一次 `scripts/conformance.sh upstream`（见 spec/），
   把行为变化记录到 `spec/upstream-divergences.md`；
4. **绝不修改 `upstream/thanos` 内部文件**——需要改动就 fork 到 `OrzMC/` 再换 URL。
