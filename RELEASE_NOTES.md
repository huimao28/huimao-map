# 灰猫地图 1.1.4-test1 测试版说明

## 修复内容

- 测试修复 Android Auto 横屏车机页面图层显示问题：车机 Surface 优先绘制百度 MiniMap 横向地图图层。
- 手机端百度导航截图改为兜底来源，避免竖屏截图在横屏车机上被裁剪后只剩大块色块。
- Android Auto Surface 可用后按车机宽高重新测量/布局百度 MiniMap。
- 手机端百度导航 Activity 固定横屏，尽量与车机横屏画布保持一致。
- API AK 设置页补充 GitHub 版 SHA1 与 Google Play 版 SHA1，便于百度开放平台安全码配置。

## 技术调整

- Android Auto 渲染顺序调整为：百度后台 MiniMap 横向位图 → 手机导航截帧 → 本地兜底导航图。
- `NavCarService` 在 Surface attach 后重新 layout MiniMap View，确保图层按车机横向尺寸输出。
- `NaviActivity` 设置 `screenOrientation="landscape"`，减少手机/车机图层方向不一致。

## 保留修复

- 保留 1.1.3 的 targetSdk 35、Google Play AAB、Android Auto manifest 修复。
- 保留手机导航帧同步、车机指引卡片、路线规划起点修复和微信位置转发插件。
- Release 继续同时提供主 APK、Google Play 用 AAB、百度/高德/腾讯三个微信位置转发插件，以及完整源码包。

## 已知问题

- 这是测试版，重点验证 Android Auto 横屏图层是否能正常显示。
- 如果百度 MiniMap 后台位图仍为空，车机端会继续回退到手机导航截帧或本地兜底图。
- Google Play 正式上架前建议继续使用稳定版；本测试版主要用于实车验证。

## 安装说明

- 主应用版本为 `1.1.4-test1`，`versionCode` 为 31，可覆盖安装 1.1.3。
- 百度 AK 安全码建议同时绑定：
  - GitHub 版：`com.huimao.map;DE:FF:00:C2:E1:8A:20:62:9C:4A:17:67:B7:27:A5:08:CC:1B:F0:E4`
  - Google Play 版：`com.huimao.map;F6:4B:0C:D1:6D:AF:A3:51:09:BF:7F:9D:21:F3:81:E2:48:FC:DA:5B`
