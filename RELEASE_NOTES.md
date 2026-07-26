# 灰猫地图 1.1.0 更新说明

## 修复内容

- Android Auto 导航显示层已整体重做，不再使用百度 Web 瓦片和自行换算坐标绘制路线/车标。

## 重大更新
- 新实现接入百度导航 SDK 官方公开的 `IBNMiniMapViewManager` 后台导航地图接口：
  - `openBackgroundDrawNavi(true)` 开启百度后台导航渲染；
  - `getMapViewBitmap()` 获取百度导航画面；
  - Android Auto Surface 直接显示该百度导航位图。
- 底图、路线、车标、道路匹配、导航缩放和比例尺现在由百度导航 SDK 自身统一处理，目标是与手机百度导航数据和样式保持一致。
- Android Auto Car App 模板继续提供系统认可的路线指引、预计时间和“结束导航”按钮。

## 保留修复

- 保留 Glide 4.16.0 依赖，避免长距离导航沿途推荐面板闪退。
- 保留固定 Release SHA-1 显示：`DE:FF:00:C2:E1:8A:20:62:9C:4A:17:67:B7:27:A5:08:CC:1B:F0:E4`。
- 保留 Android Auto 指引读取手机当前可见百度指引，以及异常隔离。

## 移除内容

- 移除 Android Auto 百度 Web 瓦片下载、缓存和重试逻辑。
- 移除自制百度墨卡托换算、自绘蓝色路线、自绘车标、路线平移校准和自制 50 米比例尺。
- 移除北向上/旋转地图等实验性车机显示逻辑。

## 已知问题

- 百度后台导航地图生成首帧前，Android Auto 会显示“正在加载百度导航画面…”。
- 当前 Android Auto 仍不能直接嵌入手机 Activity 的 Android View；本版使用的是百度公开的后台导航地图位图接口，这是 Android Auto Surface 架构下最接近百度原生横屏导航的实现。
- 不同百度 SDK 授权状态或车型环境下，后台地图接口若不可用，会停留在加载提示并输出 `NavCarBaidu` 日志。

## 安装说明

- 主应用版本升级为 `1.1.0`，`versionCode` 为 26，可直接覆盖安装 1.0.24。
- 百度 AK 应绑定包名 `com.huimao.map` 和上述固定 Release SHA-1。
- Release 同时提供百度、高德和腾讯三个微信位置转发插件。
