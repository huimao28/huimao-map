# 灰猫地图 Android Auto OSM 预发布版

## 修复内容

- 恢复 Android Auto 投屏应用入口。
- Android Auto 使用独立 OSM 车机渲染层，不再投送手机百度导航截图。
- 手机端百度导航继续负责路线规划、实时定位、转向状态、偏航处理和语音播报。
- 车机端显示 OSM 地图、当前位置、路线覆盖层、路口转向信息和剩余距离。
- 路线绘制采用路线投影、已行驶路线裁剪和即将到来的路口箭头处理。
- 保留 Wear OS 导航状态同步和微信位置转发插件。
- 本版本仅通过 GitHub Releases 发布为 Pre-release。

## 未修改内容

- 手机端仍使用百度地图/导航 SDK。
- Wear OS 仍接收手机端导航状态，不独立规划路线。
- 手机端版本按原规则递增为 `1.1.8`，内部 `versionCode` 为 `35`。
- 生成手机 APK 和 AAB，但仅附加到 GitHub Pre-release，不上传 Google Play。
- Wear OS 本次无代码和版本变动，不重新构建或发布。
- 不发布正式版 Release。

## 已知问题

- OSM 车机渲染器目前为首个预发布实现，真实车机和 DHU 仍需测试。
- OSM 底图服务、离线地图和瓦片缓存策略仍需进一步完善。
- Android Auto 端的路线折线以手机端实时导航状态为准。
- 手机导航 Activity 或导航进程被系统回收时，车机状态可能暂时中断。
- 百度 SDK 需要有效 AK，且包名和签名 SHA-1 必须匹配。

## 安装或升级注意事项

- 从 GitHub Pre-release 下载 `HuimaoMap_*.apk`。
- AMap、Tencent 和 Baidu 微信转发插件不能与对应官方地图使用相同包名同时安装。
- 这是测试版本，不代表正式发布质量。
