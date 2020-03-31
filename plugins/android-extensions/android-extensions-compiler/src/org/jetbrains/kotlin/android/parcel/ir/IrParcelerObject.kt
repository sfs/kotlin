/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel.ir

import org.jetbrains.kotlin.android.parcel.PARCELER_FQNAME
import org.jetbrains.kotlin.backend.common.lower.allOverridden
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.name.FqName

class IrParcelerObject(val objectClass: IrClass) {
    // fun T.write(parcel: Parcel, flags: Int)
    val writeFunctionSymbol: IrSimpleFunctionSymbol = parcelerSymbolByName("write")!!

    // fun create(parcel: Parcel): T
    val createFunctionSymbol: IrSimpleFunctionSymbol = parcelerSymbolByName("create")!!

    // fun newArray(size: Int): Array<T>
    val newArraySymbol: IrSimpleFunctionSymbol? = parcelerSymbolByName("newArray")

    // Find a named function declaration which overrides the corresponding function in [Parceler].
    // This is more reliable than trying to match the functions signature ourselves, since the frontend
    // has already done the work.
    private fun parcelerSymbolByName(name: String): IrSimpleFunctionSymbol? =
        objectClass.functions.firstOrNull { function ->
            !function.isFakeOverride && function.name.asString() == name && function.allOverridden().any {
                it.parentClassOrNull?.fqNameWhenAvailable == PARCELER_FQNAME
            }
        }?.symbol
}

// Custom parcelers are resolved in *reverse* lexical scope order (why??)
class ParcelerScope(val parent: ParcelerScope? = null) {
    private val typeParcelers = mutableMapOf<IrType, IrParcelerObject>()

    fun add(type: IrType, parceler: IrParcelerObject) {
        typeParcelers.putIfAbsent(type, parceler)
    }

    fun get(type: IrType): IrParcelerObject? =
        parent?.get(type) ?: typeParcelers[type]
}

fun IrBuilderWithScope.parcelerWrite(
    parceler: IrParcelerObject,
    parcel: IrValueDeclaration,
    flags: IrValueDeclaration,
    value: IrExpression
): IrExpression = irCall(parceler.writeFunctionSymbol).apply {
    dispatchReceiver = irGetObject(parceler.objectClass.symbol)
    extensionReceiver = value
    putValueArgument(0, irGet(parcel))
    putValueArgument(1, irGet(flags))
}

fun IrBuilderWithScope.parcelerCreate(parceler: IrParcelerObject, parcel: IrValueDeclaration): IrExpression =
    irCall(parceler.createFunctionSymbol).apply {
        dispatchReceiver = irGetObject(parceler.objectClass.symbol)
        putValueArgument(0, irGet(parcel))
    }

fun IrBuilderWithScope.parcelerNewArray(parceler: IrParcelerObject?, size: IrValueDeclaration): IrExpression? =
    parceler?.newArraySymbol?.let { newArraySymbol ->
        irCall(newArraySymbol).apply {
            dispatchReceiver = irGetObject(parceler.objectClass.symbol)
            putValueArgument(0, irGet(size))
        }
    }
