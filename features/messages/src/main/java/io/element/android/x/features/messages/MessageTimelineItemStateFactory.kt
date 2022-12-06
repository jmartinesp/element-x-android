package io.element.android.x.features.messages

import androidx.recyclerview.widget.DiffUtil
import io.element.android.x.designsystem.components.avatar.AvatarData
import io.element.android.x.designsystem.components.avatar.AvatarSize
import io.element.android.x.features.messages.diff.CacheInvalidator
import io.element.android.x.features.messages.diff.MatrixTimelineItemsDiffCallback
import io.element.android.x.features.messages.model.AggregatedReaction
import io.element.android.x.features.messages.model.MessagesItemGroupPosition
import io.element.android.x.features.messages.model.MessagesItemReactionState
import io.element.android.x.features.messages.model.MessagesTimelineItemState
import io.element.android.x.features.messages.model.content.*
import io.element.android.x.matrix.MatrixClient
import io.element.android.x.matrix.media.MediaResolver
import io.element.android.x.matrix.room.MatrixRoom
import io.element.android.x.matrix.timeline.MatrixTimelineItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.matrix.rustcomponents.sdk.FormattedBody
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.MessageType
import timber.log.Timber
import kotlin.system.measureTimeMillis

class MessageTimelineItemStateFactory(
    private val client: MatrixClient,
    private val room: MatrixRoom,
    private val dispatcher: CoroutineDispatcher,
) {

    private val timelineItemCaches = arrayListOf<MessagesTimelineItemState?>()
    private var currentSnapshot: List<MatrixTimelineItem> = emptyList()

    private val lock = Mutex()
    private val cacheInvalidator = CacheInvalidator(timelineItemCaches)

    suspend fun create(
        timelineItems: List<MatrixTimelineItem>,
    ): List<MessagesTimelineItemState> =
        withContext(dispatcher) {
            lock.withLock {
                calculateAndApplyDiff(timelineItems)
                getOrCreateFromCache(timelineItems)
            }
        }

    private suspend fun getOrCreateFromCache(timelineItems: List<MatrixTimelineItem>): List<MessagesTimelineItemState> {
        val messagesTimelineItemState = ArrayList<MessagesTimelineItemState>()
        for (index in timelineItemCaches.indices.reversed()) {
            val cacheItem = timelineItemCaches[index]
            if (cacheItem == null) {
                buildAndCacheItem(timelineItems, index)?.also { timelineItemState ->
                    messagesTimelineItemState.add(timelineItemState)
                }
            } else {
                messagesTimelineItemState.add(cacheItem)
            }
        }
        return messagesTimelineItemState
    }


    private fun calculateAndApplyDiff(timelineItems: List<MatrixTimelineItem>) {
        val timeToDiff = measureTimeMillis {
            val diffCallback =
                MatrixTimelineItemsDiffCallback(
                    oldList = currentSnapshot,
                    newList = timelineItems
                )

            val diffResult = DiffUtil.calculateDiff(diffCallback, false)
            currentSnapshot = timelineItems
            diffResult.dispatchUpdatesTo(cacheInvalidator)
        }
        Timber.v("Time to apply diff on new list of ${timelineItems.size} items: $timeToDiff ms")
    }

    private suspend fun buildAndCacheItem(
        timelineItems: List<MatrixTimelineItem>,
        index: Int
    ): MessagesTimelineItemState? {
        val timelineItemState =
            when (val currentTimelineItem = timelineItems[index]) {
                is MatrixTimelineItem.Event -> {
                    buildMessageEvent(
                        currentTimelineItem,
                        index,
                        timelineItems,
                    )
                }
                is MatrixTimelineItem.Virtual -> MessagesTimelineItemState.Virtual(
                    "virtual_item_$index"
                )
                MatrixTimelineItem.Other -> null
            }
        timelineItemCaches[index] = timelineItemState
        return timelineItemState
    }

    private suspend fun buildMessageEvent(
        currentTimelineItem: MatrixTimelineItem.Event,
        index: Int,
        timelineItems: List<MatrixTimelineItem>,
    ): MessagesTimelineItemState.MessageEvent {
        val currentSender = currentTimelineItem.event.sender()
        val groupPosition =
            computeGroupPosition(currentTimelineItem, timelineItems, index)
        val senderDisplayName = room.userDisplayName(currentSender).getOrNull()
        val senderAvatarUrl = room.userAvatarUrl(currentSender).getOrNull()
        val senderAvatarData =
            loadAvatarData(senderDisplayName ?: currentSender, senderAvatarUrl)
        return MessagesTimelineItemState.MessageEvent(
            id = currentTimelineItem.uniqueId,
            senderId = currentSender,
            senderDisplayName = senderDisplayName,
            senderAvatar = senderAvatarData,
            content = currentTimelineItem.computeContent(),
            isMine = currentTimelineItem.event.isOwn(),
            groupPosition = groupPosition,
            reactionsState = currentTimelineItem.computeReactionsState()
        )
    }

    private fun MatrixTimelineItem.Event.computeReactionsState(): MessagesItemReactionState {
        val aggregatedReactions = event.reactions().map {
            AggregatedReaction(key = it.key, count = it.count.toString(), isHighlighted = false)
        }
        return MessagesItemReactionState(aggregatedReactions)
    }

    private fun MatrixTimelineItem.Event.computeContent(): MessagesTimelineItemContent {
        val content = event.content()
        content.asUnableToDecrypt()?.let { encryptedMessage ->
            return MessagesTimelineItemEncryptedContent(encryptedMessage)
        }
        if (content.isRedactedMessage()) {
            return MessagesTimelineItemRedactedContent
        }
        val contentAsMessage = content.asMessage()
        return when (val messageType = contentAsMessage?.msgtype()) {
            is MessageType.Emote -> MessagesTimelineItemEmoteContent(
                body = messageType.content.body,
                htmlDocument = messageType.content.formatted?.toHtmlDocument()
            )
            is MessageType.Image -> {
                val height = messageType.content.info?.height?.toFloat()
                val width = messageType.content.info?.width?.toFloat()
                val aspectRatio = if (height != null && width != null) {
                    width / height
                } else {
                    0.7f
                }
                MessagesTimelineItemImageContent(
                    body = messageType.content.body,
                    imageMeta = MediaResolver.Meta(
                        source = messageType.content.source,
                        kind = MediaResolver.Kind.Content
                    ),
                    blurhash = messageType.content.info?.blurhash,
                    aspectRatio = aspectRatio
                )
            }
            is MessageType.Notice -> MessagesTimelineItemNoticeContent(
                body = messageType.content.body,
                htmlDocument = messageType.content.formatted?.toHtmlDocument()
            )
            is MessageType.Text -> MessagesTimelineItemTextContent(
                body = messageType.content.body,
                htmlDocument = messageType.content.formatted?.toHtmlDocument()
            )
            else -> MessagesTimelineItemUnknownContent

        }
    }

    private fun FormattedBody.toHtmlDocument(): Document? {
        return takeIf { it.format == MessageFormat.HTML }?.body?.let { formattedBody ->
            Jsoup.parse(formattedBody)
        }
    }

    private fun computeGroupPosition(
        currentTimelineItem: MatrixTimelineItem.Event,
        timelineItems: List<MatrixTimelineItem>,
        index: Int
    ): MessagesItemGroupPosition {
        val prevTimelineItem =
            timelineItems.getOrNull(index - 1) as? MatrixTimelineItem.Event
        val nextTimelineItem =
            timelineItems.getOrNull(index + 1) as? MatrixTimelineItem.Event
        val currentSender = currentTimelineItem.event.sender()
        val previousSender = prevTimelineItem?.event?.sender()
        val nextSender = nextTimelineItem?.event?.sender()

        return when {
            previousSender != currentSender && nextSender == currentSender -> MessagesItemGroupPosition.First
            previousSender == currentSender && nextSender == currentSender -> MessagesItemGroupPosition.Middle
            previousSender == currentSender && nextSender != currentSender -> MessagesItemGroupPosition.Last
            else -> MessagesItemGroupPosition.None
        }
    }

    private suspend fun loadAvatarData(
        name: String,
        url: String?,
        size: AvatarSize = AvatarSize.SMALL
    ): AvatarData {
        val model = client.mediaResolver()
            .resolve(url, kind = MediaResolver.Kind.Thumbnail(size.value))
        return AvatarData(name, model, size)
    }
}
