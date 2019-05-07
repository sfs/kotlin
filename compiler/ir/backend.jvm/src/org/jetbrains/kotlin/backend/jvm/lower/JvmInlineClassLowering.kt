/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.phaser.makeIrFilePhase
import org.jetbrains.kotlin.backend.jvm.lower.inlineclasses.InlineClassDeclarationLowering
import org.jetbrains.kotlin.backend.jvm.lower.inlineclasses.InlineClassManager
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

val jvmInlineClassPhase = makeIrFilePhase(
    ::JvmInlineClassLowering,
    name = "Inline Classes",
    description = "Lower inline classes"
)

class JvmInlineClassLowering(context: BackendContext) : FileLoweringPass {
    private val inlineClassManager = InlineClassManager()
    private val declarationLowering = InlineClassDeclarationLowering(context, inlineClassManager)

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(declarationLowering)
        irFile.patchDeclarationParents()
    }
}
