/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.versions

import com.intellij.openapi.projectRoots.JavaSdkVersion
import org.jetbrains.kotlin.config.JvmTarget

internal fun JavaSdkVersion?.toJvmTarget(): JvmTarget? = when {
    this == null -> null
    isAtLeast(JavaSdkVersion.JDK_11) -> JvmTarget.JVM_11
    isAtLeast(JavaSdkVersion.JDK_10) -> JvmTarget.JVM_10
    isAtLeast(JavaSdkVersion.JDK_1_9) -> JvmTarget.JVM_9
    isAtLeast(JavaSdkVersion.JDK_1_8) -> JvmTarget.JVM_1_8
    isAtLeast(JavaSdkVersion.JDK_1_6) -> JvmTarget.JVM_1_6
    else -> null
}
