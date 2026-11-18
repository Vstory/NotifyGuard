# 第三方组件声明

本项目（NotifyGuard，`Copyright (c) 2026 Vstory`）以 AGPL-3.0 分发，包含以下第三方内容。

---

## 1. libxposed API（LSPosed）

模块基于 libxposed Modern API 102 开发。`app/libs/libxposed/` 下三个 jar 为编译与运行期依赖（`api.jar` 仅编译期，`interface.jar` / `service.jar` 随 APK 分发）。

| 项 | 值 |
|---|---|
| 来源 | https://github.com/LSPosed/libxposed |
| 许可 | Apache License 2.0 |

许可全文：https://www.apache.org/licenses/LICENSE-2.0

---

## 2. AppIconLoader

界面上的应用图标按 AOSP `iconloaderlib` 渲染（自适应图标的阴影与 badging，即系统自己画通知栏图标的那套）。
`me.zhanghai.android.appiconloader:appiconloader` 将该库打包并提供简易 API，随 APK 分发。

| 项 | 值 |
|---|---|
| 来源 | https://github.com/zhanghai/AppIconLoader |
| 许可 | Apache License 2.0（内含 AOSP iconloaderlib，Copyright 2020 Google LLC） |

许可全文：https://www.apache.org/licenses/LICENSE-2.0
