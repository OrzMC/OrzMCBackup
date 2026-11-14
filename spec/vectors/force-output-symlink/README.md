# force-output-symlink

覆盖条款：WOS-10.3（`--force` 清空输出目录时不得跟随符号链接，避免删掉链接目标）

## 输入与运行器准备

- `input/` 一个最小世界（槽位 0 会被保留）
- 运行器在运行前创建：`out/junk.dat`、`out/link -> victim/`、`victim/data.txt`
- 参数：`--inhabited-time-seconds 300 --force`（输出非空，必须 force）

## 期望

- 输出目录被重建为处理后世界：只有 `region/r.0.0.mca` + `level.dat`（junk 与 link 都应消失）
- **`victim/data.txt` 必须仍存在**（删除操作只删链接本身，不进入目标）

