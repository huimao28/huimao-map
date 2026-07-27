# 灰猫地图 1.1.3 更新说明

## 修复内容

- 修复 Android Auto 画面仍无法同步手机百度导航的问题：新增手机端百度导航 View 实时截帧同步通道，Android Auto Surface 优先绘制手机导航帧。
- 修复 Android Auto 左上角路线指引可能不显示的问题：在车机 Surface 内固定叠加路线指引卡片，显示转向距离和当前指令。
- 保留百度 `IBNMiniMapViewManager` 后台地图作为次级来源；手机截帧不可用时再回退到百度后台位图和本地兜底图。

## 技术调整

- `NaviActivity` 在百度导航 View 创建后通过 PixelCopy/软件绘制周期性采集手机导航画面。
- `CarNavigationBridge` 增加导航帧缓存，Android Auto 服务跨组件读取并绘制最新帧。
- Android Auto 渲染顺序调整为：手机导航截帧 → 百度后台 MiniMap 位图 → 本地兜底导航图。
- Android Auto 每帧最后叠加自绘指引卡片，避免 Car App 宿主模板隐藏路线指引。

## 保留修复

- 保留 1.1.2 的路线规划起点修复，启动参数异常缓存点不再锁死真实定位。
- 保留 1.1.1 的百度后台地图离屏测量/布局与定位弱惯性导航提示。
- Release 继续同时提供主 APK、Google Play 用 AAB、百度/高德/腾讯三个微信位置转发插件，以及完整源码包。

## 已知问题

- 手机导航截帧依赖手机端导航 Activity 保持运行；如果系统强制暂停手机 Activity，车机端会自动回退到百度后台 MiniMap 或本地兜底图。

## 安装说明

- 主应用版本升级为 `1.1.3`，`versionCode` 为 29，可直接覆盖安装 1.1.2。
- 百度 AK 应绑定包名 `com.huimao.map` 和固定 Release SHA-1：`DE:FF:00:C2:E1:8A:20:62:9C:4A:17:67:B7:27:A5:08:CC:1B:F0:E4`。
