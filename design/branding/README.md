# DevLib 图标素材

## 当前采用：工具箱 v3

用户确认采用 Android 系统风格工具箱图标：浅薄荷背景、深绿色箱体、奶油色锁扣，不含文字。母版为 `devlib-toolbox-v3.svg` / `devlib-toolbox-v3.png`，圆角与尺寸预览见 `devlib-toolbox-v3-board.png`。

已接入 `app/src/main/res/drawable/ic_launcher_foreground.xml`、`ic_launcher_monochrome.xml` 和 `mipmap-anydpi-v26/v33/ic_launcher.xml`。前景保留透明区域，满幅背景由系统裁切；API 33+ 提供单色主题图标，图标归 App 壳所有，不写入独立库。Manifest 的普通与圆形图标均指向同一自适应资源。

以下内容为未采用的早期设计记录，不是当前启动器图标。

设计：代码括号包围四个可组合模块；右上模块轻微旋转，表示独立能力可接入与扩展。不使用名称或字母缩写。深蓝背景、蓝色基础模块、青色功能模块，与工作台现有配色呼应。

- devlib-icon.svg：可编辑矢量源。
- devlib-icon-1024.png：1024 像素透明圆角画布。
- devlib-icon-192.png：小尺寸预览。

采用直接矢量绘制及 Pillow 栅格化，未调用 AI 图像生成服务。未替换 Android 启动器资源；若接入自适应图标，应使用独立前景和满幅背景，避免双重圆角。
