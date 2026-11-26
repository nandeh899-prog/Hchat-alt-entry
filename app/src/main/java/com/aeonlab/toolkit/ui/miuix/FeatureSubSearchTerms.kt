package com.aeonlab.toolkit.ui.miuix

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.DatePicker
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TimePicker
import android.widget.Toast
import android.widget.VideoView
import com.aeonlab.toolkit.BuildConfig
import com.aeonlab.toolkit.R
import com.aeonlab.toolkit.crash.CrashReportRuntime
import com.aeonlab.toolkit.crash.CrashReportSettings
import com.aeonlab.toolkit.crash.CrashReportSettingsProvider
import com.aeonlab.toolkit.hooks.api.ui.AeonLabAgentIconDrawable
import com.aeonlab.toolkit.hooks.items.floatingshortcut.FloatingShortcutFeature
import com.aeonlab.toolkit.hooks.items.script.market.PluginMarketClient
import com.aeonlab.toolkit.hooks.items.script.market.PluginMarketSettings
import com.aeonlab.toolkit.hooks.items.floatingshortcut.FloatingShortcutGlyph
import com.aeonlab.toolkit.hooks.items.floatingshortcut.FloatingShortcutGlyphDrawable
import com.aeonlab.toolkit.hooks.items.floatingshortcut.FloatingShortcutGlyphs
import com.aeonlab.toolkit.hooks.items.floatingshortcut.FloatingShortcutIconPickResult
import com.aeonlab.toolkit.hooks.items.floatingshortcut.FloatingShortcutIconPicker
import com.aeonlab.toolkit.hooks.items.floatingshortcut.FloatingShortcutIconStore
import com.aeonlab.toolkit.hooks.items.floatingshortcut.FloatingShortcutItem
import com.aeonlab.toolkit.hooks.items.floatingshortcut.FloatingShortcutRuntime
import com.aeonlab.toolkit.hooks.items.floatingshortcut.FloatingShortcutSettings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.hooks.api.media.WeChatFavoriteItem
import com.aeonlab.toolkit.hooks.api.message.WeChatMessageObserveApi
import com.aeonlab.toolkit.hooks.api.model.ContactLabelBean
import com.aeonlab.toolkit.hooks.api.model.WeChatContact
import com.aeonlab.toolkit.hooks.items.audiotransform.AudioTransformFeature
import com.aeonlab.toolkit.hooks.items.audiotransform.AudioTransformCancelledException
import com.aeonlab.toolkit.hooks.items.audiotransform.AudioTransformSegmenter
import com.aeonlab.toolkit.hooks.items.audiotransform.AudioTransformSettings
import com.aeonlab.toolkit.hooks.items.antirecall.AntiRecallFeature
import com.aeonlab.toolkit.hooks.items.antirecall.AntiRecallSettings
import com.aeonlab.toolkit.hooks.items.autoreply.AutoReplyFeature
import com.aeonlab.toolkit.hooks.items.autoreply.AutoReplyRule
import com.aeonlab.toolkit.hooks.items.autoreply.AutoReplyRuntime
import com.aeonlab.toolkit.hooks.items.autoreply.AutoReplySettings
import com.aeonlab.toolkit.hooks.items.autoreply.AutoReplyStep
import com.aeonlab.toolkit.hooks.items.autoreply.XiaozhiMcpStatus
import com.aeonlab.toolkit.hooks.items.autoreply.AutoReplyXiaozhiConfig
import com.aeonlab.toolkit.hooks.items.autoreply.AutoReplyZhiliaConfig
import com.aeonlab.toolkit.hooks.items.autoreply.XiaozhiAgentOption
import com.aeonlab.toolkit.hooks.items.autoreply.XiaozhiConsoleApi
import com.aeonlab.toolkit.hooks.items.autoreply.XiaozhiModelOption
import com.aeonlab.toolkit.hooks.items.autoreply.XiaozhiVoiceOption
import com.aeonlab.toolkit.hooks.items.autooriginal.AutoOriginalImageFeature
import com.aeonlab.toolkit.hooks.items.autooriginal.AutoOriginalImageSettings
import com.aeonlab.toolkit.hooks.items.autovieworiginal.AutoViewOriginalFeature
import com.aeonlab.toolkit.hooks.items.autovieworiginal.AutoViewOriginalSettings
import com.aeonlab.toolkit.hooks.items.atallnotify.AtAllNotificationBlockFeature
import com.aeonlab.toolkit.hooks.items.atallnotify.AtAllNotificationBlockSettings
import com.aeonlab.toolkit.hooks.items.automessageforward.AutoMessageForwardFeature
import com.aeonlab.toolkit.hooks.items.automessageforward.AutoMessageForwardRule
import com.aeonlab.toolkit.hooks.items.automessageforward.AutoMessageForwardSettings
import com.aeonlab.toolkit.hooks.items.callmedialimit.CallMediaLimitFeature
import com.aeonlab.toolkit.hooks.items.callmedialimit.CallMediaLimitSettings
import com.aeonlab.toolkit.hooks.items.callmedialimit.CallRingtoneBlockFeature
import com.aeonlab.toolkit.hooks.items.chattime.ChatTimeStyleFeature
import com.aeonlab.toolkit.hooks.items.chattime.ChatTimeStyleSettings
import com.aeonlab.toolkit.hooks.items.conversationgroup.ConversationGroup
import com.aeonlab.toolkit.hooks.items.conversationgroup.ConversationGroupFeature
import com.aeonlab.toolkit.hooks.items.conversationgroup.ConversationGroupPickerSupport
import com.aeonlab.toolkit.hooks.items.conversationgroup.ConversationGroupRuntime
import com.aeonlab.toolkit.hooks.items.conversationgroup.ConversationGroupStore
import com.aeonlab.toolkit.hooks.items.conversationtabs.ConversationTabsFeature
import com.aeonlab.toolkit.hooks.items.conversationtabs.ConversationTabsStore
import com.aeonlab.toolkit.hooks.items.custombottombar.CustomBottomBarFeature
import com.aeonlab.toolkit.hooks.items.custombottombar.FloatingBottomBarSettings
import com.aeonlab.toolkit.hooks.items.customnotify.CustomNotificationFeature
import com.aeonlab.toolkit.hooks.items.customnotify.CustomNotificationRule
import com.aeonlab.toolkit.hooks.items.customnotify.CustomNotificationRuntime
import com.aeonlab.toolkit.hooks.items.customfriendavatar.CustomFriendAvatarFeature
import com.aeonlab.toolkit.hooks.items.customfriendavatar.CustomFriendAvatarPicker
import com.aeonlab.toolkit.hooks.items.customfriendavatar.CustomFriendAvatarSettings
import com.aeonlab.toolkit.hooks.items.customfriendavatar.CustomFriendAvatarStore
import com.aeonlab.toolkit.hooks.items.customnotify.CustomNotificationSettings
import com.aeonlab.toolkit.hooks.items.editmsg.EditMessageFeature
import com.aeonlab.toolkit.hooks.items.editmsg.EditMessageSettings
import com.aeonlab.toolkit.hooks.items.emojisave.EmojiSaveFeature
import com.aeonlab.toolkit.hooks.items.emojisave.EmojiSaveSettings
import com.aeonlab.toolkit.hooks.items.fakelocation.FakeLocationFeature
import com.aeonlab.toolkit.hooks.items.fakelocation.FakeLocationSettings
import com.aeonlab.toolkit.hooks.items.fakescancamera.FakeScanCameraFeature
import com.aeonlab.toolkit.hooks.items.fakescancamera.FakeScanCameraSettings
import com.aeonlab.toolkit.hooks.items.fakevoiceduration.FakeVoiceDurationFeature
import com.aeonlab.toolkit.hooks.items.fakevoiceduration.FakeVoiceDurationSettings
import com.aeonlab.toolkit.hooks.items.forwardlimit.RemoveForwardLimitFeature
import com.aeonlab.toolkit.hooks.items.forwardlimit.RemoveForwardLimitSettings
import com.aeonlab.toolkit.hooks.items.gameemoji.GameEmojiFeature
import com.aeonlab.toolkit.hooks.items.gameemoji.GameEmojiSettings
import com.aeonlab.toolkit.hooks.items.groupadmin.GroupAdminFeature
import com.aeonlab.toolkit.hooks.items.groupadmin.GroupAdminMiuixPage
import com.aeonlab.toolkit.hooks.items.groupadmin.GroupAdminSettings
import com.aeonlab.toolkit.hooks.items.groupleave.GroupLeaveMonitorFeature
import com.aeonlab.toolkit.hooks.items.groupleave.GroupLeaveReplyTemplate
import com.aeonlab.toolkit.hooks.items.groupleave.GroupLeaveReplyTemplateBinding
import com.aeonlab.toolkit.hooks.items.groupleave.GroupLeaveMonitorSettings
import com.aeonlab.toolkit.hooks.items.grouplabel.GroupChatLabel
import com.aeonlab.toolkit.hooks.items.grouplabel.GroupChatLabelFeature
import com.aeonlab.toolkit.hooks.items.grouplabel.GroupChatLabelStore
import com.aeonlab.toolkit.hooks.items.grouplabel.QuickGroupChatLabelFeature
import com.aeonlab.toolkit.hooks.items.grouplabel.QuickGroupChatLabelSettings
import com.aeonlab.toolkit.hooks.items.groupnicknamecolor.GroupNicknameColorSettings
import com.aeonlab.toolkit.hooks.items.groupnicknamecolor.GroupNicknameColorStore
import com.aeonlab.toolkit.hooks.items.grouprename.GroupRenameMonitorFeature
import com.aeonlab.toolkit.hooks.items.grouprename.GroupRenameReplyTemplate
import com.aeonlab.toolkit.hooks.items.grouprename.GroupRenameMonitorSettings
import com.aeonlab.toolkit.hooks.items.grouprename.GroupRenameTemplateBinding
import com.aeonlab.toolkit.hooks.items.hideavatar.HideChatAvatarFeature
import com.aeonlab.toolkit.hooks.items.hideavatar.HideChatAvatarSettings
import com.aeonlab.toolkit.hooks.items.hidemenu.HideChatMenuFeature
import com.aeonlab.toolkit.hooks.items.hidemenu.HideChatMenuSettings
import com.aeonlab.toolkit.hooks.items.hotupdate.DisableHotUpdateFeature
import com.aeonlab.toolkit.hooks.items.hotupdate.DisableHotUpdateSettings
import com.aeonlab.toolkit.hooks.items.hometextcolor.HomeTextColorFeature
import com.aeonlab.toolkit.hooks.items.hometextcolor.HomeTextColorSettings
import com.aeonlab.toolkit.hooks.items.inputhint.InputHintFeature
import com.aeonlab.toolkit.hooks.items.inputhint.InputHintSettings
import com.aeonlab.toolkit.hooks.items.inputhint.InputHintStats
import com.aeonlab.toolkit.hooks.items.inputhint.OutgoingMessageStatsRepository
import com.aeonlab.toolkit.hooks.items.keepalive.WeChatKeepAliveFeature
import com.aeonlab.toolkit.hooks.items.keepalive.WeChatKeepAliveRuntime
import com.aeonlab.toolkit.hooks.items.keepalive.WeChatKeepAliveSettings
import com.aeonlab.toolkit.hooks.items.keywordnotify.KeywordNotificationFeature
import com.aeonlab.toolkit.hooks.items.keywordnotify.KeywordNotificationRuntime
import com.aeonlab.toolkit.hooks.items.keywordnotify.KeywordNotificationSettings
import com.aeonlab.toolkit.hooks.items.keywordnotify.KeywordRule
import com.aeonlab.toolkit.hooks.items.zombiecheck.ZombieCheckController
import com.aeonlab.toolkit.hooks.items.zombiecheck.ZombieCheckActionResult
import com.aeonlab.toolkit.hooks.items.zombiecheck.ZombieCheckFeature
import com.aeonlab.toolkit.hooks.items.zombiecheck.ZombieCheckResultType
import com.aeonlab.toolkit.hooks.items.zombiecheck.ZombieCheckSettings
import com.aeonlab.toolkit.hooks.items.membertitle.MemberTitleFeature
import com.aeonlab.toolkit.hooks.items.membertitle.MemberTitleSettings
import com.aeonlab.toolkit.hooks.items.membertitle.MemberTitleStore
import com.aeonlab.toolkit.hooks.items.messageaffix.MessageAffixFeature
import com.aeonlab.toolkit.hooks.items.messageaffix.MessageAffixSettings
import com.aeonlab.toolkit.hooks.items.messagebubble.MessageBubbleFeature
import com.aeonlab.toolkit.hooks.items.messagebubble.MessageBubblePickResult
import com.aeonlab.toolkit.hooks.items.messagebubble.MessageBubblePicker
import com.aeonlab.toolkit.hooks.items.messagebubble.MessageBubbleSettings
import com.aeonlab.toolkit.hooks.items.messagebubble.MessageBubbleSlot
import com.aeonlab.toolkit.hooks.items.messagebubble.MessageBubbleStore
import com.aeonlab.toolkit.hooks.items.messagebubble.MessageBubbleKind
import com.aeonlab.toolkit.hooks.items.messagetextcolor.MessageTextColorFeature
import com.aeonlab.toolkit.hooks.items.messagetextcolor.MessageTextColorSettings
import com.aeonlab.toolkit.hooks.items.messageblock.MessageBlockBinding
import com.aeonlab.toolkit.hooks.items.messageblock.MessageBlockDefaultRule
import com.aeonlab.toolkit.hooks.items.messageblock.MessageBlockFeature
import com.aeonlab.toolkit.hooks.items.messageblock.MessageBlockSettings
import com.aeonlab.toolkit.hooks.items.messageblock.MessageBlockTemplate
import com.aeonlab.toolkit.hooks.items.messageforward.MessageForwardFeature
import com.aeonlab.toolkit.hooks.items.messageforward.MessageForwardSettings
import com.aeonlab.toolkit.hooks.items.miniprogrambaselib.FakeMiniProgramBaseLibFeature
import com.aeonlab.toolkit.hooks.items.miniprogrambaselib.FakeMiniProgramBaseLibSettings
import com.aeonlab.toolkit.hooks.items.miniprogramsplashad.SkipGlobalMiniProgramSplashAdsFeature
import com.aeonlab.toolkit.hooks.items.miniprogramsplashad.SkipGlobalMiniProgramSplashAdsSettings
import com.aeonlab.toolkit.hooks.items.miniprogramvideoad.SkipMiniProgramVideoAdsFeature
import com.aeonlab.toolkit.hooks.items.miniprogramvideoad.SkipMiniProgramVideoAdsSettings
import com.aeonlab.toolkit.hooks.items.musicorder.QQMusicOrderFeature
import com.aeonlab.toolkit.hooks.items.musicorder.QQMusicOrderSettings
import com.aeonlab.toolkit.hooks.items.moments.OriginalMomentsUploadFeature
import com.aeonlab.toolkit.hooks.items.moments.OriginalMomentsUploadSettings
import com.aeonlab.toolkit.hooks.items.moments.MomentsAutoCommentFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsAutoCommentSettings
import com.aeonlab.toolkit.hooks.items.moments.MomentsAutoLikeFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsAutoLikeSettings
import com.aeonlab.toolkit.hooks.items.moments.MomentsAutoForwardFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsAutoForwardSettings
import com.aeonlab.toolkit.hooks.items.moments.MomentsAutoRefreshFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsAutoRefreshSettings
import com.aeonlab.toolkit.hooks.items.moments.MomentsBottomDetailFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsBottomDetailSettings
import com.aeonlab.toolkit.hooks.items.moments.MomentsContactFilterFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsContactFilterSettings
import com.aeonlab.toolkit.hooks.items.moments.MomentsKeywordBlockFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsKeywordBlockSettings
import com.aeonlab.toolkit.hooks.items.moments.MomentsPostNotificationFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsPostNotificationSettings
import com.aeonlab.toolkit.hooks.items.moments.MomentsUploadTailFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsUploadTailSettings
import com.aeonlab.toolkit.hooks.items.moments.RemoveMomentsAdsFeature
import com.aeonlab.toolkit.hooks.items.moments.RemoveMomentsAdsSettings
import com.aeonlab.toolkit.hooks.items.moments.SnsAntiRecallFeature
import com.aeonlab.toolkit.hooks.items.moments.SnsAntiRecallSettings
import com.aeonlab.toolkit.hooks.items.momentsfake.MomentsFakeCommentSettingsProvider
import com.aeonlab.toolkit.hooks.items.momentsfake.MomentsFakeForwardRuntimeRegistry
import com.aeonlab.toolkit.hooks.items.momentsfake.MomentsFakeForwardSettingsProvider
import com.aeonlab.toolkit.hooks.items.momentsfake.MomentsFakeLikeSettingsProvider
import com.aeonlab.toolkit.hooks.items.momentsfake.MomentsFakeLikeCandidateRepository
import com.aeonlab.toolkit.hooks.items.momentsfake.MomentsFakeInteractionRuntimeRegistry
import com.aeonlab.toolkit.hooks.items.momentsfake.MomentsFakeInteractionSettings
import com.aeonlab.toolkit.hooks.items.multirecall.MultiRecallFeature
import com.aeonlab.toolkit.hooks.items.multirecall.MultiRecallSettings
import com.aeonlab.toolkit.hooks.items.payment.core.AutoRedPacketFeature
import com.aeonlab.toolkit.hooks.items.payment.core.PaymentTemplateTimeFormatter
import com.aeonlab.toolkit.hooks.items.payment.core.RedPacketReplyStep
import com.aeonlab.toolkit.hooks.items.payment.core.RedPacketRuleBinding
import com.aeonlab.toolkit.hooks.items.payment.core.RedPacketRuleConfig
import com.aeonlab.toolkit.hooks.items.payment.core.RedPacketRuleTemplate
import com.aeonlab.toolkit.hooks.items.payment.core.RedPacketSettings
import com.aeonlab.toolkit.hooks.items.payment.fakebalance.FakeWalletBalanceFeature
import com.aeonlab.toolkit.hooks.items.payment.fakebalance.FakeWalletBalanceSettings
import com.aeonlab.toolkit.hooks.items.payment.gift.AutoGiftFeature
import com.aeonlab.toolkit.hooks.items.payment.gift.GiftSettings
import com.aeonlab.toolkit.hooks.items.payment.transfer.AutoTransferFeature
import com.aeonlab.toolkit.hooks.items.payment.transfer.AutoTransferSettings
import com.aeonlab.toolkit.hooks.items.payment.transfer.TransferReceiveAccountStore
import com.aeonlab.toolkit.hooks.items.payment.transfer.TransferRuleBinding
import com.aeonlab.toolkit.hooks.items.payment.transfer.TransferRuleConfig
import com.aeonlab.toolkit.hooks.items.payment.transfer.TransferRuleTemplate
import com.aeonlab.toolkit.hooks.items.patblock.PatBlockFeature
import com.aeonlab.toolkit.hooks.items.patblock.PatBlockSettings
import com.aeonlab.toolkit.hooks.items.profileid.ProfileIdFeature
import com.aeonlab.toolkit.hooks.items.profileid.ProfileIdSettings
import com.aeonlab.toolkit.hooks.items.protobuf.ProtobufPacketFeature
import com.aeonlab.toolkit.hooks.items.protobuf.ProtobufPacketRuntime
import com.aeonlab.toolkit.hooks.items.protobuf.ProtobufPacketSettings
import com.aeonlab.toolkit.hooks.items.quoteclear.QuoteDeleteClearFeature
import com.aeonlab.toolkit.hooks.items.quoteclear.QuoteDeleteClearSettings
import com.aeonlab.toolkit.hooks.items.quickcontactedit.QuickContactEditFeature
import com.aeonlab.toolkit.hooks.items.quickcontactedit.QuickContactEditSettings
import com.aeonlab.toolkit.hooks.items.quickmoments.QuickMomentsFeature
import com.aeonlab.toolkit.hooks.items.quickmoments.QuickMomentsSettings
import com.aeonlab.toolkit.hooks.items.quickread.QuickMarkReadFeature
import com.aeonlab.toolkit.hooks.items.quickread.QuickMarkReadSettings
import com.aeonlab.toolkit.hooks.items.quickterminate.QuickTerminateFeature
import com.aeonlab.toolkit.hooks.items.quickterminate.QuickTerminateSettings
import com.aeonlab.toolkit.hooks.items.realtail.RealNameTailFeature
import com.aeonlab.toolkit.hooks.items.realtail.RealNameTailSettings
import com.aeonlab.toolkit.hooks.items.realtail.RealNameTailStore
import com.aeonlab.toolkit.hooks.items.roundavatar.RoundAvatarFeature
import com.aeonlab.toolkit.hooks.items.roundavatar.RoundAvatarSettings
import com.aeonlab.toolkit.hooks.items.theme.ThemeFeature
import com.aeonlab.toolkit.hooks.items.theme.ThemeSettings
import com.aeonlab.toolkit.hooks.items.script.ScriptPluginFeature
import com.aeonlab.toolkit.hooks.items.script.ScriptPluginManager
import com.aeonlab.toolkit.hooks.items.script.ScriptPluginRuntime
import com.aeonlab.toolkit.hooks.items.script.ScriptPluginSettings
import com.aeonlab.toolkit.hooks.items.script.market.PluginMarketHistoryVersion
import com.aeonlab.toolkit.hooks.items.script.market.PluginMarketInstaller
import com.aeonlab.toolkit.hooks.items.script.market.PluginMarketException
import com.aeonlab.toolkit.hooks.items.script.market.PluginMarketFile
import com.aeonlab.toolkit.hooks.items.script.market.PluginMarketComment
import com.aeonlab.toolkit.hooks.items.script.market.PluginMarketNotification
import com.aeonlab.toolkit.hooks.items.script.market.PluginMarketPlugin
import com.aeonlab.toolkit.hooks.items.script.market.PluginMarketRepository
import com.aeonlab.toolkit.hooks.items.script.market.PluginMarketReviewStatus
import com.aeonlab.toolkit.hooks.items.script.market.PluginMarketUserIdentity
import com.aeonlab.toolkit.hooks.items.script.agent.CodexSettings
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentClient
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentAttachment
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentChatMessage
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentConfig
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentCancellation
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentContext
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentDiff
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentDraft
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentEventIds
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentLocalFiles
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentMcpServer
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentReasoning
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentRequest
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentResumeState
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentSession
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentSessionStore
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentSettings
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentProfile
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentQuotedMessage
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentSpeech
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentTextMerge
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentToolEvent
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentToolResultStore
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentValidator
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentWorkspaceChange
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentWorkspaceToolConfirmation
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentWorkspaceWriteDecision
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentWorkspaceTools
import com.aeonlab.toolkit.hooks.items.script.agent.ScriptPluginAgentWriter
import com.aeonlab.toolkit.hooks.items.scheduledtask.ScheduledTaskContentItem
import com.aeonlab.toolkit.hooks.items.scheduledtask.ScheduledTaskFeature
import com.aeonlab.toolkit.hooks.items.scheduledtask.ScheduledTaskItem
import com.aeonlab.toolkit.hooks.items.scheduledtask.ScheduledTaskRuntimeCoordinator
import com.aeonlab.toolkit.hooks.items.scheduledtask.ScheduledTaskSettings
import com.aeonlab.toolkit.hooks.items.selectedmessages.SelectedMessageSnapshot
import com.aeonlab.toolkit.hooks.items.selectedmessages.SelectedMessagesFeature
import com.aeonlab.toolkit.hooks.items.selectedmessages.SelectedMessagesRuntimeCoordinator
import com.aeonlab.toolkit.hooks.items.selectedmessages.SelectedMessagesSettings
import com.aeonlab.toolkit.hooks.items.settings.PluginAgentEntryProvider
import com.aeonlab.toolkit.hooks.items.settings.SettingsEntrySettings
import com.aeonlab.toolkit.hooks.items.settings.SettingsFeature
import com.aeonlab.toolkit.hooks.items.shortvideo.FinderMediaDownloadFeature
import com.aeonlab.toolkit.hooks.items.shortvideo.FinderMediaDownloadSettings
import com.aeonlab.toolkit.hooks.items.statuslimit.StatusTextLimitFeature
import com.aeonlab.toolkit.hooks.items.statuslimit.StatusTextLimitSettings
import com.aeonlab.toolkit.hooks.items.swipequote.SwipeQuoteFeature
import com.aeonlab.toolkit.hooks.items.swipequote.SwipeQuoteSettings
import com.aeonlab.toolkit.hooks.items.tablet.WeChatTabletFeature
import com.aeonlab.toolkit.hooks.items.tablet.WeChatTabletSettings
import com.aeonlab.toolkit.hooks.items.textspeech.TextSpeechFeature
import com.aeonlab.toolkit.hooks.items.textspeech.TextSpeechEngineCatalog
import com.aeonlab.toolkit.hooks.items.textspeech.TextSpeechSettings
import com.aeonlab.toolkit.hooks.items.textspeech.TextSpeechVoiceCatalog
import com.aeonlab.toolkit.hooks.items.textvoice.TextVoiceFeature
import com.aeonlab.toolkit.hooks.items.textvoice.TextVoiceSettings
import com.aeonlab.toolkit.hooks.items.typingreport.TypingReportBlockFeature
import com.aeonlab.toolkit.hooks.items.typingreport.TypingReportBlockSettings
import com.aeonlab.toolkit.hooks.items.voiceforward.VoiceForwardFeature
import com.aeonlab.toolkit.hooks.items.voiceforward.VoiceForwardSettings
import com.aeonlab.toolkit.hooks.items.aeonlabextra.GroupMemberHistorySettingsProvider
import com.aeonlab.toolkit.hooks.items.aeonlabextra.MessageDetailsSettingsProvider
import com.aeonlab.toolkit.hooks.items.aeonlabextra.RedPacketDetailsSettingsProvider
import com.aeonlab.toolkit.hooks.items.aeonlabextra.SkipWebRiskSettingsProvider
import com.aeonlab.toolkit.hooks.items.aeonlabextra.AeonLabExtraSettings
import com.aeonlab.toolkit.hooks.items.voicepreview.VoicePreviewFeature
import com.aeonlab.toolkit.hooks.items.voicepreview.VoicePreviewSettings
import com.aeonlab.toolkit.media.AudioTransformBridge
import com.aeonlab.toolkit.preferences.AeonLabStorage
import com.aeonlab.toolkit.preferences.TermsGate
import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.UIRegistry
import com.aeonlab.toolkit.utils.KavaReflector
import com.aeonlab.toolkit.utils.KeywordReplacementRule
import com.aeonlab.toolkit.xposed.compat.XC_MethodHook
import com.aeonlab.toolkit.xposed.compat.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BreadcrumbBar
import top.yukonga.miuix.kmp.basic.BreadcrumbItem
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.menu.WindowDropdownMenu
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.ref.WeakReference
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.random.Random

/**
 * 功能搜索关键词表（从 MiuixSettingsPage.kt 拆出）。
 *
 * 原因：MiuixSettingsPageKt 文件门面类已顶到 JVM 单类 64KB 上限，
 * 这张大字符串表占了相当比例，单独成文件即可腾出余量。
 */
internal fun featureSubSearchTerms(featureId: String): List<String> {
    return when (featureId) {
        AntiRecallFeature.ID -> listOf(
            "私聊防撤回", "群聊防撤回", "公众号防撤回", "撤回提示", "自定义撤回提示", "发送时间", "撤回时间", "时间格式", "语音防撤回", "图片防撤回", "视频防撤回"
        )
        MultiRecallFeature.ID -> listOf("多选撤回", "批量撤回", "多选消息", "分享菜单", "撤回自己消息")
        MessageForwardFeature.ID -> listOf("转发", "转发[H]", "朋友圈", "好友", "分享", "群发助手", "好友标签", "朋友圈转发", "个人主页朋友圈")
        SelectedMessagesFeature.ID -> listOf("群发助手", "群发助手[H]", "定时转发[H]", "多选消息", "定时转发", "模块群发", "微信原生群发助手", "群发助手间隔延迟", "群发间隔延迟")
        AutoReplyFeature.ID -> listOf(
            "自动回复规则", "任意消息", "关键词", "正则", "艾特我", "@我", "艾特全体", "@所有人", "回复步骤", "延迟回复",
            "回复冷却时间", "冷却秒数", "最大回复次数",
            "小智", "小智AI", "小智回复", "小智语音", "小智语音回复", "小智文字回复", "唤醒词", "语音输入", "TTS",
            "上下文", "固定ID", "清空上下文", "智谱", "模型", "Agent", "角色", "语音角色", "好友自动化", "新好友打招呼"
        )
        AutoMessageForwardFeature.ID -> listOf(
            "消息自动转发", "自动转发消息", "转发规则", "监听会话", "转发到", "好友", "群聊", "公众号",
            "文字", "图片", "语音", "视频", "表情", "文件", "链接", "音乐", "小程序", "关键词", "包含关键词", "排除关键词", "替换关键词", "替换规则"
        )
        ConversationGroupFeature.ID -> listOf(
            "会话分组", "聊天分组", "嵌套分组", "归拢会话", "隐藏会话", "移动会话", "分组管理"
        )
        ConversationTabsFeature.ID -> listOf(
            "标签分组", "顶栏分组", "全部会话", "未读会话", "群聊", "私聊", "公众号", "自定义标签", "标签图标"
        )
        MessageAffixFeature.ID -> listOf(
            "发送文本格式", "消息格式", "消息前后缀", "消息前缀", "消息后缀", "固定文字", "发送时间", "换行"
        )
        InputHintFeature.ID -> listOf(
            "输入框提示", "聊天输入框", "默认提示", "占位文字", "发送统计", "消息数量", "文字字数"
        )
        MessageBlockFeature.ID -> listOf(
            "屏蔽消息", "屏蔽规则", "默认规则", "私聊规则", "群聊规则", "公众号规则", "任意消息", "关键词", "正则",
            "艾特我", "@我", "艾特全体", "@所有人", "允许入库", "消息入库", "模板", "名单", "批量绑定", "标签好友", "未知类型"
        )
        QuickMarkReadFeature.ID -> listOf("快捷已读", "一键已读", "长按已读", "通知已读", "会话已读")
        AutoOriginalImageFeature.ID -> listOf("自动勾选原图", "原图发送", "聊天图片原图", "发送图片")
        AutoViewOriginalFeature.ID -> listOf(
            "自动查看原图", "查看原图", "自动查看原视频", "查看原视频", "聊天图片", "聊天视频", "原画质"
        )
        RemoveForwardLimitFeature.ID -> listOf("移除转发限制", "转发上限", "9个会话", "多选转发", "原生转发")
        TypingReportBlockFeature.ID -> listOf("拦截正在输入上报", "屏蔽正在输入", "正在输入", "输入状态", "输入上报")
        PatBlockFeature.ID -> listOf("禁止拍一拍", "屏蔽拍一拍", "双击头像", "头像双击")
        MessageDetailsSettingsProvider.FEATURE_ID -> listOf(
            "消息显示时间", "消息显示详情", "消息详情", "消息时间", "消息类型", "时间格式", "时间颜色", "显示位置", "点击显详情"
        )
        GroupMemberHistorySettingsProvider.FEATURE_ID -> listOf("历史发言记录", "群成员历史发言", "群成员资料")
        AtAllNotificationBlockFeature.ID -> listOf("屏蔽艾特所有人", "屏蔽@所有人", "艾特全体", "通知拦截")
        QuoteDeleteClearFeature.ID -> listOf("删除键清引用", "清除引用", "引用消息", "删除引用")
        EmojiSaveFeature.ID -> listOf("保存表情", "表情保存", "动画表情", "长按菜单", "本地文件")
        SwipeQuoteFeature.ID -> listOf("滑动手势", "左滑引用", "右滑复读", "长按复读", "菜单复读", "+1", "复读语音", "语音时长", "滑动距离")
        CallMediaLimitFeature.ID -> listOf("通话媒体限制", "语音通话", "视频通话", "播放语音", "查看视频", "打开拍摄")
        CallRingtoneBlockFeature.ID -> listOf("屏蔽通话铃声", "呼入铃声", "呼出铃声", "来电铃声", "等待铃声", "语音通话", "视频通话")
        AudioTransformFeature.ID -> listOf(
            "音频转换",
            "音频转码",
            "语音转码",
            "silk",
            "mp3",
            "m4a",
            "语音发送",
            "切割语音",
            "分段语音",
            "裁剪语音",
            "自动分割",
            "开始时间",
            "结束时间",
            "试听",
            "播放音频"
        )
        TextVoiceFeature.ID -> listOf("文本转语音", "文字转语音", "在线语音", "发送文字转语音", "转语音播放", "#tts", "英文语音", "TTS引擎", "TTS角色", "语速", "播放速度")
        FakeVoiceDurationFeature.ID -> listOf("伪造语音时长", "语音显示时长", "假语音时长", "语音秒数")
        VoicePreviewFeature.ID -> listOf("语音消息预览", "预览语音", "播放语音", "语音时长", "长按语音")
        FakeMiniProgramBaseLibFeature.ID -> listOf("兼容低版本小程序", "伪装小程序宿主版本", "阻止小程序升级跳转", "微信版本较低", "官方升级页")
        SkipMiniProgramVideoAdsFeature.ID -> listOf("跳过小程序视频广告", "小程序广告", "视频广告")
        SkipGlobalMiniProgramSplashAdsFeature.ID -> listOf("跳过全局小程序开屏广告", "小程序开屏广告", "启动广告", "全局广告")
        FakeLocationFeature.ID -> listOf("虚拟定位", "模拟定位", "小程序定位", "微信地图", "地图选点", "搜索地点", "经纬度", "GPS")
        GameEmojiFeature.ID -> listOf("指定骰子猜拳", "自定义骰子", "骰子点数", "猜拳结果", "剪刀", "石头", "布", "发送时选择")
        VoiceForwardFeature.ID -> listOf("语音转发", "语音保存", "合并语音", "合并MP3", "转发弹窗", "好友列表", "群聊列表", "语音列表", "批量转发")
        ScheduledTaskFeature.ID -> listOf("定时任务", "定时发送", "群发任务", "计划发送", "循环发送", "每日发送", "每周发送", "朋友圈", "图文", "视文")
        AutoRedPacketFeature.ID -> listOf(
            "自动抢红包", "红包关键词", "红包规则", "规则模板", "回复步骤", "私聊红包回复", "群红包回复", "延迟抢红包", "仅群聊", "仅私聊", "排除名单", "领取后回复"
        )
        AutoTransferFeature.ID -> listOf(
            "自动收款", "转账关键词", "转账规则", "延迟到账", "自动领取转账", "自动收转账", "收款回复",
            "收款模板", "适用聊天", "批量套用", "随机延迟", "禁收时段", "收款通知", "收款播报", "媒体回复"
        )
        FakeWalletBalanceFeature.ID -> listOf("伪造零钱", "钱包余额", "零钱余额", "零钱通", "经营账户", "金额显示")
        RedPacketDetailsSettingsProvider.FEATURE_ID -> listOf("红包显示详情", "红包详情", "红包金额", "领取时间")
        CustomNotificationFeature.ID -> listOf(
            "自定义通知", "通知规则", "通知模板", "私聊通知", "群聊通知", "公众号通知", "艾特我", "@我", "艾特全体", "@所有人", "免打扰通知", "微信消息免打扰"
        )
        KeywordNotificationFeature.ID -> listOf(
            "关键词通知", "关键词列表", "关键词模板", "通知模板", "艾特我", "@我", "艾特全体", "@所有人", "允许入库", "消息入库"
        )
        TextSpeechFeature.ID -> listOf(
            "文字转语音播报", "文字转语音", "语音播报", "TTS引擎", "语音引擎", "播报角色", "TTS角色", "声音角色", "音色", "Voice", "自动播放语音", "播放语音消息", "原语音", "音键控制", "音量键控制", "播报发送人", "免打扰", "开始时间", "结束时间", "设置允许名单", "允许名单"
        )
        ZombieCheckFeature.ID -> listOf("僵尸粉检测", "好友关系检测", "单向好友", "删除好友", "异常好友", "好友标签")
        QQMusicOrderFeature.ID -> listOf("QQ点歌", "点歌", "音乐卡片", "点歌发送卡片", "点歌发送语音", "触发词", "歌手", "封面", "AppID", "允许点歌聊天")
        GroupChatLabelFeature.ID -> listOf("群聊标签", "群组分类", "群聊分类", "标签群聊", "批量选择群聊")
        QuickGroupChatLabelFeature.ID -> listOf("快捷设置群聊标签", "群聊标签", "会话长按菜单", "聊天分组", "群聊分类")
        GroupLeaveMonitorFeature.ID -> listOf("进退群监控", "进群邀请", "退群提醒", "监听群", "批量保护", "新群保护", "群成员变化")
        GroupRenameMonitorFeature.ID -> listOf("改名监控", "群昵称", "群内昵称", "改名提醒", "改名卡片", "改名模板", "批量套用", "改名系统消息")
        GroupAdminFeature.ID -> listOf(
            "群管理助手", "群管理", "群管理员", "群主", "代理群主", "管理员名单", "白名单", "黑名单",
            "踢人", "移出群", "拉黑", "豁免", "告别语", "纳管", "群成员", "群昵称"
        )
        MomentsAutoLikeFeature.ID -> listOf("朋友圈自动点赞", "自动点赞", "点赞自己的朋友圈", "点赞白名单", "点赞黑名单", "同一人每天点赞数量", "每日点赞上限", "随机延迟", "发布时间限制", "关键词过滤", "链接朋友圈", "音乐朋友圈", "卡片朋友圈", "未知类型朋友圈")
        MomentsAutoCommentFeature.ID -> listOf("朋友圈自动评论", "自动评论", "评论内容", "时间变量", "评论时间", "时间格式", "评论自己的朋友圈", "评论白名单", "评论黑名单", "同一人每天评论数量", "每日评论上限", "评论延迟", "随机延迟", "发布时间限制", "关键词过滤", "评论日志", "链接朋友圈", "音乐朋友圈", "卡片朋友圈", "未知类型朋友圈")
        MomentsAutoForwardFeature.ID -> listOf("朋友圈自动转发", "静默转发", "指定好友", "每日上限", "包含关键词", "排除关键词", "替换关键词", "替换规则", "文案替换", "文案模板", "链接朋友圈", "音乐朋友圈", "卡片朋友圈", "未知类型朋友圈")
        MomentsAutoRefreshFeature.ID -> listOf("朋友圈自动刷新", "后台刷新", "原生刷新", "刷新间隔", "刷新时段")
        MomentsContactFilterFeature.ID -> listOf("朋友圈过滤", "好友过滤", "过滤好友", "只看好友", "朋友圈白名单", "朋友圈黑名单", "好友标签", "聊天分组")
        MomentsKeywordBlockFeature.ID -> listOf("朋友圈关键词屏蔽", "朋友圈屏蔽", "关键词过滤", "隐藏朋友圈")
        MomentsBottomDetailFeature.ID -> listOf("朋友圈底部详情", "朋友圈时间", "底部时间", "自定义时间", "时间格式", "可见范围")
        MomentsPostNotificationFeature.ID -> listOf("朋友圈发布通知", "朋友圈提醒", "好友发布朋友圈", "朋友圈Toast", "通知模板")
        OriginalMomentsUploadFeature.ID -> listOf("朋友圈原图", "原图上传", "原视频", "原视频上传", "不压缩", "朋友圈图片", "朋友圈视频")
        MomentsUploadTailFeature.ID -> listOf("朋友圈上传尾巴", "SDK ID", "SDK名称", "SDK来源", "第三方来源")
        SnsAntiRecallFeature.ID -> listOf(
            "朋友圈防撤回", "朋友圈评论防撤回", "评论防撤回", "已删除标签", "自定义撤回提示", "评论撤回提示", "提示开关", "三天可见", "个人主页朋友圈", "发现页朋友圈", "强制旧版个人主页朋友圈"
        )
        MomentsFakeLikeSettingsProvider.ID -> listOf("朋友圈伪集赞", "伪集赞[H]", "本地点赞", "使用非好友", "群成员", "随机排序", "自动勾选好友", "排除名单", "随机选择好友", "凭空生成点赞", "虚拟点赞人", "伪造点赞数量", "隐藏长按菜单", "自定义文本")
        MomentsFakeCommentSettingsProvider.ID -> listOf("朋友圈伪评论", "伪评论[H]", "使用非好友", "群成员", "多选好友", "全选好友", "随机评论", "添加新评论", "评论池", "评论时间", "评论顺序", "隐藏长按菜单", "自定义文本", "清除伪评论")
        MomentsFakeForwardSettingsProvider.ID -> listOf("朋友圈伪转发", "伪转发[H]", "本地朋友圈", "日期", "时间", "重复转发", "隐藏长按菜单", "自定义文本", "清空伪转发", "调试日志")
        RemoveMomentsAdsFeature.ID -> listOf("去除朋友圈广告", "朋友圈广告", "广告屏蔽")
        MessageBubbleFeature.ID -> listOf("消息气泡", "聊天气泡", "自定义气泡", "左侧气泡", "右侧气泡", "红包气泡", "转账气泡", "系统消息气泡", "浅色模式", "深色模式", "NinePatch", "九宫格")
        MessageTextColorFeature.ID -> listOf("消息文本颜色", "文字颜色", "聊天气泡文字", "取色器", "浅色模式", "深色模式")
        HomeTextColorFeature.ID -> listOf("首页文字颜色", "标题颜色", "副标题颜色", "渐变文字", "微信首页", "通讯录", "发现", "我")
        ChatTimeStyleFeature.ID -> listOf("会话时间样式", "聊天时间", "微信时间", "自定义时间", "隐藏时间", "时间格式")
        HideChatAvatarFeature.ID -> listOf("隐藏头像", "隐藏自己头像", "隐藏对方头像", "聊天头像", "群聊头像", "私聊头像")
        CustomBottomBarFeature.ID -> listOf(
            "自定义底栏", "底部导航", "微信首页", "修改图标", "修改标题", "隐藏标题", "隐藏底栏"
        )
        FloatingBottomBarSettings.FEATURE_ID -> listOf(
            "悬浮底栏", "底部导航", "微信首页", "液态玻璃", "模糊半径", "隐藏标签", "显示角标", "振动反馈", "振动强度"
        )
        RoundAvatarFeature.ID -> listOf("圆角头像", "圆形头像", "头像圆角", "头像弧度", "朋友圈头像", "公众号头像", "通知头像")
        ThemeFeature.ID -> listOf(
            "美化主题", "主题", "主题包", "导出主题", "导入主题", "底栏主题", "圆形头像", "隐藏 ActionBar",
            "标题颜色", "页面背景", "启动图", "MDWechat"
        )
        SettingsFeature.ID -> listOf(
            "设置入口",
            "微信设置入口",
            "加号菜单入口",
            "长按加号",
            "启动器入口",
            "入口开关"
        )
        PluginAgentEntryProvider.ID -> listOf("插件 Agent 入口", "插件Agent入口", "右上角加号", "AI 插件入口")
        FloatingShortcutFeature.ID -> listOf(
            "悬浮快捷菜单", "悬浮按钮", "快捷入口", "插件 Agent", "展开 Agent", "收起 Agent",
            "按钮外观", "主按钮大小", "主按钮颜色", "副按钮大小", "副按钮颜色", "按钮渐变",
            "菜单名称颜色", "菜单名称大小", "菜单文字",
            "微信页面", "扫一扫", "朋友圈", "视频号", "收藏", "钱包"
        )
        ProfileIdFeature.ID -> listOf("显示ID", "好友ID", "群聊ID", "wxid", "复制ID", "资料页ID")
        QuickContactEditFeature.ID -> listOf("快捷设置备注和标签", "好友备注", "好友标签", "新建标签", "会话长按菜单", "朋友圈头像")
        QuickMomentsFeature.ID -> listOf("快捷查看朋友圈", "朋友圈", "会话长按菜单", "个人朋友圈", "聊天分组", "自己", "企业微信联系人")
        QuickTerminateFeature.ID -> listOf("快捷终止", "结束微信", "终止微信", "加号菜单", "关闭微信")
        HideChatMenuFeature.ID -> listOf("隐藏聊天菜单", "长按菜单", "隐藏菜单项", "提醒", "搜一搜", "收藏")
        WeChatKeepAliveFeature.ID -> listOf("微信保活", "常驻通知", "WakeLock", "Root保活", "保活服务")
        WeChatTabletFeature.ID -> listOf("平板模式", "Pad模式", "设备登录", "多设备登录", "登录为平板")
        DisableHotUpdateFeature.ID -> listOf("屏蔽热更新", "禁用热更新", "Tinker", "补丁", "热补丁")
        FakeScanCameraFeature.ID -> listOf("伪造扫码相机", "扫码相机", "扫一扫", "相册扫码", "扫码入口")
        SkipWebRiskSettingsProvider.FEATURE_ID -> listOf("跳过网页风险", "网页风险", "WebView", "风险拦截")
        EditMessageFeature.ID -> listOf("修改聊天记录", "编辑消息", "改消息", "消息XML", "发送XML")
        ProtobufPacketFeature.ID -> listOf("Protobuf抓包", "Protobuf发包", "抓包", "发包", "pb", "网络包", "重放")
        CrashReportSettingsProvider.ID -> listOf("捕获异常日志", "异常日志", "闪退日志", "Java异常", "Native异常", "ANR", "调试")
        StatusTextLimitFeature.ID -> listOf("解除状态词长度限制", "状态词", "微信状态", "状态长度", "字数限制")
        RealNameTailFeature.ID -> listOf("实名尾巴", "群聊尾巴", "实名后缀", "尾巴格式", "群成员实名")
        GroupNicknameColorSettings.FEATURE_ID -> listOf("群昵称自定义颜色", "群昵称颜色", "群内昵称颜色", "昵称渐变", "昵称粗细", "颜色取色器")
        MemberTitleFeature.ID -> listOf("群成员头衔", "头衔颜色", "自定义头衔", "头衔模板", "群昵称头衔", "颜色取色器")
        ScriptPluginFeature.ID -> listOf("脚本插件", "插件列表", "插件 Agent", "AI 生成插件", "插件 README", "插件配置", "启用插件", "禁用插件")
        else -> emptyList()
    }
}
