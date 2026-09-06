# Amadeus Android Server Console

一个基于 Jetpack Compose 的 Android 服务器管理控制台，支持 SSH 连接、受保护的本地管理隧道、Mihomo 节点测速，以及多订阅查看和切换。

## AI 生成说明

本项目的界面、业务代码、构建脚本和文档由 AI 辅助生成，并由项目维护者进行审核、修改和验证。发布版本不包含服务器凭据、SSH 私钥、签名材料或个人配置。

## 构建

需要 Android SDK 35、JDK 17 和 Gradle 8.11.1。将连接参数写入未提交的 `default-config.properties`，然后运行：

```powershell
./build-release.ps1
```

首次使用时在应用设置中导入 SSH 私钥，并确认服务器主机密钥指纹。发布签名材料不在仓库中。

## 依赖与许可证

- Jetpack Compose、AndroidX：Apache License 2.0
- Haze 1.3.1：Apache License 2.0
- JSch（mwiede）：BSD 3-Clause
- Bouncy Castle：MIT License

应用图标素材由项目维护者从萌娘百科获取的《命运石之门》相关图片制作；该素材的原作者、条款和再分发许可需要在发布前由维护者确认。若无法确认，请删除 `app/src/main/res/drawable-nodpi/amadeus_logo.png` 并替换为自有素材。

本项目自身采用 MIT License，见 `LICENSE`。第三方依赖的完整版权和许可证文本以其发行包为准。

## 安全边界

应用通过 SSH 连接服务器，不新增公网管理端口。订阅凭据只由服务器端管理接口保存和返回；请勿把真实订阅地址、私钥、`default-config.properties`、`.signing/` 或构建输出提交到公开仓库。
