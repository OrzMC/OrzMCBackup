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
| `vectors/`（S3） | 一致性测试向量：`input/` + `params.json` + `expected/manifest.json` |
| `schema/`（S3） | manifest / report 的 JSON Schema |
| `tools/`（S3） | `make_manifest.py`（独立参考实现）、`compare.py`（中立比较器） |

## 使用

```bash
# 一致性校验（S3 起可用）
scripts/conformance.sh java      # 跑 java 域并比对 spec/vectors
scripts/conformance.sh rust
scripts/conformance.sh upstream  # 可选：跑 PHP 参考实现（已知分歧 expected-fail）
scripts/conformance.sh all
```

## 约束

- 改行为：**先改条款**，再改实现；PR 描述必须引用 `WOS-###`。
- 期望清单只能由 `tools/make_manifest.py` 生成并经人审，**禁止**用被测实现的输出当 golden。
- 上游 PHP 不进硬门禁（已冻结且有已知分歧），用 `upstream-divergences.md` + expected-fail 标记。
