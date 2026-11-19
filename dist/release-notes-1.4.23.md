# dhy 服务器简易控制台 v1.4.23

甲骨文 CPA 控制台现在沿用老 ECS 的接入方式：应用按全局服务器选择使用对应档案的 SSH 私钥和固定主机指纹，建立只到服务器回环 `127.0.0.1:8317` 的隧道，再打开 CLIProxyAPI 原生管理页 `/management.html`。只有页面返回 HTTP 200 后才打开；网页登录时由 CPA 校验 Management Secret，Android 不读取或保存该值。阿里云原有 SSH 私钥和既有功能保持不变。

Oracle `ap-osaka-1` 的管理页已在服务器端单独启用，继续保持 `allow-remote: false`，没有开放公网管理端口。电脑端 SSH 隧道验证了管理页 HTTP 200、管理 API 未认证 401 和密钥认证 200。Android Release、Lint、43 项 JVM 测试、Public 空默认地址和原签名连续性检查通过；真机安装及交互尚待验证。
