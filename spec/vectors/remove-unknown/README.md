# remove-unknown

覆盖条款：WOS-5.2（`removeUnknown` 只影响未知/外部区块）、WOS-5.3（外部存储按开关处置）

## 输入

- 槽位 0 ZLIB `InhabitedTime=0`、槽位 1 外部存储（method `-126`，无内联数据）、
  槽位 2 ZLIB `InhabitedTime=7000`
- 参数：`--inhabited-time-seconds 300 --remove-unknown`

## 期望

- 只保留槽位 {2}：槽位 0 被阈值删除，槽位 1 因 `--remove-unknown` 被删除
- 对照：`decision-inhabited` 向量证明**不带**该开关时外部区块必须保留

