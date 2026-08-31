# Huimao Map

[中文 README](README.md) · [Privacy Policy in English](docs/privacy-policy.en.md) · [中文隐私政策](docs/privacy-policy.md)

Huimao Map is an Android map and driving-navigation application based on the Baidu Maps and Baidu Navigation SDKs. The project also provides a WeChat location redirection plugin and a Wear OS companion app for navigation information.

> Android Auto support is currently unavailable. The rendering layer of the Baidu mobile navigation SDK cannot be directly reused by Android Auto's car-host rendering model. If car support is restored, it will use a separate car rendering and navigation-state integration design.

## Features

### Mobile app

- Baidu map location, POI search, and nearby search
- Driving route planning and built-in Baidu navigation
- Home, work, and frequently used place shortcuts
- `baidunaviauto://` deep link support for starting navigation

### Wear OS companion app

The Wear OS version is a **companion app** for the mobile Huimao Map app. It requires a paired phone running Huimao Map with navigation started.

- Shows phone connection and navigation waiting states
- Displays the next navigation instruction and turn distance
- Displays the current road and remaining distance
- Synchronizes navigation information through Google Play services Wearable Data Layer

The Wear OS app does not provide independent place search, route planning, or map navigation.

### WeChat location redirection

The `redirector/` module provides three optional APKs that redirect WeChat location actions to Huimao Map:

| Entry version | Proxy package name |
| --- | --- |
| Baidu Maps | `com.baidu.BaiduMap` |
| AMap | `com.autonavi.minimap` |
| Tencent Maps | `com.tencent.map` |

All three versions start Huimao Map through:

```text
baidunaviauto://navigate?lat=...&lng=...&name=...
```

> Each proxy APK uses the package name of its corresponding map application and cannot be installed alongside that official application. Choose a version that does not conflict with the map app already installed on the device.

## Downloads and installation

Google Play currently provides the closed-testing version. Join the testing program before installing:

- [Google Play: Huimao Map](https://play.google.com/store/apps/details?id=com.huimao.map)
- The Wear OS version is distributed as a companion version of the mobile Huimao Map app through the same Google Play listing and is installed on supported watches.
- Before installation, join the Google Play closed test with an eligible tester account and accept the testing invitation.

GitHub Releases provides test packages, unreleased versions, and the WeChat location redirection plugins:

- `HuimaoMap_<version>.apk`: mobile APK
- `HuimaoMap_<version>.aab`: mobile Google Play upload bundle
- `HuimaoMap_Wear_<version>.apk`: Wear OS APK for local testing
- `HuimaoMap_Wear_<version>.aab`: Wear OS Google Play upload bundle
- `WeChatRedirect_*_<version>.apk`: WeChat location redirection plugin

Before installing the Wear OS companion version, make sure the phone and watch are paired, use the same Google account, and have Huimao Map installed on the phone.

## Build

### Requirements

- JDK 17
- Android SDK with `compileSdk 36`
- A valid Baidu Maps Android AK
- Release keystore and signing configuration for release builds

The Baidu AK package configuration must include `com.huimao.map`, and the SHA-1 must match the actual signing certificate.

### Common commands

```bash
gradle :app:assembleRelease :app:bundleRelease
gradle :wear:assembleRelease :wear:bundleRelease
```

The project includes local Baidu AAR and native `.so` files. Confirm that you have the required rights to use and redistribute the relevant SDK components before building or redistributing the project.

## Privacy policy

See the complete [Privacy Policy](docs/privacy-policy.en.md).

The policy covers mobile location and navigation data, Wear OS navigation-state synchronization, WeChat location redirection, third-party SDK processing, local storage, permissions, and user data deletion.

## Release notes

Before creating a release tag, update [`RELEASE_NOTES.md`](RELEASE_NOTES.md). GitHub Actions validates the required sections and uses the file as the GitHub Release description.

## Current version

```text
1.1.7
```

## Feedback

Please report issues or suggestions through [GitHub Issues](https://github.com/huimao28/huimao-map/issues).
