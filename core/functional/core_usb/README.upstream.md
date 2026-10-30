# lib_usb

独立 Android Library，模块 `:core:lib_usb`，包名 `com.wkq.usb`。通过 Android SAF 访问用户授权的 USB、SD 卡或其他文档目录，无 NAS、Room、Worker、UI 和第三方运行时依赖。

它不提供 NTFS/exFAT 驱动，也不负责 USB 原始块设备访问；系统须先成功挂载设备。不能从库的存在推断所有硬盘或文件系统均可用。

文件系统、挂载、传输大小与临时空间的完整边界见 [USB 存储限制](../../doc/USB_STORAGE_LIMITS.md)。FAT32 单文件最大 4GiB−1；exFAT 小文件通过不代表超过 4GiB 已验收。生产队列的 SAF 提交可能需要两份完整文件空间，目前 SAF 容量未知时无法提前保证空间足够。

## 接入

```groovy
implementation project(':core:lib_usb')
```

调用方通过 `ACTION_OPEN_DOCUMENT_TREE` 获取目录，在返回结果中使用实际授予的读写 flags 调用 `takePersistableUriPermission`，再将树 URI 交给库。选择目录、持久授权及授权撤销提示由宿主负责。所有文件操作在后台线程执行。

```kotlin
val storage = UsbStorage(context, grantedTreeUri.toString())
val entries = storage.list("") // 授权目录下的直接子项
storage.mkdirs("backup")
sourceStream.use { input ->
    storage.write("backup/new.bin", input) { bytes -> /* 更新进度或检查取消 */ }
}
storage.read("backup/new.bin").use { input -> /* 流式消费数据 */ }
storage.delete("backup/new.bin")
storage.deleteEmptyDirectory("backup")
```

## 接口与约束

- `stat/list/visit`：查询元数据、列举目录，`visit` 逐项回调。时间、大小可能未知（`-1`），不保证可靠 mtime。
- `read`：返回输入流，调用方须关闭。
- `write`：使用 64 KiB 缓冲写入新文件，拒绝覆盖；不关闭调用方提供的输入流，内部输出流自动关闭。失败可能留下部分目标，交由宿主的任务恢复或清理流程处理。
- `mkdirs`：创建目录层级。
- `moveNew`：复制到新文件后删除源文件，可通过回调检查取消；只用于文件，不是原子重命名，不支持目录移动。
- `delete`：仅删除文件。
- `deleteEmptyDirectory`：仅删除具有系统删除标记的空子目录，禁止删除授权根。非空目录须由宿主先预览确认，再逐项删除。SAF 不提供原子空目录删除，检查后仍存在外部并发写入窗口。

路径为授权根内的相对路径，拒绝绝对路径、反斜杠、空字符以及 `.`/`..`。`UsbEntry` 为独立元数据；`UsbException.error` 表示库检测到的业务错误，系统权限和 I/O 异常保留给宿主处理。拔盘、只读或授权失效均可能导致操作失败。

NAS 项目通过 `core_transfer/provider/SafProvider` 映射元数据和错误，原有删除预览、任务记录、取消与恢复仍由任务层承担。

## 验证

```text
gradlew.bat :core:lib_usb:testDebugUnitTest
```

独立用例覆盖流式读写与文件删除、目录删除保护及路径越界；真实介质能力以项目测试报告为准。
