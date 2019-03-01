/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.reporter

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.Consumer
import com.intellij.util.io.HttpRequests
import org.jetbrains.kotlin.idea.KotlinPluginUpdater
import org.jetbrains.kotlin.idea.KotlinPluginUtil
import org.jetbrains.kotlin.idea.PluginUpdateStatus
import org.jetbrains.kotlin.idea.util.isEap
import java.awt.Component
import java.util.*
import javax.swing.Icon

/**
 * We need to wrap ITNReporter for force showing or errors from kotlin plugin even from released version of IDEA.
 */
class KotlinReportSubmitter : ITNReporterCompat() {
    companion object {
        private const val KOTLIN_FATAL_ERROR_NOTIFICATION_PROPERTY = "kotlin.fatal.error.notification"
        private const val IDEA_FATAL_ERROR_NOTIFICATION_PROPERTY = "idea.fatal.error.notification"
        private const val DISABLED_VALUE = "disabled"
        private const val ENABLED_VALUE = "enabled"

        private fun fetchPluginUpdatedDate(pluginId: String, version: String): Date? {
            // TODO: Need better request
            val url =
                "https://plugins.jetbrains.com/plugins/list?pluginId=$pluginId&pluginVersion=$version"
            val responseDoc = HttpRequests.request(url).connect {
                JDOMUtil.load(it.inputStream)
            }
            if (responseDoc.name != "plugin-repository") {
                // Failed
                // Allow retry
                return null
            }

            if (responseDoc.children.isEmpty()) {
                // Failed
                // Shouldn't retry
                return null
            }

            val dateString = responseDoc.getChild("category")?.getChildren("idea-plugin")?.mapNotNull { pluginElement ->
                if (pluginElement.getChild("version")?.text == version) {
                    pluginElement.getAttribute("date")
                } else {
                    null
                }
            }?.singleOrNull()

            if (dateString == null) {
                // Can't find plugin with the current version
                // Shouldn't retry
                return null
            }

            return Date(dateString.longValue)
        }

        private const val KOTLIN_FATAL_ERROR_REPORTING_DISABLED = "kotlin.fatal.error.reporting.disabled"
        private const val KOTLIN_PLUGIN_RELEASE_DATE = "kotlin.plugin.releaseDate"

        private @Volatile var FATAL_ERROR_REPORTING_DISABLED = true

        private const val NUMBER_OF_DAYS = 7

        fun checkAllowReporting() {
            // TODO: check Android Studio
            val isReleaseLikeIdea = DISABLED_VALUE == System.getProperty(IDEA_FATAL_ERROR_NOTIFICATION_PROPERTY, ENABLED_VALUE)
            val isKotlinRelease = !(KotlinPluginUtil.isSnapshotVersion() || KotlinPluginUtil.isDevVersion() || isEap(KotlinPluginUtil.getPluginVersion()))
            if (!isReleaseLikeIdea || !isKotlinRelease) {
                FATAL_ERROR_REPORTING_DISABLED = false
                return
            }

            val currentKotlinVersion = KotlinPluginUtil.getPluginVersion()
            val disabledReportingFor = PropertiesComponent.getInstance().getValue(KOTLIN_FATAL_ERROR_REPORTING_DISABLED)
            if (disabledReportingFor != null) {
                if (disabledReportingFor == currentKotlinVersion) {
                    FATAL_ERROR_REPORTING_DISABLED = true
                    return
                } else {
                    // Plugin was updated
                    PropertiesComponent.getInstance().setValue(KOTLIN_FATAL_ERROR_REPORTING_DISABLED, null)
                }
            }

            val pluginVersionToReleaseDate = PropertiesComponent.getInstance().getValue(KOTLIN_PLUGIN_RELEASE_DATE)
            if (pluginVersionToReleaseDate != null) {
                val parts = pluginVersionToReleaseDate.split("_")
                if (parts.size == 2) {
                    val pluginVersion = parts[0]
                    val dateString = parts[1]

                    if (pluginVersion == currentKotlinVersion) {
                        val releaseDate = dateString.toLongOrNull()?.let { long -> Date(long) }
                        if (releaseDate != null) {
                            FATAL_ERROR_REPORTING_DISABLED = false
                            return
                        }
                    }
                }
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                val kotlinInstallDate = fetchPluginUpdatedDate(KotlinPluginUtil.KOTLIN_PLUGIN_ID.idString, currentKotlinVersion)
                if (kotlinInstallDate != null) {
                    PropertiesComponent.getInstance().setValue(KOTLIN_PLUGIN_RELEASE_DATE, "${currentKotlinVersion}_${kotlinInstallDate.time}")
                }
            }
        }
    }

    private var forbidReportFromRelease: Boolean? = null
    private var hasUpdate = false
    private var hasLatestVersion = false

    override fun showErrorInRelease(event: IdeaLoggingEvent): Boolean {
        val kotlinNotificationEnabled = DISABLED_VALUE != System.getProperty(KOTLIN_FATAL_ERROR_NOTIFICATION_PROPERTY, ENABLED_VALUE)
        if (!kotlinNotificationEnabled) {
            // Kotlin notifications are explicitly disabled
            return false
        }

        if (hasUpdate) {
            // Don't report from outdated versions
            // TODO: check it actually works
            return ApplicationManager.getApplication().isInternal
        }

        // TODO: Check reporting allowence is expired

        return forbidReportFromRelease != true
    }

    override fun submitCompat(
        events: Array<IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component?,
        consumer: Consumer<SubmittedReportInfo>
    ): Boolean {
        if (hasUpdate) {
            if (ApplicationManager.getApplication().isInternal) {
                return super.submitCompat(events, additionalInfo, parentComponent, consumer)
            }

            // TODO: What happens here? User clicks report but not report is send?
            return true
        }

        if (hasLatestVersion) {
            return super.submitCompat(events, additionalInfo, parentComponent, consumer)
        }

        if (forbidReportFromRelease == true) {
            // TODO: Actually should never be there
            return true
        }

        // It's eap version or user have enabled reporting manually
        // TODO: check Android Studio
//        val isReleaseLikeIdea = DISABLED_VALUE == System.getProperty(IDEA_FATAL_ERROR_NOTIFICATION_PROPERTY, ENABLED_VALUE)
//        val isKotlinRelease = !(KotlinPluginUtil.isSnapshotVersion() || KotlinPluginUtil.isDevVersion() || isEap(KotlinPluginUtil.getPluginVersion()))
//        if (isReleaseLikeIdea && isKotlinRelease) {
//            val date = fetchPluginUpdatedDate()
//            println(date)
//        }

        KotlinPluginUpdater.getInstance().runUpdateCheck { status ->
            if (status is PluginUpdateStatus.Update) {
                hasUpdate = true

                if (ApplicationManager.getApplication().isInternal) {
                    super.submitCompat(events, additionalInfo, parentComponent, consumer)
                }

                val rc = showDialog(
                    parentComponent,
                    "You're running Kotlin plugin version ${KotlinPluginUtil.getPluginVersion()}, " +
                            "while the latest version is ${status.pluginDescriptor.version}",
                    "Update Kotlin Plugin",
                    arrayOf("Update", "Ignore"),
                    0, Messages.getInformationIcon()
                )

                if (rc == 0) {
                    KotlinPluginUpdater.getInstance().installPluginUpdate(status)
                }
            } else {
                hasLatestVersion = true
                super.submitCompat(events, additionalInfo, parentComponent, consumer)
            }
            false
        }

        return true
    }

    fun showDialog(parent: Component?, message: String, title: String, options: Array<String>, defaultOptionIndex: Int, icon: Icon?): Int {
        return if (parent != null) {
            Messages.showDialog(parent, message, title, options, defaultOptionIndex, icon)
        } else {
            Messages.showDialog(message, title, options, defaultOptionIndex, icon)
        }
    }


}
