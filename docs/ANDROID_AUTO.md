# Android Auto 接入与排障

本文档记录灰猫地图在 Android Auto（手机投屏，非 Automotive OS）上的接入要点、
已知问题与排查顺序。

## 1. 依赖与声明

| 项目 | 要求 | 说明 |
| --- | --- | --- |
| `androidx.car.app:app` | 必需 | Car App Library 核心库 |
| `androidx.car.app:app-projected` | 必需 | Android Auto（投屏）专用实现，官方 `Declaring dependencies` 明确要求与核心库一起引入 |
| `com.google.android.gms.car.application` meta-data | 必需 | 指向 `@xml/automotive_app_desc`，Android Auto 靠它识别应用 |
| `@xml/automotive_app_desc` | 必需 | 至少包含 `<uses name="template"/>` |
| `CarAppService` + `androidx.car.app.category.NAVIGATION` | 必需 | 导航类模板应用 |
| `androidx.car.app.NAVIGATION_TEMPLATES` / `ACCESS_SURFACE` | 必需 | 使用 `NavigationTemplate` 与自绘 Surface |
| `androidx.car.app.action.NAVIGATE` intent-filter | 必需 | **必须声明在 Activity 上**（投屏场景），否则车机/语音助手无法下发目的地 |

参考：

- <https://developer.android.com/training/cars/apps/navigation>
- <https://developer.android.com/jetpack/androidx/releases/car-app>

## 2. 导航意图

车机端与语音助手通过下列意图下发目的地，`LocationRedirectActivity` 负责解析：

```text
androidx.car.app.action.NAVIGATE  geo:39.915,116.404
androidx.car.app.action.NAVIGATE  geo:0,0?q=39.915,116.404(天安门)
android.intent.action.VIEW        google.navigation:q=39.915,116.404
android.intent.action.VIEW        baidunaviauto://navigate?lat=&lng=&name=   # 内部协议
```

坐标系约定：

- `geo:` / `google.navigation:` 为 **WGS-84**，需转换为 **BD09LL** 后再交给百度导航；
- `baidunaviauto://` 为内部协议，已是 **BD09LL**，不做转换。

`NavCarSession` 同时实现 `onCreateScreen()` 与 `onNewIntent()`：会话冷启动与热启动
两条路径都要处理导航意图，否则第二次下发目的地会没有反应。

## 3. 车机端画面刷新

- 地图画面：`SurfaceCallback` 拿到车机 Surface 后，按 250ms 周期把百度 MiniMap 的
  位图绘制到 Surface；`ensureFrameTicker()` 保证导航开始后刷新循环一定处于运行状态
  （Surface 往往在导航开始之前就绪，早期实现会让刷新循环永久停摆并导致黑屏）。
- 模板刷新：车机主机对模板刷新有速率限制，超限会被断开。手机端每 300ms 推帧、
  每秒回调定位，因此 `invalidate()` 被限流到 1s 一次。

## 4. 排查顺序

1. **应用是否出现在 Android Auto 启动器？**
   - 非 Google Play 渠道安装（自签 APK / 侧载）必须在 Android Auto 中开启
     开发者选项，并勾选「未知来源的应用」；
   - 确认 Android Auto 与 Google Play 服务已是最新版本；
   - 确认 `automotive_app_desc` 与 `CarAppService` 声明未被打包剥离。
2. **应用能打开但一直提示「请在手机端选择目的地并开始导航」？**
   - 说明车机侧会话正常，但 `CarNavigationBridge.navigating` 仍为 false；
   - 用语音助手或第三方 POI 应用下发一次导航意图验证 NAVIGATE 链路；
   - 检查手机端百度导航是否真的启动成功（见下一条）。
3. **手机端导航起不来？**
   - 必须配置真实的百度地图 AK（`PLACEHOLDER_AK` 会直接失败），且 AK 的包名
     `com.huimao.map` 与签名 SHA-1 必须与实际安装包一致；
   - 关注 `initFailed`、`路线规划失败`、`15 秒内未获取到当前位置` 等错误提示；
   - 定位 `locType=68` 表示 AK 鉴权失败。
4. **车机有界面但地图黑屏 / 画面冻结？**
   - 抓取日志过滤 `NavCarBaidu` 与 `NaviActivity`；
   - 检查是否有 `Baidu background map init failed`；
   - 确认手机端 `NaviActivity` 仍在前台（当前架构下百度导航引擎依赖该 Activity）。

调试命令：

```bash
adb logcat -s NavCarBaidu NaviActivity LocationRedirect CarApp
```

桌面头单元（DHU）可在没有真实车机的情况下验证：
<https://developer.android.com/training/cars/testing/dhu>

## 5. 已知遗留问题（待后续处理）

- `NavCarService.createHostValidator()` 使用 `ALLOW_ALL_HOSTS_VALIDATOR`，
  正式版建议改为「debug 允许全部 + release 使用主机白名单」。
- 百度导航引擎目前只能在手机端 `NaviActivity` 中初始化，手机侧 Activity 被回收后
  车机端会立即失去导航状态；更稳妥的做法是把导航状态与定位放进前台 Service。
- 车机端仍无法独立选择目的地（没有搜索/收藏/最近目的地界面），只能接收导航意图。
