/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.reporter;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import org.jetbrains.kotlin.idea.KotlinPluginUtil;

import java.util.Date;

public class SomeTest {
    public static void test() {
        IdeaPluginDescriptor plugin = PluginManager.getPlugin(KotlinPluginUtil.KOTLIN_PLUGIN_ID);
        Date date = plugin.getReleaseDate();
    }
}
