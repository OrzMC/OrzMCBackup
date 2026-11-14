# custom-compression

覆盖条款：WOS-2.6（`CUSTOM(127)` 压缩无法解压 → 必须安全保留，不得视为可删除）

## 输入

- 槽位 0：method `127` + 自定义方法名 `test:custom` + 不透明数据
- 槽位 1：正常 ZLIB 且 `InhabitedTime=0`（应被删除）

## 期望

- 保留槽位 {0}（payload 逐字节一致，含 2 字节方法名长度与名称）；槽位 1 删除

