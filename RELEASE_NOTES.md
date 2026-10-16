# 灰猫地图 1.1.10

## 修复内容

- 关于页新增「赞助」区：三个档位的一次性内购（请喝咖啡 / 请吃午餐 / 请吃大餐），可重复购买，与 GhostTrack 同款实现。
- 赞助商品 ID：`sponsor_coffee`、`sponsor_lunch`、`sponsor_feast`，需在 Play Console（创收 → 应用内商品）创建并激活后才会显示。
- 鸣谢列表新增 Google Play Billing Library。

## 未修改内容

- 基于 1.1.9，无其他功能改动；地图、定位、路线规划、导航、微信位置转发行为不变。
- 手机主应用包名仍为 `com.huimao.map`。
- 百度地图 Android AK 配置方式不变。

## 已知问题

- 当前环境无法直接运行 Gradle，本次构建以 GitHub Actions 为最终编译验证。
- 百度 SDK 仍需要有效的 Android AK，且包名和签名 SHA-1 必须匹配。
- 赞助功能依赖 Play Console 后台商品配置，未创建商品时显示"赞助功能暂未开放"。

## 安装或升级注意事项

- 本版本为 `1.1.10`，`versionCode` 为 `120`。
- 主应用安装包：`HuimaoMap_1.1.10.apk`；上架 Play 请使用 `HuimaoMap_1.1.10.aab`。
- 手表端本次不更新，仍为 1.1.7 版本。
