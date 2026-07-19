# 灰猫地图

基于百度地图与百度导航 SDK 的 Android 地图/导航应用，支持 Android Auto 和微信位置转发。

## 功能

- 地图定位与 POI 搜索
- 驾车路线规划和内置导航
- Android Auto 路线指引与地图瓦片
- 微信位置代理转发
- 家、公司和周边快捷入口

## 构建

1. 使用 Android Studio 或 Gradle，JDK 17。
2. 在应用设置中配置百度地图 Android AK。
3. 百度 AK 的包名需包含 `com.huimao.map`，SHA-1 需与实际签名证书一致。
4. 本项目依赖的百度本地 AAR/so 文件体积较大；请确认你有权使用并分发相关 SDK。

```bash
gradle assembleRelease
```

## 微信位置转发

配套转发器源码位于仓库的 `redirector/` 模块。每次 GitHub Release 会同时提供三个 APK：

- 百度地图入口：包名 `com.baidu.BaiduMap`
- 高德地图入口：包名 `com.autonavi.minimap`
- 腾讯地图入口：包名 `com.tencent.map`

三个版本都会通过：

```text
baidunaviauto://navigate?lat=...&lng=...&name=...
```

启动灰猫地图。

> 代理 APK 与对应官方地图使用相同包名，不能同时安装；只需安装一个与手机现有地图不冲突的版本。旧腾讯 1.4 与当前固定签名不同，升级 1.5 时需先卸载旧代理；之后可覆盖升级。

## 更新说明规范

每次发布新标签前必须更新仓库根目录的 `RELEASE_NOTES.md`，至少写明：

- 本版本修复内容
- 未修改内容
- 已知问题
- 安装或升级注意事项

GitHub Actions 会校验该文件，不允许包含“待填写”，并将其作为 Release 正文。

## 版本

```text
1.0.21
```

## Android Auto 混合导航架构

- 底图：独立道路瓦片渲染，不嵌入百度 MapView
- 路线：百度规划路线坐标
- 位置：百度 BD09LL 定位
- 方向与速度：百度导航回调
- 转向距离与文字：百度 `BNaviInfo + GuidePanelMessage`
- 剩余距离与时间：百度 `onRemainInfoUpdate`
- 车机界面：Android Auto `NavigationTemplate`
