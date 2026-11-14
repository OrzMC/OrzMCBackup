# world-basic

覆盖条款：WOS-6.4（全空 region 不生成文件）、WOS-6.5（entities 锁步）、
WOS-7.3（region 下非 `.mca` 当杂项）、WOS-7.4（`session.lock` 不复制）

## 输入

- `region/r.0.0.mca` 槽位 0（`InhabitedTime=0`）→ 全部被删 → **输出不得存在该文件**
- `region/r.0.1.mca` 槽位 3（`InhabitedTime=7000` > 6000）→ 保留；`entities/r.0.1.mca` 槽位 3 锁步保留
- `region/r.0.1.mca.bak`、`region/notes.txt` → 杂项原样复制
- `session.lock` → **不得出现**在输出；`level.dat`、`datapacks/pack.mcmeta` 复制

## 期望

- 输出文件集合：`region/r.0.1.mca`、`entities/r.0.1.mca`、`region/r.0.1.mca.bak`、
  `region/notes.txt`、`level.dat`、`datapacks/pack.mcmeta`（**无** `r.0.0.mca`、**无** `session.lock`）

