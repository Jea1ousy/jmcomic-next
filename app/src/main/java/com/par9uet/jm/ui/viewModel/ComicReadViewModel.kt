package com.par9uet.jm.ui.viewModel

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.par9uet.jm.cache.getDownloadDir
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.dao.ReadingProgressDao
import com.par9uet.jm.database.dao.ChapterProgressDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.CollectComicResponse
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.ui.models.CommonUIState
import com.par9uet.jm.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

class ComicReadViewModel(
    private val comicRepository: ComicRepository,
    private val picImageLoader: ImageLoader,
    private val localSettingManager: LocalSettingManager,
    private val downloadComicDao: DownloadComicDao,
    private val readingProgressDao: ReadingProgressDao,
    private val chapterProgressDao: ChapterProgressDao,
    private val toastManager: ToastManager,
) : ViewModel() {
    var isShowToolBar = mutableStateOf(false)
    var currentIndexState = mutableIntStateOf(0)
    private val _comicPicState = MutableStateFlow(
        CommonUIState<List<ComicPicImageState>>(
            isLoading = true
        )
    )
    val comicPicState = _comicPicState.asStateFlow()
    private val _comicDetailState = MutableStateFlow(CommonUIState<Comic>())
    val comicDetailState = _comicDetailState.asStateFlow()
    private val _localChapterNavigationState = MutableStateFlow(LocalChapterNavigationState())
    val localChapterNavigationState = _localChapterNavigationState.asStateFlow()

    val size: Int get() = _comicPicState.value.data?.size ?: 0

    private val prefetchSet = mutableSetOf<Int>()
    private val decodeJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()

    // Reading progress tracking
    private var currentComicId: Int = 0
    private var currentIsLocal: Boolean = false
    private var lastSaveTime = 0L
    private val SAVE_DEBOUNCE_MS = 3000L

    fun getComicDetail(comicId: Int) {
        viewModelScope.launch {
            _comicDetailState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = comicRepository.getComicDetail(comicId)) {
                is NetWorkResult.Error -> {
                    _comicDetailState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<ComicDetailResponse> -> {
                    _comicDetailState.update {
                        it.copy(
                            data = data.data.toComic()
                        )
                    }
                }
            }
            _comicDetailState.update {
                it.copy(isLoading = false)
            }
        }
    }

    fun clearComicDetail() {
        _comicDetailState.update { CommonUIState() }
        _localChapterNavigationState.update { LocalChapterNavigationState() }
    }

    fun loadLocalComicChapters(comicId: Int) {
        viewModelScope.launch {
            val currentComic = downloadComicDao.getById(comicId)
            _localChapterNavigationState.update { LocalChapterNavigationState() }
            if (currentComic != null) {
                val parentId = if (currentComic.parentId != 0) {
                    currentComic.parentId
                } else {
                    currentComic.id
                }

                val allChapters = downloadComicDao.getChaptersByParent(parentId)
                    .sortedWith(compareBy<DownloadComic> { it.chapterIndex }
                        .thenBy { it.createTime }
                        .thenBy { it.id })

                val previousChapterIndex = currentComic.chapterIndex - 1
                val nextChapterIndex = currentComic.chapterIndex + 1
                _localChapterNavigationState.update {
                    LocalChapterNavigationState(
                        previousChapter = allChapters.firstOrNull { it.chapterIndex == previousChapterIndex }
                            ?.takeIf { it.status == "complete" }
                            ?.toComicChapter(),
                        nextChapter = allChapters.firstOrNull { it.chapterIndex == nextChapterIndex }
                            ?.takeIf { it.status == "complete" }
                            ?.toComicChapter()
                    )
                }

                val completedChapters = allChapters.filter { it.status == "complete" }

                if (completedChapters.isNotEmpty()) {
                    val chapterList = completedChapters.map { chapter -> chapter.toComicChapter() }

                    _comicDetailState.update {
                        it.copy(
                            data = Comic.create(
                                id = parentId,
                                name = currentComic.parentName.ifBlank { currentComic.name },
                                authorList = currentComic.authorList
                            ).copy(comicChapterList = chapterList)
                        )
                    }
                }
            }
        }
    }

    private fun DownloadComic.toComicChapter(): ComicChapter {
        return ComicChapter(
            id = id,
            name = buildChapterName(this)
        )
    }

    private fun buildChapterName(chapter: DownloadComic): String {
        val hasChapterMetadata = chapter.parentId != chapter.id ||
            chapter.chapterCount > 1 ||
            chapter.chapterName.isNotBlank()

        if (!hasChapterMetadata) {
            return chapter.name
        }

        val numberText = "第" + (chapter.chapterIndex + 1) + "话"
        return if (chapter.chapterName.isBlank()) {
            numberText
        } else {
            numberText + " " + chapter.chapterName
        }
    }

    fun collect(comicId: Int) {
        updateCollectState(comicId, true)
    }

    fun unCollect(comicId: Int) {
        updateCollectState(comicId, false)
    }

    private fun updateCollectState(comicId: Int, targetCollect: Boolean) {
        viewModelScope.launch {
            when (val data: NetWorkResult<CollectComicResponse> = if (targetCollect) {
                comicRepository.collectComic(comicId)
            } else {
                comicRepository.unCollectComic(comicId)
            }) {
                is NetWorkResult.Error -> {
                    toastManager.showAsync(data.message)
                }

                is NetWorkResult.Success<CollectComicResponse> -> {
                    toastManager.showAsync(if (targetCollect) "收藏成功" else "取消收藏成功")
                    _comicDetailState.update {
                        it.copy(
                            data = it.data?.copy(isCollect = targetCollect)
                        )
                    }
                }
            }
        }
    }

    fun getComicPicList(comicId: Int, shunt: String, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            _comicPicState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            prefetchSet.clear()
            decodeJobs.values.forEach { it.cancel() }
            decodeJobs.clear()
            when (val data = comicRepository.getComicPicList(comicId, shunt)) {
                is NetWorkResult.Error -> {
                    _comicPicState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<ComicPicListResponse> -> {
                    _comicPicState.update {
                        it.copy(
                            data = data.data.list.mapIndexed { index, item ->
                                ComicPicImageState(
                                    index,
                                    comicId,
                                    item,
                                    data.data.__scrambleId,
                                    data.data.__speed,
                                    picImageLoader,
                                )
                            }
                        )
                    }
                    onSuccess?.invoke()
                }
            }
            _comicPicState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }

    fun getLocalComicPicList(comicId: Int, context: Context, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            _comicPicState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            prefetchSet.clear()
            decodeJobs.values.forEach { it.cancel() }
            decodeJobs.clear()
            val downloadComic = downloadComicDao.getById(comicId)
            val imageDir = ensureLocalImageDir(context, comicId, downloadComic?.zipPath.orEmpty())
            val files = imageDir
                ?.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in setOf("webp", "jpg", "jpeg", "png") }
                ?.sortedWith(compareBy<File> { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.name })
                .orEmpty()

            if (files.isEmpty()) {
                _comicPicState.update {
                    it.copy(
                        isLoading = false,
                        isError = true,
                        errorMsg = "未找到本地缓存图片"
                    )
                }
                return@launch
            }

            _comicPicState.update {
                it.copy(
                    data = files.mapIndexed { index, file ->
                        ComicPicImageState(
                            index = index,
                            comicId = comicId,
                            originSrc = file.absolutePath,
                            __scrambleId = Int.MAX_VALUE,
                            __speed = "1",
                            picImageLoader = picImageLoader
                        )
                    },
                    isLoading = false
                )
            }
            onSuccess?.invoke()
        }
    }

    private fun ensureLocalImageDir(context: Context, comicId: Int, zipPath: String): File? {
        val dir = File(getDownloadDir(context), "$comicId")
        if (dir.exists() && dir.listFiles()?.isNotEmpty() == true) {
            return dir
        }
        val zipFile = File(zipPath)
        if (!zipFile.exists()) {
            return dir.takeIf { it.exists() }
        }
        dir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zipIn ->
            while (true) {
                val entry = zipIn.nextEntry ?: break
                if (!entry.isDirectory) {
                    val output = File(dir, File(entry.name).name)
                    FileOutputStream(output).use { out ->
                        zipIn.copyTo(out)
                    }
                }
                zipIn.closeEntry()
            }
        }
        return dir
    }

    fun decodeIndex(index: Int, context: Context) {
        log("decode index $index")
        val count = localSettingManager.localSettingState.value.prefetchCount
        val start = max(0, index - count)
        val end = min(size - 1, index + count)
        decode(index, context) {
            for (i in index + 1..end) {
                log("pre decode index $i")
                decode(i, context)
            }
            for (i in index - 1 downTo start) {
                log("pre decode index $i")
                decode(i, context)
            }
        }
    }

    fun decodeScrollMode(index: Int, context: Context) {
        log("decode scroll mode $index")
        val count = localSettingManager.localSettingState.value.prefetchCount
        // 滚动模式：预加载范围更大，至少5页
        val expandedCount = max(count, 5)
        val start = max(0, index - expandedCount)
        val end = min(size - 1, index + expandedCount)

        // 取消不在当前范围内的预加载任务
        val toCancel = decodeJobs.keys.filter { it !in start..end }
        toCancel.forEach { jobIndex ->
            decodeJobs[jobIndex]?.cancel()
            decodeJobs.remove(jobIndex)
            prefetchSet.remove(jobIndex)
        }

        // 优先解码当前页和紧邻的页面（高优先级）
        decodeWithPriority(index, context, highPriority = true)
        if (index + 1 <= end) decodeWithPriority(index + 1, context, highPriority = true)
        if (index - 1 >= start) decodeWithPriority(index - 1, context, highPriority = true)

        // 然后批量预加载后续页面（低优先级）
        for (i in index + 2..end) {
            decodeWithPriority(i, context, highPriority = false)
        }
        // 最后预加载前面的页面（低优先级）
        for (i in index - 2 downTo start) {
            decodeWithPriority(i, context, highPriority = false)
        }
    }

    fun decodeCurrentPageOnly(index: Int, context: Context) {
        log("decode current page only $index")
        // 立即解码当前页和下一页（因为 LazyColumn 通常会预加载下一页）
        decode(index, context)
        if (index + 1 < size) {
            decode(index + 1, context)
        }
    }

    fun prefetchAroundIndex(index: Int, context: Context) {
        log("prefetch around index $index")
        val count = localSettingManager.localSettingState.value.prefetchCount
        val start = max(0, index - count)
        val end = min(size - 1, index + count)

        // 预加载周围的页面，跳过当前页和下一页（已经解码过了）
        for (i in index + 2..end) {
            log("prefetch decode index $i")
            decode(i, context)
        }
        for (i in index - 1 downTo start) {
            log("prefetch decode index $i")
            decode(i, context)
        }
    }

    fun prev(context: Context) {
        hideToolBar()
        val index = max(0, currentIndexState.intValue - 1)
        currentIndexState.intValue = index
        decodeIndex(index, context)
        autoSaveProgress()
    }

    fun next(context: Context) {
        hideToolBar()
        val index = min(size - 1, currentIndexState.intValue + 1)
        currentIndexState.intValue = index
        decodeIndex(index, context)
        autoSaveProgress()
    }

    private fun decode(index: Int, context: Context, onComplete: (() -> Unit)? = null) {
        val comicPicImageState = comicPicState.value.data?.getOrNull(index) ?: return

        // 如果已经解码成功，跳过
        if (comicPicImageState.imageResultState is com.par9uet.jm.data.models.ImageResultState.Success) {
            onComplete?.invoke()
            return
        }

        // 如果正在解码中（已在 prefetchSet 但状态是 Loading），也跳过避免重复
        if (prefetchSet.contains(index) &&
            comicPicImageState.imageResultState is com.par9uet.jm.data.models.ImageResultState.Loading) {
            onComplete?.invoke()
            return
        }

        prefetchSet.add(index)
        viewModelScope.launch {
            comicPicImageState.decode(context)
            onComplete?.invoke()
        }
    }

    private fun decodeWithPriority(index: Int, context: Context, highPriority: Boolean) {
        val comicPicImageState = comicPicState.value.data?.getOrNull(index) ?: return

        // 如果已经解码成功，跳过
        if (comicPicImageState.imageResultState is com.par9uet.jm.data.models.ImageResultState.Success) {
            return
        }

        // 如果是高优先级且已有任务在执行，取消旧任务重新执行
        if (highPriority && decodeJobs.containsKey(index)) {
            decodeJobs[index]?.cancel()
            decodeJobs.remove(index)
            prefetchSet.remove(index)
        }

        // 如果正在解码中，跳过
        if (prefetchSet.contains(index)) {
            return
        }

        prefetchSet.add(index)
        val job = viewModelScope.launch(if (highPriority) kotlinx.coroutines.Dispatchers.Main else kotlinx.coroutines.Dispatchers.Default) {
            try {
                comicPicImageState.decode(context)
            } finally {
                decodeJobs.remove(index)
            }
        }
        decodeJobs[index] = job
    }

    fun triggerToolBar() {
        isShowToolBar.value = !isShowToolBar.value
    }

    fun hideToolBar() {
        isShowToolBar.value = false
    }

    fun showToolBar() {
        isShowToolBar.value = true
    }

    // Reading progress management
    suspend fun loadSavedProgress(comicId: Int): com.par9uet.jm.database.model.ReadingProgress? {
        return readingProgressDao.getProgress(comicId)
    }

    fun autoSaveProgress() {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime < SAVE_DEBOUNCE_MS) return
        lastSaveTime = now

        saveProgress()
    }

    private fun saveProgress() {
        viewModelScope.launch {
            val comic = _comicDetailState.value.data ?: return@launch
            val picList = _comicPicState.value.data ?: return@launch
            if (picList.isEmpty()) return@launch

            val currentPage = currentIndexState.intValue
            val chapterId = if (currentIsLocal) {
                currentComicId
            } else {
                if (comic.comicChapterList.isEmpty()) comic.id else currentComicId
            }

            val chapterName = if (currentIsLocal) {
                downloadComicDao.getById(currentComicId)?.let { buildChapterName(it) } ?: ""
            } else {
                comic.comicChapterList.find { it.id == chapterId }?.name ?: comic.name
            }

            val coverPath = if (currentIsLocal) {
                downloadComicDao.getById(currentComicId)?.coverPath ?: ""
            } else {
                ""
            }

            val parentComicId = if (currentIsLocal) {
                downloadComicDao.getById(currentComicId)?.let {
                    if (it.parentId != 0) it.parentId else it.id
                } ?: currentComicId
            } else {
                comic.id
            }

            // Save to ReadingProgress (for "Continue Reading" feature)
            val progress = com.par9uet.jm.database.model.ReadingProgress(
                comicId = parentComicId,
                chapterId = chapterId,
                chapterName = chapterName,
                pageIndex = currentPage,
                totalPages = picList.size,
                lastReadTime = System.currentTimeMillis(),
                isLocal = currentIsLocal,
                comicName = comic.name,
                comicCover = coverPath
            )
            readingProgressDao.saveProgress(progress)

            // Save to ChapterProgress (for per-chapter progress display)
            val chapterProgress = com.par9uet.jm.database.model.ChapterProgress(
                chapterId = chapterId,
                comicId = parentComicId,
                chapterName = chapterName,
                pageIndex = currentPage,
                totalPages = picList.size,
                lastReadTime = System.currentTimeMillis(),
                isCompleted = currentPage >= picList.size - 1
            )
            chapterProgressDao.saveProgress(chapterProgress)
        }
    }

    suspend fun restoreProgress(comicId: Int, isLocal: Boolean): Int? {
        currentComicId = comicId
        currentIsLocal = isLocal

        val savedProgress = readingProgressDao.getProgress(
            if (isLocal) {
                downloadComicDao.getById(comicId)?.let {
                    if (it.parentId != 0) it.parentId else it.id
                } ?: comicId
            } else {
                comicId
            }
        )
        return if (savedProgress != null && savedProgress.chapterId == comicId) {
            savedProgress.pageIndex
        } else {
            null
        }
    }

    fun onReadingExit() {
        saveProgress()
    }
}

data class LocalChapterNavigationState(
    val previousChapter: ComicChapter? = null,
    val nextChapter: ComicChapter? = null,
)
