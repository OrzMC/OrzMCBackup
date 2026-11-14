# forceload-forced

覆盖条款：WOS-4.1, WOS-4.2, WOS-4.3, WOS-4.5

## 输入

- `input/` 是一个最小世界（仅 `region/r.0.0.mca` + `level.dat`，以及 `data/chunks.dat`）
- chunk payload 是最小 NBT（仅 `InhabitedTime`，或空 compound），ZLIB 压缩
- `data/chunks.dat` 的 `data.Forced` 含 **3 个 packed long**（奇数项）＝强制加载坐标 [(5, 0), (1, 2), (10, 20)]
- packed 规则：vanilla `ChunkPos.toLong(x,z)` = x 低 32 位、z 高 32 位、**每 long 一个 chunk**

## 期望

- 参数：`--inhabited-time-seconds 1000000`
- 保留槽位：[5, 65, 650]（其余删除；严格 `>`、缺失 tag 保留、外部压缩保留）
- 保留槽位 payload 与时间戳必须与输入逐字节一致，杂项文件必须原样复制

期望清单由本目录的生成脚本 `spec/tools/make_vectors.py` 按规范手工推导（不运行实现）。
