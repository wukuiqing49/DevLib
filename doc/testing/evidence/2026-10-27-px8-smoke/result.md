# Pixel 8 真机冒烟验证

日期：2026-10-27。用户本次明确要求真机运行，覆盖此前设备排除偏好，仅限本任务。

- 设备：Pixel 8，Android 16 / API 36。
- APK：Debug，com.wkq.dev；SHA-256：`3301041cfcda7d7c3ec799b13f22cb1f2a5338aff641bff95c80c539aedb2a4e`。
- 构建：assembleDebug 成功，536 项 UP-TO-DATE；安装：adb install -r 成功，未清除数据。
- 启动：MainActivity，COLD，Status ok，TotalTime 628 ms（单次样本）。
- PX8-DEPLOY：安装及冷启动通过。
- PX8-PDF-SMOKE：首页进入图片 PDF 编排 → 使用示例图片 → A4 / 边距24 / 每行1 → 生成 → 显示执行完成及首页预览，通过。仅使用 App 内置素材，未分享或另存至外部目录。
- 当前进程日志崩溃关键字匹配：0。原始日志留在 build 临时目录，不提交设备日志。
- 视觉：当前中文竖屏深色模式下首页和 PDF 表单/结果可见，状态栏及手势区域未见内容重叠；PDF 页返回箭头对比度偏低，记录为待优化项，不称作全面视觉验收。
- 未验证：其余工具完整流程、浅色/横屏/大字体、Android 17 运行期、Google 交易、NAS/USB、真实网络、分享/系统文档选择及 Release。
- 使用 android-cli 交互规范及 android-functional-test-workflow；没有修改功能实现或 Base/文案/适配代码。
- 不推进历史全功能测试基线；本次证据限定上述两个冒烟用例。未提交、未推送。
