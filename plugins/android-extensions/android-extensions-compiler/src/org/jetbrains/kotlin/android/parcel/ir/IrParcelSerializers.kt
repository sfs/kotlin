/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel.ir

import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getPropertyGetter
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.primaryConstructor

interface IrParcelSerializer {
    val parcelType: IrType
    fun IrBuilderWithScope.readParcel(parcel: IrValueDeclaration): IrExpression
    fun IrBuilderWithScope.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression
}

class BoxedPrimitiveParcelSerializer(override val parcelType: IrType, val serializer: PrimitiveParcelSerializer) :
    IrParcelSerializer by serializer {
    override fun IrBuilderWithScope.readParcel(parcel: IrValueDeclaration): IrExpression {
        val deserializedPrimitive = with(serializer) { readParcel(parcel) }
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

class PrimitiveParcelSerializer(
    val reader: IrFunctionSymbol,
    val writer: IrFunctionSymbol,
    override val parcelType: IrType = reader.owner.returnType
) : IrParcelSerializer {
    private fun IrBuilderWithScope.castIfNeeded(expression: IrExpression, type: IrType): IrExpression =
        if (expression.type != type) irImplicitCast(expression, type) else expression

    override fun IrBuilderWithScope.readParcel(parcel: IrValueDeclaration): IrExpression =
        castIfNeeded(irCall(reader).apply {
            dispatchReceiver = irGet(parcel)
        }, parcelType)

    override fun IrBuilderWithScope.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        irCall(writer).apply {
            dispatchReceiver = irGet(parcel)
            putValueArgument(0, castIfNeeded(value, writer.owner.valueParameters.single().type))
        }
}

class NullAwareParcelSerializer(
    override val parcelType: IrType,
    val serializer: IrParcelSerializer,
    val symbols: AndroidSymbols,
    val irBuiltIns: IrBuiltIns
) : IrParcelSerializer {
    override fun IrBuilderWithScope.readParcel(parcel: IrValueDeclaration): IrExpression =
        irIfThenElse(
            parcelType,
            irEquals(irCall(symbols.parcelReadInt).apply {
                dispatchReceiver = irGet(parcel)
            }, irInt(0)),
            irNull(),
            with(serializer) { readParcel(parcel) }
        )

    override fun IrBuilderWithScope.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        irLetS(value) { irValueSymbol ->
            irIfNull(
                irBuiltIns.unitType,
                irGet(irValueSymbol.owner),
                irCall(symbols.parcelWriteInt).apply {
                    dispatchReceiver = irGet(parcel)
                    putValueArgument(0, irInt(0))
                },
                irBlock {
                    +irCall(symbols.parcelWriteInt).apply {
                        dispatchReceiver = irGet(parcel)
                        putValueArgument(0, irInt(1))
                    }
                    +with(serializer) { writeParcel(parcel, irGet(irValueSymbol.owner)) }
                }
            )
        }
}

class ObjectSerializer(override val parcelType: IrType) : IrParcelSerializer {
    override fun IrBuilderWithScope.readParcel(parcel: IrValueDeclaration): IrExpression {
        return irGetObject(parcelType.classOrNull!!)
    }

    override fun IrBuilderWithScope.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression {
        return irNull() // TODO: Don't generate an expression here...
    }
}

class EnumSerializer(override val parcelType: IrType, val stringSerializer: PrimitiveParcelSerializer) : IrParcelSerializer {
    override fun IrBuilderWithScope.readParcel(parcel: IrValueDeclaration): IrExpression {
        // TODO More precise matching for valueOf function
        val enumValueOfFunction = parcelType.classOrNull!!.getSimpleFunction("valueOf")!!
        return irCall(enumValueOfFunction).apply {
            putValueArgument(0, with(stringSerializer) {
                readParcel(parcel)
            })
        }
    }

    override fun IrBuilderWithScope.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression {
        val nameProperty = parcelType.getClass()!!.getPropertyGetter("name")!!
        return with(stringSerializer) {
            writeParcel(parcel, irCall(nameProperty).apply {
                dispatchReceiver = value
            })
        }
    }
}

class NoParameterClassSerializer(override val parcelType: IrType) : IrParcelSerializer {
    override fun IrBuilderWithScope.readParcel(parcel: IrValueDeclaration): IrExpression {
        val defaultConstructor = parcelType.getClass()!!.primaryConstructor!!
        return irCall(defaultConstructor)
    }

    override fun IrBuilderWithScope.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression {
        return irNull() // TODO: Don't generate an expression here...
    }
}

class CharSequenceSerializer(override val parcelType: IrType, private val symbols: AndroidSymbols): IrParcelSerializer {
    override fun IrBuilderWithScope.readParcel(parcel: IrValueDeclaration): IrExpression {
        val creator = irGetField(null, symbols.parcelCharSequenceCreator.owner)
        val reader = symbols.parcelableCreator.getSimpleFunction("createFromParcel")!!
        return irCall(reader).apply {
            dispatchReceiver = creator
            putValueArgument(0, irGet(parcel))
        }
    }

    override fun IrBuilderWithScope.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        irCall(symbols.parcelWriteCharSequence).apply {
            putValueArgument(0, value)
            putValueArgument(1, irGet(parcel))
            putValueArgument(2, irInt(0))
        }
}

class GenericSerializer(override val parcelType: IrType, val symbols: AndroidSymbols) : IrParcelSerializer {
    private fun IrBuilderWithScope.kClassReference(classType: IrType) =
        IrClassReferenceImpl(
            startOffset, endOffset, context.irBuiltIns.kClassClass.starProjectedType, context.irBuiltIns.kClassClass, classType
        )

    private fun IrBuilderWithScope.kClassToJavaClass(kClassReference: IrExpression) =
        irGet(symbols.javaLangClass.starProjectedType, null, symbols.kClassJava.owner.getter!!.symbol).apply {
            extensionReceiver = kClassReference
        }

    private fun IrBuilderWithScope.javaClassReference(classType: IrType) =
        kClassToJavaClass(kClassReference(classType))

    override fun IrBuilderWithScope.readParcel(parcel: IrValueDeclaration): IrExpression =
        irCall(symbols.parcelReadValue).apply {
            dispatchReceiver = irGet(parcel)
            putValueArgument(0, irCall(symbols.classGetClassLoader).apply {
                dispatchReceiver = javaClassReference(parcelType)
            })
        }

    override fun IrBuilderWithScope.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression =
        irCall(symbols.parcelWriteValue).apply {
            dispatchReceiver = irGet(parcel)
            putValueArgument(0, value)
        }
}
