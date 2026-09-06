# dhy 服务器简易控制台

一个基于 Jetpack Compose 的 Android 服务器管理控制台，支持 SSH 连接、受保护的本地管理隧道、Mihomo 节点测速，以及多订阅查看和切换。

## AI 生成说明

本项目的界面、业务代码、构建脚本和文档由 AI 辅助生成，并由项目维护者进行审核、修改和验证。发布版本不包含服务器凭据、SSH 私钥、签名材料或个人配置。

## 构建

需要 Android SDK 35、JDK 17 和已安装的 Gradle 8.11.1（本仓库暂不包含 Gradle Wrapper）。配置 ANDROID_HOME，或者在未提交的 local.properties 中设置 sdk.dir，然后运行：

```powershell
gradle assembleDebug lintDebug
```

输出为 `app/build/outputs/apk/debug/app-debug.apk`。默认不包含服务器地址，在应用设置中填写连接参数、导入 SSH 私钥并核对指纹即可使用。构建过程不会读取维护者的 Windows 工具箱配置。Release 签名材料不在仓库中，自行签名的包不能覆盖维护者签名的安装包。

Android 8.0+，当前源码版本 1.2.2。手机使用悬浮磨砂导航，宽度达到 600dp 时保留侧边导航。

### 可选的服务器扩展

SSH 终端和管理页面隧道使用标准 SSH。API 管理页默认转发服务器回环端口 8317，AstrBot 默认转发 6185，需要自行部署对应服务。
节点测速按钮要求服务器提供 `/usr/local/sbin/mihomo-auto force`；订阅管理要求 `/usr/local/sbin/mihomo-subscriptions` 接受一行 JSON 请求并返回 JSON。它们属于维护者的自定义服务器组件，不是原版 Mihomo 自带命令，也不由本 Android 仓库自动安装。未部署这些扩展时，这两项功能不可用，不影响普通 SSH 功能。

## 依赖与许可证

- Jetpack Compose、AndroidX：Apache License 2.0
- Haze 1.3.1：Apache License 2.0
- JSch（mwiede）：BSD 3-Clause
- Bouncy Castle：MIT License

应用图标 `app/src/main/res/drawable-nodpi/amadeus_logo.png` 据维护者说明来自萌娘百科的《命运石之门》相关图片。此第三方素材不适用本仓库的 MIT 授权，版权归原权利人；来源站点可下载不代表无版权或已取得开源再授权。具体授权尚未核实，复用和再分发时请自行确认，或替换为自有图标。本项目与作品权利方无官方关联。

本项目自有代码采用 MIT License，见 `LICENSE`。第三方依赖保持各自许可证，附带文本见 `licenses/`；核对来源：[AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)、[Haze](https://github.com/chrisbanes/haze/tree/1.3.1)、[JSch](https://github.com/mwiede/jsch/blob/master/LICENSE.txt)、[Bouncy Castle](https://www.bouncycastle.org/licence.html)。

## 安全边界

应用通过 SSH 连接服务器，不新增公网管理端口。订阅凭据只由服务器端管理接口保存和返回；请勿把真实订阅地址、私钥、`default-config.properties`、`.signing/` 或构建输出提交到公开仓库。
