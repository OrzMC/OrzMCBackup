# mcc-external

覆盖条款：WOS-2.5（外部存储区块不做内联解压、字节原样保留）、WOS-5.3（默认保留）、
WOS-7.3（`region/` 下的 `.mcc` 属杂项，必须随输出复制）

## 输入

- 槽位 0：外部存储（method `-126`，无内联数据）；槽位 1：ZLIB 且 `InhabitedTime=0`
- `region/r.0.0.mcc`：外部数据文件

## 期望

- 保留槽位 {0}（`removeUnknown=false`）；槽位 1 被阈值删除
- `region/r.0.0.mcc` **原样复制**（否则恢复世界时外部区块内容缺失）

