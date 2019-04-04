/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.codegen

import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.codegen.StackValue
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.toKotlinType
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter

// A value that may not have been fully constructed yet. The ability to "roll back" code generation
// is useful for certain optimizations.
abstract class PromisedValue(val mv: InstructionAdapter, val type: Type, var irType: IrType?) {
    // If this value is immaterial, construct an object on the top of the stack. This
    // must always be done before generating other values or emitting raw bytecode.
    abstract fun materialize()

    val kotlinType: KotlinType?
        get() = irType?.toKotlinType()
}

// A value that *has* been fully constructed.
class MaterialValue(mv: InstructionAdapter, type: Type, irType: IrType?) : PromisedValue(mv, type, irType) {
    override fun materialize() {}
}

// A value that can be branched on. JVM has certain branching instructions which can be used
// to optimize these.
abstract class BooleanValue(mv: InstructionAdapter) : PromisedValue(mv, Type.BOOLEAN_TYPE, null) {
    abstract fun jumpIfFalse(target: Label)
    abstract fun jumpIfTrue(target: Label)

    override fun materialize() {
        val const0 = Label()
        val end = Label()
        jumpIfFalse(const0)
        mv.iconst(1)
        mv.goTo(end)
        mv.mark(const0)
        mv.iconst(0)
        mv.mark(end)
    }
}

// Same as materialize(), but return a representation of the result.
val PromisedValue.materialized: MaterialValue
    get() {
        materialize()
        return MaterialValue(mv, type, irType)
    }

// Materialize and disregard this value. Materialization is forced because, presumably,
// we only wanted the side effects anyway.
fun PromisedValue.discard(): MaterialValue {
    materialize()
    if (type !== Type.VOID_TYPE)
        AsmUtil.pop(mv, type)
    return MaterialValue(mv, Type.VOID_TYPE, null)
}

// On materialization, cast the value to a different type.
fun PromisedValue.coerce(target: Type, irTarget: IrType?) = when (target) {
    type -> {
        this.irType = irTarget
        this
    }
    else -> object : PromisedValue(mv, target, irTarget) {
        // TODO remove dependency
        override fun materialize() {
            val value = this@coerce.materialized
            StackValue.coerce(value.type, value.kotlinType, type, irTarget?.toKotlinType(), mv)
        }
    }
}

// Same as above, but with a return type that allows conditional jumping.
fun PromisedValue.coerceToBoolean() = when (val coerced = coerce(Type.BOOLEAN_TYPE, null)) {
    is BooleanValue -> coerced
    else -> object : BooleanValue(mv) {
        override fun jumpIfFalse(target: Label) = coerced.materialize().also { mv.ifeq(target) }
        override fun jumpIfTrue(target: Label) = coerced.materialize().also { mv.ifne(target) }
        override fun materialize() = coerced.materialize()
    }
}

val ExpressionCodegen.voidValue: MaterialValue
    get() = MaterialValue(mv, Type.VOID_TYPE, null)
