# Android 服务器控制台

维护版本 1.3.0（versionCode 14），Jetpack Compose + SSH。支持 API/AstrBot 回环隧道、WebView、单命令终端与 Mihomo 管理。第一次使用需填写连接设置、导入自己的 SSH 私钥并核实服务器主机密钥。

## 唯一源码与目录

本目录是唯一编辑源；opensource/amadeus-android-server-console 由 export-public-source.py 白名单导出，不能两处手工改。opensource/amadeus-console-release-123 是冻结的 1.2.3 历史快照。1.3.0 的公开源码已同步到 GitHub，正式 APK 发布在 [GitHub Release v1.3.0](https://github.com/dhy824/dhy-server-console/releases/tag/v1.3.0)。本轮仍未安装手机或做真机交互验收。

| 入口 | 职责 |
| --- | --- |
| data/AppConfig.kt | 配置存储；每次 current 重新读取，Service/VM 不持有过期配置 |
| data/SecureKeyStore.kt | 私钥安全存储、有界读取、密文修订指纹 |
| ssh/ConnectionIdentity.kt | 主机、端口、用户名、信任与私钥版本决定复用 |
| ssh/BoundedOutput.kt / SshEngine.kt | 有界输出、单调时钟超时、取消与超限失败 |
| service/TunnelService.kt | 单线程连接管理、请求回执、五秒存活/配置检查 |
| AppViewModel.kt | 用户动作与请求回执关联；更改设置时停止旧连接 |
| Test-Apk.ps1 | APK 本体包名/版本/公开默认值/签名验收 |
| Build-State.ps1 | 构建/发布共用互斥、配置与环境恢复、临时目录及盘符归属校验 |
| tests/Test-Build-State.ps1 | 合成目录中的构建失败、空文件、并发与清理回归；无需手机或签名 |

隧道关闭后不会残留“运行中”状态；断开后提示重新打开，不做无界重连。持续输出不会不断积累内存；订阅 JSON 超限报错，不返回截断内容伪装成成功。修改连接相关代码，应连同 app/src/test 中的契约测试维护。

## 本机构建

需要 .toolchain 中的 SDK 35、JDK 17、Gradle 8.11.1；现有工具链可直接复用，缺少时先阅读 prepare-toolchain.ps1。本目录执行：

~~~powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File build-debug.ps1 -Mode Public
powershell.exe -NoProfile -ExecutionPolicy Bypass -File build-release.ps1 -Mode Public
~~~

脚本执行 assemble、lint 和 testDebugUnitTest，随后核验 APK 本体。中文路径通过临时 subst 盘构建，aapt 验收用临时 ASCII 路径。Public 是默认模式，构建前明确写入空 host，不读取本机个人连接设置。Personal 显式读取桌面配置，生成的个人 APK 不得公开分享。构建结束恢复原 default-config.properties。

构建与发行打包使用同一源码目录互斥锁，另一个进程并发执行会明确失败。成功或异常退出都恢复 default-config.properties、local.properties 的原始字节及 JAVA_HOME/ANDROID_SDK_ROOT/ANDROID_HOME；原先不存在的临时配置会移除，空文件仍保持为空。只移除本次成功创建且仍指向同一项目的 subst 盘；盘符被改指向时拒绝清理，并报告待处理项。系统强杀或断电无法保证 finally 执行，恢复后先检查这些项。

盘符归属使用 Unicode Win32 API 核验，避免中文路径被本地化命令输出误解码。Gradle 合并接收标准输出和错误输出，并以退出码判定构建成败；警告不会被 PowerShell 5 当作脚本异常提前终止，非零退出仍失败。

只验证构建脚本契约，无需运行 Gradle：

~~~powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/Test-Build-State.ps1
~~~

更改 Build-State 时需连同根 release-public-apk.ps1、Test-Apk.ps1 和白名单导出检查。回滚这组脚本须保持同一版本，不能只移除共享 helper。

Release 只能使用已有 .signing 中的一对签名文件。缺少任一个立即失败，**不会自动生成新签名**。恢复时从用户已有备份找回原文件；不得为“修构建”换钥。验收与工作区 release-public/v1.2.3 原正式包比较签名连续性。公开贡献者无签名时只构建 Debug，不能冒充正式发布。

## 公开源码与本地正式产物

工作区根目录执行：

~~~powershell
python -X utf8 Amadeus安卓端/export-public-source.py
python -X utf8 Amadeus安卓端/export-public-source.py --check
powershell.exe -NoProfile -ExecutionPolicy Bypass -File release-public-apk.ps1
~~~

已有刚构建的 Release 可用 -UseExistingBuild，但仍必须通过 APK 本体验收。公开发布写入新的 release-public/v版本/，包含 APK、SHA256SUMS、验收 JSON 和许可证。既有版本目录拒绝覆盖；没有自动上传。源码导出仅复制登记的代码/资源/许可证/说明，不带 .signing、toolchain、默认个人配置、local.properties 或生成产物，修改前副本保存在 output/source-export-backups。

验收读取 APK 中的所有 default_host 值，而非旁边生成的 XML；校验签名、包名、版本和非调试标志通过后才晋升正式目录。构建输入与验收结果应随该次实施报告留档。

## 验证范围与回滚

1.3.0 已完成真实构建、Lint、3 项 JVM 契约测试、APK 空 host 与旧签名连续性检查，并已发布公开源码和正式 APK。尚无真机安装、系统杀后台、网络切换、服务器连接交互的实测，不将单测当作这些场景的证明。

2026-09-30 构建事务补验：8 项合成状态回归通过，覆盖成功/失败恢复、空文件、中文路径盘符、外来映射、嵌套锁、跨进程并发与目录清理边界。中文工作区内 Public Debug 实际构建通过，构建前后两个配置文件及三个环境变量一致；已有 1.3.0 正式发行目录保留。

源码回滚使用同次备份；设备回滚 Android 通常不能覆盖安装低 versionCode，需要用户自行决定保留数据、卸载或发布更高 versionCode 的回退构建。本工具不会自动卸载、清数据或更换签名。旧 APK 与签名备份必须保留。

## 依赖与隐私

自身 MIT；Compose/AndroidX/Haze 为 Apache 2.0，JSch 为 BSD 3-Clause，Bouncy Castle 为 MIT，详见 LICENSE/licenses 与各发行包。项目由 AI 辅助维护并接受人工审核。

不新增公网管理端口；不公开真实服务器地址、用户名、私钥路径、订阅凭据与日志。构建日志仅在本机留存，分享前脱敏。
