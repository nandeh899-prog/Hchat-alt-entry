package com.aeonlab.toolkit.hooks.items.conversationgroup

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.aeonlab.toolkit.hooks.api.contact.WeChatContactChangeApi
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.utils.HLog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Wait for asynchronous chatroom contact writes before rebinding native rows. */
internal object ConversationDndRefresh {
    private const val TIMEOUT_MS = 90_000L
    private val lock = Any()
    private val watches = mutableSetOf<Watch>()

    fun watch(activity: Activity, ids: List<String>, canceled: AtomicBoolean): Watch? =
        synchronized(lock) {
            val targets = ids.filter { it.endsWith("@chatroom") }.toSet()
            if (targets.isEmpty() || canceled.get() || activity.isFinishing || activity.isDestroyed)
                return@synchronized null
            val api = WeChatApis.contactChanges()
            if (api == null || !api.isAvailable) return@synchronized null
            val watch = Watch(activity, targets, canceled)
            watches.add(watch)
            try {
                watch.start(api)
                watch
            } catch (error: Throwable) {
                watch.close()
                HLog.e("[AeonLab:ConversationGroup] 注册群聊免打扰落库监听失败", error)
                null
            }
        }

    fun cancelAll() = synchronized(lock) {
        watches.toList().forEach { it.close() }
    }

    class Watch(
        activity: Activity,
        private val targets: Set<String>,
        private val canceled: AtomicBoolean
    ) : Application.ActivityLifecycleCallbacks {
        private val owner = WeakReference(activity)
        private val application = activity.application
        private val main = Handler(Looper.getMainLooper())
        private var subscription: WeChatContactChangeApi.Subscription? = null
        private var registered = false
        private var closed = false
        private var completed = false
        private var expiresAt = SystemClock.uptimeMillis() + TIMEOUT_MS

        private fun alive(): Activity? {
            val activity = owner.get()
            if (closed) return null
            if (canceled.get() || activity == null || activity.isFinishing ||
                activity.isDestroyed || SystemClock.uptimeMillis() >= expiresAt) {
                close()
                return null
            }
            return activity
        }

        private fun refreshOnce() {
            val activity = alive() ?: return
            runCatching { ConversationGlobalMenu.refreshAfterContactChange(activity) }
                .onFailure { HLog.e("[AeonLab:ConversationGroup] 群聊免打扰落库后刷新失败", it) }
        }

        private val settle = Runnable { synchronized(lock) { refreshOnce() } }
        private val refresh = Runnable {
            synchronized(lock) {
                if (alive() != null && completed) refreshOnce()
            }
        }
        private val firstRefresh = Runnable {
            synchronized(lock) {
                if (alive() != null) {
                    refreshOnce()
                    // Fallback when the SDK write emits no matching contact notification.
                    main.removeCallbacks(settle)
                    main.postDelayed(settle, 900L)
                }
            }
        }
        private val check = object : Runnable {
            override fun run() {
                synchronized(lock) {
                    if (alive() != null) main.postDelayed(this, 1000L)
                }
            }
        }

        fun start(api: WeChatContactChangeApi) {
            application.registerActivityLifecycleCallbacks(this)
            registered = true
            subscription = api.subscribe { change ->
                val db = change.databaseChange
                if (db != null && db.table.equals("rcontact", ignoreCase = true) &&
                    (change.wxId() in targets || db.whereArgs?.any { it in targets } == true)) {
                    synchronized(lock) {
                        if (!closed && completed && !canceled.get()) {
                            // Trailing debounce: only the latest notification needs correction.
                            main.removeCallbacks(refresh)
                            main.removeCallbacks(settle)
                            main.postDelayed(refresh, 250L)
                        }
                    }
                }
            }
            main.post(check)
        }

        fun keepAlive() = synchronized(lock) {
            if (!closed) expiresAt = SystemClock.uptimeMillis() + TIMEOUT_MS
        }

        fun finish(completed: Boolean) = synchronized(lock) {
            if (!completed || canceled.get()) {
                close()
            } else if (!closed && !this.completed) {
                this.completed = true
                keepAlive()
                main.post(firstRefresh)
            }
        }

        fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                main.removeCallbacksAndMessages(null)
                subscription?.unsubscribe()
                subscription = null
                if (registered) {
                    runCatching { application.unregisterActivityLifecycleCallbacks(this) }
                        .onFailure { HLog.e("[AeonLab:ConversationGroup] 释放群聊刷新监听失败", it) }
                    registered = false
                }
                owner.clear()
                watches.remove(this)
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            synchronized(lock) { if (owner.get() === activity) close() }
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }
}
