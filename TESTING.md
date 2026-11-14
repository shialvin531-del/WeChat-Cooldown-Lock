# 验证记录 · 1.0.2

日期：2026-11-13（America/Toronto）。

## 已执行

完整构建执行 APK 编译、核心测试、Android 单元测试与 Lint，结果为 `BUILD SUCCESSFUL`。随后将补充的规则说明文字编入 APK，再次执行 `:app:assembleDebug :app:lintDebug`，结果为 `BUILD SUCCESSFUL`。

| 测试类 | 数量 | 结果 | 环境与范围 |
| --- | ---: | --- | --- |
| BehaviorEngineTest | 17 | 通过 | 纯 Kotlin；组合条件、首次进入、内部切换、短暂离开、外链、长聊、时段、时钟、进入本应用后的重开、跳过时保留累计、显示计数随窗口过期 |
| RepositoryTest | 15 | 通过 | 5 个场景 × API 29/33/35；实际 Room/DataStore、持久化、重建、时钟、到期、设置变化不清除截止时间、删除与统计 |
| UsageSourceTest | 21 | 通过 | 7 个场景 × API 29/33/35；晚到桌面事件、批量快速切换、内部 Activity、授权、无障碍声明不读取内容或执行手势 |
| OverlayLifecycleTest | 6 | 通过 | API 29/33/35；实际 ComposeView 生命周期、显示/释放、无障碍覆盖页类型 |
| ProtectionUiTest | 3 | 通过 | API 35；四个情绪动作、无强制输入、冷静/继续聊天、倒计时、无提前退出、到期页面 |
| MainActivityTest | 1 | 通过 | API 35；导航、Room 保存查看；冷静期间隐藏暂停入口并拒绝提前暂停 |
| EnhancedProtectionTest | 5 | 通过 | API 35；连续重进维持同一截止时间；锁屏事件在解锁后恢复；服务重连；其他观察者处理到期后清除风险；选择心情不错及访问本应用保留累计，第三次合格重开启动 5 分钟自动冷静 |
| SessionHealthTest | 2 | 通过 | 增强保护心跳、错误及过期状态判断 |
| 合计 | 70 | 0 失败、0 错误 | JUnit / Robolectric 桌面 Android 环境 |

Android Lint：0 错误、11 条依赖与 Gradle 可更新提示，没有忽略错误的 baseline。

本版重点回归：1/3 后选择“心情不错”，离开微信查看本应用，再返回微信。该返回满足时长条件时应继续累计；不足时长则显示原累计及本次跳过原因。累计达到门槛后创建 source=automatic、minutes=5 的真实 DataStore 截止时间并显示覆盖页。测试同时保留外链排除、正常长聊清空、短暂离开不累计。

曾出现 Gradle 转换缓存缺失导致构建失败，清理专用临时转换缓存后重新构建通过。曾有到期测试读取上一条状态过早结束等待，修正等待目标后通过，没有删除断言或跳过测试。

预览来自实际 Compose 页面绘制，并非微信联动截图。报告位于：

- `outputs/core/reports/tests/test/index.html`
- `outputs/data/reports/tests/testDebugUnitTest/index.html`
- `outputs/app/reports/tests/testDebugUnitTest/index.html`
- `outputs/app/reports/lint-results-debug.html`

## 未执行

没有连接实体手机或运行中的模拟器，尚未完成：

- 实际安装与微信联动、发送入口覆盖验证。
- 小米 15 / HyperOS 手势路径、后台存活、电池策略、重启恢复的真机验证。
- 不同厂商后台限制、微信分身、分屏、小窗、通知快捷回复的实际覆盖验证。
- Android 16 / API 36 运行测试；当前仅以 SDK 36 编译并设为 target。

Robolectric 控制事件与权限状态，不代表真实系统一定及时派发事件。不能将通过结果表述为“无法绕过”“保证锁住所有操作”或“正常聊天绝无误触发”。

## 安装包检查

- 文件：`outputs/WeChat-Cooldown-Lock-1.0.2-debug.apk`，30,609,834 字节。
- 包名 `local.wechat.cooldown`，versionCode 3，versionName 1.0.2。
- min SDK 29 / target SDK 36。
- `apksigner verify --verbose`：APK Signature Scheme v2 验证通过，1 个调试签名者。
- 与 1.0.1 的签名证书 SHA-256 一致，可覆盖安装，无需卸载或清除记录。
- `zipalign -c -P 16 4` 通过；不代表完成所有 16 KB 页大小设备的真机验证。
- `aapt2 dump permissions`：没有 INTERNET 或微信聊天内容读取权限。
- SHA-256：`3A756E55C0D01E241055AEC16642F185E6DEA4348603F359FC5A15F4784839B1`。
