# world-26-1

覆盖条款：WOS-7.1（递归发现 `dimensions/` 维度）、WOS-7.2（26.1+ 世界级杂项）、
WOS-4.1/4.2（`chunk_tickets.dat` 的 `tickets[].chunk_pos` 与 `minecraft:forced`）

## 输入

- `dimensions/minecraft/overworld/region/r.0.0.mca` 槽位 {0, 33}（`InhabitedTime=0`）
- `dimensions/minecraft/overworld/data/minecraft/chunk_tickets.dat` 强制加载 (1,1) → 槽位 33
- `dimensions/minecraft/the_nether/region/r.0.0.mca` 槽位 5（无强制加载）
- 世界级：`level.dat`、`players/uuid.dat`（必须复制，位于维度祖先目录）

## 期望

- overworld：只保留槽位 33（强制加载）；the_nether：全部删除 → **不生成 region 文件**（WOS-6.4）
- `level.dat` 与 `players/uuid.dat` 复制；`chunk_tickets.dat` 作为维度内杂项复制

