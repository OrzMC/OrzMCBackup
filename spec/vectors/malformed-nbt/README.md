# malformed-nbt

覆盖条款：WOS-6.6（解压成功但内容不是合法 chunk NBT → 安全保留原字节 + 记错误，不得丢弃）

## 输入

- 槽位 0：ZLIB 压缩的 `not-nbt-at-all`（解压没问题，但不是 NBT compound）
- 槽位 1：正常 ZLIB `InhabitedTime=7000`

## 期望

- 两个槽位都保留（槽位 0 的 payload 逐字节一致）
- 这同时覆盖「流式读取时结构损坏」的路径（上游 PHP 在同样输入下也会保留）

