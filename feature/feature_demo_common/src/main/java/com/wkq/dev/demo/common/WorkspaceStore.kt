package com.wkq.dev.demo.common

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 已登记的宿主文件资产，包含标识、规范路径、显示名、来源类型、工具及大小和创建时间。 */
data class WorkspaceAsset(
    val id: String,
    val path: String,
    val name: String,
    val kind: String,
    val tool: String,
    val created: Long,
    val bytes: Long,
) {
    val file
        get() = File(path)
}

/** 记录前台操作状态、工具路由及输入输出资产 ID，不代表可自动恢复的后台任务。 */
data class WorkspaceTask(
    val id: String,
    val title: String,
    val status: String,
    val created: Long,
    val outputs: Int,
    val route: String,
    val inputIds: List<String>,
    val outputIds: List<String>,
)

/** 工作区的只读展示快照，组合文件、操作历史、最近工具与收藏路由。 */
data class WorkspaceSnapshot(
    val assets: List<WorkspaceAsset>,
    val tasks: List<WorkspaceTask>,
    val recent: List<String>,
    val favorites: Set<String>,
)

/** 仅保存宿主资产和前台操作历史。传输引擎的队列仍由 lib_transfer 管理。调用方使用 IO。 */
class WorkspaceStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "devlib-workspace.db", null, 2) {
    private val root = File(context.filesDir, "shared/demo")
    private val preferences =
        context.getSharedPreferences("workspace-navigation", Context.MODE_PRIVATE)
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    /** 首次创建资产和前台操作记录表，资产规范路径保持唯一。 */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE assets (id TEXT PRIMARY KEY, path TEXT UNIQUE NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, tool TEXT NOT NULL, created INTEGER NOT NULL, bytes INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE tasks (id TEXT PRIMARY KEY, title TEXT NOT NULL, status TEXT NOT NULL, created INTEGER NOT NULL, outputs INTEGER NOT NULL DEFAULT 0, route TEXT NOT NULL DEFAULT '', inputIds TEXT NOT NULL DEFAULT '', outputIds TEXT NOT NULL DEFAULT '')"
        )
    }

    /** 从旧版数据库补齐任务路由及输入输出资产字段，保留已有记录。 */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN route TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tasks ADD COLUMN inputIds TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tasks ADD COLUMN outputIds TEXT NOT NULL DEFAULT ''")
        }
    }

    /** 递增内存版本号，通知订阅者重新读取工作区快照。 */
    private fun changed() {
        mutableRevision.value += 1
    }

    /** 在 IO 登记工作区内的现存文件，规范路径已存在时复用资产；越界或缺失文件返回空。 */
    @Synchronized
    fun register(file: File, name: String, kind: String, tool: String): WorkspaceAsset? {
        if (!file.isFile || !WorkspacePolicy.managed(root, file)) return null
        val path = file.canonicalPath
        writableDatabase.query("assets", null, "path=?", arrayOf(path), null, null, null).use {
            cursor ->
            if (cursor.moveToFirst()) return asset(cursor)
        }
        val value =
            WorkspaceAsset(
                UUID.randomUUID().toString(),
                path,
                name,
                kind,
                tool,
                System.currentTimeMillis(),
                file.length(),
            )
        writableDatabase.insertOrThrow(
            "assets",
            null,
            ContentValues().apply {
                put("id", value.id)
                put("path", value.path)
                put("name", value.name)
                put("kind", value.kind)
                put("tool", value.tool)
                put("created", value.created)
                put("bytes", value.bytes)
            },
        )
        changed()
        return value
    }

    /** 按列名把当前游标行映射为资产记录，不移动或关闭调用方游标。 */
    private fun asset(c: android.database.Cursor) =
        WorkspaceAsset(
            c.getString(c.getColumnIndexOrThrow("id")),
            c.getString(c.getColumnIndexOrThrow("path")),
            c.getString(c.getColumnIndexOrThrow("name")),
            c.getString(c.getColumnIndexOrThrow("kind")),
            c.getString(c.getColumnIndexOrThrow("tool")),
            c.getLong(c.getColumnIndexOrThrow("created")),
            c.getLong(c.getColumnIndexOrThrow("bytes")),
        )

    /** 按 ID 查找资产，仅返回仍存在且位于工作区内的文件；由 IO 调用。 */
    @Synchronized
    fun find(id: String): WorkspaceAsset? =
        readableDatabase.query("assets", null, "id=?", arrayOf(id), null, null, null).use { c ->
            if (!c.moveToFirst()) null
            else asset(c).takeIf { it.file.isFile && WorkspacePolicy.managed(root, it.file) }
        }

    /** 按规范路径查找已登记资产，不额外检查当前文件是否存在；由 IO 调用。 */
    @Synchronized
    fun findByFile(file: File): WorkspaceAsset? =
        readableDatabase
            .query("assets", null, "path=?", arrayOf(file.canonicalPath), null, null, null)
            .use { c -> if (c.moveToFirst()) asset(c) else null }

    /** 清理并限制资产显示名后写入数据库，不修改磁盘文件名；空名称会被拒绝。 */
    @Synchronized
    fun rename(id: String, name: String) {
        val clean = name.trim().take(160)
        require(clean.isNotBlank())
        writableDatabase.update(
            "assets",
            ContentValues().apply { put("name", clean) },
            "id=?",
            arrayOf(id),
        )
        changed()
    }

    /** 创建运行中的前台操作记录并关联已登记输入，返回供结束更新使用的任务 ID。 */
    @Synchronized
    fun start(title: String, route: String, inputs: List<File>): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow(
            "tasks",
            null,
            ContentValues().apply {
                put("id", id)
                put("title", title)
                put("status", "running")
                put("created", System.currentTimeMillis())
                put("route", route)
                put(
                    "inputIds",
                    inputs.mapNotNull { file -> findByFile(file)?.id }.joinToString("|"),
                )
            },
        )
        changed()
        return id
    }

    /** 写入前台操作最终状态与产物 ID，更新产物数并通知工作区刷新。 */
    @Synchronized
    fun finish(id: String, status: String, outputIds: List<String> = emptyList()) {
        writableDatabase.update(
            "tasks",
            ContentValues().apply {
                put("status", status)
                put("outputs", outputIds.size)
                put("outputIds", outputIds.joinToString("|"))
            },
            "id=?",
            arrayOf(id),
        )
        changed()
    }

    /** 在 IO 读取最近 300 个有效资产、100 条操作及导航偏好；不触发后台任务恢复。 */
    @Synchronized
    fun snapshot(): WorkspaceSnapshot {
        val assets =
            readableDatabase
                .query("assets", null, null, null, null, null, "created DESC", "300")
                .use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val a = asset(c)
                            if (a.file.isFile && WorkspacePolicy.managed(root, a.file)) add(a)
                        }
                    }
                }
        val tasks =
            readableDatabase
                .query("tasks", null, null, null, null, null, "created DESC", "100")
                .use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            WorkspaceTask(
                                c.getString(0),
                                c.getString(1),
                                c.getString(2),
                                c.getLong(3),
                                c.getInt(4),
                                c.getString(5),
                                c.getString(6).split('|').filter { it.isNotBlank() },
                                c.getString(7).split('|').filter { it.isNotBlank() },
                            )
                        )
                    }
                }
        return WorkspaceSnapshot(
            assets,
            tasks,
            preferences.getString("recent", "").orEmpty().split('|').filter { it.isNotBlank() },
            preferences.getStringSet("favorites", emptySet()).orEmpty().toSet(),
        )
    }

    /** 将路由移到最近使用列表首位，去重后最多保留 8 项并通知刷新。 */
    @Synchronized
    fun visit(route: String) {
        val recent =
            (listOf(route) + preferences.getString("recent", "").orEmpty().split('|'))
                .filter { it.isNotBlank() }
                .distinct()
                .take(8)
        preferences.edit().putString("recent", recent.joinToString("|")).apply()
        changed()
    }

    /** 切换指定路由的收藏状态，将结果保存到宿主偏好并通知刷新。 */
    @Synchronized
    fun favorite(route: String) {
        val values = preferences.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
        if (!values.add(route)) values.remove(route)
        preferences.edit().putStringSet("favorites", values).apply()
        changed()
    }

    companion object {
        @Volatile private var instance: WorkspaceStore? = null

        /** 使用应用 Context 获取进程单例；首次打开将遗留运行记录标为中断，调用方须使用 IO。 */
        fun get(context: Context): WorkspaceStore =
            instance
                ?: synchronized(this) {
                    instance
                        ?: WorkspaceStore(context.applicationContext).also { store ->
                            // 每个进程只执行一次：前台协程任务不能冒充可后台恢复的队列。
                            store.writableDatabase.execSQL(
                                "UPDATE tasks SET status='interrupted' WHERE status='running'"
                            )
                            instance = store
                        }
                }
    }
}
