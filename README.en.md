# Huimao Map

[中文 README](README.md) · [Privacy Policy in English](docs/privacy-policy.en.md) · [中文隐私政策](docs/privacy-policy.md)

Huimao Map is an Android map and driving-navigation application based on the Baidu Maps and Baidu Navigation SDKs. The project also provides a WeChat location redirection plugin and a Wear OS companion app for navigation information.

> The Android Auto pre-release uses an independent OSM car renderer. The phone continues to use the Baidu navigation SDK for routing, positioning, navigation state, and voice guidance. This version is distributed only as a GitHub Pre-release.

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

This version is published only as a **GitHub Pre-release** and is not uploaded to Google Play.

[Download GitHub Pre-release](https://github.com/huimao28/huimao-map/releases)

[![Get it on Google Play](https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png)](https://play.google.com/store/apps/details?id=com.huimao.map)

- The mobile app version is `1.1.8` (versionCode `35`), with APK and AAB included.
- The Wear OS companion has no code or version change and is not rebuilt for this release.

GitHub Releases provides test packages, unreleased versions, and the WeChat location redirection plugins:

[![Get it on GitHub](docs/assets/github-download-badge.png)](https://github.com/huimao28/huimao-map/releases)

- `HuimaoMap_<version>.apk`: mobile APK
- `HuimaoMap_<version>.aab`: mobile Google Play upload bundle
- The Wear OS version is distributed as the Wear OS device version of the mobile Huimao Map app and uses the same package name: `com.huimao.map`.
- Wear OS versionName: `1.0.4`; internal versionCode: `200005` (used only for version ordering and not shown as the version name).
- Navigation notifications use Wear OS Ongoing Activity, displaying a tappable activity indicator on the watch face during background navigation.
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
Mobile app: 1.1.8 (versionCode 35)
Wear OS companion: unchanged for this release
```

## Feedback

Please report issues or suggestions through [GitHub Issues](https://github.com/huimao28/huimao-map/issues).
