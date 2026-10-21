package com.aeonlab.toolkit.hooks.core

import com.aeonlab.toolkit.hooks.api.core.WechatApiFeature
import com.aeonlab.toolkit.hooks.items.audiotransform.AudioTransformFeature
import com.aeonlab.toolkit.hooks.items.antirecall.AntiRecallFeature
import com.aeonlab.toolkit.hooks.items.atallnotify.AtAllNotificationBlockFeature
import com.aeonlab.toolkit.hooks.items.automessageforward.AutoMessageForwardFeature
import com.aeonlab.toolkit.hooks.items.autoreply.AutoReplyFeature
import com.aeonlab.toolkit.hooks.items.autooriginal.AutoOriginalImageFeature
import com.aeonlab.toolkit.hooks.items.autovieworiginal.AutoViewOriginalFeature
import com.aeonlab.toolkit.hooks.items.callmedialimit.CallMediaLimitFeature
import com.aeonlab.toolkit.hooks.items.callmedialimit.CallRingtoneBlockFeature
import com.aeonlab.toolkit.hooks.items.chattime.ChatTimeStyleFeature
import com.aeonlab.toolkit.hooks.items.conversationgroup.ConversationGroupFeature
import com.aeonlab.toolkit.hooks.items.conversationtabs.ConversationTabsFeature
import com.aeonlab.toolkit.hooks.items.custombottombar.CustomBottomBarFeature
import com.aeonlab.toolkit.hooks.items.customfriendavatar.CustomFriendAvatarFeature
import com.aeonlab.toolkit.hooks.items.customnotify.CustomNotificationFeature
import com.aeonlab.toolkit.hooks.items.editmsg.EditMessageFeature
import com.aeonlab.toolkit.hooks.items.emojisave.EmojiSaveFeature
import com.aeonlab.toolkit.hooks.items.fakelocation.FakeLocationFeature
import com.aeonlab.toolkit.hooks.items.fakescancamera.FakeScanCameraFeature
import com.aeonlab.toolkit.hooks.items.fakevoiceduration.FakeVoiceDurationFeature
import com.aeonlab.toolkit.hooks.items.floatingshortcut.FloatingShortcutFeature
import com.aeonlab.toolkit.hooks.items.forwardlimit.RemoveForwardLimitFeature
import com.aeonlab.toolkit.hooks.items.gameemoji.GameEmojiFeature
import com.aeonlab.toolkit.hooks.items.groupleave.GroupLeaveMonitorFeature
import com.aeonlab.toolkit.hooks.items.grouplabel.GroupChatLabelFeature
import com.aeonlab.toolkit.hooks.items.grouplabel.QuickGroupChatLabelFeature
import com.aeonlab.toolkit.hooks.items.grouprename.GroupRenameMonitorFeature
import com.aeonlab.toolkit.hooks.items.hideavatar.HideChatAvatarFeature
import com.aeonlab.toolkit.hooks.items.hidemenu.HideChatMenuFeature
import com.aeonlab.toolkit.hooks.items.hotupdate.DisableHotUpdateFeature
import com.aeonlab.toolkit.hooks.items.hometextcolor.HomeTextColorFeature
import com.aeonlab.toolkit.hooks.items.inputhint.InputHintFeature
import com.aeonlab.toolkit.hooks.items.keepalive.WeChatKeepAliveFeature
import com.aeonlab.toolkit.hooks.items.keywordnotify.KeywordNotificationFeature
import com.aeonlab.toolkit.hooks.items.membertitle.MemberTitleFeature
import com.aeonlab.toolkit.hooks.items.messageaffix.MessageAffixFeature
import com.aeonlab.toolkit.hooks.items.messagebubble.MessageBubbleFeature
import com.aeonlab.toolkit.hooks.items.messagetextcolor.MessageTextColorFeature
import com.aeonlab.toolkit.hooks.items.messageblock.MessageBlockFeature
import com.aeonlab.toolkit.hooks.items.messageforward.MessageForwardFeature
import com.aeonlab.toolkit.hooks.items.miniprogrambaselib.FakeMiniProgramBaseLibFeature
import com.aeonlab.toolkit.hooks.items.miniprogramsplashad.SkipGlobalMiniProgramSplashAdsFeature
import com.aeonlab.toolkit.hooks.items.miniprogramvideoad.SkipMiniProgramVideoAdsFeature
import com.aeonlab.toolkit.hooks.items.musicorder.QQMusicOrderFeature
import com.aeonlab.toolkit.hooks.items.moments.OriginalMomentsUploadFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsAutoLikeFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsAutoCommentFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsAutoForwardFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsAutoRefreshFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsBottomDetailFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsContactFilterFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsKeywordBlockFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsPostNotificationFeature
import com.aeonlab.toolkit.hooks.items.moments.RemoveMomentsAdsFeature
import com.aeonlab.toolkit.hooks.items.moments.SnsAntiRecallFeature
import com.aeonlab.toolkit.hooks.items.moments.MomentsUploadTailFeature
import com.aeonlab.toolkit.hooks.items.momentsfake.MomentsFakeInteractionFeature
import com.aeonlab.toolkit.hooks.items.multirecall.MultiRecallFeature
import com.aeonlab.toolkit.hooks.items.payment.core.AutoRedPacketFeature
import com.aeonlab.toolkit.hooks.items.payment.fakebalance.FakeWalletBalanceFeature
import com.aeonlab.toolkit.hooks.items.payment.gift.AutoGiftFeature
import com.aeonlab.toolkit.hooks.items.payment.transfer.AutoTransferFeature
import com.aeonlab.toolkit.hooks.items.patblock.PatBlockFeature
import com.aeonlab.toolkit.hooks.items.profileid.ProfileIdFeature
import com.aeonlab.toolkit.hooks.items.protobuf.ProtobufPacketFeature
import com.aeonlab.toolkit.hooks.items.quoteclear.QuoteDeleteClearFeature
import com.aeonlab.toolkit.hooks.items.quickcontactedit.QuickContactEditFeature
import com.aeonlab.toolkit.hooks.items.quickmoments.QuickMomentsFeature
import com.aeonlab.toolkit.hooks.items.quickread.QuickMarkReadFeature
import com.aeonlab.toolkit.hooks.items.quickterminate.QuickTerminateFeature
import com.aeonlab.toolkit.hooks.items.realtail.RealNameTailFeature
import com.aeonlab.toolkit.hooks.items.roundavatar.RoundAvatarFeature

import com.aeonlab.toolkit.hooks.items.script.ScriptPluginFeature
import com.aeonlab.toolkit.hooks.items.scheduledtask.ScheduledTaskFeature
import com.aeonlab.toolkit.hooks.items.selectedmessages.SelectedMessagesFeature
import com.aeonlab.toolkit.hooks.items.settings.SettingsFeature
import com.aeonlab.toolkit.hooks.items.shortvideo.FinderMediaDownloadFeature
import com.aeonlab.toolkit.hooks.items.statuslimit.StatusTextLimitFeature
import com.aeonlab.toolkit.hooks.items.swipequote.SwipeQuoteFeature
import com.aeonlab.toolkit.hooks.items.tablet.WeChatTabletFeature
import com.aeonlab.toolkit.hooks.items.textspeech.TextSpeechFeature
import com.aeonlab.toolkit.hooks.items.textvoice.TextVoiceFeature
import com.aeonlab.toolkit.hooks.items.typingreport.TypingReportBlockFeature
import com.aeonlab.toolkit.hooks.items.voiceforward.VoiceForwardFeature
import com.aeonlab.toolkit.hooks.items.hchatextra.HchatExtraFeature
import com.aeonlab.toolkit.hooks.items.voicepreview.VoicePreviewFeature
import com.aeonlab.toolkit.hooks.items.zombiecheck.ZombieCheckFeature

/**
 * 全部功能的集中注册表。
 */
object FeatureRegistry {
    @JvmStatic
    fun createDefaultManager(): FeatureManager {
        return FeatureManager()
            .register(SettingsFeature())
            .register(WechatApiFeature())

            .register(FloatingShortcutFeature())
            .register(CustomBottomBarFeature())
            .register(RoundAvatarFeature())
            .register(CustomFriendAvatarFeature())
            .register(RealNameTailFeature())
            .register(MemberTitleFeature())
            .register(AutoRedPacketFeature())
            .register(AutoGiftFeature())
            .register(WeChatTabletFeature())
            .register(AutoTransferFeature())
            .register(FakeWalletBalanceFeature())
            .register(AntiRecallFeature())
            .register(MultiRecallFeature())
            .register(DisableHotUpdateFeature())
            .register(ProfileIdFeature())
            .register(HideChatMenuFeature())
            .register(QuickContactEditFeature())
            .register(QuickGroupChatLabelFeature())
            .register(QuickMomentsFeature())
            .register(QuickMarkReadFeature())
            .register(QuickTerminateFeature())
            .register(AutoReplyFeature())
            .register(AutoMessageForwardFeature())
            .register(ConversationGroupFeature())
            .register(ConversationTabsFeature())
            .register(MessageAffixFeature())
            .register(TypingReportBlockFeature())
            .register(PatBlockFeature())
            .register(AutoOriginalImageFeature())
            .register(AutoViewOriginalFeature())
            .register(RemoveForwardLimitFeature())
            .register(AtAllNotificationBlockFeature())
            .register(CallMediaLimitFeature())
            .register(CallRingtoneBlockFeature())
            .register(CustomNotificationFeature())
            .register(KeywordNotificationFeature())
            .register(TextSpeechFeature())
            .register(TextVoiceFeature())
            .register(ZombieCheckFeature())
            .register(WeChatKeepAliveFeature())
            .register(QuoteDeleteClearFeature())
            .register(SwipeQuoteFeature())
            .register(AudioTransformFeature())
            .register(FakeVoiceDurationFeature())
            .register(ChatTimeStyleFeature())
            .register(InputHintFeature())
            .register(MessageBubbleFeature())
            .register(MessageTextColorFeature())
            .register(HomeTextColorFeature())
            .register(HideChatAvatarFeature())
            .register(MessageBlockFeature())
            .register(FakeLocationFeature())
            .register(GameEmojiFeature())
            .register(FakeMiniProgramBaseLibFeature())
            .register(SkipMiniProgramVideoAdsFeature())
            .register(SkipGlobalMiniProgramSplashAdsFeature())
            .register(QQMusicOrderFeature())
            .register(MomentsAutoLikeFeature())
            .register(MomentsAutoCommentFeature())
            .register(MomentsAutoForwardFeature())
            .register(MomentsAutoRefreshFeature())
            .register(MomentsKeywordBlockFeature())
            .register(MomentsContactFilterFeature())
            .register(MomentsBottomDetailFeature())
            .register(MomentsPostNotificationFeature())
            .register(OriginalMomentsUploadFeature())
            .register(MomentsUploadTailFeature())
            .register(SnsAntiRecallFeature())
            .register(MomentsFakeInteractionFeature())
            .register(GroupChatLabelFeature())
            .register(GroupLeaveMonitorFeature())
            .register(GroupRenameMonitorFeature())
            .register(RemoveMomentsAdsFeature())
            .register(EditMessageFeature())
            .register(EmojiSaveFeature())
            .register(VoicePreviewFeature())
            .register(VoiceForwardFeature())
            .register(SelectedMessagesFeature())
            .register(MessageForwardFeature())
            .register(ScheduledTaskFeature())
            .register(FakeScanCameraFeature())
            .register(ProtobufPacketFeature())
            .register(StatusTextLimitFeature())
            .register(FinderMediaDownloadFeature())
            .register(HchatExtraFeature())
            .register(ScriptPluginFeature())
    }
}
