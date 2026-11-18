# Magpie Panel (Android)

Magpie 网关面板的原生 Android 壳。本机运行 [magpie](https://github.com/yetone/magpie) 网关时，用它代替浏览器直接打开面板，无需输入地址和密钥。

## 特性

- 直接加载本机 magpie 面板（默认 `http://127.0.0.1:3430`），面板地址与访问密钥可在 App 内设置并记住。
- 面板导出的文件（会话 Markdown / 用量 CSV / 存档）保存到系统 Download 目录，无需存储权限（MediaStore）。
- 面板里的外部链接自动交给系统浏览器打开。
- 深色模式跟随系统；localStorage 持久化（记住上次页面与偏好）。
- **没有**任何自启动、保活、后台服务、通知、地理位置等行为——网关服务由系统开机脚本管理，与本体无关。

## 权限说明

| 权限 | 用途 |
|---|---|
| INTERNET | 连接本机面板，仅此而已 |

## 构建

GitHub Actions 自动构建（推送 main 或手动触发 workflow），签名密钥通过仓库 Secrets 注入（`MAGPIE_KEYSTORE_B64` / `MAGPIE_KS_PASS` / `MAGPIE_KEY_PASS`，别名 `magpie`），产物在 Actions Artifacts 与 Releases。

本地构建（需要 JDK 17 与 Android SDK 35）：

```sh
gradle -PVERSION_CODE=1 -PVERSION_NAME=1.0.0 assembleRelease
```

## 结构

纯 Java + 系统 WebView，零第三方依赖。核心只有一个 `MainActivity`：

- `src/main/java/com/landrinkjuice/magpiepanel/MainActivity.java`
- `src/main/res/` — 布局 / 主题 / 图标（图标来自 magpie 官方面板 favicon 的矢量重绘）
