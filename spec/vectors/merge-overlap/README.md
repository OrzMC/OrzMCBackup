# merge-overlap

覆盖条款：WOS-8.1（base/patch/output 三者互不重叠，拒绝先于任何写入）

## 输入与参数

- `input/base`、`input/patch` 各含一个 region；**OUTPUT 直接指向 `input/base`**

## 期望

- 退出码 1；base 与 patch 的清单在运行前后完全一致（不得被 `--force` 清空）
