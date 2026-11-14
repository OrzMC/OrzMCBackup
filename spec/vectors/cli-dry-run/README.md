# cli-dry-run

覆盖条款：WOS-9.5（`--dry-run` 不得创建/修改/删除任何输出路径）

## 输入与参数

- `input/` 最小世界（`region/r.0.0.mca` 槽位 0 会被保留）
- 参数：`--dry-run --inhabited-time-seconds 300`

## 期望

- 输出目录 **不得存在**（运行器断言）；输入目录的清单在运行前后必须完全一致
