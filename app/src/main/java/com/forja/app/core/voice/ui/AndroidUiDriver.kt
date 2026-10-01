package com.forja.app.core.voice.ui

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** All node handles stay inside one observation/action and are never held across a suspension. */
internal class AndroidUiDriver(
    private val service: AccessibilityService,
    private val targetPackage: String,
    private val windowClass: (Int) -> String
) : UiDriver {
    private val keyguard = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    override suspend fun launch(packageName: String): Boolean {
        currentCoroutineContext().ensureActive()
        if (keyguard.isKeyguardLocked) throw UiControlException("Deblochează telefonul înainte de comandă.", "app_opened")
        val launch = service.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            true
        } catch (_: RuntimeException) { false }
    }

    override suspend fun snapshot(): UiSnapshot? {
        currentCoroutineContext().ensureActive()
        return activeRoot()?.let { root -> try { observe(root) } finally { root.recycle() } }
    }

    override suspend fun perform(snapshot: UiSnapshot, node: UiNode, action: UiAction, text: String?): Boolean {
        currentCoroutineContext().ensureActive()
        val root = activeRoot() ?: return false
        return try {
            val fresh = observe(root)
            UiSafety.requireSafe(fresh, snapshot.packageName, "action_executed")
            if (fresh.windowId != snapshot.windowId) return false
            val freshNode = fresh.nodes.firstOrNull { it.id == node.id } ?: return false
            if (!node.sameTarget(freshNode) || action !in freshNode.actions) return false
            UiSafety.requireActionSafe(freshNode, "action_executed")
            val target = resolve(root, node.id) ?: return false
            try {
                if (!target.refresh() || !node.sameTarget(describe(target, node.id, node.parentId))) return false
                // The keyguard can be raised while a tree is being collected.
                currentCoroutineContext().ensureActive()
                if (keyguard.isKeyguardLocked) throw UiControlException("Telefonul a fost blocat; comanda a fost oprită.", "action_executed")
                val foreground = activeRoot() ?: return false
                try {
                    UiSafety.requireSafe(UiSnapshot(foreground.packageName?.toString().orEmpty(), foreground.windowId,
                        emptyList(), keyguard.isKeyguardLocked, blockingSystemWindow(), windowClass(foreground.windowId)),
                        targetPackage, "action_executed")
                    if (foreground.windowId != fresh.windowId) return false
                } finally { foreground.recycle() }
                currentCoroutineContext().ensureActive()
                when (action) {
                    UiAction.CLICK -> target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    UiAction.SET_TEXT -> target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text ?: "")
                    })
                    UiAction.IME_ENTER -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) else false
                }
            } finally { target.recycle() }
        } finally { root.recycle() }
    }

    override suspend fun pause(milliseconds: Long) = delay(milliseconds)
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    private fun activeRoot(): AccessibilityNodeInfo? {
        val interactive = service.windows
        try {
            // Accessibility overlays and the IME must never become automation targets.
            return interactive.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
                ?.root ?: service.rootInActiveWindow
        } finally { interactive.forEach { it.recycle() } }
    }

    private fun observe(root: AccessibilityNodeInfo): UiSnapshot {
        val nodes = ArrayList<UiNode>()
        var truncated = false
        fun visit(node: AccessibilityNodeInfo, path: String, parent: String?, depth: Int) {
            if (depth > 40 || nodes.size >= 1_200) { truncated = true; return }
            nodes.add(describe(node, path, parent))
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { visit(child, "$path/$index", path, depth + 1) } finally { child.recycle() }
                if (nodes.size >= 1_200) { truncated = true; break }
            }
        }
        // Foreign surfaces are observed only for routing/safety, never for their content.
        if (root.packageName?.toString() == targetPackage) visit(root, "0", null, 0)
        return UiSnapshot(root.packageName?.toString().orEmpty(), root.windowId, nodes,
            keyguard.isKeyguardLocked, blockingSystemWindow() || truncated,
            if (root.className?.toString()?.contains("dialog", true) == true) root.className.toString() else windowClass(root.windowId))
    }

    private fun blockingSystemWindow(): Boolean {
        val interactive = service.windows
        return try { interactive.any {
            it.type == AccessibilityWindowInfo.TYPE_SYSTEM && (it.isActive || it.isFocused)
        } } finally { interactive.forEach { it.recycle() } }
    }

    private fun describe(node: AccessibilityNodeInfo, path: String, parent: String?): UiNode {
        val actionIds = node.actionList.map { it.id }.toSet()
        val actions = buildSet {
            if (AccessibilityNodeInfo.ACTION_CLICK in actionIds && node.isClickable) add(UiAction.CLICK)
            if (AccessibilityNodeInfo.ACTION_SET_TEXT in actionIds && node.isEditable) add(UiAction.SET_TEXT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id in actionIds)
                add(UiAction.IME_ENTER)
        }
        return UiNode(path, node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(),
            node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(), parent,
            node.isVisibleToUser, node.isEnabled, node.isClickable, node.isEditable, node.isPassword, actions)
    }

    private fun resolve(root: AccessibilityNodeInfo, path: String): AccessibilityNodeInfo? {
        var current = AccessibilityNodeInfo.obtain(root)
        for (part in path.split('/').drop(1)) {
            val index = part.toIntOrNull() ?: run { current.recycle(); return null }
            val next = current.getChild(index)
            current.recycle()
            current = next ?: return null
        }
        return current
    }
}
