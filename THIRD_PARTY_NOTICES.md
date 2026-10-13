# 来源与许可

本项目的新代码、界面和主机检查以根目录 MIT LICENSE 许可。

源码包附带两个官方的**编译期** API JAR；它们不会进入 APK 的 DEX。APK 运行时由 LSPosed/对应框架提供 API。

| 文件 | 作者/项目 | 许可 | 下载地址 |
| --- | --- | --- | --- |
| libs/libxposed-api-101.0.1.jar | libxposed contributors | Apache-2.0 | 从 https://repo.maven.apache.org/maven2/io/github/libxposed/api/101.0.1/api-101.0.1.aar 的 classes.jar 提取 |
| libs/xposed-api-82.jar | rovo89 / Xposed contributors | Apache-2.0 | https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar |

Apache 许可全文附于 `libs/APACHE-2.0.txt`。许可声明见对应 Maven POM（随附 `libs/libxposed-api-101.0.1.pom` 与 `libs/xposed-api-82.pom`）。

研究过程中阅读了 AGPL-3.0 的 HyperCeiler、GPL-3.0 的 HyperLux 及 Apache-2.0 的 AOSP 源码，用以识别接口、区分不同显示路径和了解退出条件。本项目为独立实现，不附带上述项目实现源码；参考链接在“分析与实现.md”。

Android SDK、JDK 与其运行库不随源码包分发。用于测试的简化 Android/显示替身是本项目新写的测试代码，不从 SDK 打包。
