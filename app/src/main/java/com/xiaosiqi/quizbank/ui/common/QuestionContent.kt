package com.xiaosiqi.quizbank.ui.common

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xiaosiqi.quizbank.image.ImageStore
import com.xiaosiqi.quizbank.rich.RichText
import com.xiaosiqi.quizbank.ui.rememberContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 解码后的位图做内存缓存，避免列表滚动时反复解码。 */
private object BitmapCache {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024 / 4) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }
}

private suspend fun loadBitmap(store: ImageStore, ref: String, imageBase: String, baseUrl: String): ImageBitmap =
    withContext(Dispatchers.IO) {
        val file: File = store.ensure(ref, imageBase, baseUrl)
        val cacheKey = file.absolutePath
        BitmapCache.get(cacheKey)?.let { return@withContext it }
        val bitmap = decodeSampled(file) ?: throw IllegalStateException("图片无法解码")
        BitmapCache.put(cacheKey, bitmap)
        bitmap
    }

private fun decodeSampled(file: File, maxWidth: Int = 1280): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}

private sealed interface ImageState {
    data object Loading : ImageState
    data class Ready(val bitmap: ImageBitmap) : ImageState
    data class Failed(val message: String) : ImageState
}

/**
 * 题干 / 选项 / 解析的统一渲染：文本与图片按原顺序混排，支持 Markdown 与 <img>。
 * 图片优先走本地缓存，取不到才联网（alist 私有网盘也能显示）。
 */
@Composable
fun RichTextView(
    raw: String,
    imageBase: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle? = null,
    selectable: Boolean = true,
) {
    val blocks = remember(raw) { RichText.parse(raw) }
    val style = textStyle ?: MaterialTheme.typography.bodyLarge
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is RichText.Block.Text -> {
                    val text = block.text.trim('\n')
                    if (text.isNotBlank()) {
                        if (selectable) {
                            SelectionContainer { Text(text, style = style) }
                        } else {
                            Text(text, style = style)
                        }
                    }
                }
                is RichText.Block.Image -> QuestionImage(block.ref, imageBase)
            }
        }
    }
}

@Composable
fun QuestionImage(
    ref: String,
    imageBase: String,
    modifier: Modifier = Modifier,
) {
    val container = rememberContainer()
    val settings by container.settings.state.collectAsState()
    var attempt by remember(ref) { mutableIntStateOf(0) }
    var state by remember(ref) { mutableStateOf<ImageState>(ImageState.Loading) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(ref, imageBase, attempt) {
        state = ImageState.Loading
        state = try {
            ImageState.Ready(loadBitmap(container.images, ref, imageBase, settings.baseUrl))
        } catch (e: Exception) {
            ImageState.Failed(e.message ?: "图片加载失败")
        }
    }

    when (val current = state) {
        is ImageState.Loading -> Box(
            modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("图片加载中…", style = MaterialTheme.typography.labelMedium)
            }
        }

        is ImageState.Ready -> Image(
            bitmap = current.bitmap,
            contentDescription = null,
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { preview = current.bitmap },
            contentScale = ContentScale.FillWidth,
        )

        is ImageState.Failed -> Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("图片没能加载", style = MaterialTheme.typography.labelLarge)
                    Text(
                        current.message.lineSequence().first(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { attempt++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重试")
                }
            }
        }
    }

    preview?.let { bitmap ->
        Dialog(onDismissRequest = { preview = null }) {
            Surface(shape = RoundedCornerShape(12.dp)) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
