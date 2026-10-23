# portrait-model-assets

这是 Google Play Asset Delivery 的按需资源包，不是普通 Android Library。实际 Asset Pack 名为 `portrait_model_assets`，采用 `on-demand` 分发。

## 包内文件

| 文件 | 用途 |
| --- | --- |
| `src/main/assets/modnet.tflite` | MODNet LiteRT 人像分割模型，约 24.95 MB。 |
| `src/main/assets/model_manifest.json` | 模型文件名、版本、输入张量规格及 SHA-256。 |
| `src/main/assets/MODEL_LICENSES.md` | 模型来源和许可证。 |

不要将业务代码、原生 `.so`、重复的 LiteRT 依赖或图片示例放在这个包中。它们会造成按需下载内容膨胀，也不属于模型资源的职责。

Release AAB 上传到 Google Play 后，用户首次调用 `PortraitMatting.prepare(context)` 或 `PortraitMatting.createMask(context, path)` 时，引擎会请求并等待该资源包下载。下载完成的模型路径由 Play 的 `AssetPackManager` 提供，模型不应由业务代码自行复制或删除。

本地 Debug 与 Internal 直装会通过 `app` 模块的变体专属 assets 引用本资源包中的模型，以便开发和测试；该回退模型不会被 Release AAB 的基础模块包含。它不能验证 Google Play 的真实下载、网络失败或 Wi-Fi 等待流程。
