# overlap-rejected

覆盖条款：WOS-10.1（输入与输出重叠必须被拒绝）、WOS-8.1（merge 同理）

## 输入与参数

- `input/` 最小世界；**OUTPUT 直接指向 INPUT 本身**
- 参数：`--inhabited-time-seconds 300 --strict`（strict 下拒绝即退出码 1）

## 期望

- 退出码 1（在写入前拒绝）
- 输入目录清单在运行前后完全一致（不得被 `--force` 清空）
- 无期望清单：本向量只断言「拒绝 + 不动源数据」
