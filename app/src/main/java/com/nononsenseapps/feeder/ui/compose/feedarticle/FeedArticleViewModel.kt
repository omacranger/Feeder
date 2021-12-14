package com.nononsenseapps.feeder.ui.compose.feedarticle

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.nononsenseapps.feeder.FeederApplication
import com.nononsenseapps.feeder.archmodel.Article
import com.nononsenseapps.feeder.archmodel.Enclosure
import com.nononsenseapps.feeder.archmodel.FeedItemStyle
import com.nononsenseapps.feeder.archmodel.ItemOpener
import com.nononsenseapps.feeder.archmodel.LinkOpener
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.archmodel.ScreenTitle
import com.nononsenseapps.feeder.archmodel.TextToDisplay
import com.nononsenseapps.feeder.archmodel.ThemeOptions
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.blob.blobFullFile
import com.nononsenseapps.feeder.blob.blobFullInputStream
import com.nononsenseapps.feeder.blob.blobInputStream
import com.nononsenseapps.feeder.db.room.FeedItemForFetching
import com.nononsenseapps.feeder.db.room.FeedTitle
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.model.PlaybackStatus
import com.nononsenseapps.feeder.model.ReadAloudStateHolder
import com.nononsenseapps.feeder.model.getPlainTextOfHtmlStream
import com.nononsenseapps.feeder.model.parseFullArticleIfMissing
import com.nononsenseapps.feeder.model.requestFeedSync
import com.nononsenseapps.feeder.ui.compose.feed.FeedListItem
import com.nononsenseapps.feeder.ui.compose.feed.FeedOrTag
import com.nononsenseapps.feeder.ui.compose.navdrawer.DrawerItemWithUnreadCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance
import org.threeten.bp.Instant
import org.threeten.bp.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class FeedArticleViewModel(
    di: DI,
    private val state: SavedStateHandle
) : DIAwareViewModel(di) {
    private val repository: Repository by instance()
    private val readAloudStateHolder: ReadAloudStateHolder by instance()

    val currentFeedListItems: Flow<PagingData<FeedListItem>> =
        repository.getCurrentFeedListItems()
            .cachedIn(viewModelScope)

    private val visibleFeedItemCount: StateFlow<Int> =
        repository.getCurrentFeedListVisibleItemCount()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                1, // So we display an empty screen before data is loaded (less flicker)
            )

    private val screenTitleForCurrentFeedOrTag: StateFlow<ScreenTitle> =
        repository.getScreenTitleForCurrentFeedOrTag()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                ScreenTitle(""),
            )

    private val visibleFeeds: StateFlow<List<FeedTitle>> =
        repository.getCurrentlyVisibleFeedTitles()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                emptyList(),
            )

    fun setShowOnlyUnread(value: Boolean) = viewModelScope.launch {
        repository.setShowOnlyUnread(value)
    }

    fun deleteFeeds(feedIds: List<Long>) = viewModelScope.launch {
        repository.deleteFeeds(feedIds)
    }

    fun markAllAsRead() = viewModelScope.launch {
        val (feedId, feedTag) = repository.currentFeedAndTag.value
        repository.markAllAsReadInFeedOrTag(feedId, feedTag)
    }

    fun markAsUnread(itemId: Long, unread: Boolean) = viewModelScope.launch {
        repository.markAsUnread(itemId, unread)
    }

    fun markBeforeAsRead(itemIndex: Int) = viewModelScope.launch {
        val (feedId, feedTag) = repository.currentFeedAndTag.value
        repository.markBeforeAsRead(itemIndex, feedId, feedTag)
    }

    fun markAfterAsRead(itemIndex: Int) = viewModelScope.launch {
        val (feedId, feedTag) = repository.currentFeedAndTag.value
        repository.markAfterAsRead(itemIndex, feedId, feedTag)
    }

    fun markAsReadAndNotified(itemId: Long) = viewModelScope.launch {
        repository.markAsReadAndNotified(itemId)
    }

    fun requestImmediateSyncOfCurrentFeedOrTag() {
        val (feedId, feedTag) = repository.currentFeedAndTag.value
        requestFeedSync(
            di = di,
            feedId = feedId,
            feedTag = feedTag,
            forceNetwork = true,
            parallel = true
        )
    }

    fun requestImmediateSyncOfAll() {
        requestFeedSync(
            di = di,
            forceNetwork = true,
            parallel = true
        )
    }

    private val toolbarVisible: MutableStateFlow<Boolean> =
        MutableStateFlow(state["toolbarMenuVisible"] ?: false)

    fun setToolbarMenuVisible(visible: Boolean) {
        state["toolbarMenuVisible"] = visible
        toolbarVisible.update { visible }
    }

    fun toggleTagExpansion(tag: String) = repository.toggleTagExpansion(tag)

    fun setCurrentFeedAndTag(feedId: Long, tag: String) {
        repository.setCurrentFeedAndTag(feedId, tag)
    }

    private val editDialogVisible = MutableStateFlow(false)
    fun setShowEditDialog(visible: Boolean) {
        editDialogVisible.update { visible }
    }

    private val deleteDialogVisible = MutableStateFlow(false)
    fun setShowDeleteDialog(visible: Boolean) {
        deleteDialogVisible.update { visible }
    }

    /**
     * This determines if the main screen is article or list on small screens - it does not impact
     * visibility of article on large landscape screens
     */
    fun setArticleOpen(value: Boolean) {
        currentArticle.update {
            it.copy(isOpen = value)
        }
        state["articleOpen"] = value
    }

    fun setCurrentArticle(itemId: Long) {
        currentArticle.update {
            it.copy(itemId = itemId, isOpen = true)
        }
        state["articleOpen"] = true
        repository.setCurrentArticle(itemId)
    }

    fun openArticle(
        itemId: Long,
        articleLink: String?,
        openInCustomTab: (String) -> Unit,
        openInBrowser: (String) -> Unit,
    ) = viewModelScope.launch {
        val itemOpener = repository.getArticleOpener(itemId = itemId)
        when {
            ItemOpener.CUSTOM_TAB == itemOpener && articleLink != null -> {
                openInCustomTab(articleLink)
            }
            ItemOpener.DEFAULT_BROWSER == itemOpener && articleLink != null -> {
                openInBrowser(articleLink)
            }
            else -> {
                setCurrentArticle(itemId)
            }
        }
        markAsUnread(itemId, false)
    }

    private val currentArticle: MutableStateFlow<CurrentArticle> =
        MutableStateFlow(
            CurrentArticle(
                itemId = repository.currentArticleId.value,
                isOpen = state["articleOpen"] ?: false
            )
        )

    // Used to trigger state update
    private val textToDisplayTrigger: MutableStateFlow<Int> = MutableStateFlow(0)
    private suspend fun getTextToDisplayFor(itemId: Long): TextToDisplay =
        state["textToDisplayFor$itemId"]
            ?: repository.getTextToDisplayForItem(itemId)

    // Only affect the state by design, settings is done in EditFeed
    private fun setTextToDisplayFor(itemId: Long, value: TextToDisplay) {
        state["textToDisplayFor$itemId"] = value
        textToDisplayTrigger.update {
            textToDisplayTrigger.value + 1
        }
    }

    val viewState: StateFlow<FeedArticleScreenViewState> =
        combine(
            repository.showOnlyUnread,
            repository.showFab,
            repository.showThumbnails,
            repository.currentTheme,
            repository.currentlySyncingLatestTimestamp,
            repository.drawerItemsWithUnreadCounts,
            repository.feedItemStyle,
            repository.expandedTags,
            toolbarVisible,
            visibleFeedItemCount,
            screenTitleForCurrentFeedOrTag,
            editDialogVisible,
            deleteDialogVisible,
            visibleFeeds,
            currentArticle,
            repository.linkOpener,
            repository.currentFeedAndTag.map { (feedId, tag) -> FeedOrTag(feedId, tag) },
            repository.currentArticle,
            readAloudStateHolder.title,
            readAloudStateHolder.readAloudState,
            textToDisplayTrigger, // Never actually read, only used as trigger
        ) { params: Array<Any> ->
            @Suppress("UNCHECKED_CAST")
            val article = params[17] as Article

            @Suppress("UNCHECKED_CAST")
            val readAloudState = params[19] as PlaybackStatus

            @Suppress("UNCHECKED_CAST")
            val haveVisibleFeedItems = (params[9] as Int) > 0

            @Suppress("UNCHECKED_CAST")
            val currentArticle = params[14] as CurrentArticle

            @Suppress("UNCHECKED_CAST")
            FeedArticleScreenViewState(
                onlyUnread = params[0] as Boolean,
                showFab = haveVisibleFeedItems && (params[1] as Boolean),
                showThumbnails = params[2] as Boolean,
                currentTheme = params[3] as ThemeOptions,
                latestSyncTimestamp = params[4] as Instant,
                drawerItemsWithUnreadCounts = params[5] as List<DrawerItemWithUnreadCount>,
                feedItemStyle = params[6] as FeedItemStyle,
                expandedTags = params[7] as Set<String>,
                showToolbarMenu = params[8] as Boolean,
                haveVisibleFeedItems = haveVisibleFeedItems,
                feedScreenTitle = params[10] as ScreenTitle,
                showEditDialog = params[11] as Boolean,
                showDeleteDialog = params[12] as Boolean,
                visibleFeeds = params[13] as List<FeedTitle>,
                articleFeedUrl = article.feedUrl,
                currentArticle = currentArticle,
                articleFeedId = article.feedId,
                linkOpener = params[15] as LinkOpener,
                pubDate = article.pubDate,
                author = article.author,
                enclosure = article.enclosure,
                articleTitle = article.title,
                feedDisplayTitle = article.feedDisplayTitle,
                currentFeedOrTag = params[16] as FeedOrTag,
                articleLink = article.link,
                textToDisplay = getTextToDisplayFor(currentArticle.itemId),
                readAloudTitle = params[18] as String,
                isReadAloudPlaying = readAloudState == PlaybackStatus.PLAYING,
                isReadAloudVisible = readAloudState != PlaybackStatus.STOPPED,
                articleFlowsInSync = article.id == currentArticle.itemId,
            )
        }
            // To avoid some flicker of out of date elements in ArticleView
            .filter { it.articleFlowsInSync }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                FeedArticleScreenViewState()
            )

    fun displayArticleText() {
        setTextToDisplayFor(viewState.value.articleId, TextToDisplay.DEFAULT)
    }

    fun displayFullText() {
        val itemId = viewState.value.articleId
        viewModelScope.launch {
            loadFullTextThenDisplayIt(itemId)
        }
    }

    private suspend fun loadFullTextThenDisplayIt(itemId: Long) {
        val filesDir = getApplication<FeederApplication>().filesDir

        if (blobFullFile(viewState.value.articleId, filesDir).isFile) {
            setTextToDisplayFor(itemId, TextToDisplay.FULLTEXT)
            return
        }

        setTextToDisplayFor(itemId, TextToDisplay.LOADING_FULLTEXT)
        val link = viewState.value.articleLink
        val result = parseFullArticleIfMissing(
            object : FeedItemForFetching {
                override val id = viewState.value.articleId
                override val link = link
            },
            di.direct.instance(),
            filesDir,
        )

        setTextToDisplayFor(
            itemId,
            when (result) {
                true -> TextToDisplay.FULLTEXT
                false -> TextToDisplay.FAILED_TO_LOAD_FULLTEXT
            }
        )
    }

    fun readAloudStop() {
        readAloudStateHolder.stop()
    }

    fun readAloudPause() {
        readAloudStateHolder.pause()
    }

    fun readAloudPlay() {
        val context = getApplication<FeederApplication>()
        viewModelScope.launch(Dispatchers.IO) {
            val fullText = when (viewState.value.textToDisplay) {
                TextToDisplay.DEFAULT -> {
                    blobInputStream(viewState.value.currentArticle.itemId, context.filesDir).use {
                        getPlainTextOfHtmlStream(
                            inputStream = it,
                            baseUrl = viewState.value.articleFeedUrl ?: ""
                        )
                    }
                }
                TextToDisplay.FULLTEXT -> {
                    blobFullInputStream(
                        viewState.value.currentArticle.itemId,
                        context.filesDir
                    ).use {
                        getPlainTextOfHtmlStream(
                            inputStream = it,
                            baseUrl = viewState.value.articleFeedUrl ?: ""
                        )
                    }
                }
                TextToDisplay.LOADING_FULLTEXT -> null
                TextToDisplay.FAILED_TO_LOAD_FULLTEXT -> null
            }

            if (fullText == null) {
                // TODO show error some message
            } else {
                readAloudStateHolder.readAloud(
                    title = viewState.value.articleTitle,
                    fullText = fullText
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        readAloudStateHolder.shutdown()
    }
}

interface FeedScreenViewState {
    val currentFeedOrTag: FeedOrTag
    val onlyUnread: Boolean
    val showFab: Boolean
    val showThumbnails: Boolean
    val currentTheme: ThemeOptions
    val latestSyncTimestamp: Instant
    val feedScreenTitle: ScreenTitle
    val visibleFeeds: List<FeedTitle>
    val drawerItemsWithUnreadCounts: List<DrawerItemWithUnreadCount>
    val feedItemStyle: FeedItemStyle
    val expandedTags: Set<String>
    val bottomBarVisible: Boolean
    val isReadAloudVisible: Boolean
    val isReadAloudPlaying: Boolean
    val readAloudTitle: String
    val showToolbarMenu: Boolean
    val showDeleteDialog: Boolean
    val showEditDialog: Boolean
    val haveVisibleFeedItems: Boolean
}

interface ArticleScreenViewState {
    val isReadAloudVisible: Boolean
    val isReadAloudPlaying: Boolean
    val readAloudTitle: String
    val articleFeedUrl: String?
    val articleId: Long
    val articleLink: String?
    val articleFeedId: Long
    val textToDisplay: TextToDisplay
    val linkOpener: LinkOpener
    val pubDate: ZonedDateTime?
    val author: String?
    val enclosure: Enclosure
    val articleTitle: String
    val showToolbarMenu: Boolean
    val feedDisplayTitle: String
}

@Immutable
data class FeedArticleScreenViewState(
    override val currentFeedOrTag: FeedOrTag = FeedOrTag(ID_UNSET, ""),
    override val onlyUnread: Boolean = true,
    override val showFab: Boolean = true,
    override val showThumbnails: Boolean = true,
    override val currentTheme: ThemeOptions = ThemeOptions.SYSTEM,
    override val latestSyncTimestamp: Instant = Instant.EPOCH,
    // Defaults to empty string to avoid rendering until loading complete
    override val feedScreenTitle: ScreenTitle = ScreenTitle(""),
    override val visibleFeeds: List<FeedTitle> = emptyList(),
    override val drawerItemsWithUnreadCounts: List<DrawerItemWithUnreadCount> = emptyList(),
    override val feedItemStyle: FeedItemStyle = FeedItemStyle.CARD,
    override val expandedTags: Set<String> = emptySet(),
    override val bottomBarVisible: Boolean = false,
    override val isReadAloudVisible: Boolean = false,
    override val isReadAloudPlaying: Boolean = false,
    override val readAloudTitle: String = "",
    override val showToolbarMenu: Boolean = false,
    override val showDeleteDialog: Boolean = false,
    override val showEditDialog: Boolean = false,
    // Defaults to true so empty screen doesn't appear before load
    override val haveVisibleFeedItems: Boolean = true,
    override val articleFeedUrl: String? = null,
    val currentArticle: CurrentArticle = CurrentArticle(ID_UNSET, false),
    override val articleFeedId: Long = ID_UNSET,
    override val textToDisplay: TextToDisplay = TextToDisplay.DEFAULT,
    override val linkOpener: LinkOpener = LinkOpener.CUSTOM_TAB,
    override val pubDate: ZonedDateTime? = null,
    override val author: String? = null,
    override val enclosure: Enclosure = Enclosure(),
    override val articleTitle: String = "",
    override val articleLink: String? = null,
    override val feedDisplayTitle: String = "",
    val articleFlowsInSync: Boolean = true,
) : FeedScreenViewState, ArticleScreenViewState {
    // Need atomic updates on these two fields which is why they are hidden behind a data class
    override val articleId = currentArticle.itemId
    val isArticleOpen = currentArticle.isOpen
}

@Immutable
data class CurrentArticle(
    val itemId: Long,
    val isOpen: Boolean,
)
