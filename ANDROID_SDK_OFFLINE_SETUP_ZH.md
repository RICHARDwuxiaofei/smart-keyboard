# Android SDK 命令行/半离线安装说明

当前机器的问题不是 Kotlin 代码本身，而是构建环境缺两块：

- `C:\Users\WXF\AppData\Local\Android\Sdk` 目录存在，但里面没有 platform/build-tools/cmdline-tools。
- Gradle 能启动，但解析不到 `com.android.application:9.2.0`，后续还需要能访问 Google Maven 或具备对应 Gradle 缓存。

官方页面提供了不安装完整 Android Studio 的方案：只下载 **Command line tools only**。Windows 包名是：

```text
commandlinetools-win-14742923_latest.zip
```

官方直链：

```text
https://dl.google.com/android/repository/commandlinetools-win-14742923_latest.zip
```

## 最小 SDK 包集合

本项目 `build-logic/convention/src/main/kotlin/Versions.kt` 声明：

```text
compileSdk = 36
targetSdk = 36
buildTools = 36.1.0
cmake = 3.31.6
ndk = 28.0.13004108
```

所以完整构建至少需要：

```text
platform-tools
platforms;android-36
build-tools;36.1.0
cmake;3.31.6
ndk;28.0.13004108
```

只验证 Kotlin 层时可以先跑：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

但 AGP 配置阶段仍可能要求 `sdk.dir` 指向有效 SDK。

## 推荐执行方式

在仓库根目录执行：

```powershell
cd D:\CODE\keybord\fcitx5-android
powershell -ExecutionPolicy Bypass -File .\tools\setup-android-sdk.ps1 -AcceptLicenses
```

脚本会：

- 写入本机专用的 `local.properties`
- 安装 Android command-line tools
- 调用 `sdkmanager` 安装项目需要的最小 SDK 包

## 如果命令行不能直连 Google

当前我在本机命令行里测试到 `dl.google.com` TLS 握手失败。可用这个半离线路线：

1. 用浏览器、下载器或其他能走 TUN 的工具下载：

```text
https://dl.google.com/android/repository/commandlinetools-win-14742923_latest.zip
```

2. 把文件放到：

```text
D:\CODE\keybord\fcitx5-android\tools\commandlinetools-win-14742923_latest.zip
```

3. 执行：

```powershell
cd D:\CODE\keybord\fcitx5-android
powershell -ExecutionPolicy Bypass -File .\tools\setup-android-sdk.ps1 -SkipDownload -AcceptLicenses
```

如果 `sdkmanager` 也不能联网，那就说明 Java/PowerShell 进程仍没有走 TUN。需要先让命令行进程能访问 Google Maven 和 Android repository，或者把完整 SDK 目录从另一台机器复制过来。

## 从另一台机器复制 SDK 的目录要求

可以直接复制一个已安装好的 SDK 到：

```text
C:\Users\WXF\AppData\Local\Android\Sdk
```

目标 SDK 至少包含：

```text
build-tools\36.1.0
cmake\3.31.6
cmdline-tools\latest
licenses
ndk\28.0.13004108
platform-tools
platforms\android-36
```

复制后在仓库根目录确认 `local.properties`：

```properties
sdk.dir=C\:\\Users\\WXF\\AppData\\Local\\Android\\Sdk
```

然后编译：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:compileDebugKotlin
```

## 还剩一个独立问题：AGP 9.2.0

即使 SDK 装好，Gradle 还必须能解析：

```text
com.android.tools.build:gradle:9.2.0
```

如果继续报：

```text
Plugin [id: 'com.android.application', version: '9.2.0'] was not found
```

那不是 SDK 缺失，而是 Google Maven 访问或 AGP 版本解析问题。届时有两个选择：

- 保持上游版本不动，修好 Google Maven 访问。
- 临时建一个本地构建分支，把 AGP/Kotlin/KSP 降到当前仓库和本机缓存能解析的版本，仅用于编译验证。
