/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.lazy

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrElementBase
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.util.DeclarationStubGenerator
import org.jetbrains.kotlin.ir.util.TypeTranslator
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.KotlinType

abstract class IrLazyDeclarationBase(
    startOffset: Int,
    endOffset: Int,
    override var origin: IrDeclarationOrigin,
    private val stubGenerator: DeclarationStubGenerator,
    protected val typeTranslator: TypeTranslator
) : IrElementBase(startOffset, endOffset), IrDeclaration {

    protected fun KotlinType.toIrType() = typeTranslator.translateType(this)

    protected fun ReceiverParameterDescriptor.generateReceiverParameterStub(): IrValueParameter =
        IrValueParameterImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, this,
            type.toIrType(), null
        )

    protected fun generateMemberStubs(memberScope: MemberScope, container: MutableList<IrDeclaration>) {
        generateChildStubs(memberScope.getContributedDescriptors(), container)
    }

    protected fun generateChildStubs(descriptors: Collection<DeclarationDescriptor>, declarations: MutableList<IrDeclaration>) {
        descriptors.mapTo(declarations) { generateMemberStub(it) }
    }

    private fun generateMemberStub(descriptor: DeclarationDescriptor): IrDeclaration =
        stubGenerator.generateMemberStub(descriptor)

    override var parent: IrDeclarationParent by lazyVar {
        stubGenerator.generateParentStub(descriptor).also { parent ->
            if (parent is IrExternalPackageFragment) {
                parent.declarations.add(this)
            }
        }
    }

    override val annotations: MutableList<IrCall> by lazy {
        descriptor.annotations.map {
            typeTranslator.constantValueGenerator.generateAnnotationConstructorCall(it)
        }.toMutableList()
    }

    override var metadata: Nothing?
        get() = null
        set(_) = error("We should never need to store metadata of external declarations.")
}
