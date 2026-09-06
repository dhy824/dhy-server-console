<div align="center">

# dhy 服务器简易控制台

**把常用的服务器操作，放进口袋里。**

连接 SSH · 打开管理面板 · 管理代理订阅

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack_Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![AI](https://img.shields.io/badge/AI-辅助生成-4285F4?style=flat-square)
[![License](https://img.shields.io/badge/License-MIT-64748B?style=flat-square)](LICENSE)

[**下载 Android 安装包**](https://github.com/dhy824/dhy-server-console/releases/latest) · [快速开始](#快速开始) · [自行构建](#自行构建) · [反馈问题](https://github.com/dhy824/dhy-server-console/issues)

</div>

---

一个面向个人服务器的 Android 控制台，把 SSH 终端、管理页面入口和 Mihomo 订阅操作集中在同一个应用里。适合已经有自己的服务器，希望用手机或平板完成日常管理的人。

界面采用 Jetpack Compose 构建：手机上是轻盈的悬浮磨砂底栏，平板上是便于切换的侧边导航。连接参数、私钥和服务器指纹统一在设置中管理。

## 能做什么

| 功能 | 日常用途 |
| :--- | :--- |
| **SSH 终端** | 连接自己的服务器，执行命令、查看输出 |
| **管理面板入口** | 通过 SSH 本地隧道访问 API 管理页与 AstrBot 面板 |
| **订阅管理**¹ | 查看当前订阅与上次检测结果，新增、修改或手动切换订阅 |
| **网络模式**¹ | 查看 DNS 状态，切换直连或恢复自动模式 |
| **节点测速**¹ | 调用服务端测速流程，为 OpenAI 策略组选取节点 |
| **本地连接设置** | 导入 SSH 私钥，使用 Android Keystore 加密保存，并核对服务器指纹 |

¹ 订阅、网络模式与测速功能需要配套的自定义服务端脚本，详见[服务端扩展](#服务端扩展)。本仓库目前发布 Android 客户端。

## 为手机和大屏分别设计

- **手机：悬浮磨砂底栏。** 总览、终端、订阅、设置四个入口，配合紧凑的图标和文字布局。
- **平板：保留侧边导航。** 可用宽度达到 600dp 时切换布局，适应大屏操作。
- **顶部：减少多余留白。** 根据显示密度调整间距和图标尺寸，同时保留系统状态栏的安全区域。

## 快速开始

1. 前往 [Releases](https://github.com/dhy824/dhy-server-console/releases/latest)，下载并安装 `.apk` 文件。支持 **Android 8.0 及以上**。
2. 打开「设置」，填写服务器地址、SSH 用户名和端口，导入自己的 SSH 私钥。
3. 点击「测试连接并核对指纹」，与可信来源的服务器指纹核对后确认信任。
4. 进入「终端」执行命令，或在「总览」打开已部署的管理面板。

公开安装包不预置私人服务器地址、订阅链接或密钥。首次使用需要自行配置；服务端尚未部署的功能不会由客户端自动安装。

### 管理页面连接方式

客户端通过 SSH 将手机本地端口转发到服务器回环地址，再打开对应页面。使用这些入口无需额外开放公网管理端口。

| 页面 | 手机本地默认端口 | 服务器目标 |
| :--- | :--- | :--- |
| API 管理页 | `18317` | `127.0.0.1:8317` |
| AstrBot | `16185` | `127.0.0.1:6185` |

手机本地端口可在设置中修改。服务器侧需要先部署相应服务；当前版本的服务器目标端口在客户端代码中指定。

### 服务端扩展

普通 SSH 连接使用标准 SSH 服务。以下功能还依赖维护者自定义的服务器组件：

| 扩展功能 | 服务端要求 |
| :--- | :--- |
| 节点测速 | 提供 `/usr/local/sbin/mihomo-auto force` 命令 |
| 订阅及网络模式管理 | 提供 `/usr/local/sbin/mihomo-subscriptions`，接受一行 JSON 请求并返回 JSON |

这些脚本**不是原版 Mihomo 自带命令，也不包含在本仓库中**。订阅故障切换、全部不可用时直连和 DNS 处理由配套服务端实现；客户端负责显示状态和发起操作。没有这些扩展时，仍可使用普通 SSH 功能。

订阅的可用性显示的是**上次检测结果**，可以手动重新检测；新增或修改地址后，需要点击「切换使用」应用。

## 自行构建

准备 **JDK 17、Android SDK 35、Gradle 8.11.1**。本仓库暂未包含 Gradle Wrapper，需要自行安装 Gradle。

配置 `ANDROID_HOME`，或在项目根目录创建不提交到 Git 的 `local.properties`，设置 `sdk.dir`。随后运行：

```shell
gradle assembleDebug lintDebug
```

调试安装包输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前源码版本为 **1.2.2（versionCode 12）**。默认构建不含服务器地址，也不会读取维护者的 Windows 工具箱配置。Release 签名材料不在仓库中；自行签名的安装包无法覆盖使用维护者签名的已安装版本。

## AI 生成说明

本项目的界面、业务代码、构建脚本和文档由 **AI 辅助生成**，维护者参与需求定义、审核、修改和验证。欢迎通过 [Issues](https://github.com/dhy824/dhy-server-console/issues) 反馈使用问题，提供应用版本、Android 版本和复现步骤即可；请先移除截图或输出中的私人连接信息。

## 许可证与素材

项目自有代码采用 [MIT License](LICENSE)，第三方依赖遵循各自的许可证，完整文本见 [licenses/](licenses/)。上方 MIT 标识仅适用于本项目自有代码。

| 组件 | 许可证 |
| :--- | :--- |
| Jetpack Compose / AndroidX | Apache License 2.0 |
| Haze 1.3.1 | Apache License 2.0 |
| JSch（mwiede） | BSD 3-Clause |
| Bouncy Castle | MIT License |

许可证来源：[AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) · [Haze](https://github.com/chrisbanes/haze/tree/1.3.1) · [JSch](https://github.com/mwiede/jsch/blob/master/LICENSE.txt) · [Bouncy Castle](https://www.bouncycastle.org/licence.html)。Release 同时提供许可说明压缩包与 SHA-256 校验文件。

<details>
<summary><strong>第三方图标说明</strong></summary>

应用图标 `app/src/main/res/drawable-nodpi/amadeus_logo.png` 据维护者说明来自萌娘百科的《命运石之门》相关图片。该素材不适用本仓库的 MIT 授权，版权归原权利人；可以从来源站点下载不代表无版权或已取得开源再授权。具体授权尚未核实，复用或再分发前请自行确认，或替换为自有图标。本项目与作品权利方无官方关联。

</details>

<details>
<summary><strong>公开提交时的配置边界</strong></summary>

请勿提交真实订阅地址、SSH 私钥、个人连接配置、`default-config.properties` 或 `.signing/`。签名材料仅保留在本地；公开发布安装包前，应确认未嵌入私人配置。订阅凭据通过服务端接口管理，分享页面截图时也应检查是否包含完整链接。

</details>
