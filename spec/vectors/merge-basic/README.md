# merge-basic

覆盖条款：WOS-8.2（patch 优先）、WOS-8.3（entities/poi 锁步）、WOS-8.4（全空不生成）、
WOS-8.5（只在一侧的 region 原样复制 + 兄弟文件）、WOS-8.7（杂项取 patch）

## 输入

- `input/base`：`region/r.0.0.mca` 槽位 {0,1,33}、`region/r.0.1.mca` 槽位 {7}、
  `entities/r.0.0.mca` {0,1}、`poi/r.0.0.mca` {1}、`level.dat`=`base-level`、`data/marker.txt`
- `input/patch`：`region/r.0.0.mca` 槽位 {0,33}、`region/r.1.0.mca` {5}、
  `entities/r.0.0.mca` {0,33}、`entities/r.1.0.mca` {5}、`poi/r.0.0.mca` {33}、`level.dat`=`patch-level`

## 期望

- `region/r.0.0.mca`：0 ← patch、1 ← base（patch 没有该槽）、33 ← patch
- `entities/r.0.0.mca`：0 ← patch、1 ← base、33 ← patch；`poi/r.0.0.mca`：1 ← base、33 ← patch
- 槽位 0 在 patch 无 poi → 输出 poi 无槽位 0（锁步，不得回退 base）
- `region/r.0.1.mca`（仅 base）与 `region/r.1.0.mca`（仅 patch，含兄弟 entities）原样复制
- `level.dat` ← patch，`data/marker.txt` ← base

期望清单由 `spec/tools/make_vectors.py` 按条款推导：每个槽位的 payload/时间戳取自它应来自的那一侧输入。

