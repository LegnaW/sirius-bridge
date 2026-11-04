# NeoForge 加载前置声明

已发布的 v0.1.0 缺少 Baritone 必需依赖。官方 NeoForge API jar 的模组 ID 是 `baritoe`，版本为 1.11.2。将它声明为客户端必需依赖，并在 Sirius Bridge 前加载。保留 0.1.0 与协议 1.12。

验收包括 Gradle build、smokeTest、检查最终 jar 内的依赖段及测试实例的 Baritone 元数据、核对发布与测试实例的 jar 哈希。

构建通过，747 项 smoke 全部通过。已检查打包后的依赖 ID、版本范围、加载顺序和客户端范围。测试实例已有官方 API jar。加载器启动与缺少前置时的提示待真机确认。
