/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.codegen

import org.jetbrains.kotlin.codegen.Callable
import org.jetbrains.kotlin.codegen.CallableMethod
import org.jetbrains.kotlin.ir.types.IrType

interface IrCallable : Callable {
    val dispatchReceiverIrType: IrType?
    val extensionReceiverIrType: IrType?
    val returnIrType: IrType?
}

// A callable with additional IrTypes.
class IrCallableImpl(
    internal val underlying: Callable,
    override val dispatchReceiverIrType: IrType?,
    override val extensionReceiverIrType: IrType?,
    override val returnIrType: IrType?
) : IrCallable, Callable by underlying

fun Callable.toCallableMethod(): CallableMethod =
    when (this) {
        is CallableMethod -> this
        is IrCallableImpl -> underlying.toCallableMethod()
        else -> error("Callable cannot be resolved to CallableMethod: $this")
    }
