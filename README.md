# 灰猫地图

[English README](README.en.md) · [中文隐私政策](docs/privacy-policy.md) · [Privacy Policy in English](docs/privacy-policy.en.md)

灰猫地图是基于百度地图与百度导航 SDK 的 Android 地图/驾车导航应用，提供微信位置转发插件和 Wear OS 导航信息伴侣应用。

> Android Auto 适配目前暂未提供。百度导航 SDK 的手机端 OpenGL 渲染层无法直接复用于 Android Auto 的车机渲染模型；项目后续如恢复车机支持，将采用独立的车机渲染与导航状态适配方案。

## 功能

### 手机端

- 百度地图定位、地点（POI）搜索与周边搜索
- 驾车路线规划和内置百度导航
- 家、公司和常用地点快捷入口
- 支持 `baidunaviauto://` 深链接启动导航

### Wear OS 伴侣应用

Wear OS 版本是手机端灰猫地图的**配套应用**，需要手机端正在运行灰猫地图并开始导航。

- 显示手机端连接与导航等待状态
- 同步显示下一步导航指令和转向距离
- 显示当前道路、剩余距离等导航信息
- 通过 Google Play services Wearable Data Layer 与已配对手机通信

Wear OS 不提供独立的地点搜索、路线规划或地图导航功能。

### 微信位置转发

`redirector/` 模块提供三个可选 APK，将微信位置调用转发到灰猫地图：

| 入口版本 | 代理包名 |
| --- | --- |
| 百度地图 | `com.baidu.BaiduMap` |
| 高德地图 | `com.autonavi.minimap` |
| 腾讯地图 | `com.tencent.map` |

三个版本均通过以下深链接启动灰猫地图：

```text
baidunaviauto://navigate?lat=...&lng=...&name=...
```

> 代理 APK 使用对应地图应用的包名，不能与该官方地图应用同时安装。请选择一个不会与当前设备地图应用冲突的版本。

## 下载与安装

Google Play 当前提供的是封闭测试版本，需要先加入测试计划，并接受测试邀请。

[![Get it on Google Play](https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png)](https://play.google.com/store/apps/details?id=com.huimao.map)

- Wear OS 版本作为手机端灰猫地图的配套版本，通过同一 Google Play 应用页面的 Wear OS 设备分发安装。

GitHub Releases 提供测试包、尚未上架版本以及微信位置转发插件：

[![GitHub Releases](https://img.shields.io/badge/下载-GitHub%20Releases-181717?logo=github&logoColor=white)](https://github.com/huimao28/huimao-map/releases)

- `HuimaoMap_<版本>.apk`：手机端安装包
- `HuimaoMap_<版本>.aab`：Google Play 上传包
- `HuimaoMap_Wear_<版本>.apk`：Wear OS 本地测试安装包
- `HuimaoMap_Wear_<版本>.aab`：Google Play Wear OS 上传包
- `WeChatRedirect_*_<版本>.apk`：微信位置转发插件

安装 Wear OS 配套版本前，请确保手机和手表已配对、使用同一 Google 账号，并已在手机端安装灰猫地图。

## 构建

### 环境

- JDK 17
- Android SDK（`compileSdk 36`）
- 有效的百度地图 Android AK
- 用于 release 的签名文件与签名配置

百度 AK 的包名应包含 `com.huimao.map`，SHA-1 必须与实际签名证书匹配。

### 常用命令

```bash
gradle :app:assembleRelease :app:bundleRelease
gradle :wear:assembleRelease :wear:bundleRelease
```

项目依赖百度本地 AAR 和 native `.so` 文件；构建或再分发前，请确认你拥有相应 SDK 的使用与分发权限。

## 隐私政策

完整隐私政策见：[中文隐私政策](docs/privacy-policy.md) / [Privacy Policy in English](docs/privacy-policy.en.md)。

英文项目说明：[README in English](README.en.md)。

### 摘要

- 灰猫地图不提供账号注册，也不运营自建服务器保存位置轨迹、搜索历史或个人资料。
- 手机端为地图、定位、路线规划和导航，会在设备本地及通过百度地图/导航 SDK 处理位置、搜索、路线和必要的设备运行信息。
- Wear OS 配套应用仅同步手机端导航状态；项目不建立服务器中转这类信息。
- 微信位置转发插件仅按用户操作把位置深链接交给灰猫地图处理。
- 项目不出售、出租或向广告商共享个人信息。

## 发布说明

每次发布标签前必须更新仓库根目录的 [`RELEASE_NOTES.md`](RELEASE_NOTES.md)。GitHub Actions 会校验其中包含修复内容、未修改内容、已知问题及安装/升级注意事项，并将其用作 GitHub Release 正文。

## 当前版本

```text
1.1.7
```

## 反馈

请通过 [GitHub Issues](https://github.com/huimao28/huimao-map/issues) 报告问题或提出建议。
