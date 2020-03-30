/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel.ir

import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.findDeclaration
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.types.Variance

private fun IrBuilderWithScope.kClassReference(classType: IrType) =
    IrClassReferenceImpl(
        startOffset, endOffset, context.irBuiltIns.kClassClass.starProjectedType, context.irBuiltIns.kClassClass, classType
    )

private fun AndroidIrBuilder.kClassToJavaClass(kClassReference: IrExpression) =
    irGet(androidSymbols.javaLangClass.starProjectedType, null, androidSymbols.kotlinKClassJava.owner.getter!!.symbol).apply {
        extensionReceiver = kClassReference
    }

fun AndroidIrBuilder.javaClassReference(classType: IrType) =
    kClassToJavaClass(kClassReference(classType))

fun IrClass.isSubclassOfFqName(fqName: String): Boolean =
    fqNameWhenAvailable?.asString() == fqName || superTypes.any { it.erasedUpperBound.isSubclassOfFqName(fqName) }

inline fun IrBlockBuilder.forUntil(upperBound: IrExpression, loopBody: IrBlockBuilder.(IrValueDeclaration) -> Unit) {
    val indexTemporary = irTemporaryVar(irInt(0))
    +irWhile().apply {
        condition = irNotEquals(irGet(indexTemporary), upperBound)
        body = irBlock {
            loopBody(indexTemporary)
            val inc = context.irBuiltIns.intClass.getSimpleFunction("inc")!!
            +irSetVar(indexTemporary.symbol, irCall(inc).apply {
                dispatchReceiver = irGet(indexTemporary)
            })
        }
    }
}

fun IrTypeArgument.upperBound(builtIns: IrBuiltIns): IrType =
    when (this) {
        is IrStarProjection -> builtIns.anyNType
        is IrTypeProjection -> {
            if (variance == Variance.OUT_VARIANCE || variance == Variance.INVARIANT)
                type
            else
                builtIns.anyNType
        }
        else -> error("Unknown type argument: ${render()}")
    }

private fun IrClass.getSimpleFunction(name: String): IrSimpleFunctionSymbol? =
    findDeclaration<IrSimpleFunction> { it.name.asString() == name }?.symbol

// This is a version of getPropertyGetter which does not throw when applied to broken lazy classes, such as java.util.HashMap,
// which contains two "size" properties with different visibilities.
fun IrClass.getPropertyGetter(name: String): IrSimpleFunctionSymbol? =
    declarations.filterIsInstance<IrProperty>().firstOrNull { it.name.asString() == name && it.getter != null }?.getter?.symbol
        ?: getSimpleFunction("<get-$name>")
