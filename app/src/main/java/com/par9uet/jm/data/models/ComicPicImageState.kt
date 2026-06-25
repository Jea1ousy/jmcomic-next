package com.par9uet.jm.data.models

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.par9uet.jm.cache.getCommonPicDecodeCacheDir
import com.par9uet.jm.utils.compressWebpCompat
import com.par9uet.jm.utils.md5
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class ImageResultState {
    object Loading : ImageResultState()
    data class Success(
        val decodeImageBitmap: ImageBitmap,
        val decodeImageAspectRatio: Float
    ) :
        ImageResultState()

    data class Failure(val reason: String) : ImageResultState()
}

class ComicPicImageState(
    val index: Int,
    val comicId: Int,
    val originSrc: String,
    val __scrambleId: Int,
    val __speed: String,
    private val picImageLoader: ImageLoader,
) {

    companion object {
        private val seedMap = listOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)
    }

    var imageResultState by mutableStateOf<ImageResultState>(ImageResultState.Loading)
    private var isDecoding = false

    suspend fun decode(context: Context) {
        // 如果已经成功加载，不重复解码
        if (imageResultState is ImageResultState.Success) {
            return
        }
        // 如果正在解码中，避免重复
        if (isDecoding) {
            return
        }
        isDecoding = true
        try {
            withContext(Dispatchers.Default) {
                imageResultState = ImageResultState.Loading
                decodeImage(context)
            }
        } finally {
            isDecoding = false
        }
    }

    private suspend fun decodeImage(context: Context) {
        // 检查是否是本地文件且不需要解密
        val isLocalFile = File(originSrc).exists()
        val needDecrypt = !(isGif() || comicId <= __scrambleId || __speed == "1")

        // 对于本地文件且不需要解密的情况，直接加载，不走 webp 缓存
        if (isLocalFile && !needDecrypt) {
            val request = ImageRequest.Builder(context)
                .data(originSrc)
                .size { Size.ORIGINAL }
                .allowHardware(false)
                .build()

            when (val result = picImageLoader.execute(request)) {
                is SuccessResult -> {
                    val bitmap = result.drawable.toBitmap().asImageBitmap()
                    val aspectRatio = bitmap.width * 1.0f / bitmap.height
                    imageResultState = ImageResultState.Success(bitmap, aspectRatio)
                }
                is ErrorResult -> {
                    Log.d("comic pic", result.throwable.stackTraceToString())
                    imageResultState = ImageResultState.Failure("加载失败")
                }
            }
            return
        }

        // 需要解密的图片走 webp 缓存流程
        val cacheDir = getCommonPicDecodeCacheDir(context, comicId)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val page = extractPageFromUrl()
        val cacheFile = File(cacheDir, "$page.webp")

        // 检查缓存文件是否存在
        if (cacheFile.exists()) {
            val decodeImageBitmap =
                BitmapFactory.decodeFile(cacheFile.absolutePath).asImageBitmap()
            val decodeImageAspectRatio =
                decodeImageBitmap.width * 1.0f / decodeImageBitmap.height
            imageResultState = ImageResultState.Success(decodeImageBitmap, decodeImageAspectRatio)
            return
        }

        // 加载原始图片
        val imageData = File(originSrc).takeIf { it.exists() } ?: originSrc
        val request = ImageRequest.Builder(context)
            .data(imageData)
            // 这里必须使用原始 size ，不然解密会有问题，出现白线
            .size { Size.ORIGINAL }
            .allowHardware(false)
            .build()

        when (val result = picImageLoader.execute(request)) {
            is SuccessResult -> {
                val originalBitmap = result.drawable.toBitmap()
                val originalImageBitmap = originalBitmap.asImageBitmap()
                val decodeImageAspectRatio =
                    originalImageBitmap.width * 1.0f / originalImageBitmap.height
                var decodedImageBitmap = originalImageBitmap
                var bitmapToCache = originalBitmap

                if (isGif() || comicId <= __scrambleId || __speed == "1") {
                    // 本地缓存，不需要解密
                    bitmapToCache = originalBitmap
                } else {
                    // 需要解密
                    val decodedBitmap = decodeBitmap(originalBitmap, page)
                    decodedImageBitmap = decodedBitmap.asImageBitmap()
                    bitmapToCache = decodedBitmap
                }

                // 先设置状态，立即显示图片
                imageResultState =
                    ImageResultState.Success(decodedImageBitmap, decodeImageAspectRatio)

                // 然后异步保存 webp 缓存，不阻塞显示
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        saveBitmapAsWebp(bitmapToCache, cacheFile)
                    } catch (e: Exception) {
                        Log.d("comic pic", "Failed to save webp cache: ${e.message}")
                    }
                }
            }

            is ErrorResult -> {
                Log.d("comic pic", result.throwable.stackTraceToString())
                imageResultState = ImageResultState.Failure("网络错误")
            }
        }
    }

    private fun decodeBitmap(originalBitmap: Bitmap, page: String): Bitmap {
        val naturalWidth = originalBitmap.width
        val naturalHeight = originalBitmap.height
        val seed = calculateSeed(comicId, page)
        val remainder = naturalHeight % seed

        val decodedBitmap =
            createBitmap(naturalWidth, naturalHeight)
        val canvas = Canvas(decodedBitmap.asImageBitmap())
        val paint = Paint().apply {
            this.isAntiAlias = false
        }
        val originImageBitmap = originalBitmap.asImageBitmap()

        for (i in 0 until seed) {
            var height = naturalHeight / seed
            var dy = height * i
            val sy = naturalHeight - height * (i + 1) - remainder
            if (i == 0) {
                height += remainder
            } else {
                dy += remainder
            }

            val srcOffset = IntOffset(0, sy)
            val srcSize = IntSize(naturalWidth, height)
            val destOffset = IntOffset(0, dy)
            val destSize = IntSize(naturalWidth, height)

            canvas.drawImageRect(
                originImageBitmap,
                srcOffset,
                srcSize,
                destOffset,
                destSize,
                paint
            )
        }

        return decodedBitmap
    }

    private fun calculateSeed(comicId: Int, pageStr: String): Int {
        val key = "$comicId$pageStr"
        val keyMd5 = md5(key)
        var charCodeOfLastChar = keyMd5.last().code
        val left = 268850
        val right = 421925

        when {
            comicId in left..right -> charCodeOfLastChar %= 10
            comicId >= right + 1 -> charCodeOfLastChar %= 8
        }

        return seedMap.getOrNull(charCodeOfLastChar) ?: 10
    }

    private fun extractPageFromUrl(): String {
        return originSrc.substringAfterLast('/').substringBeforeLast('.')
    }

    private suspend fun saveBitmapAsWebp(bitmap: Bitmap, file: File) {
        withContext(Dispatchers.IO) {
            FileOutputStream(file).use { out ->
                bitmap.compressWebpCompat(50, out)
            }
        }
    }

    private fun isGif(): Boolean {
        return originSrc.endsWith(".gif")
    }
}
