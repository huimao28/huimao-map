# 灰猫地图 1.1.1 更新说明

## 修复内容

- 修复 Android Auto 端一直停留在“正在加载百度导航画面…”的问题：百度后台地图位图首帧为空时，不再死等加载文案，而是立即显示车机兜底导航画面。
- 为百度 `IBNMiniMapViewManager` 创建的地图 View 增加离屏测量与布局，避免后台地图因 View 没有尺寸而无法输出 `getMapViewBitmap()`。
- 优化定位信号差或丢失时的处理：过滤明显异常跳点，保留最近可靠位置，Android Auto 显示“定位弱，车机惯性导航中”，避免车机画面乱跳。

## 重大更新

- Android Auto 显示采用双层保险：
  - 优先显示百度导航 SDK 官方后台地图位图；
  - 百度位图暂不可用时，使用本地 Surface 兜底导航图，显示路线、车位、朝向和当前指引。
- 兜底图只在百度后台画面未产出或恢复中时显示；一旦 `getMapViewBitmap()` 返回有效位图，会自动切回百度导航画面。

## 保留修复

- 保留 1.1.0 使用的百度后台导航地图接口：`openBackgroundDrawNavi(true)` / `getMapViewBitmap()`。
- 保留 Glide 4.16.0 依赖，避免长距离导航沿途推荐面板闪退。
- 保留 Android Auto 指引读取手机当前可见百度指引，以及异常隔离。
- 保留固定 Release SHA-1：`DE:FF:00:C2:E1:8A:20:62:9C:4A:17:67:B7:27:A5:08:CC:1B:F0:E4`。

## 已知问题

- 百度后台导航地图首帧生成速度取决于百度 SDK 授权、导航状态和车型环境；本版已增加兜底图，不会再空等加载文字。
- Android Auto 仍不能直接嵌入手机 Activity 的 Android View；本版继续使用 Surface 位图输出方案。

## 安装说明

- 主应用版本升级为 `1.1.1`，`versionCode` 为 27，可直接覆盖安装 1.1.0。
- 百度 AK 应绑定包名 `com.huimao.map` 和上述固定 Release SHA-1。
- Release 同时提供百度、高德和腾讯三个微信位置转发插件。
