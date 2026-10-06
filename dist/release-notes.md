# 服务器控制台 Android v1.4.0

新增“更新”页，集中查看 Mihomo、Nginx、CLIProxyAPI、AstrBot 的服务器版本与 GitHub 更新说明。

- Mihomo、Nginx、CLIProxyAPI：准备计划、逐项确认、备份/失败回退、查询升级结果。
- 计划绑定服务器、主机信任及私钥；提交前保存回执，断线后查询原编号，结果未知时暂停新升级。
- CLIProxyAPI 跨主版本需要兼容性评审；Nginx 沿用原 APT 源及当前索引；AstrBot 仅检查。
- 服务器需由维护端安装工具箱固定更新组件；APK 不自动安装该组件，也不自动升级服务。

Android 8.0+；versionCode 15。沿用 v1.3.0/v1.2.3 的正式签名，公开包不预置私人服务器地址。覆盖安装旨在保留设置、密钥和登录状态；请勿清除应用数据。

验证：Public Release、Lint 无问题、9 项 JVM 测试、APK 包名/版本/空 host/签名连续性、48 文件源码导出一致通过。四项目服务器只读发行信息查询通过。未连接真机，安装、后台回收、断网恢复与真实升级仍待设备验收。

APK SHA-256：`2eb5e5c5360f3a6fd68010eb3930bf8bdd92fa24de3471e095776d99223c3ee1`。

旧 v1.3.0 标签及发行包保留。Android 通常不能直接覆盖安装更低 versionCode；如需恢复旧功能，应使用原签名制作更高 versionCode 回退包，避免卸载清数据。本包附带文档记录构建时的候选验收状态，是否已公开发布以本 Release 状态为准。
