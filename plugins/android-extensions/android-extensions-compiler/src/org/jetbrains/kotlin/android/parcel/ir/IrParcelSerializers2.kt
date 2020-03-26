/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel.ir

import org.jetbrains.kotlin.backend.jvm.ir.isBoxedArray
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*

interface IrParcelSerializer2 {
    fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression
    fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression
}

fun AndroidIrBuilder.readParcelWith(serializer: IrParcelSerializer2, parcel: IrValueDeclaration): IrExpression =
    with(serializer) { readParcel(parcel) }

fun AndroidIrBuilder.writeParcelWith(serializer: IrParcelSerializer2, parcel: IrValueDeclaration, value: IrExpression): IrExpression =
    with(serializer) { writeParcel(parcel, value) }

// Creates a serializer from a pair of parcel methods of the form reader()T and writer(T)V.
class SimpleParcelSerializer(val reader: IrSimpleFunctionSymbol, val writer: IrSimpleFunctionSymbol) : IrParcelSerializer2 {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        irCall(reader).apply { dispatchReceiver = irGet(parcel) }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        irCall(writer).apply {
            dispatchReceiver = irGet(parcel)
            putValueArgument(0, value)
        }
}

class WrappedParcelSerializer(val parcelType: IrType, val serializer: IrParcelSerializer2) :
    IrParcelSerializer2 by serializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        val deserializedPrimitive = readParcelWith(serializer, parcel)
        return if (parcelType.isBoolean()) {
            irIfThenElse(
                parcelType,
                irNotEquals(deserializedPrimitive, irInt(0)),
                irInt(1),
                irInt(0)
            )
        } else {
            val conversion = deserializedPrimitive.type.getClass()!!.functions.first { function ->
                function.name.asString() == "to${parcelType.getClass()!!.name}"
            }
            irCall(conversion).apply {
                dispatchReceiver = deserializedPrimitive
            }
        }
    }
}

class NullAwareParcelSerializer2(private val serializer: IrParcelSerializer2) : IrParcelSerializer2 {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        val nonNullResult = readParcelWith(serializer, parcel)
        return irIfThenElse(
            nonNullResult.type.makeNullable(),
            irEquals(parcelReadInt(irGet(parcel)), irInt(0)),
            irNull(),
            nonNullResult
        )
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        irLetS(value) { irValueSymbol ->
            irIfNull(
                context.irBuiltIns.unitType,
                irGet(irValueSymbol.owner),
                parcelWriteInt(irGet(parcel), irInt(0)),
                irBlock {
                    +parcelWriteInt(irGet(parcel), irInt(1))
                    writeParcelWith(serializer, parcel, irGet(irValueSymbol.owner))?.let { +it }
                }
            )
        }
}

class ObjectSerializer2(val objectClass: IrClass) : IrParcelSerializer2 {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        // Avoid empty parcels
        irBlock {
            +parcelReadInt(irGet(parcel))
            +irGetObject(objectClass.symbol)
        }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        parcelWriteInt(irGet(parcel), irInt(1))
}

class NoParameterClassSerializer2(val irClass: IrClass) : IrParcelSerializer2 {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        // TODO: What if the class has no constructor? Do we generate a synthetic one already in psi2ir?
        val defaultConstructor = irClass.primaryConstructor!!
        return irBlock {
            +parcelReadInt(irGet(parcel))
            +irCall(defaultConstructor)
        }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        parcelWriteInt(irGet(parcel), irInt(1))
}

class EnumSerializer2(val enumClass: IrClass) : IrParcelSerializer2 {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        irCall(enumValueOf).apply {
            putValueArgument(0, parcelReadString(irGet(parcel)))
        }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        parcelWriteString(irGet(parcel), irCall(enumName).apply {
            dispatchReceiver = value
        })

    private val enumValueOf: IrFunctionSymbol =
        enumClass.functions.single { function ->
            function.name.asString() == "valueOf" && function.dispatchReceiverParameter == null
                    && function.extensionReceiverParameter == null && function.valueParameters.size == 1
                    && function.valueParameters.single().type.isString()
        }.symbol

    private val enumName: IrFunctionSymbol =
        enumClass.getPropertyGetter("name")!!
}

class CharSequenceSerializer2 : IrParcelSerializer2 {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        // TODO: create an accessor in AndroidIrBuilder
        val reader = androidSymbols.androidOsParcelableCreator.getSimpleFunction("createFromParcel")!!
        return irCall(reader).apply {
            dispatchReceiver = getTextUtilsCharSequenceCreator()
            putValueArgument(0, irGet(parcel))
        }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        textUtilsWriteToParcel(value, irGet(parcel), irInt(0))
}

class GenericSerializer2(val parcelType: IrType) : IrParcelSerializer2 {
    // TODO: Move to irUtils
    private fun IrBuilderWithScope.kClassReference(classType: IrType) =
        IrClassReferenceImpl(
            startOffset, endOffset, context.irBuiltIns.kClassClass.starProjectedType, context.irBuiltIns.kClassClass, classType
        )

    private fun AndroidIrBuilder.kClassToJavaClass(kClassReference: IrExpression) =
        irGet(androidSymbols.javaLangClass.starProjectedType, null, androidSymbols.kotlinKClassJava.owner.getter!!.symbol).apply {
            extensionReceiver = kClassReference
        }

    private fun AndroidIrBuilder.javaClassReference(classType: IrType) =
        kClassToJavaClass(kClassReference(classType))

    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        parcelReadValue(irGet(parcel), classGetClassLoader(javaClassReference(parcelType)))

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        parcelWriteValue(irGet(parcel), value)
}

// TODO: Unsigned array types
class ArraySerializer2(val arrayType: IrType, val elementType: IrType, val elementSerializer: IrParcelSerializer2) : IrParcelSerializer2 {
    // TODO: Avoid code duplication with IrArrayBuilder
    private fun AndroidIrBuilder.newArray(size: IrExpression): IrExpression {
        val arrayConstructor: IrFunctionSymbol = if (arrayType.isBoxedArray)
            androidSymbols.irSymbols.arrayOfNulls
        else
            arrayType.classOrNull!!.constructors.single { it.owner.valueParameters.size == 1 }

        return irCall(arrayConstructor, arrayType).apply {
            if (typeArgumentsCount != 0)
                putTypeArgument(0, elementType)
            putValueArgument(0, size)
        }
    }

    // TODO Move to irUtils?
    private inline fun IrBlockBuilder.forUntil(upperBound: IrExpression, loopBody: IrBlockBuilder.(IrValueDeclaration) -> Unit) {
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

    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        irBlock {
            val arraySize = irTemporary(parcelReadInt(irGet(parcel)))
            val arrayTemporary = irTemporary(newArray(irGet(arraySize)))
            forUntil(irGet(arraySize)) { index ->
                val setter = arrayType.classOrNull!!.getSimpleFunction("set")!!
                +irCall(setter).apply {
                    dispatchReceiver = irGet(arrayTemporary)
                    putValueArgument(0, irGet(index))
                    putValueArgument(1, readParcelWith(elementSerializer, parcel))
                }
            }
            +irGet(arrayTemporary)
        }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        irBlock {
            val arrayTemporary = irTemporary(value)
            val arraySizeSymbol = arrayType.classOrNull!!.getPropertyGetter("size")!!
            val arraySize = irTemporary(irCall(arraySizeSymbol).apply {
                dispatchReceiver = irGet(arrayTemporary)
            })

            +parcelWriteInt(irGet(parcel), irGet(arraySize))

            forUntil(irGet(arraySize)) { index ->
                val getter = context.irBuiltIns.arrayClass.getSimpleFunction("get")!!
                val element = irCall(getter).apply {
                    dispatchReceiver = irGet(arrayTemporary)
                    putValueArgument(0, irGet(index))
                }
                +writeParcelWith(elementSerializer, parcel, element)
            }
        }
}

class SparseArraySerializer2(val arrayType: IrType, val elementType: IrType, val elementSerializer: IrParcelSerializer2) : IrParcelSerializer2 {
    // TODO Move to irUtils? DUPLICATE!
    private inline fun IrBlockBuilder.forUntil(upperBound: IrExpression, loopBody: IrBlockBuilder.(IrValueDeclaration) -> Unit) {
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

    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        irBlock {
            val remainingSizeTemporary = irTemporaryVar(parcelReadInt(irGet(parcel)))

            val sparseArrayClass = arrayType.classOrNull!!.owner
            val sparseArrayConstructor = sparseArrayClass.constructors.first { irConstructor ->
                irConstructor.valueParameters.size == 1 && irConstructor.valueParameters.single().type.isInt()
            }

            val constructorCall = if (sparseArrayClass.typeParameters.isEmpty())
                irCall(sparseArrayConstructor)
            else
                irCallConstructor(sparseArrayConstructor.symbol, listOf(elementType))

            val arrayTemporary = irTemporary(constructorCall.apply {
                putValueArgument(0, irGet(remainingSizeTemporary))
            })

            +irWhile().apply {
                condition = irNotEquals(irGet(remainingSizeTemporary), irInt(0))
                body = irBlock {
                    val sparseArrayPut = sparseArrayClass.functions.first { function ->
                        function.name.asString() == "put" && function.valueParameters.size == 2
                    }
                    +irCall(sparseArrayPut).apply {
                        dispatchReceiver = irGet(arrayTemporary)
                        putValueArgument(0, parcelReadInt(irGet(parcel)))
                        putValueArgument(1, readParcelWith(elementSerializer, parcel))
                    }

                    val dec = context.irBuiltIns.intClass.getSimpleFunction("dec")!!
                    +irSetVar(remainingSizeTemporary.symbol, irCall(dec).apply {
                        dispatchReceiver = irGet(remainingSizeTemporary)
                    })
                }
            }

            +irGet(arrayTemporary)
        }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        irBlock {
            val sparseArrayClass = arrayType.classOrNull!!.owner
            val sizeFunction = sparseArrayClass.functions.first { function ->
                function.name.asString() == "size" && function.valueParameters.isEmpty()
            }
            val keyAtFunction = sparseArrayClass.functions.first { function ->
                function.name.asString() == "keyAt" && function.valueParameters.size == 1
            }
            val valueAtFunction = sparseArrayClass.functions.first { function ->
                function.name.asString() == "valueAt" && function.valueParameters.size == 1
            }

            val arrayTemporary = irTemporary(value)
            val sizeTemporary = irTemporary(irCall(sizeFunction).apply {
                dispatchReceiver = irGet(arrayTemporary)
            })

            +parcelWriteInt(irGet(parcel), irGet(sizeTemporary))

            forUntil(irGet(sizeTemporary)) { index ->
                +parcelWriteInt(irGet(parcel), irCall(keyAtFunction).apply {
                    dispatchReceiver = irGet(arrayTemporary)
                    putValueArgument(0, irGet(index))
                })

                +writeParcelWith(elementSerializer, parcel, irCall(valueAtFunction).apply {
                    dispatchReceiver = irGet(arrayTemporary)
                    putValueArgument(0, irGet(index))
                })
            }
        }
}
