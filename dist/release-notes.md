审核中心自动刷新适配（1.4.7 / versionCode22）。

- Android 进入首页“总览”时自动读取服务器审核批次；“刷新审核批次”按钮仍可手动重读。
- 与 Windows 控制台保持一致，服务器审核记录仍沿原 SSH/Dashboard 通道，手机和电脑共用保存结果。
- 沿用原包名、原签名、私钥/主机信任、WebView 登录和 Public 空默认值；不上传个人配置或凭据。
- Release、Lint、JVM、Public 空默认值、APK 签名连续性和 SHA-256 校验通过。
- 手机真机安装与网络交互尚未验证；旧 v1.4.6 保留。回退须沿原签名使用更高 versionCode，不卸载清数据。
