# report-fields

覆盖条款：WOS-9.1（`processedChunks` = 访问过的区块总数，保留数 = processed − removed）、
WOS-9.2（报告字段语义跨实现一致）

## 输入

- 4 个区块：槽位 0/2 的 `InhabitedTime=0`（删除），槽位 1/3 为 7000（保留）

## 期望

- `processedChunks=4`、`removedChunks=2`、无错误
- 输出保留槽位 {1,3}
- 只比较规范声明的字段；`keptChunks`/`beforeSize`/`durationMs` 等属实现定义（WOS-11）

