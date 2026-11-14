# spec/ — 语言无关行为规范

这里是本仓库的**唯一行为真值**。三个实现共用同一套条款：

| 实现 | 位置 | 角色 |
|---|---|---|
| Kotlin/JVM | `../java/` | 生产实现（CLI + Maven Central 库） |
| Rust | `../rust/` | 生产实现（CLI + lib） |
| PHP（官方） | `../upstream/thanos/` | **只读参考**，用于补充与印证条款 |

## 文件

| 文件 | 内容 |
|---|---|
| `SPEC.md` | 规范性条款（`WOS-<章>.<款>`，RFC 2119 措辞）+ 条款→实现映射 + 一致性进度 |
| `divergences.md` | 允许的实现间分歧登记表（每项含理由与检测向量） |
| `upstream-divergences.md` | 与官方 PHP 实现的对照（差异、有意保留、潜在增强） |
| `vectors/` | 一致性测试向量：`input/` + `params.json` + `expected/manifest.json`（期望值按条款手工推导） |
| `schema/manifest.schema.json` | 清单格式（region 按槽位比内容+时间戳；其余文件比整文件 SHA-256） |
| `tools/mca.py` | 中立 MCA 读取（只读头表 + payload 哈希） |
| `tools/manifest.py` | 从输出目录派生实际清单（实现不产 manifest） |
| `tools/compare.py` | 中立比较器（严格集合/payload/时间戳；忽略扇区布局等实现定义项） |
| `tools/make_vectors.py` | 按条款生成向量的输入与**期望**清单（确定性，无时钟） |
| `tools/check_report.py` | 比较报告 JSON 的**规范字段**（忽略实现定义的附加字段） |
| `tools/coverage.py` | 条款 ↔ 向量覆盖矩阵（`--strict` 可作 CI 门禁） |
| `tools/upstream_adapter.php` | 驱动上游 PHP 实现跑同一批向量（唯一与实现绑定的脚本） |
| `upstream-expectations.json` | 上游的 `not_applicable` / `expected_failures` 登记表（未登记的不一致 = 失败） |

## 使用

```bash
# 一致性校验（CI 门禁 = conformance.yml，失败阻断合并）
scripts/conformance.sh all       # 构建 java+rust 并跑全部向量
scripts/conformance.sh java      # 只跑 java 域并比对 spec/vectors
scripts/conformance.sh rust
scripts/conformance.sh upstream  # 跑 PHP 参考实现（需 php + upstream/thanos/vendor；见下）
scripts/conformance.sh all
```

## 约束

- 改行为：**先改条款**，再改实现；PR 描述必须引用 `WOS-###`。
- 期望清单只能按条款手工推导（`tools/make_vectors.py`），**禁止**用被测实现的输出当 golden。
- 上游 PHP **在门禁内**（WOS-12.5）：CI 装 PHP 8.4 + `composer install --working-dir=upstream/thanos`，
  再跑 `CONFORMANCE_REQUIRE_UPSTREAM=1 ./scripts/conformance.sh all`。
  已知分歧用 `upstream-expectations.json` 登记（`xfail`），**未登记的不一致直接失败**；
  登记了却通过会报 `XPASS` 失败，迫使登记表保持真实。
