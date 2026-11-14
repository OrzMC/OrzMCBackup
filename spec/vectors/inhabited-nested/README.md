# inhabited-nested

覆盖条款：WOS-3.1（`InhabitedTime` 在 chunk 根；≤1.17 为 `root.Level`）、
WOS-3.4（只接受根层或 `root.Level` 的同名 tag，其它嵌套必须忽略；无法解析时安全保留）

## 输入

- 槽位 0：`structures` 子 compound 里先出现一个 **诱饵** `InhabitedTime=999999`，
  真正的根层 `InhabitedTime=0` 在其后
- 槽位 1：旧版布局 `Level.InhabitedTime=7000`

## 期望

- 只保留槽位 {1}：槽位 0 的根层值为 0（诱饵必须被忽略）；槽位 1 走 `Level` 兼容路径
- 反证：纯字节扫描会把槽位 0 判为 999999（保留）→ 本向量变红

