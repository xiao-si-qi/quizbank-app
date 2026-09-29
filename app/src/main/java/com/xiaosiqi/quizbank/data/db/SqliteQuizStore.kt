package com.xiaosiqi.quizbank.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.xiaosiqi.quizbank.data.JsonCodec
import com.xiaosiqi.quizbank.data.QuizStore
import com.xiaosiqi.quizbank.importer.TextNorm
import com.xiaosiqi.quizbank.model.Bank
import com.xiaosiqi.quizbank.model.BankProgress
import com.xiaosiqi.quizbank.model.BankStats
import com.xiaosiqi.quizbank.model.DayCount
import com.xiaosiqi.quizbank.model.OverallStats
import com.xiaosiqi.quizbank.model.PracticeMode
import com.xiaosiqi.quizbank.model.Question
import com.xiaosiqi.quizbank.model.QuestionType
import com.xiaosiqi.quizbank.exam.ExamJson
import com.xiaosiqi.quizbank.exam.ExamRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 直接用 SQLiteOpenHelper 手写持久层。
 *
 * 不用 Room 的原因：Room 需要 KSP 注解处理器，构建链路长、出问题难排查，
 * 而这里的表结构很简单（6 张表），手写 SQL 的可控性更高。
 * 题目数据随时可以从 alist 重新下载，所以升级时直接重建表即可。
 */
class SqliteQuizStore(context: Context) : QuizStore {

    private val helper = Helper(context.applicationContext)

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            SCHEMA.forEach { db.execSQL(it) }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 -> v2：成绩表换成考试记录表（记录里自带逐题快照）。
            // 错题、收藏、进度都不动，用户数据不丢。
            if (oldVersion < 2) {
                db.execSQL("DROP TABLE IF EXISTS scores")
                db.execSQL(
                    """
                    CREATE TABLE exams(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      data TEXT NOT NULL DEFAULT '',
                      bank_id INTEGER NOT NULL DEFAULT 0,
                      user_name TEXT NOT NULL DEFAULT '',
                      uploaded INTEGER NOT NULL DEFAULT 0,
                      remote_path TEXT NOT NULL DEFAULT '',
                      created_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX idx_exams_created ON exams(created_at)")
            }
        }
    }

    private val read get() = helper.readableDatabase
    private val write get() = helper.writableDatabase

    // ------------------------------------------------------------------ 题库

    override fun banks(): List<Bank> =
        read.rawQuery("SELECT * FROM banks ORDER BY imported_at DESC", null).use { c -> c.mapAll(::readBank) }

    override fun bank(id: Long): Bank? =
        read.rawQuery("SELECT * FROM banks WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) readBank(c) else null
        }

    override fun findBank(remoteId: String, fileUrl: String): Bank? {
        val sql = when {
            remoteId.isNotBlank() && fileUrl.isNotBlank() ->
                "SELECT * FROM banks WHERE remote_id=? OR file_url=? LIMIT 1"
            remoteId.isNotBlank() -> "SELECT * FROM banks WHERE remote_id=? LIMIT 1"
            fileUrl.isNotBlank() -> "SELECT * FROM banks WHERE file_url=? LIMIT 1"
            else -> return null
        }
        val args = when {
            remoteId.isNotBlank() && fileUrl.isNotBlank() -> arrayOf(remoteId, fileUrl)
            remoteId.isNotBlank() -> arrayOf(remoteId)
            else -> arrayOf(fileUrl)
        }
        return read.rawQuery(sql, args).use { c -> if (c.moveToFirst()) readBank(c) else null }
    }

    override fun upsertBank(bank: Bank): Long {
        val values = ContentValues().apply {
            put("remote_id", bank.remoteId)
            put("name", bank.name)
            put("category", bank.category)
            put("description", bank.description)
            put("file_url", bank.fileUrl)
            put("resolved_url", bank.resolvedUrl)
            put("declared_count", bank.declaredCount)
            put("version", bank.version)
            put("tags", JsonCodec.encodeStrings(bank.tags))
            put("imported_at", if (bank.importedAt > 0) bank.importedAt else System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
            put("source_name", bank.sourceName)
            put("image_base", bank.imageBase)
            put("exam_blueprint", bank.examBlueprintJson)
        }
        val existing = if (bank.id > 0) bank.id else findBank(bank.remoteId, bank.fileUrl)?.id ?: 0L
        return if (existing > 0) {
            write.update("banks", values, "id=?", arrayOf(existing.toString()))
            existing
        } else {
            write.insert("banks", null, values)
        }
    }

    override fun deleteBank(bankId: Long) {
        write.beginTransaction()
        try {
            write.delete("questions", "bank_id=?", arrayOf(bankId.toString()))
            write.delete("wrongs", "bank_id=?", arrayOf(bankId.toString()))
            write.delete("favorites", "bank_id=?", arrayOf(bankId.toString()))
            write.delete("records", "bank_id=?", arrayOf(bankId.toString()))
            write.delete("progress", "bank_id=?", arrayOf(bankId.toString()))
            write.delete("banks", "id=?", arrayOf(bankId.toString()))
            write.setTransactionSuccessful()
        } finally {
            write.endTransaction()
        }
    }

    // ------------------------------------------------------------------ 题目

    override fun questionCount(bankId: Long): Int =
        read.rawQuery("SELECT COUNT(*) FROM questions WHERE bank_id=?", arrayOf(bankId.toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    override fun questions(bankId: Long): List<Question> =
        read.rawQuery("SELECT * FROM questions WHERE bank_id=? ORDER BY order_index ASC", arrayOf(bankId.toString()))
            .use { c -> c.mapAll(::readQuestion) }

    override fun questionsByIds(ids: List<Long>): List<Question> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Question>(ids.size)
        ids.chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            read.rawQuery("SELECT * FROM questions WHERE id IN ($placeholders)", chunk.map { it.toString() }.toTypedArray())
                .use { c -> out.addAll(c.mapAll(::readQuestion)) }
        }
        val byId = out.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    override fun question(id: Long): Question? =
        read.rawQuery("SELECT * FROM questions WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) readQuestion(c) else null
        }

    /**
     * 用新解析出的题目替换题库内容，但**尽量保留原有题目行的 id**：
     * 先按「同一顺序 + 题干一致」配对，再按题干兜底配对，配不上的才新增。
     * 这样重新导入升级过的题库时，收藏、错题、答题记录都不会丢。
     */
    override fun replaceQuestions(bankId: Long, questions: List<Question>) {
        write.beginTransaction()
        try {
            val existing = ArrayList<Triple<Long, Int, String>>()
            read.rawQuery(
                "SELECT id, order_index, stem FROM questions WHERE bank_id=? ORDER BY order_index ASC",
                arrayOf(bankId.toString()),
            ).use { c ->
                while (c.moveToNext()) existing.add(Triple(c.getLong(0), c.getInt(1), c.getString(2).orEmpty()))
            }
            val byIndex = existing.associateBy { it.second }
            val byStem = existing.groupBy { TextNorm.loose(it.third) }
            val reused = HashSet<Long>()
            val updates = ArrayList<Pair<Long, Question>>()
            val inserts = ArrayList<Question>()

            questions.forEachIndexed { index, q ->
                val order = q.orderIndex.takeIf { it > 0 } ?: index
                val stemKey = TextNorm.loose(q.stem)
                val sameIndex = byIndex[order]
                val candidate = when {
                    sameIndex != null && TextNorm.loose(sameIndex.third) == stemKey -> sameIndex
                    else -> byStem[stemKey]?.firstOrNull { it.first !in reused }
                }
                if (candidate != null && reused.add(candidate.first)) updates.add(candidate.first to q)
                else inserts.add(q)
            }

            val leftovers = existing.map { it.first }.filterNot { it in reused }
            if (leftovers.isNotEmpty()) {
                leftovers.chunked(400).forEach { chunk ->
                    val ph = chunk.joinToString(",") { "?" }
                    val args = chunk.map { it.toString() }.toTypedArray()
                    write.delete("questions", "id IN ($ph)", args)
                    write.delete("wrongs", "question_id IN ($ph)", args)
                    write.delete("favorites", "question_id IN ($ph)", args)
                    write.delete("records", "question_id IN ($ph)", args)
                }
            }

            updates.forEachIndexed { i, (id, q) ->
                write.update("questions", questionValues(bankId, q, q.orderIndex.takeIf { it > 0 } ?: i), "id=?", arrayOf(id.toString()))
            }

            if (inserts.isNotEmpty()) {
                val statement = write.compileStatement(INSERT_QUESTION)
                inserts.forEach { q ->
                    statement.clearBindings()
                    bindQuestion(statement, bankId, q)
                    statement.executeInsert()
                }
            }
            write.setTransactionSuccessful()
        } finally {
            write.endTransaction()
        }
    }

    private fun questionValues(bankId: Long, q: Question, orderIndex: Int): ContentValues = ContentValues().apply {
        put("bank_id", bankId)
        put("order_index", orderIndex)
        put("qtype", q.type.name)
        put("stem", q.stem)
        put("options", JsonCodec.encodeOptions(q.options))
        put("answers", JsonCodec.encodeStrings(q.answers))
        put("analysis", q.analysis)
        put("difficulty", q.difficulty)
        put("chapter", q.chapter)
        put("tags", JsonCodec.encodeStrings(q.tags))
        put("score", q.score)
        put("source_row", q.sourceRow)
    }

    private fun bindQuestion(statement: android.database.sqlite.SQLiteStatement, bankId: Long, q: Question) {
        statement.bindLong(1, bankId)
        statement.bindLong(2, q.orderIndex.toLong())
        statement.bindString(3, q.type.name)
        statement.bindString(4, q.stem)
        statement.bindString(5, JsonCodec.encodeOptions(q.options))
        statement.bindString(6, JsonCodec.encodeStrings(q.answers))
        statement.bindString(7, q.analysis)
        statement.bindLong(8, q.difficulty.toLong())
        statement.bindString(9, q.chapter)
        statement.bindString(10, JsonCodec.encodeStrings(q.tags))
        statement.bindDouble(11, q.score)
        statement.bindLong(12, q.sourceRow.toLong())
    }

    // ------------------------------------------------------------ 错题与收藏

    override fun wrongQuestions(bankId: Long?): List<Question> {
        val (where, args) = bankFilter(bankId)
        return read.rawQuery(
            "SELECT q.* FROM questions q JOIN wrongs w ON w.question_id=q.id $where ORDER BY w.last_wrong_at DESC",
            args,
        ).use { c -> c.mapAll(::readQuestion) }
    }

    override fun favoriteQuestions(): List<Question> =
        read.rawQuery(
            "SELECT q.* FROM questions q JOIN favorites f ON f.question_id=q.id ORDER BY f.created_at DESC",
            null,
        ).use { c -> c.mapAll(::readQuestion) }

    override fun favoriteIds(): Set<Long> =
        read.rawQuery("SELECT question_id FROM favorites", null).use { c ->
            val set = HashSet<Long>()
            while (c.moveToNext()) set.add(c.getLong(0))
            set
        }

    override fun wrongIds(bankId: Long?): Set<Long> {
        val (where, args) = bankFilter(bankId, "bank_id")
        return read.rawQuery("SELECT question_id FROM wrongs $where", args).use { c ->
            val set = HashSet<Long>()
            while (c.moveToNext()) set.add(c.getLong(0))
            set
        }
    }

    override fun isFavorite(questionId: Long): Boolean =
        read.rawQuery("SELECT 1 FROM favorites WHERE question_id=?", arrayOf(questionId.toString())).use { it.moveToFirst() }

    override fun toggleFavorite(questionId: Long, bankId: Long): Boolean {
        if (isFavorite(questionId)) {
            write.delete("favorites", "question_id=?", arrayOf(questionId.toString()))
            return false
        }
        write.insertWithOnConflict(
            "favorites",
            null,
            ContentValues().apply {
                put("question_id", questionId)
                put("bank_id", bankId)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return true
    }

    override fun removeWrong(questionId: Long) {
        write.delete("wrongs", "question_id=?", arrayOf(questionId.toString()))
    }

    override fun clearWrong(bankId: Long?) {
        val (where, args) = bankFilter(bankId, "bank_id")
        write.delete("wrongs", where.removePrefix("WHERE ").ifBlank { null }, args)
    }

    private fun bankFilter(bankId: Long?, column: String = "q.bank_id"): Pair<String, Array<String>?> =
        if (bankId == null || bankId <= 0) "" to null
        else "WHERE $column=?" to arrayOf(bankId.toString())

    // -------------------------------------------------------------- 答题记录

    override fun recordAnswer(
        bankId: Long,
        questionId: Long,
        correct: Boolean?,
        userAnswer: String,
        mode: PracticeMode,
        autoRemoveWrong: Boolean,
    ) {
        val now = System.currentTimeMillis()
        write.beginTransaction()
        try {
            write.insert(
                "records",
                null,
                ContentValues().apply {
                    put("bank_id", bankId)
                    put("question_id", questionId)
                    put("correct", correct?.let { if (it) 1 else 0 })
                    put("user_answer", userAnswer)
                    put("mode", mode.name)
                    put("answered_at", now)
                },
            )
            when (correct) {
                false -> {
                    val existing = read.rawQuery("SELECT wrong_count FROM wrongs WHERE question_id=?", arrayOf(questionId.toString()))
                        .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                    write.insertWithOnConflict(
                        "wrongs",
                        null,
                        ContentValues().apply {
                            put("question_id", questionId)
                            put("bank_id", bankId)
                            put("wrong_count", existing + 1)
                            put("last_wrong_at", now)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                true -> if (autoRemoveWrong) write.delete("wrongs", "question_id=?", arrayOf(questionId.toString()))
                null -> Unit
            }
            write.setTransactionSuccessful()
        } finally {
            write.endTransaction()
        }
    }

    override fun stats(bankId: Long): BankStats {
        val questions = questionCount(bankId)
        var answered = 0
        var correct = 0
        var wrong = 0
        read.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN correct=1 THEN 1 ELSE 0 END), SUM(CASE WHEN correct=0 THEN 1 ELSE 0 END) " +
                "FROM records WHERE bank_id=?",
            arrayOf(bankId.toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                answered = c.getInt(0)
                correct = if (c.isNull(1)) 0 else c.getInt(1)
                wrong = if (c.isNull(2)) 0 else c.getInt(2)
            }
        }
        val distinct = read.rawQuery(
            "SELECT COUNT(DISTINCT question_id) FROM records WHERE bank_id=?",
            arrayOf(bankId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val favorites = read.rawQuery(
            "SELECT COUNT(*) FROM favorites WHERE bank_id=?",
            arrayOf(bankId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val wrongs = read.rawQuery(
            "SELECT COUNT(*) FROM wrongs WHERE bank_id=?",
            arrayOf(bankId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return BankStats(bankId, questions, answered, correct, wrong, distinct, favorites, wrongs)
    }

    override fun overallStats(days: Int): OverallStats {
        var answered = 0
        var correct = 0
        var wrong = 0
        read.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN correct=1 THEN 1 ELSE 0 END), SUM(CASE WHEN correct=0 THEN 1 ELSE 0 END) FROM records",
            null,
        ).use { c ->
            if (c.moveToFirst()) {
                answered = c.getInt(0)
                correct = if (c.isNull(1)) 0 else c.getInt(1)
                wrong = if (c.isNull(2)) 0 else c.getInt(2)
            }
        }
        val banks = read.rawQuery("SELECT COUNT(*) FROM banks", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val questions = read.rawQuery("SELECT COUNT(*) FROM questions", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val favorites = read.rawQuery("SELECT COUNT(*) FROM favorites", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val wrongs = read.rawQuery("SELECT COUNT(*) FROM wrongs", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return OverallStats(answered, correct, wrong, banks, questions, favorites, wrongs, dailyCounts(days))
    }

    override fun dailyCounts(days: Int): List<DayCount> {
        val safeDays = days.coerceIn(1, 90)
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -(safeDays - 1))
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val found = HashMap<String, Pair<Int, Int>>()
        read.rawQuery(
            "SELECT strftime('%Y-%m-%d', answered_at/1000, 'unixepoch', 'localtime') AS d, COUNT(*), " +
                "SUM(CASE WHEN correct=1 THEN 1 ELSE 0 END) FROM records WHERE answered_at>=? GROUP BY d",
            arrayOf(start.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val day = c.getString(0) ?: continue
                val total = c.getInt(1)
                val ok = if (c.isNull(2)) 0 else c.getInt(2)
                found[day] = total to ok
            }
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val labelFmt = SimpleDateFormat("MM-dd", Locale.US)
        val out = ArrayList<DayCount>(safeDays)
        val cal = Calendar.getInstance()
        cal.timeInMillis = start
        for (i in 0 until safeDays) {
            val key = fmt.format(Date(cal.timeInMillis))
            val (total, ok) = found[key] ?: (0 to 0)
            out.add(DayCount(labelFmt.format(Date(cal.timeInMillis)), total, ok))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    // ------------------------------------------------------------------ 进度

    override fun progress(bankId: Long): BankProgress? =
        read.rawQuery("SELECT * FROM progress WHERE bank_id=?", arrayOf(bankId.toString())).use { c ->
            if (!c.moveToFirst()) return null
            BankProgress(
                bankId = bankId,
                mode = runCatching { PracticeMode.valueOf(c.getString(c.getColumnIndexOrThrow("mode"))) }
                    .getOrDefault(PracticeMode.SEQUENTIAL),
                lastIndex = c.getInt(c.getColumnIndexOrThrow("last_index")),
                answered = c.getInt(c.getColumnIndexOrThrow("answered")),
                correct = c.getInt(c.getColumnIndexOrThrow("correct")),
                wrong = c.getInt(c.getColumnIndexOrThrow("wrong")),
                updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
            )
        }

    override fun saveProgress(progress: BankProgress) {
        write.insertWithOnConflict(
            "progress",
            null,
            ContentValues().apply {
                put("bank_id", progress.bankId)
                put("mode", progress.mode.name)
                put("last_index", progress.lastIndex)
                put("answered", progress.answered)
                put("correct", progress.correct)
                put("wrong", progress.wrong)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun resetProgress(bankId: Long) {
        write.beginTransaction()
        try {
            write.delete("progress", "bank_id=?", arrayOf(bankId.toString()))
            write.delete("records", "bank_id=?", arrayOf(bankId.toString()))
            write.delete("wrongs", "bank_id=?", arrayOf(bankId.toString()))
            write.setTransactionSuccessful()
        } finally {
            write.endTransaction()
        }
    }

    override fun resetAllProgress() {
        write.beginTransaction()
        try {
            write.delete("progress", null, null)
            write.delete("records", null, null)
            write.delete("wrongs", null, null)
            write.setTransactionSuccessful()
        } finally {
            write.endTransaction()
        }
    }

    // -------------------------------------------------------------- 考试记录

    override fun saveExam(record: ExamRecord): Long {
        val values = ContentValues().apply {
            put("data", ExamJson.encode(record))
            put("bank_id", record.bankId)
            put("user_name", record.user)
            put("uploaded", if (record.uploaded) 1 else 0)
            put("remote_path", record.remotePath)
            put("created_at", if (record.finishedAt > 0) record.finishedAt else System.currentTimeMillis())
        }
        return if (record.localId > 0) {
            write.update("exams", values, "id=?", arrayOf(record.localId.toString()))
            record.localId
        } else {
            write.insert("exams", null, values)
        }
    }

    override fun exams(limit: Int): List<ExamRecord> =
        read.rawQuery("SELECT * FROM exams ORDER BY created_at DESC LIMIT ?", arrayOf(limit.coerceIn(1, 5000).toString()))
            .use { c -> c.mapAll(::readExam) }

    override fun exam(localId: Long): ExamRecord? =
        read.rawQuery("SELECT * FROM exams WHERE id=?", arrayOf(localId.toString())).use { c ->
            if (c.moveToFirst()) readExam(c) else null
        }

    override fun deleteExam(localId: Long) {
        write.delete("exams", "id=?", arrayOf(localId.toString()))
    }

    override fun markExamUploaded(localId: Long, remotePath: String) {
        write.update(
            "exams",
            ContentValues().apply {
                put("uploaded", 1)
                put("remote_path", remotePath)
            },
            "id=?",
            arrayOf(localId.toString()),
        )
    }

    private fun readExam(c: android.database.Cursor): ExamRecord {
        val id = c.getLong(c.getColumnIndexOrThrow("id"))
        val data = c.getStringOrEmpty("data")
        val uploaded = c.getInt(c.getColumnIndexOrThrow("uploaded")) == 1
        val remotePath = c.getStringOrEmpty("remote_path")
        return ExamJson.decodeOrNull(data)?.copy(localId = id, uploaded = uploaded, remotePath = remotePath)
            ?: ExamRecord(localId = id, uploaded = uploaded, remotePath = remotePath)
    }

    // ------------------------------------------------------------- 行 -> 模型

    private fun readBank(c: Cursor): Bank = Bank(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        remoteId = c.getStringOrEmpty("remote_id"),
        name = c.getStringOrEmpty("name"),
        category = c.getStringOrEmpty("category"),
        description = c.getStringOrEmpty("description"),
        fileUrl = c.getStringOrEmpty("file_url"),
        resolvedUrl = c.getStringOrEmpty("resolved_url"),
        declaredCount = c.getInt(c.getColumnIndexOrThrow("declared_count")),
        version = c.getStringOrEmpty("version"),
        tags = JsonCodec.decodeStrings(c.getStringOrEmpty("tags")),
        importedAt = c.getLong(c.getColumnIndexOrThrow("imported_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        sourceName = c.getStringOrEmpty("source_name"),
        imageBase = c.getStringOrEmpty("image_base"),
        examBlueprintJson = c.getStringOrEmpty("exam_blueprint"),
    )

    private fun readQuestion(c: Cursor): Question = Question(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        bankId = c.getLong(c.getColumnIndexOrThrow("bank_id")),
        orderIndex = c.getInt(c.getColumnIndexOrThrow("order_index")),
        type = runCatching { QuestionType.valueOf(c.getStringOrEmpty("qtype")) }.getOrDefault(QuestionType.SINGLE),
        stem = c.getStringOrEmpty("stem"),
        options = JsonCodec.decodeOptions(c.getStringOrEmpty("options")),
        answers = JsonCodec.decodeStrings(c.getStringOrEmpty("answers")),
        analysis = c.getStringOrEmpty("analysis"),
        difficulty = c.getInt(c.getColumnIndexOrThrow("difficulty")),
        chapter = c.getStringOrEmpty("chapter"),
        tags = JsonCodec.decodeStrings(c.getStringOrEmpty("tags")),
        score = c.getDouble(c.getColumnIndexOrThrow("score")),
        sourceRow = c.getInt(c.getColumnIndexOrThrow("source_row")),
    )

    private fun Cursor.getStringOrEmpty(column: String): String {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) "" else getString(index)
    }

    private fun <T> Cursor.mapAll(map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count.coerceAtLeast(0))
        while (moveToNext()) out.add(map(this))
        return out
    }

    private companion object {
        const val DB_NAME = "quizbank.db"
        const val DB_VERSION = 2

        const val INSERT_QUESTION =
            "INSERT INTO questions(bank_id,order_index,qtype,stem,options,answers,analysis,difficulty,chapter,tags,score,source_row) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"

        val TABLES = listOf("questions", "banks", "progress", "records", "wrongs", "favorites", "exams")

        val SCHEMA = listOf(
            """
            CREATE TABLE banks(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              remote_id TEXT NOT NULL DEFAULT '',
              name TEXT NOT NULL DEFAULT '',
              category TEXT NOT NULL DEFAULT '',
              description TEXT NOT NULL DEFAULT '',
              file_url TEXT NOT NULL DEFAULT '',
              resolved_url TEXT NOT NULL DEFAULT '',
              declared_count INTEGER NOT NULL DEFAULT 0,
              version TEXT NOT NULL DEFAULT '',
              tags TEXT NOT NULL DEFAULT '[]',
              imported_at INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL DEFAULT 0,
              source_name TEXT NOT NULL DEFAULT '',
              image_base TEXT NOT NULL DEFAULT '',
              exam_blueprint TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
            """
            CREATE TABLE questions(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              bank_id INTEGER NOT NULL,
              order_index INTEGER NOT NULL DEFAULT 0,
              qtype TEXT NOT NULL DEFAULT 'SINGLE',
              stem TEXT NOT NULL DEFAULT '',
              options TEXT NOT NULL DEFAULT '[]',
              answers TEXT NOT NULL DEFAULT '[]',
              analysis TEXT NOT NULL DEFAULT '',
              difficulty INTEGER NOT NULL DEFAULT 0,
              chapter TEXT NOT NULL DEFAULT '',
              tags TEXT NOT NULL DEFAULT '[]',
              score REAL NOT NULL DEFAULT 0,
              source_row INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_questions_bank ON questions(bank_id, order_index)",
            """
            CREATE TABLE progress(
              bank_id INTEGER PRIMARY KEY,
              mode TEXT NOT NULL DEFAULT 'SEQUENTIAL',
              last_index INTEGER NOT NULL DEFAULT 0,
              answered INTEGER NOT NULL DEFAULT 0,
              correct INTEGER NOT NULL DEFAULT 0,
              wrong INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE records(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              bank_id INTEGER NOT NULL,
              question_id INTEGER NOT NULL,
              correct INTEGER,
              user_answer TEXT NOT NULL DEFAULT '',
              mode TEXT NOT NULL DEFAULT 'SEQUENTIAL',
              answered_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_records_bank ON records(bank_id, answered_at)",
            """
            CREATE TABLE wrongs(
              question_id INTEGER PRIMARY KEY,
              bank_id INTEGER NOT NULL,
              wrong_count INTEGER NOT NULL DEFAULT 1,
              last_wrong_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE favorites(
              question_id INTEGER PRIMARY KEY,
              bank_id INTEGER NOT NULL,
              created_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE exams(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              data TEXT NOT NULL DEFAULT '',
              bank_id INTEGER NOT NULL DEFAULT 0,
              user_name TEXT NOT NULL DEFAULT '',
              uploaded INTEGER NOT NULL DEFAULT 0,
              remote_path TEXT NOT NULL DEFAULT '',
              created_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_exams_created ON exams(created_at)",
        )
    }
}
