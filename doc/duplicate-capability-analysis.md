# DevLib 重复能力收敛（历史分析）

> 已由用户确认的功能范围裁剪取代，当前范围请见 library-scope-review.md。文中旧模块数量与待办不代表当前工程；上一轮 Utils 修改已撤回。

日期：2026-10-23。当前 33 个 core 模块：8 个基础模块、25 个功能模块。Starxen、BeautySDK、DevHub 已排除。

## 已落实：同一种功能只保留一份

| 能力 | 唯一归属 | 移除内容 |
| --- | --- | --- |
| Android 系统分享 | core_utils.ShareUtil | core_share 整个库、独立分享页面与依赖 |
| SMB / SFTP / WebDAV | core_nas | lib_upload 的三个协议客户端、配置类型、枚举与 SSHJ/jCIFS 依赖 |
| FTP / FTPS / HTTP 上传 | lib_upload | 页面不再列出已迁出协议 |
| 文件 SHA-256 | core_utils.FileUtil | Utils Demo 的手写摘要循环 |

分享实现保留 content Uri 校验、所有 Uri 的 ClipData、临时读授权，以及非 Activity Context 的 NEW_TASK 支持。通用结果页与 Utils 页面都调用同一 ShareUtil，不保留兼容门面。

上传默认新配置为 FTPS。读取已迁出协议的历史配置时明确失败并指向 core_nas，不自动替换协议、删除配置或迁移密码。旧消费者需要改用 core_nas 的连接/会话 API；SFTP 按该库要求配置主机指纹。支持的 FTP/FTPS/HTTP 配置继续读取。

## 不属于同一种职责的能力

- core_network 负责 HTTP 基础请求；lib_upload 负责服务器上传；core_nas 负责 NAS 会话与文件操作。这些层次继续保留，但相同远程文件协议不再维护两份。
- core_image_render 是 Android Bitmap 后端，phototoolkit-engine 是 Native 图像后端；RAW 库是解码管线。后续去重应按具体操作与消费要求选定后端，不能直接删掉独有格式或处理能力。
- core_pdf 负责图片排版，lib_report_pdf 负责结构化报告模板，lib_document 负责 DOCX/XLSX。相同输出扩展名不等于相同业务。
- Credentials 获取登录凭据，Firebase 在其上建立会话；Billing 负责购买，Entitlement 负责授权判断。
- lib_drop 是局域网分享服务，core_qrcode 是二维码编解码。Nearby 与 lib_html 已移出；当前接收网页由宿主 feature_demo_files 提供。
- Routers 的注解、运行时、KSP 和插件是同一工具链的四个构件，保留四个构建产物；其 Demo 展示重复属于演示编排问题。

不通过保留第二套旧 API 门面来假装完成去重。后续继续按具体职责选定唯一实现，而非只按库名或输出格式合并。

## 验证

本轮运行结果见 [最新测试报告](testing/TEST_REPORT.md)。不执行真机测试；服务器认证、实际分享接收与远程文件操作仍需要相应运行环境验收。

## 仍待收敛的重复点

1. **路由演示**：AnnotationActivity、RouterActivity、ProcessorActivity、PluginActivity 的 inspect 实现相同，均展示同一生成类与 RouteTable。建议保留一个完整路由演示入口；四个构建模块仍承担不同职责。
2. **图片解码**：core_image_render 的 BitmapFileDecoder 与 lib_report_pdf 的 NativePdfCanvas 都有 BitmapFactory 采样、EXIF 方向处理。建议统一解码实现，保留报告层自己的目标尺寸与内存预算。
3. **图片合成**：WatermarkRenderer 与 Native 图片引擎有合成类能力交叉。需要按文字、图片水印、格式与坐标语义逐项选定唯一实现，尚不能认定整个引擎可删除。
4. **Uri 文件导入**：DemoFiles 与 Utils.UriUtil 均复制输入流。统一前须保留 Demo 的取消、文件数量和大小限制。
5. **HTTP 上传基础环节**：core_network 与 lib_upload.http 都有请求体、进度和客户端配置封装。应收敛重复传输原语，保留服务器表单与幂等提交的特有逻辑。

这些仍是待处理项，不计为本轮已完成。
