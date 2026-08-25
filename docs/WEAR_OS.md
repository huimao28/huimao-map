# Wear OS 适配

Wear OS 目前提供独立的 `:wear` 应用模块，首版包含：

- 手表设备声明与独立 applicationId：`com.huimao.map.wear`
- Compose 基础界面
- “同步手机导航”入口占位
- 定位权限声明，为后续导航状态同步做准备

后续建议接入 Wearable Data Layer，将手机端 `CarNavigationBridge` 的通用导航状态改造成共享状态仓库，同步道路、下一步指引、剩余距离和到达状态。手表端不应直接复用车机 Surface 或百度原生导航 Activity。
