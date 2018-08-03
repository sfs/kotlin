/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.configuration.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

internal class KotlinFormatterUpcomingUpdateNotification(val project: Project) : Notification(
    "Formatter upcoming update",
    "Kotlin Formatting",
    "Default kotlin formatting settings are going to be updated in 1.3 release",
    NotificationType.INFORMATION,
    null
) {
    init {
        addAction(object : NotificationAction("Move to Kotlin official codestyle") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                doAction(notification)
            }
        })
        addAction(object : NotificationAction("Fix current settings") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                doAction(notification)
            }
        })
    }

    companion object {
        private fun doAction(notification: Notification) {
            notification.expire()
        }
    }
}

internal class KotlinFormatterUpdateNotification(val project: Project) : Notification(
    "Formatter update",
    "Kotlin Formatting",
    "Default kotlin formatting settings were changed",
    NotificationType.WARNING,
    null
) {
    init {
        addAction(object : NotificationAction("Restore previous codestyle") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                doAction(notification)
            }
        })
        addAction(object : NotificationAction("More information") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                doAction(notification)
            }
        })
    }

    companion object {
        private fun doAction(notification: Notification) {
            notification.expire()
        }
    }
}