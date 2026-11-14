# decompress-bomb

覆盖条款：WOS-10.2（解压总量上限；超限走安全保留路径，不 OOM、不丢数据）

## 输入

- 槽位 0：ZLIB 压缩的 65 MiB 零字节（压缩后约 64 KiB，未超 8 MiB 压缩上限）
- 槽位 1：正常 ZLIB `InhabitedTime=7000`

## 期望

- 两个槽位都保留：槽位 0 因无法安全解压（超上限或读不到 `InhabitedTime`）而安全保留原字节

