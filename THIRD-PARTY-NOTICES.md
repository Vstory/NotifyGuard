# 第三方组件与语料声明

本项目（NotifyGuard，`Copyright (c) 2026 Vstory`）以 AGPL-3.0 分发，包含以下第三方内容。

---

## 1. 训练语料：ytdttj/NotificationCleaner

内置模型（`app/src/main/resources/model/model.bin`）由下列语料训练而来。语料原文收录在本仓库 `training/samples/` 下。

| 项 | 值 |
|---|---|
| 来源 | https://github.com/ytdttj/NotificationCleaner |
| 路径 | `training/samples/` |
| 钉定 commit | `da22d84452193042c94dd505747d38347896105a` |
| 许可 | MIT |

```
MIT License

Copyright (c) 2026 ytdttj

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. libxposed API（LSPosed）

模块基于 libxposed Modern API 102 开发。`app/libs/libxposed/` 下三个 jar 为编译与运行期依赖（`api.jar` 仅编译期，`interface.jar` / `service.jar` 随 APK 分发）。

| 项 | 值 |
|---|---|
| 来源 | https://github.com/LSPosed/libxposed |
| 许可 | Apache License 2.0 |

许可全文：https://www.apache.org/licenses/LICENSE-2.0
