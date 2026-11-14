# corrupt-chunk

覆盖条款：WOS-6.6（无法解析的 chunk 保留原字节，记录错误但不删除）

## 输入

- 槽位 0：压缩方法 `9`（未定义）→ 解压必然失败，无法读到 `InhabitedTime`
- 槽位 1：正常 ZLIB，`InhabitedTime=7000`

## 期望

- 两个槽位都保留；槽位 0 的 payload 与输入**逐字节一致**（不得被丢弃，也不得被改写）

