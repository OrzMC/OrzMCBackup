# compression-matrix

覆盖条款：WOS-2.4（压缩方法码表 RAW=3/GZIP=1/ZLIB=2/LZ4=4）、WOS-6.1（payload 原字节透传）

## 输入

- 槽位 0 RAW、1 GZIP、2 ZLIB、3 LZ4（单个 `LZ4Block`，token `0x10` 未压缩块 + xxHash 校验）
  的 `InhabitedTime` 均为 7000；槽位 4 为 ZLIB 且 `InhabitedTime=0`
- 所有 chunk 的 NBT 内容相同（仅压缩方式不同）

## 期望

- 保留槽位 {0,1,2,3}，删除槽位 4
- 保留槽位的 **payload 必须与输入逐字节一致**（不得重压缩/重编码）
- 这一条同时验证：读得懂四种压缩（否则 7000 读不到 → 被误删）；不重编码（否则哈希变化）

