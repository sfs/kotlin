/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.codegen

import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.backend.jvm.lower.inlineclasses.InlineClassAbi
import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.codegen.StackValue
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.toKotlinType
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter

// A value that may not have been fully constructed yet. The ability to "roll back" code generation
// is useful for certain optimizations.
abstract class PromisedValue(val codegen: ExpressionCodegen, val type: Type, var irType: IrType?) {
    // If this value is immaterial, construct an object on the top of the stack. This
    // must always be done before generating other values or emitting raw bytecode.
    abstract fun materialize()

    val mv: InstructionAdapter
        get() = codegen.mv

    val typeMapper: IrTypeMapper
        get() = codegen.typeMapper

    val kotlinType: KotlinType?
        get() = irType?.toKotlinType()
}

// A value that *has* been fully constructed.
class MaterialValue(codegen: ExpressionCodegen, type: Type, irType: IrType?) : PromisedValue(codegen, type, irType) {
    override fun materialize() {}
}

// A value that can be branched on. JVM has certain branching instructions which can be used
// to optimize these.
abstract class BooleanValue(codegen: ExpressionCodegen) : PromisedValue(codegen, Type.BOOLEAN_TYPE, null) {
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
        return MaterialValue(codegen, type, irType)
    }

// Materialize and disregard this value. Materialization is forced because, presumably,
// we only wanted the side effects anyway.
fun PromisedValue.discard(): MaterialValue {
    materialize()
    if (type !== Type.VOID_TYPE)
        AsmUtil.pop(mv, type)
    return MaterialValue(codegen, Type.VOID_TYPE, null)
}

private val IrType.unboxed: IrType
    get() = InlineClassAbi.getUnderlyingType(erasedUpperBound)

fun PromisedValue.coerceInlineClasses(type: Type, irType: IrType, target: Type, irTarget: IrType): PromisedValue? {
    val isFromTypeInlineClass = irType.erasedUpperBound.isInline
    val isToTypeInlineClass = irTarget.erasedUpperBound.isInline
    if (!isFromTypeInlineClass && !isToTypeInlineClass) return null

    val isFromTypeUnboxed = isFromTypeInlineClass && typeMapper.mapType(irType.unboxed) == type
    val isToTypeUnboxed = isToTypeInlineClass && typeMapper.mapType(irTarget.unboxed) == target

    return when {
        isFromTypeUnboxed && !isToTypeUnboxed -> object : PromisedValue(codegen, target, irTarget) {
            override fun materialize() {
                this@coerceInlineClasses.materialize()
                // TODO: This is broken for type parameters
                StackValue.boxInlineClass(irType.toKotlinType(), mv)
            }
        }
        !isFromTypeUnboxed && isToTypeUnboxed -> object : PromisedValue(codegen, target, irTarget) {
            override fun materialize() {
                val value = this@coerceInlineClasses.materialized
                StackValue.unboxInlineClass(value.type, irTarget.toKotlinType(), mv)
            }
        }
        else -> null
    }
}

// On materialization, cast the value to a different type.
fun PromisedValue.coerce(target: Type, irTarget: IrType?): PromisedValue {
    val irType = irType
    if (irType != null && irTarget != null)
        coerceInlineClasses(type, irType, target, irTarget)?.let { return it }
    return when {
        target == type -> {
            this.irType = irTarget
            this
        }
        else -> object : PromisedValue(codegen, target, irTarget) {
            override fun materialize() {
                val value = this@coerce.materialized
                StackValue.coerce(value.type, type, mv)
            }
        }
    }
}

fun PromisedValue.coerceToBoxed(irTarget: IrType) =
    coerce(typeMapper.boxType(irTarget), irTarget)

// Same as above, but with a return type that allows conditional jumping.
fun PromisedValue.coerceToBoolean() = when (val coerced = coerce(Type.BOOLEAN_TYPE, null)) {
    is BooleanValue -> coerced
    else -> object : BooleanValue(codegen) {
        override fun jumpIfFalse(target: Label) = coerced.materialize().also { mv.ifeq(target) }
        override fun jumpIfTrue(target: Label) = coerced.materialize().also { mv.ifne(target) }
        override fun materialize() = coerced.materialize()
    }
}

val ExpressionCodegen.voidValue: MaterialValue
    get() = MaterialValue(this, Type.VOID_TYPE, null)
