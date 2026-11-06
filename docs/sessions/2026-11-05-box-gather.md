# 长方体采集

core.gather 使用 from/to 两个包含端点的角点，可用 blocks 精确注册 ID 列表筛选，省略则采集非空气且可破坏方块。allow_other_blocks 默认 false，用户确认 true 时可以清理沿途障碍。按轮重扫并重新尝试，直到无进展、达到可选上限或超时。去掉血量、支撑面、涉水和落差等脚本判断。Bridge 的 dig 增加 safety_checks 开关，原版距离、动作权限及服务端裁决仍有效。保留背包增量、未知范围和剩余目标回报。

Bridge 离线构建通过，755 项 smoke 通过。已备份并部署协议 1.16 的测试 jar，SHA-256 为 9FD7031AECB0366639C09CBF35509D1D171C3D1EBEFA6968B9EAF498C8EB4F1E。真机待验。


最终筛选契约已修正：blocks 必填，缺失/空值拒绝；仅显式字符串 "all" 允许采集全部方块。以配套 Whale 的最终实现和 README 为准。
