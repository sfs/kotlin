/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.reporter

import com.intellij.ide.plugins.PluginManager
import org.jetbrains.kotlin.idea.KotlinPluginUtil

fun checkAllowReporting() {
    val kotlinPlugin = PluginManager.getPlugin(KotlinPluginUtil.KOTLIN_PLUGIN_ID) ?: return
    val releaseDate = kotlinPlugin.getReleaseDate() ?: return // works
}

fun checkAllowReporting1() {
    val kotlinPlugin = PluginManager.getPlugin(KotlinPluginUtil.KOTLIN_PLUGIN_ID) ?: return
    val releaseDate = kotlinPlugin.releaseDate ?: return // works
}

class Test {
    companion object {
        fun checkAllowReporting1() {
            val kotlinPlugin = PluginManager.getPlugin(KotlinPluginUtil.KOTLIN_PLUGIN_ID) ?: return
            val releaseDate = kotlinPlugin.releaseDate ?: return // works
        }
    }
}