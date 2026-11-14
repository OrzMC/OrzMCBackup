# decision-inhabited

覆盖条款：WOS-3.1, WOS-3.2, WOS-3.3, WOS-5.3

## 输入

- `input/` 是一个最小世界（仅 `region/r.0.0.mca` + `level.dat`）
- chunk payload 是最小 NBT（仅 `InhabitedTime`，或空 compound），ZLIB 压缩

## 期望

- 参数：`--inhabited-time-seconds 300`
- 保留槽位：[3, 4, 5]（其余删除；严格 `>`、缺失 tag 保留、外部压缩保留）
- 保留槽位 payload 与时间戳必须与输入逐字节一致，杂项文件必须原样复制

期望清单由本目录的生成脚本 `spec/tools/make_vectors.py` 按规范手工推导（不运行实现）。
