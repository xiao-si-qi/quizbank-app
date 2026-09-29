package com.xiaosiqi.quizbank.data.repo

import com.xiaosiqi.quizbank.alist.AlistClient
import com.xiaosiqi.quizbank.alist.AlistSession
import com.xiaosiqi.quizbank.alist.FileRef
import com.xiaosiqi.quizbank.data.QuizStore
import com.xiaosiqi.quizbank.data.prefs.SettingsGateway
import com.xiaosiqi.quizbank.excel.SheetTable
import com.xiaosiqi.quizbank.excel.WorkbookLoader
import com.xiaosiqi.quizbank.image.ImageStore
import com.xiaosiqi.quizbank.importer.BankListParser
import com.xiaosiqi.quizbank.importer.ImportException
import com.xiaosiqi.quizbank.importer.QuestionParser
import com.xiaosiqi.quizbank.importer.TemplateFactory
import com.xiaosiqi.quizbank.model.Bank
import com.xiaosiqi.quizbank.model.BankListItem
import com.xiaosiqi.quizbank.model.QuestionType
import com.xiaosiqi.quizbank.rich.RichText

/** 题库列表快照：既包含远端条目，也标记哪些已经下载过。 */
data class BankListSnapshot(
    val items: List<BankListItem>,
    val warnings: List<String>,
    val sourceLabel: String,
    val installedKeys: Set<String>,
) {
    fun keyOf(item: BankListItem): String = item.remoteId.ifBlank { item.fileUrl }

    fun isInstalled(item: BankListItem): Boolean = keyOf(item) in installedKeys
}

data class ImportSummary(
    val bankId: Long,
    val bankName: String,
    val questionCount: Int,
    val typeCounts: Map<QuestionType, Int>,
    val warnings: List<String>,
    val skipped: Int,
    val fileName: String,
    /** 题库里引用到的图片（用于「顺便下载图片」）。 */
    val imageRefs: List<String> = emptyList(),
    val imageBase: String = "",
)

data class BankMeta(
    val name: String = "",
    val category: String = "",
    val description: String = "",
    val remoteId: String = "",
    val fileUrl: String = "",
    val resolvedUrl: String = "",
    val declaredCount: Int = 0,
    val version: String = "",
    val tags: List<String> = emptyList(),
    val sourceName: String = "",
    val imageBase: String = "",
    /** 考试组卷配置（来自题库列表 Excel 的可选列）。 */
    val blueprint: com.xiaosiqi.quizbank.exam.ExamBlueprint = com.xiaosiqi.quizbank.exam.ExamBlueprint(),
)

/**
 * 题库仓库：负责「从 alist 拿文件 → 解析 → 入库 → 预下载图片」的完整流程。
 * UI 只跟它打交道，不关心 Excel 与网络细节。
 */
class BankRepository(
    private val store: QuizStore,
    private val settings: SettingsGateway,
    private val session: AlistSession,
    private val images: ImageStore? = null,
) {

    // ------------------------------------------------------------ 连接相关

    fun client(): AlistClient = session.client()

    suspend fun authorizedClient(): AlistClient = session.authorized()

    suspend fun testConnection(baseUrl: String, token: String): String = session.testConnection(baseUrl, token)

    suspend fun login(baseUrl: String, username: String, password: String): String =
        session.login(baseUrl, username, password)

    suspend fun loginWithSaved(): String {
        val s = settings.current()
        return session.login(s.baseUrl, s.username, s.password)
    }

    fun logout() = session.logout()

    suspend fun checkBankListSource(source: String, password: String = ""): String {
        val s = settings.current()
        val client = session.authorized()
        val ref = FileRef.parse(source, s.baseUrl)
        val file = client.download(ref, password)
        val sheets = WorkbookLoader.load(file.bytes, file.fileName.ifBlank { "题库列表" })
        val items = parseBankList(sheets).first
        return "连接成功，共读到 ${items.size} 个题库"
    }

    // ------------------------------------------------------------ 题库列表

    suspend fun loadBankList(sourceOverride: String? = null): BankListSnapshot {
        val s = settings.current()
        val source = (sourceOverride ?: s.listPath).trim()
        if (source.isEmpty()) {
            throw ImportException("还没有设置「题库列表」地址。\n请到「设置」里填写题库列表 Excel 的 alist 路径或直链。")
        }
        val file = session.withAuth { client ->
            client.download(FileRef.parse(source, s.baseUrl), s.listPassword)
        }
        val sheets = WorkbookLoader.load(file.bytes, file.fileName.ifBlank { "题库列表.xlsx" })
        val (items, warnings) = parseBankList(sheets)

        val installed = store.banks().map { it.remoteId.ifBlank { it.fileUrl } }.toSet()
        val label = file.fileName.ifBlank { source }
        settings.edit {
            it.copy(
                lastSyncAt = System.currentTimeMillis(),
                lastSyncSummary = "同步成功：$label，共 ${items.size} 个题库",
            )
        }
        return BankListSnapshot(items, warnings, label, installed)
    }

    private fun parseBankList(sheets: List<SheetTable>): Pair<List<BankListItem>, List<String>> {
        var best: BankListParser.Result? = null
        val errors = ArrayList<String>()
        for (sheet in sheets) {
            val result = runCatching { BankListParser.parse(sheet) }
                .onFailure { errors.add("「${sheet.name}」：${it.message}") }
                .getOrNull()
            val current = best
            if (result != null && (current == null || result.items.size > current.items.size)) best = result
        }
        return best?.let { it.items to it.warnings }
            ?: throw ImportException(
                "这个 Excel 看起来不是「题库列表」。\n" +
                    (errors.firstOrNull() ?: "没有找到包含「题库名称」「文件地址」的表头。")
            )
    }

    // ---------------------------------------------------------------- 导入

    suspend fun importBankItem(item: BankListItem, sourceLabel: String = ""): ImportSummary {
        val s = settings.current()
        val (file, resolvedUrl) = session.withAuth { client ->
            client.downloadWithUrl(FileRef.parse(item.fileUrl, s.baseUrl), s.listPassword)
        }
        val meta = BankMeta(
            name = item.name,
            category = item.category,
            description = item.description,
            remoteId = item.remoteId,
            fileUrl = item.fileUrl,
            resolvedUrl = resolvedUrl,
            declaredCount = item.declaredCount,
            version = item.version,
            tags = item.tags,
            sourceName = sourceLabel,
            imageBase = FileRef.directoryOf(item.fileUrl, s.baseUrl),
            blueprint = item.blueprint,
        )
        return importBytes(file.bytes, file.fileName.ifBlank { "${item.name}.xlsx" }, meta)
    }

    /** 重新下载并覆盖导入（保留收藏/错题/记录中仍然存在的题目）。 */
    suspend fun reimportBank(bank: Bank): ImportSummary {
        val s = settings.current()
        val source = bank.fileUrl.ifBlank { bank.resolvedUrl }
        val (file, resolvedUrl) = session.withAuth { client ->
            client.downloadWithUrl(FileRef.parse(source, s.baseUrl), s.listPassword)
        }
        val meta = BankMeta(
            name = bank.name,
            category = bank.category,
            description = bank.description,
            remoteId = bank.remoteId,
            fileUrl = bank.fileUrl,
            resolvedUrl = resolvedUrl,
            declaredCount = bank.declaredCount,
            version = bank.version,
            tags = bank.tags,
            sourceName = bank.sourceName,
            imageBase = FileRef.directoryOf(source, s.baseUrl),
            blueprint = com.xiaosiqi.quizbank.exam.ExamJson.decodeBlueprint(bank.examBlueprintJson),
        )
        return importBytes(file.bytes, file.fileName.ifBlank { "${bank.name}.xlsx" }, meta, existingId = bank.id)
    }

    fun importLocalFile(bytes: ByteArray, fileName: String): ImportSummary {
        val dir = if (fileName.contains('/')) fileName.substringBeforeLast('/') else ""
        val meta = BankMeta(
            name = fileName.substringAfterLast('/').substringBeforeLast('.'),
            fileUrl = "local://$fileName",
            sourceName = "本地文件",
            imageBase = dir,
        )
        return importBytes(bytes, fileName, meta)
    }

    fun importSample(): ImportSummary = importBytes(
        TemplateFactory.sampleBank(),
        "示例题库.xlsx",
        BankMeta(
            name = "综合练习（内置示例）",
            category = "示例",
            description = "覆盖单选/多选/判断/填空/简答，可直接练习",
            remoteId = "demo-1",
            fileUrl = "builtin://sample",
            declaredCount = 12,
            version = "v1",
            tags = listOf("示例", "入门"),
            sourceName = "内置",
        ),
    )

    /**
     * 解析并入库。整个过程是「先解析成功、再落库」，解析失败不会破坏已有题库。
     */
    fun importBytes(
        bytes: ByteArray,
        fileName: String,
        meta: BankMeta,
        existingId: Long = 0L,
    ): ImportSummary {
        val sheets = WorkbookLoader.load(bytes, fileName)
        val parsed = QuestionParser.parseWorkbook(sheets)
        val name = meta.name.ifBlank { fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "未命名题库" } }

        val bank = Bank(
            id = existingId,
            remoteId = meta.remoteId,
            name = name,
            category = meta.category,
            description = meta.description,
            fileUrl = meta.fileUrl,
            resolvedUrl = meta.resolvedUrl,
            declaredCount = meta.declaredCount.takeIf { it > 0 } ?: parsed.count,
            version = meta.version,
            tags = meta.tags,
            importedAt = System.currentTimeMillis(),
            sourceName = meta.sourceName,
            imageBase = meta.imageBase,
            examBlueprintJson = com.xiaosiqi.quizbank.exam.ExamJson.encodeBlueprint(meta.blueprint),
        )
        val bankId = store.upsertBank(bank)
        store.replaceQuestions(bankId, parsed.questions)

        return ImportSummary(
            bankId = bankId,
            bankName = name,
            questionCount = parsed.count,
            typeCounts = parsed.questions.groupingBy { it.type }.eachCount(),
            warnings = parsed.warnings,
            skipped = parsed.skipped,
            fileName = fileName,
            imageRefs = collectImageRefs(parsed.questions),
            imageBase = meta.imageBase,
        )
    }

    /** 汇总题库里所有图片引用（题干、选项、解析都算）。 */
    fun collectImageRefs(questions: List<com.xiaosiqi.quizbank.model.Question>): List<String> {
        val out = LinkedHashSet<String>()
        questions.forEach { q ->
            out.addAll(RichText.imageRefs(q.stem))
            q.options.forEach { out.addAll(RichText.imageRefs(it.text)) }
            if (q.analysis.isNotBlank()) out.addAll(RichText.imageRefs(q.analysis))
        }
        return out.toList()
    }

    /** 预下载题库里的图片（导入后调用，保证离线也能看图）。 */
    suspend fun prefetchImages(
        summary: ImportSummary,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImageStore.PrefetchResult {
        val imageStore = images ?: return ImageStore.PrefetchResult(0, 0, emptyList())
        if (summary.imageRefs.isEmpty()) return ImageStore.PrefetchResult(0, 0, emptyList())
        val baseUrl = settings.current().baseUrl
        val pairs = summary.imageRefs.map { it to summary.imageBase }
        return imageStore.prefetch(pairs, baseUrl, onProgress)
    }

    // ---------------------------------------------------------------- 其他

    fun questionTemplate(): ByteArray = TemplateFactory.questionTemplate()

    fun bankListTemplate(): ByteArray = TemplateFactory.bankListTemplate()

    fun sampleBank(): ByteArray = TemplateFactory.sampleBank()

    fun sampleBankList(): ByteArray = TemplateFactory.sampleBankList()
}
