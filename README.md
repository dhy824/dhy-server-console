# 2026-10-22 审核中心自动刷新适配 1.4.7（发布以本批成功回执为准）

当前维护源码1.4.7/versionCode22。审核模块已移到首页“总览”，进入总览时自动刷新服务器批次，仍可手动刷新；底栏恢复总览/终端/订阅/更新/设置五项；刷新审核批次与打开审核中心按钮各至少48dp、间距14dp，旧审核页号恢复为总览。审核在原SSH/Dashboard中完成，服务器需配套1.28.13修复继续审核后列表未隐藏的问题；此网页修复也适用于Windows，无需Windows改版。系统浏览器打开审核时继续识别AstrBot通道。

原签名、包名、连接/私钥/主机信任/WebView登录保持，Public默认空host。Release/Lint/JVM/Public与原签名构建通过；真实桥接iframe触摸/保存下一题通过，正式发行与真机分别以本批回执为准。更新包生成后可沿原签名覆盖安装，不卸载、不清数据。服务器回退软件保留最新评分/业务库；Android回退用原签名更高versionCode并保留新旧密文读取。

# 2026-10-17 通用审核中心 1.4.5

当前维护源码1.4.5/versionCode20。新增“审核”页，显示服务器批次、已审/待审和最近保存时间；经现有SSH通道在应用内打开插件审核中心，手机和电脑共用保存记录。评分支持备注、标签及可选分数，多端冲突会提示处理；审核只记录意见，不直接应用人格或执行运维。

沿原包名、原签名和Keystore读写方式，保留用户连接、密钥、主机信任和WebView登录。Public构建默认空host；不内嵌题目、评分或凭据。唯一源码仍是本目录，公开导出沿export-public-source.py；服务器数据/实现不进入公开APK源码。版本回退用原签名更高versionCode并保留新旧密文读取能力，不卸载清数据。构建/Lint/JVM/签名已过，已由Actions39009697514正式发布v1.4.5；标签2168a62fd2a713604729a6d8124587c0479148f4及实际下载四附件逐项核对通过。APK SHA256为fb2d36119751273e65d5b40490cf2c8891cdecc934a716afd9fbb45b5f15b474，手机真机交互仍待验证。

# 2026-10-14 本机 Keystore 恢复修复 1.4.4

当前维护版本1.4.4/versionCode19，沿原签名覆盖安装；已通过GitHub Actions38496112672正式发布，实际下载四附件与标签绑定均核验通过；APK SHA256为837db6905ead152e4667d4ba03825c0650c09685d1ef472bb6af82678874117e。正式发行入口为 https://github.com/dhy824/dhy-server-console/releases/tag/v1.4.4 。本次修复截图中的KeyStoreException：读取旧密文时只查已有Android Keystore密钥，不再因密钥缺失生成错误的新密钥；本机密钥读取错误与SSH认证、主机校验错误分别提示。系统明确允许即时重试的临时故障最多重试三次；永久失效提示重新导入，不循环建通道。

若更新后仍提示密钥失效或无法使用，先解锁或重启手机再试；仍失败时打开“设置 → 更换私钥”，选择原来连接服务器的SSH私钥文件（不是.pub公钥）。重新导入用新的应用专属AES密钥，先实际加解密验证，再原子保存，并保留旧加密备份和原AES别名。无需清除应用数据、更换服务器SSH密钥或重新填写所有设置。旧系统AES密钥确实丢失时，更新无法从密文恢复私钥，必须有原SSH私钥文件；若手机Keystore服务仍无法创建或使用新密钥，应用会明确失败并保留原数据，不改用明文存储。PlayIntegrityFix是否导致此次故障尚未证实，本应用不会更改它或系统设置。

Release、Lint、26项JVM测试及APK空host/非调试/原签名连续性通过。新增11项实际AES/GCM测试覆盖旧格式、缺失密钥、临时/永久故障、显式重新导入、坏密文与提交异常；JVM测试不代表小米14 Ultra/Android16硬件Keystore或手机网络已验收。手机/平板布局、Cookie和服务器严格主机校验保持既有方式。

旧v1.4.3正式发行及签名保留；原加密备份位于应用私有ssh_identity.previous.enc，仅显式更换私钥时产生，不上传。回退不卸载、不清数据；Android通常不能直接覆盖较低versionCode，应沿原签名构建更高versionCode的回退包，并保留本版新旧密文读取能力，不能用旧版读不懂的新格式覆盖恢复。发布与下载核验回执见维护工作区output/keystore-version-repair-20261014；未获得成功回执前不能称GitHub发布完成。

# 2026-10-14 隧道健康与错误提示 1.4.3（已正式发布）

当前源码和原签名正式包为1.4.3/versionCode18，已通过GitHub Actions38436101635正式发布：[APK与附件](https://github.com/dhy824/dhy-server-console/releases/tag/v1.4.3)。实际下载的APK/许可证附件逐字节一致，校验清单和JSON按内容核验一致。旧1.4.2保留。已有通道在复用前和五秒状态检查中探测固定回环页面；页面不响应时不再显示运行中，用户再次打开时仅重建一次，并恢复原已打开的两个转发。新通道通过页面检查才显示成功。关闭/网络失败不进行无界自动重连，保持现有密钥和严格主机校验。

错误提示包含失败阶段以及安全的异常类型，区分认证、主机密钥、超时、DNS、连接拒绝和端口占用；不展示原始库错误中的地址、用户名或密钥路径。SSH连接失败时清理未返回的会话。内置页面等待预算35秒，覆盖一次重连与有界页面检查。HTTP检查只访问127.0.0.1根路径，不跟随重定向、不读正文、不携带登录信息、不走系统代理；200/401/302视为可达，超时或5xx失败。

Release/Lint和15项JVM测试通过，公开空host/非调试/原签名连续性通过。同版本JSch与相同移动端KEX/cipher在本机连接当前服务器，两个转发均HTTP200；这仅证明本机网络下兼容，手机网络、安装与错误是否消失尚未验证。APK见output/tunnel-repair-20261014/服务器控制台1.4.3-candidate.apk。手机和平板布局、Cookie和设置存储保持既有方式。

本批已取得用户发布确认。旧正式v1.4.2及签名保留；回退保留旧APK和源码，不清除应用数据、不换签名。Android通常不允许低versionCode直接覆盖，真机若需回退应由维护端用旧源码构建更高versionCode并沿原签名发布，不建议卸载清数据。本批沿唯一手册及context_sync原生全文验收收尾。

# 1.4.2 蒸馏进度入口（已发布）

版本1.4.2/versionCode17：总览新增“查看蒸馏进度”，复用已有AstrBot SSH隧道、应用内WebView和系统浏览器，登录后直达Null/仔仔同一个蒸馏中心。服务器1.28.0提供双人看板；不在手机运行分析或保存聊天记录。已有SSH配置、密钥和登录状态沿原应用保留；使用原签名连续构建，旧v1.4.1发行保留。应用不创建分析任务或应用Persona；手机实际覆盖安装与交互仍待真机验收。

# Android 服务器控制台

维护版本 1.4.5（versionCode 20），Jetpack Compose + SSH。支持 API/AstrBot 回环隧道、WebView、单命令终端、Mihomo 管理与项目更新。第一次使用需填写连接设置、导入自己的 SSH 私钥并核实服务器主机密钥。

1.4.1 已于 2026-10-08 经用户确认发布：[正式 APK 与附件](https://github.com/dhy824/dhy-server-console/releases/tag/v1.4.1)。Actions 37704959564 成功，实际下载 APK 摘要与本地一致。更新页复用首页的磨砂白卡、蓝色图标、22dp 圆角与间距；版本并排展示，窄屏改为纵排，说明与任务编号按需展开。查询原任务可显示下载阶段、已下载 MiB 与具体失败原因；不显示未经核验的完成百分比，也不将下载失败误称回退成功。

## 唯一源码与目录

本目录是唯一编辑源；opensource/amadeus-android-server-console 由 export-public-source.py 白名单导出，不能两处手工改。opensource/amadeus-console-release-123 是冻结的 1.2.3 历史快照。1.4.2已正式发布：[APK与附件](https://github.com/dhy824/dhy-server-console/releases/tag/v1.4.2)，本批已验证手机393dp、320dp/1.5倍字体和800dp平板实际Compose布局；Release/Lint/12项JVM及空host/非调试/签名连续性通过。真机安装与连接交互未测。此前 1.4.0 已于 2026-10-06 通过 GitHub Actions 发布：[正式 APK 与附件](https://github.com/dhy824/dhy-server-console/releases/tag/v1.4.0)。旧 v1.3.0 保留。真机安装及交互仍待验收。

| 入口 | 职责 |
| --- | --- |
| data/AppConfig.kt | 配置存储；每次 current 重新读取，Service/VM 不持有过期配置 |
| data/SecureKeyStore.kt | 私钥安全存储、有界读取、密文修订指纹 |
| ssh/ConnectionIdentity.kt | 主机、端口、用户名、信任与私钥版本决定复用 |
| ssh/BoundedOutput.kt / SshEngine.kt | 有界输出、单调时钟超时、取消与超限失败 |
| service/TunnelService.kt | 单线程连接管理、请求回执、五秒存活/配置检查 |
| updates/UpdateProtocol.kt / UpdatesViewModel.kt / ui/UpdatesScreen.kt | 项目版本、逐项升级确认、原任务回执恢复 |
| AppViewModel.kt | 用户动作与请求回执关联；更改设置时停止旧连接 |
| Test-Apk.ps1 | APK 本体包名/版本/公开默认值/签名验收 |
| Build-State.ps1 | 构建/发布共用互斥、配置与环境恢复、临时目录及盘符归属校验 |
| tests/Test-Build-State.ps1 | 合成目录中的构建失败、空文件、并发与清理回归；无需手机或签名 |

隧道关闭后不会残留“运行中”状态；断开后提示重新打开，不做无界重连。持续输出不会不断积累内存；订阅 JSON 超限报错，不返回截断内容伪装成成功。修改连接相关代码，应连同 app/src/test 中的契约测试维护。

## 项目更新（1.4.1）

“更新”页提供 Mihomo、Nginx、CLIProxyAPI、AstrBot 的已安装版本、GitHub 版本与发布说明。先在“设置”导入管理员 SSH 私钥并核对主机指纹，再点“检查所有项目”。来源固定为 MetaCubeX/mihomo、nginx/nginx、router-for-me/CLIProxyAPI、AstrBotDevs/AstrBot；GitHub 由服务器访问，失败显示未核验，可打开官方发布页，不沿用旧检查结果。

服务器前提是已由维护端审阅安装 `/usr/local/lib/server-toolbox/project_updates.py`，客户端固定执行 `/usr/bin/python3 -B /usr/local/lib/server-toolbox/project_updates.py`，通过标准输入发送 JSON。此应用不自动安装服务器组件；组件不可用时检查/准备失败。服务端策略唯一源码位于运维工作区 `个人服务器工具箱/server/project_updates.py`，手机仅实现既有 release、inspect、plan、execute、result 契约。`assets/update_probe.py` 是工具箱同名只读探针的发行快照；更新探针时从唯一源同步并核验一致，不在手机另写升级策略。

Mihomo、Nginx、CLIProxyAPI 按 inspect → plan → 用户确认 → execute → result 执行；确认对话框列明实际版本、影响、备份编号和回退方式。计划限时 5 分钟，绑定连接、固定主机密钥及私钥修订；服务器重新核对程序、配置、服务和目标版本。确认只消费一次，提交前将任务编号、计划摘要和连接指纹原子写入应用私有 no-backup 目录。退出/断网不会自动重发或取消服务器任务；重开应用恢复编号，点“查询上次升级结果”。没有批量升级或自动更新。

- Mihomo：官方包摘要、候选配置、备份、代理与控制器健康检查，失败尝试恢复旧程序。
- Nginx：GitHub 最近 100 个标签仅作参考，可能含 mainline；安装走原 APT 源和当前索引，不刷新索引，不切软件源，先保存原版本包与配置，再安装并做 nginx/HTTP 验收，失败尝试恢复。
- CLIProxyAPI：同主版本升级才允许准备计划，跨主版本需单独兼容性评审；只替换程序，OAuth 数据原位保留。
- AstrBot：仅检查；带 beta 等标记的版本明确显示预览，核心升级继续走既有维护流程。

结果为 queued/running/not_found/needs_attention 或响应不可核验时，阻止新升级，继续查询同一编号。not_found 不证明请求从未到达：联系维护端核对原编号及服务状态，不清应用数据绕过门禁。回执损坏或连接不符也拒绝新提交；恢复原连接/原回执后再查。回执只保存标识，不保存私钥、配置正文或完整计划。更换手机不会迁移 no-backup 回执，必须先在原设备或维护端核对未完成任务。

服务器 helper 1.0.1 提供阶段、下载字节与生产修改标记：大包 60 秒读取、900 秒总预算，网络失败最多回退已有 Mihomo 回环代理一次，仍校验官方 HTTPS 来源、大小与 SHA-256。Android 不另写下载策略或安装 helper。1.4.1 只在回执明确为 production_change_started=false 时说明服务器程序未改动；未知状态继续查询，矛盾回执不解锁新升级。旧 helper 没有这些字段时显示已有状态，不推断已回退。CLIProxyAPI 跨主版本在版本卡上直接提示需兼容性评审；AstrBot 保持仅查看。

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

### GitHub 发行

历史 GitHub 公开版本1.4.2 / versionCode17于2026-10-13经用户明确授权发布，Actions38421804663及实际APK/附件下载摘要通过。白名单源码与release-public/v1.4.2已验收产物复制到公开仓库dist，codex/android-updates-v1.4.2触发publish-release-v1.4.2.yml；发布提交7a22395ccf925d65002d804c1964c79a878db34b，APK SHA-256为9db1a5c73fce2438e238a9b36b31c2f83b0f0091e0b2314e602f4867ab27f247。原v1.4.1保留。已有同名 Release 时拒绝覆盖，失败先检查草稿和运行记录，不强推、不替换已公开附件。源码/签名备份与旧 v1.4.0 保留。推送前仍按工作区要求确认本次具体公开内容。

本地构建与导出不代表已发布。发布后需检查 Actions、标签指向和下载 APK 的 SHA-256。撤下新 Release 不能把已安装设备降级；设备恢复旧功能建议用原签名构建更高 versionCode 的回退包，避免卸载清数据。

## 验证范围与回滚

1.4.1 Public Release、Lint 与 12 项 JVM 契约测试通过，APK 空 host、非调试与原签名连续性核验通过。新增覆盖下载进度、修改标记缺失/矛盾、未知阶段及 CLIProxyAPI 跨主版本提示。隔离预览使用实际 Compose 页面源码，实验截图工具不进入正式依赖或公开源码；393dp 手机、320dp/1.5倍字体、700dp 平板与下载中状态四份实际 Compose 预览已生成并检查，未见挤压或遮挡。真机覆盖安装、后台回收、网络切换与连接交互仍待实测。

1.4.0 已完成 Public Release 构建、Lint、9 项 JVM 契约测试、APK 空 host 与旧签名连续性检查。测试覆盖数字版本、预览标识、计划项目/时效/连接绑定、未知或不匹配回执不得解锁新升级。GitHub Actions 37488403331 成功，公开附件 SHA-256 与本地正式包一致。尚无真机安装、系统杀后台、网络切换、服务器连接交互的实测，不将单测当作这些场景的证明。

2026-09-30 构建事务补验：8 项合成状态回归通过，覆盖成功/失败恢复、空文件、中文路径盘符、外来映射、嵌套锁、跨进程并发与目录清理边界。中文工作区内 Public Debug 实际构建通过，构建前后两个配置文件及三个环境变量一致；已有 1.3.0 正式发行目录保留。

源码回滚使用同次备份（本次运维工作区 output/android-updates-20261008/source-backup，公开导出另有逐文件备份）；设备回滚 Android 通常不能覆盖安装低 versionCode，优先以原签名制作更高 versionCode 的回退构建。不要为降级卸载或清除数据。本工具不会自动卸载、清数据或更换签名。旧 APK 与签名备份必须保留。

## 依赖与隐私

自身 MIT；Compose/AndroidX/Haze 为 Apache 2.0，JSch 为 BSD 3-Clause，Bouncy Castle 为 MIT，详见 LICENSE/licenses 与各发行包。项目由 AI 辅助维护并接受人工审核。

不新增公网管理端口；不公开真实服务器地址、用户名、私钥路径、订阅凭据与日志。构建日志仅在本机留存，分享前脱敏。
