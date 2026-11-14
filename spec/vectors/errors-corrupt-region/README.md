# errors-corrupt-region

覆盖条款：WOS-6.7（无法解析的 region 必须原样保留）、WOS-9.3（记录错误）、WOS-9.4（非 strict 退出码 0）

## 输入

- `region/r.0.0.mca` 槽位 0（`InhabitedTime=0`）→ 正常流程删除，输出无该文件
- `region/r.0.1.mca` 与 `entities/r.0.1.mca` 为 **损坏文件**（长度 < 8 KiB）→ 无法解析

## 期望

- 两个损坏文件 **原样出现在输出**（字节完全一致）——备份不得因为解析失败而丢数据
- 合法但被裁剪的 `region/r.0.0.mca` 不生成；`level.dat` 复制
- 错误被记录（`kind` 属 `MCA`），非 strict 下退出码仍为 0

