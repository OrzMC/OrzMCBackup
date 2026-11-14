# 一致性向量

每个向量是一个目录：

```
<name>/
├── README.md             # 覆盖条款、输入构造方式、期望值的推导说明
├── params.json           # {"vector","clauses","command","args":[CLI 参数]}
├── input/                # 世界目录（实现只读，不得修改）
└── expected/manifest.json  # 期望清单（按条款手工推导）
```

## 规则

1. **确定性**：向量由 `spec/tools/make_vectors.py` 生成，不使用当前时间（时间戳全为固定值）。
   CI 会重新生成并 `git diff --exit-code`，防止手工改动输入却忘记同步期望。
2. **期望值独立**：`expected/manifest.json` 由生成脚本按条款推导（保留哪些槽位是手工列出的，
   payload 哈希取自输入字节），**不运行任何实现**。
3. **实现只读**：运行器把 `input/` 直接传给实现的 CLI，输出到临时目录；实现不得原地修改输入。
4. **比较口径**：见 `spec/tools/compare.py` 与 `spec/schema/manifest.schema.json`。
5. **可选字段**：`params.json` 支持 `world`（`symlink` = 运行器先把 `input` 做成符号链接）、
   `force_victim`（运行器在输出目录里放一个指向 `victim/` 的符号链接并断言目标未被删）、
   `output`（`input`/`base`/`patch`，用于重叠拒绝类向量）、`expect_exit`、`expect_no_output`、
   `dry_run`、`report`（只比较规范字段）。

## 现有向量

| 向量 | 条款 | 证明什么 |
|---|---|---|
| `forceload-forced` | WOS-4.1/4.2/4.3/4.5 | `data.Forced` 是「每 long 一个 chunk，x 低 32 位 / z 高 32 位」；奇数项不丢；非强制区块按阈值删除 |
| `decision-inhabited` | WOS-3.1/3.2/3.3、WOS-5.3 | 严格 `>`（等于阈值删除）、缺失 `InhabitedTime` 保留、外部压缩默认保留 |
| `threshold-zero` | WOS-3.2 | `-t 0` 只删 `InhabitedTime == 0` |
| `remove-unknown` | WOS-5.2/5.3 | 带开关时外部区块被删除 |
| `compression-matrix` | WOS-2.4/6.1 | RAW/GZIP/ZLIB/LZ4 均可读；payload 原字节透传 |
| `mcc-external` | WOS-2.5/7.3 | 外部区块不内联解压；`region/*.mcc` 随输出复制 |
| `corrupt-chunk` | WOS-6.6 | 未定义压缩方法 → 保留原字节 + 记错误 |
| `world-basic` | WOS-6.4/6.5/7.3/7.4 | 惰性写入、锁步、侧车文件、`session.lock` 排除 |
| `world-26-1` | WOS-7.1/7.2/4.1/4.2 | 26.1+ 嵌套维度 + `chunk_tickets.dat` |
| `symlink-world` | WOS-7.5 | 世界根是符号链接时正常处理（不跟随即空跑变红） |
| `merge-basic` / `merge-parallel` / `merge-corrupt-patch` | WOS-8.x | 槽位并集/锁步/单侧复制/并行确定性/损坏回退 |
| `errors-corrupt-region` | WOS-6.7/9.3/9.4 | 损坏 region 原样保留、错误记录、非 strict 退出码 0 |
| `report-fields` | WOS-9.1/9.2 | 报告计数语义（`processed` = 访问数） |
| `cli-dry-run` | WOS-9.5 | `--dry-run` 零写入、输入不变 |
| `overlap-rejected` / `merge-overlap` | WOS-10.1 / WOS-8.1 | 重叠拒绝且不动源数据（backup / merge 两侧） |
| `custom-compression` | WOS-2.6 | `CUSTOM(127)` 无法解压 → 安全保留 |
| `forceload-corrupt` | WOS-4.4 | 强制加载文件损坏 → 视为无强制加载，正常按阈值处理 |
| `decompress-bomb` | WOS-10.2 | 65 MiB 解压炸弹 → 安全保留、不 OOM |
| `inhabited-nested` | WOS-3.1/3.4 | 根层/`Level` 兼容；`structures` 里的同名诱饵 tag 必须忽略 |
| `force-output-symlink` | WOS-10.3 | `--force` 清空输出目录时不跟随符号链接（链接目标必须完好） |

> 反证：`forceload-forced` 的 3 个 packed long 若按旧的「两个 long 拼一个坐标」解释，会得到
> `(5,1)` 一个坐标并丢掉最后一项 → 保留槽位变成 `{37}`，与期望 `{5,65,650}` 不符，向量必红。

## 追加向量

1. 在 `spec/tools/make_vectors.py` 里新增 `vector_xxx()`（或手工构造 `input/`）；
2. 按条款推导 `expected/manifest.json` 并在向量 `README.md` 写明推导过程；
3. 本地跑 `scripts/conformance.sh all`，java 与 rust 都必须 PASS；
4. 提交时同时带上 `input/`、`params.json`、`expected/`、`README.md`。
