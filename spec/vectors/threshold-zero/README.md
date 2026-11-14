# threshold-zero

覆盖条款：WOS-3.2（严格 `>`；`threshold = 0` 只移除 `InhabitedTime == 0` 的区块）

## 输入

- 槽位 0：`InhabitedTime=0`、槽位 1：`=1`、槽位 2：`=0`
- 参数：`--inhabited-time-seconds 0`

## 期望

- 只保留槽位 {1}；槽位 0 与 2 被删除（0 不大于 0）

