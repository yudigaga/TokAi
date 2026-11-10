# TokAi

TikTok 增强模块：界面与播放设置、内容筛选、下载、AI 回复与评论、语音生成、配置备份和多语言界面。

- Android 9+，需要支持 libxposed API 102 的框架。
- 当前适配 TikTok `47.5.3 / 2024705030`（`com.zhiliaoapp.musically`）。
- 正式版：`0.1.18 / 19`。兼容性受宿主版本、账号及服务权限限制。

从 [Releases](https://github.com/yudigaga/TokAi/releases/latest) 下载正式 APK，安装并在框架中启用、勾选 TikTok 后重启 TikTok。在 TikTok「设置与隐私 → TokAi 模块设置」中配置；AI 与语音服务需要自行配置。

本版增加强制字幕与多语言界面。无字幕视频的原生在线识别需要上传音轨，并受 TikTok 服务权限限制。

## 构建

使用 JDK 21、Android SDK 37，配置本地 `local.properties` 后运行：

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

Release 使用 `:app:assembleRelease`；签名通过 `TOKAI_RELEASE_STORE_FILE`、`TOKAI_RELEASE_STORE_PASSWORD`、`TOKAI_RELEASE_KEY_ALIAS`、`TOKAI_RELEASE_KEY_PASSWORD` 环境变量配置。签名密钥不随源码提供。

## 免责声明

本项目仅用于学习与技术研究，按现状提供。用户的应用、配置、二次开发及生成、下载、发送或发布内容的行为与结果由用户自行负责，与本项目及作者无关。使用时请遵守适用法律、平台规则和服务协议，尊重隐私与知识产权。

完整说明见 [DISCLAIMER.md](DISCLAIMER.md)，来源与第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
