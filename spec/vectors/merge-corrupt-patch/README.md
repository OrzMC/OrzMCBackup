# merge-corrupt-patch

覆盖条款：WOS-8.6（patch 无法读取时整文件回退 base）

## 输入

- `base/region/r.0.0.mca` 槽位 {0,1}、`base/entities/r.0.0.mca` 槽位 {0}
- `patch/region/r.0.0.mca` 为损坏文件（< 8 KiB）；`patch/level.dat` 正常（较新）

## 期望

- patch 不可读时按 WOS-8.6 回退 base：输出 `region/r.0.0.mca` 的槽位即 base 的 {0,1}
  （payload 与时间戳取自 base 文件，`entities` 同理）；`level.dat` 仍取 patch 版本
- 若实现改成保留损坏的 patch 文件，本向量会红（与 WOS-8.6 冲突，应改规范而不是改向量）

