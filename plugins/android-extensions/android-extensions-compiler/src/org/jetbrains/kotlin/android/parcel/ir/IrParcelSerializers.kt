/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel.ir

import org.jetbrains.kotlin.backend.jvm.codegen.isJvmInterface
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.backend.jvm.ir.isBoxedArray
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*

interface IrParcelSerializer {
    fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression
    fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression
}

fun AndroidIrBuilder.readParcelWith(serializer: IrParcelSerializer, parcel: IrValueDeclaration): IrExpression =
    with(serializer) { readParcel(parcel) }

fun AndroidIrBuilder.writeParcelWith(
    serializer: IrParcelSerializer,
    parcel: IrValueDeclaration,
    flags: IrValueDeclaration,
    value: IrExpression
): IrExpression =
    with(serializer) { writeParcel(parcel, flags, value) }

// Creates a serializer from a pair of parcel methods of the form reader()T and writer(T)V.
class SimpleParcelSerializer(val reader: IrSimpleFunctionSymbol, val writer: IrSimpleFunctionSymbol) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        irCall(reader).apply { dispatchReceiver = irGet(parcel) }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
        irCall(writer).apply {
            dispatchReceiver = irGet(parcel)
            putValueArgument(0, value)
        }
}

class WrappedParcelSerializer(val parcelType: IrType, val serializer: IrParcelSerializer) : IrParcelSerializer {
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

    private fun IrBuilderWithScope.irCastIfNeeded(expression: IrExpression, irType: IrType): IrExpression =
        if (expression.type == irType) expression else irAs(expression, irType)

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
        writeParcelWith(serializer, parcel, flags, irCastIfNeeded(value, parcelType))
}

class NullAwareParcelSerializer(private val serializer: IrParcelSerializer) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        val nonNullResult = readParcelWith(serializer, parcel)
        return irIfThenElse(
            nonNullResult.type.makeNullable(),
            irEquals(parcelReadInt(irGet(parcel)), irInt(0)),
            irNull(),
            nonNullResult
        )
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
        irLetS(value) { irValueSymbol ->
            irIfNull(
                context.irBuiltIns.unitType,
                irGet(irValueSymbol.owner),
                parcelWriteInt(irGet(parcel), irInt(0)),
                irBlock {
                    +parcelWriteInt(irGet(parcel), irInt(1))
                    +writeParcelWith(serializer, parcel, flags, irGet(irValueSymbol.owner))
                }
            )
        }
}

class ObjectSerializer(val objectClass: IrClass) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        // Avoid empty parcels
        irBlock {
            +parcelReadInt(irGet(parcel))
            +irGetObject(objectClass.symbol)
        }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
        parcelWriteInt(irGet(parcel), irInt(1))
}

class NoParameterClassSerializer(val irClass: IrClass) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        val defaultConstructor = irClass.primaryConstructor!!
        return irBlock {
            +parcelReadInt(irGet(parcel))
            +irCall(defaultConstructor)
        }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
        parcelWriteInt(irGet(parcel), irInt(1))
}

class EnumSerializer(val enumClass: IrClass) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        irCall(enumValueOf).apply {
            putValueArgument(0, parcelReadString(irGet(parcel)))
        }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
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

class CharSequenceSerializer : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        // TODO: create an accessor in AndroidIrBuilder
        val reader = androidSymbols.androidOsParcelableCreator.getSimpleFunction("createFromParcel")!!
        return irCall(reader).apply {
            dispatchReceiver = getTextUtilsCharSequenceCreator()
            putValueArgument(0, irGet(parcel))
        }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
        textUtilsWriteToParcel(value, irGet(parcel), irGet(flags))
}

class EfficientParcelableSerializer(val irClass: IrClass, val creatorField: IrField?, val creatorGetter: IrSimpleFunctionSymbol?) :
    IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        val creator: IrExpression = if (creatorField != null) {
            irGetField(null, creatorField)
        } else {
            irCall(creatorGetter!!).apply {
                dispatchReceiver = irGetObject((irClass.companionObject()!! as IrClass).symbol)
            }
        }

        // TODO: Accessor in AndroidSymbols
        return irCall(androidSymbols.androidOsParcelableCreator.getSimpleFunction("createFromParcel")!!).apply {
            dispatchReceiver = creator
            putValueArgument(0, irGet(parcel))
        }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        // TODO: Accessor in AndroidSymbols!
        val writeToParcel = irClass.functions.first { function ->
            function.name.asString() == "writeToParcel" && function.dispatchReceiverParameter != null
                    && function.extensionReceiverParameter == null && function.valueParameters.size == 2
                    && function.valueParameters[1].type.isInt()
                    && function.valueParameters[0].type.erasedUpperBound.fqNameWhenAvailable?.asString() == "android.os.Parcel"
        }
        return irCall(writeToParcel).apply {
            dispatchReceiver = value
            putValueArgument(0, irGet(parcel))
            putValueArgument(1, irGet(flags))
        }
    }
}

// This needs a reference to the parcelize type itself in order to find the correct class loader to use, see KT-20027.
class GenericParcelableSerializer(private val parcelizeType: IrType) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        parcelReadParcelable(irGet(parcel), classGetClassLoader(javaClassReference(parcelizeType)))

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
        parcelWriteParcelable(irGet(parcel), value, irGet(flags))
}

class GenericSerializer(private val parcelizeType: IrType) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        parcelReadValue(irGet(parcel), classGetClassLoader(javaClassReference(parcelizeType)))

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
        parcelWriteValue(irGet(parcel), value)
}

class CustomParcelSerializer(private val parcelerObject: IrParcelerObject) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        parcelerCreate(parcelerObject, parcel)

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
        parcelerWrite(parcelerObject, parcel, flags, value)
}

// TODO: Unsigned array types
class ArraySerializer(val arrayType: IrType, val elementType: IrType, val elementSerializer: IrParcelSerializer) : IrParcelSerializer {
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

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
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
                +writeParcelWith(elementSerializer, parcel, flags, element)
            }
        }
}

class SparseArraySerializer(val arrayType: IrType, val elementType: IrType, val elementSerializer: IrParcelSerializer) :
    IrParcelSerializer {
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

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
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

                +writeParcelWith(elementSerializer, parcel, flags, irCall(valueAtFunction).apply {
                    dispatchReceiver = irGet(arrayTemporary)
                    putValueArgument(0, irGet(index))
                })
            }
        }
}

// TODO: Use code duplication with MapParceler
class ListParceler(val irClass: IrClass, val elementSerializer: IrParcelSerializer) : IrParcelSerializer {
    private fun IrClass.getNullaryFunction(name: String): IrSimpleFunction =
        functions.first { function ->
            function.name.asString() == name && function.dispatchReceiverParameter != null
                    && function.extensionReceiverParameter == null && function.valueParameters.isEmpty()
        }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        val sizeFunction = irClass.getPropertyGetter("size")!!
        val iteratorFunction = irClass.getNullaryFunction("iterator")
        val iteratorClass = iteratorFunction.returnType.erasedUpperBound
        val iteratorHasNext = iteratorClass.getNullaryFunction("hasNext")
        val iteratorNext = iteratorClass.getNullaryFunction("next")

        return irBlock {
            val list = irTemporary(value)
            +parcelWriteInt(irGet(parcel), irCall(sizeFunction).apply {
                dispatchReceiver = irGet(list)
            })
            val iterator = irTemporary(irCall(iteratorFunction).apply {
                dispatchReceiver = irGet(list)
            })
            +irWhile().apply {
                condition = irCall(iteratorHasNext).apply { dispatchReceiver = irGet(iterator) }
                body = writeParcelWith(elementSerializer, parcel, flags, irCall(iteratorNext).apply {
                    dispatchReceiver = irGet(iterator)
                })
            }
        }
    }

    private fun listSymbols(symbols: AndroidSymbols): Pair<IrConstructorSymbol, IrSimpleFunctionSymbol> {
        // If the IrClass refers to a concrete type, try to find a constructor with capactiy or fall back
        // the the default constructor if none exist.
        if (!irClass.isJvmInterface) {
            val constructor = irClass.constructors.find { constructor ->
                constructor.valueParameters.size == 1 && constructor.valueParameters.single().type.isInt()
            } ?: irClass.constructors.first { constructor -> constructor.valueParameters.isEmpty() }

            val add = irClass.functions.first { function ->
                function.name.asString() == "add" && function.valueParameters.size == 1
            }

            return constructor.symbol to add.symbol
        }

        return when (irClass.fqNameWhenAvailable?.asString()) {
            "kotlin.collections.MutableList", "kotlin.collections.List", "java.util.List" ->
                symbols.arrayListConstructor to symbols.arrayListAdd
            "kotlin.collections.MutableSet", "kotlin.collections.Set", "java.util.Set" ->
                symbols.linkedHashSetConstructor to symbols.linkedHashSetAdd
            "java.util.NavigableSet", "java.util.SortedSet" ->
                symbols.treeSetConstructor to symbols.treeSetAdd
            else -> error("Unknown list interface type: ${irClass.render()}")
        }
    }

    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        irBlock {
            val (constructorSymbol, addSymbol) = listSymbols(androidSymbols)
            val sizeTemporary = irTemporary(parcelReadInt(irGet(parcel)))
            val list = irTemporary(irCall(constructorSymbol).apply {
                if (constructorSymbol.owner.valueParameters.isNotEmpty())
                    putValueArgument(0, irGet(sizeTemporary))
            })
            forUntil(irGet(sizeTemporary)) {
                +irCall(addSymbol).apply {
                    dispatchReceiver = irGet(list)
                    putValueArgument(0, readParcelWith(elementSerializer, parcel))
                }
            }
            +irGet(list)
        }
}

class MapParceler(val irClass: IrClass, val keySerializer: IrParcelSerializer, val valueSerializer: IrParcelSerializer) :
    IrParcelSerializer {
    private fun IrClass.getNullaryFunction(name: String): IrSimpleFunction =
        functions.first { function ->
            function.name.asString() == name && function.dispatchReceiverParameter != null
                    && function.extensionReceiverParameter == null && function.valueParameters.isEmpty()
        }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        val sizeFunction = irClass.getPropertyGetter("size")!!
        val entriesFunction = irClass.getPropertyGetter("entries")!!
        val entrySetClass = entriesFunction.owner.returnType.erasedUpperBound
        val iteratorFunction = entrySetClass.getNullaryFunction("iterator")
        val iteratorClass = iteratorFunction.returnType.erasedUpperBound
        val iteratorHasNext = iteratorClass.getNullaryFunction("hasNext")
        val iteratorNext = iteratorClass.getNullaryFunction("next")
        val elementClass =
            (entriesFunction.owner.returnType as IrSimpleType).arguments.single().upperBound(context.irBuiltIns).erasedUpperBound
        val elementKey = elementClass.getPropertyGetter("key")!!
        val elementValue = elementClass.getPropertyGetter("value")!!

        return irBlock {
            val list = irTemporary(value)
            +parcelWriteInt(irGet(parcel), irCall(sizeFunction).apply {
                dispatchReceiver = irGet(list)
            })
            val iterator = irTemporary(irCall(iteratorFunction).apply {
                dispatchReceiver = irCall(entriesFunction).apply {
                    dispatchReceiver = irGet(list)
                }
            })
            +irWhile().apply {
                condition = irCall(iteratorHasNext).apply { dispatchReceiver = irGet(iterator) }
                body = irBlock {
                    val element = irTemporary(irCall(iteratorNext).apply {
                        dispatchReceiver = irGet(iterator)
                    })
                    +writeParcelWith(keySerializer, parcel, flags, irCall(elementKey).apply {
                        dispatchReceiver = irGet(element)
                    })
                    +writeParcelWith(valueSerializer, parcel, flags, irCall(elementValue).apply {
                        dispatchReceiver = irGet(element)
                    })
                }
            }
        }
    }

    private fun mapSymbols(symbols: AndroidSymbols): Pair<IrConstructorSymbol, IrSimpleFunctionSymbol> {
        // If the IrClass refers to a concrete type, try to find a constructor with capactiy or fall back
        // the the default constructor if none exist.
        if (!irClass.isJvmInterface) {
            val constructor = irClass.constructors.find { constructor ->
                constructor.valueParameters.size == 1 && constructor.valueParameters.single().type.isInt()
            } ?: irClass.constructors.find { constructor ->
                constructor.valueParameters.isEmpty()
            }!!

            val put = irClass.functions.first { function ->
                function.name.asString() == "put" && function.valueParameters.size == 2
            }

            return constructor.symbol to put.symbol
        }

        return when (irClass.fqNameWhenAvailable?.asString()) {
            "kotlin.collections.MutableMap", "kotlin.collections.Map", "java.util.Map" ->
                symbols.linkedHashMapConstructor to symbols.linkedHashMapPut
            "java.util.SortedMap", "java.util.NavigableMap" ->
                symbols.treeMapConstructor to symbols.treeMapPut
            else -> error("Unknown map interface type: ${irClass.render()}")
        }
    }

    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        irBlock {
            val (constructorSymbol, putSymbol) = mapSymbols(androidSymbols)
            val sizeTemporary = irTemporary(parcelReadInt(irGet(parcel)))
            val map = irTemporary(irCall(constructorSymbol).apply {
                if (constructorSymbol.owner.valueParameters.isNotEmpty())
                    putValueArgument(0, irGet(sizeTemporary))
            })
            forUntil(irGet(sizeTemporary)) {
                +irCall(putSymbol).apply {
                    dispatchReceiver = irGet(map)
                    putValueArgument(0, readParcelWith(keySerializer, parcel))
                    putValueArgument(1, readParcelWith(valueSerializer, parcel))
                }
            }
            +irGet(map)
        }
}
