# 灰猫地图 1.1.8

## 修复内容

- 以 1.1.7 为基线重新发布：回滚 1.1.7 之后的所有改动，Android Auto 等功能暂不实装。
- 移除后台定位权限声明（`ACCESS_BACKGROUND_LOCATION`，含百度导航 AAR 合并项），应用仅使用前台定位 + 前台服务，符合 Google Play 用户数据政策。
- 删除未使用的百度全景 native 库，arm64 全部 `.so` 均为 16KB 对齐，满足 Play 16KB 内存页面要求。
- 移除未使用的 fresco 依赖，减小包体积。
- 本次 release 仅构建手机端应用；手表端保持 1.1.7，单独更新；微信位置转发插件本次不打。

## 未修改内容

- 手机主应用包名仍为 `com.huimao.map`。
- 百度地图 Android AK 配置方式不变。
- 百度导航、路线规划、定位和微信位置转发功能保持原有行为。

## 已知问题

- 当前环境无法直接运行 Gradle，本次构建以 GitHub Actions 为最终编译验证。
- 百度 SDK 仍需要有效的 Android AK，且包名和签名 SHA-1 必须匹配。

## 安装或升级注意事项

- 本版本为 `1.1.8`，`versionCode` 为 `36`。
- 主应用安装包：`HuimaoMap_1.1.8.apk`；上架 Play 请使用 `HuimaoMap_1.1.8.aab`。
- 手表端本次不更新，仍为 1.1.7 版本。
