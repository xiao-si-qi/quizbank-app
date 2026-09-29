package com.xiaosiqi.quizbank.exam

import com.xiaosiqi.quizbank.alist.AlistClient
import com.xiaosiqi.quizbank.alist.FileRef
import com.xiaosiqi.quizbank.data.QuizStore
import com.xiaosiqi.quizbank.data.prefs.SettingsGateway

class ExamException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 考试仓库：本机考试记录 + 上传到 alist + 管理员拉取所有人的考试记录。
 *
 * 几个刻意的决定：
 * - **只有考试模式**才会生成记录并上传；普通练习只记错题，不产生成绩。
 * - 每条记录**自带逐题快照**（题干/选项/我的答案/正确答案/解析），
 *   所以题库以后重新导入或更新，历史考试依然能原样回顾。
 * - 一次考试一个 JSON 文件，多设备并发上传不会互相覆盖。
 * - 权限完全交给 alist：管理员账号能列出成绩目录下所有用户，普通用户只能看到自己的。
 */
class ExamRepository(
    private val store: QuizStore,
    private val settings: SettingsGateway,
    private val clientProvider: suspend () -> AlistClient,
) {

    // ------------------------------------------------------------ 本机记录

    fun saveLocal(record: ExamRecord): Long = store.saveExam(record)

    fun local(limit: Int = 200): List<ExamRecord> = store.exams(limit)

    fun record(localId: Long): ExamRecord? = store.exam(localId)

    /** 这个题库有没有没交卷的考试。 */
    fun inProgress(bankId: Long): ExamRecord? = store.inProgressExam(bankId)

    fun localSummary(limit: Int = 500): ExamSummary = ExamSummary.of(store.exams(limit).filter { !it.inProgress })

    fun deleteLocal(localId: Long) = store.deleteExam(localId)

    /** 可以上传的：只有交过卷的。进行中的考试不上传。 */
    fun pending(): List<ExamRecord> = store.exams(1000).filter { !it.uploaded && !it.inProgress }

    // ------------------------------------------------------------ 远端目录

    fun scoreRoot(): String = settings.current().scoreUploadPath.trim().trimEnd('/')

    fun userDirName(): String = settings.current().effectiveName.trim().ifBlank { "匿名" }

    fun myDir(): String {
        val root = scoreRoot()
        if (root.isEmpty()) return ""
        return "$root/${userDirName()}"
    }

    fun hasUploadTarget(): Boolean = scoreRoot().isNotEmpty()

    // ---------------------------------------------------------------- 上传

    /** 上传一次考试成绩（始终带逐题明细，管理员才能回顾每道题）。 */
    suspend fun upload(record: ExamRecord): String {
        if (record.inProgress) throw ExamException("这场考试还没交卷，交卷后才能上传成绩。")
        val root = scoreRoot()
        if (root.isEmpty()) {
            throw ExamException("还没有设置「考试记录上传目录」。\n请到「设置 → 考试记录」里填写 alist 上的目标目录。")
        }
        val client = clientProvider()
        val dir = myDir()
        client.ensureDir(dir)
        val path = "$dir/${record.fileName()}"
        client.uploadText(path, ExamJson.encode(record))
        if (record.localId > 0) store.markExamUploaded(record.localId, path)
        return path
    }

    /** 删掉云端某个文件（交卷后清理掉旧的「进行中」文件）。 */
    suspend fun removeRemote(path: String) {
        if (path.isBlank()) return
        runCatching { clientProvider().remove(path) }
    }

    /**
     * 找这个题库在云端**进行中**的那场考试（跨设备续考用）。
     *
     * 只下载文件名以 `inprogress_<题库id>_` 开头的那一两个文件，
     * 不会把账号下所有成绩都拉一遍。
     */
    suspend fun findInProgress(bankId: Long, maxFiles: Int = 20): ExamRecord? {
        val dir = myDir()
        if (dir.isBlank()) return null
        val client = clientProvider()
        val prefix = ExamRecord.inProgressPrefix(bankId)
        val candidates = runCatching { client.list(dir) }
            .getOrNull()
            .orEmpty()
            .filter { !it.isDir && it.name.startsWith(prefix) && it.name.endsWith(".json", true) }
            .sortedByDescending { it.name }
            .take(maxFiles)
        var best: ExamRecord? = null
        for (item in candidates) {
            val path = "$dir/${item.name}".replace("//", "/")
            val record = runCatching {
                ExamJson.decode(String(client.download(FileRef.AlistPath(path)).bytes, Charsets.UTF_8))
                    .copy(remotePath = path, uploaded = true)
            }.getOrNull() ?: continue
            if (record.inProgress && (best == null || record.updatedAt > best.updatedAt)) best = record
        }
        return best
    }

    suspend fun uploadAllPending(): Pair<Int, List<String>> {
        val items = pending()
        var ok = 0
        val errors = ArrayList<String>()
        for (item in items) {
            runCatching { upload(item) }
                .onSuccess { ok++ }
                .onFailure { if (errors.size < 5) errors.add("${item.bankName}：${it.message}") }
        }
        return ok to errors
    }

    // ------------------------------------------------------------ 拉取记录

    data class RemoteResult(
        val records: List<ExamRecord>,
        val scanned: Int,
        val failed: Int,
        val errors: List<String>,
    )

    /** 某个用户的全部考试记录（管理员视角）。 */
    data class UserExams(val userName: String, val records: List<ExamRecord>) {
        val summary: ExamSummary get() = ExamSummary.of(records)
    }

    private suspend fun readDir(dir: String, maxFiles: Int): RemoteResult {
        val client = clientProvider()
        val listing = client.list(dir)
        val files = listing.filter { !it.isDir && it.name.endsWith(".json", true) }.take(maxFiles)
        val records = ArrayList<ExamRecord>(files.size)
        var failed = 0
        val errors = ArrayList<String>()

        for (item in files) {
            val path = "$dir/${item.name}".replace("//", "/")
            runCatching {
                val file = client.download(FileRef.AlistPath(path))
                ExamJson.decode(String(file.bytes, Charsets.UTF_8)).copy(remotePath = path, uploaded = true)
            }.onSuccess { records.add(it) }
                .onFailure {
                    failed++
                    if (errors.size < 5) errors.add("${item.name}：${it.message}")
                }
        }
        return RemoteResult(records.sortedByDescending { it.finishedAt }, files.size, failed, errors)
    }

    /** @param scopeMine true 只读自己的目录；false 读根目录下所有人的记录。 */
    suspend fun fetchRemote(scopeMine: Boolean = true, maxFiles: Int = 200): RemoteResult {
        val root = scoreRoot()
        if (root.isEmpty()) throw ExamException("还没有设置「考试记录上传目录」，请先到设置里填写。")
        return readDir(if (scopeMine) myDir() else root, maxFiles)
    }

    /**
     * 管理员视角：列出成绩目录下每个用户的考试记录。
     * 普通用户调用会因为 alist 的路径权限被拒，异常信息会直接展示给用户。
     */
    suspend fun fetchAllUsers(maxUsers: Int = 50, maxFilesPerUser: Int = 300): List<UserExams> {
        val root = scoreRoot()
        if (root.isEmpty()) throw ExamException("还没有设置「考试记录上传目录」，请先到设置里填写。")
        val client = clientProvider()
        val dirs = client.list(root).filter { it.isDir }.take(maxUsers)
        if (dirs.isEmpty()) {
            val flat = readDir(root, maxFilesPerUser)
            return flat.records.groupBy { it.user.ifBlank { "未署名" } }
                .map { (name, list) -> UserExams(name, list.sortedByDescending { it.finishedAt }) }
                .sortedByDescending { it.records.size }
        }
        val out = ArrayList<UserExams>(dirs.size)
        for (dir in dirs) {
            val path = "$root/${dir.name}".replace("//", "/")
            val result = runCatching { readDir(path, maxFilesPerUser) }.getOrNull() ?: continue
            if (result.records.isEmpty()) continue
            out.add(UserExams(dir.name, result.records))
        }
        return out.sortedByDescending { it.records.size }
    }

    suspend fun listUsers(): List<String> {
        val root = scoreRoot()
        if (root.isEmpty()) return emptyList()
        return clientProvider().list(root).filter { it.isDir }.map { it.name }
    }

    suspend fun ensureRoot() {
        val root = scoreRoot()
        if (root.isEmpty()) return
        clientProvider().ensureDir(root)
    }
}
