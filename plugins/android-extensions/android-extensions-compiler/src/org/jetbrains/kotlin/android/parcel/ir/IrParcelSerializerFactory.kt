/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel.ir

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.Variance

class IrParcelSerializerFactory(private val context: CommonBackendContext, private val symbols: AndroidSymbols) {
    private val builtIns: IrBuiltIns = context.irBuiltIns

    private data class Info(val fqName: String, val serializer: IrParcelSerializer, val nullable: Boolean? = null)

    private val stringSerializer = PrimitiveParcelSerializer(symbols.parcelReadString, symbols.parcelWriteString)
    private val byteSerializer = PrimitiveParcelSerializer(symbols.parcelReadByte, symbols.parcelWriteByte)
    private val intSerializer = PrimitiveParcelSerializer(symbols.parcelReadInt, symbols.parcelWriteInt)
    private val longSerializer = PrimitiveParcelSerializer(symbols.parcelReadLong, symbols.parcelWriteLong)
    private val floatSerializer = PrimitiveParcelSerializer(symbols.parcelReadFloat, symbols.parceWriteFloat)
    private val doubleSerializer = PrimitiveParcelSerializer(symbols.parcelReadDouble, symbols.parcelWriteDouble)

    private val booleanSerializer = BoxedPrimitiveParcelSerializer(builtIns.booleanType, intSerializer)
    private val shortSerializer = BoxedPrimitiveParcelSerializer(builtIns.shortType, intSerializer)
    private val charSerializer = BoxedPrimitiveParcelSerializer(builtIns.charType, intSerializer)

    private val charSequenceSerializer = CharSequenceSerializer(context.ir.symbols.charSequence.defaultType, symbols)

    private val intArraySerializer = PrimitiveParcelSerializer(
        symbols.parcelReadIntArray, symbols.parcelWriteIntArray, builtIns.primitiveArrayForType.getValue(builtIns.intType).owner.defaultType
    )

    private val booleanArraySerializer = PrimitiveParcelSerializer(
        symbols.parcelReadBooleanArray,
        symbols.parcelWriteBooleanArray,
        builtIns.primitiveArrayForType.getValue(builtIns.booleanType).owner.defaultType
    )

    private val byteArraySerializer = PrimitiveParcelSerializer(
        symbols.parcelReadByteArray,
        symbols.parcelWriteByteArray,
        builtIns.primitiveArrayForType.getValue(builtIns.byteType).owner.defaultType
    )

    private val charArraySerializer = PrimitiveParcelSerializer(
        symbols.parcelReadCharArray,
        symbols.parcelWriteCharArray,
        builtIns.primitiveArrayForType.getValue(builtIns.charType).owner.defaultType
    )

    private val doubleArraySerializer = PrimitiveParcelSerializer(
        symbols.parcelReadDoubleArray,
        symbols.parcelWriteDoubleArray,
        builtIns.primitiveArrayForType.getValue(builtIns.doubleType).owner.defaultType
    )

    private val floatArraySerializer = PrimitiveParcelSerializer(
        symbols.parcelReadFloatArray,
        symbols.parcelWriteFloatArray,
        builtIns.primitiveArrayForType.getValue(builtIns.floatType).owner.defaultType
    )

    private val longArraySerializer = PrimitiveParcelSerializer(
        symbols.parcelReadLongArray,
        symbols.parcelWriteLongArray,
        builtIns.primitiveArrayForType.getValue(builtIns.longType).owner.defaultType
    )

    private val stringArraySerializer = PrimitiveParcelSerializer(
        symbols.parcelReadStringArray, symbols.parcelWriteStringArray, builtIns.arrayClass.typeWith(builtIns.stringType)
    )

    // TODO Add tests for IBinder, IInterface, and IBinder arrays
    private val iBinderSerializer = PrimitiveParcelSerializer(symbols.parcelReadIBinder, symbols.parcelWriteIBinder)
    private val iInterfaceSerializer = PrimitiveParcelSerializer(symbols.parcelReadIInterface, symbols.parcelWriteIInterface)
    private val iBinderArraySerializer = PrimitiveParcelSerializer(symbols.parcelReadIBinderArray, symbols.parcelWriteIBinderArray)

    private val fileDescriptorSerializer = PrimitiveParcelSerializer(symbols.parcelReadFileDescriptor, symbols.parcelWriteFileDescriptor)
    private val serializableSerializer = PrimitiveParcelSerializer(symbols.parcelReadSerializable, symbols.parcelWriteSerializable)

    private val serializerInfos = listOf<Info>(
        Info("kotlin.String", stringSerializer),
        Info("kotlin.Byte", byteSerializer, false),
        Info("kotlin.Boolean", booleanSerializer, false),
        Info("kotlin.Char", charSerializer, false),
        Info("kotlin.Short", shortSerializer, false),
        Info("kotlin.Int", intSerializer, false),
        Info("kotlin.Long", longSerializer, false),
        Info("kotlin.Float", floatSerializer, false),
        Info("kotlin.Double", doubleSerializer, false),

        Info("java.lang.String", stringSerializer),
        Info("java.lang.Byte", byteSerializer),
        Info("java.lang.Boolean", booleanSerializer),
        Info("java.lang.Character", charSerializer),
        Info("java.lang.Short", shortSerializer),
        Info("java.lang.Integer", intSerializer),
        Info("java.lang.Long", longSerializer),
        Info("java.lang.Float", floatSerializer),
        Info("java.lang.Double", doubleSerializer),

        // FIXME: Only use this if we don't have a custom parceler for the element type
        // FIXME: What about ShortArrays?
        Info("kotlin.IntArray", intArraySerializer),
        Info("kotlin.BooleanArray", booleanArraySerializer),
        Info("kotlin.ByteArray", byteArraySerializer),
        Info("kotlin.CharArray", charArraySerializer),
        Info("kotlin.FloatArray", floatArraySerializer),
        Info("kotlin.DoubleArray", doubleArraySerializer),
        Info("kotlin.LongArray", longArraySerializer),

        Info("kotlin.CharSequence", charSequenceSerializer),
        Info("java.lang.CharSequence", charSequenceSerializer),

        Info("java.io.FileDescriptor", fileDescriptorSerializer, nullable = false),

        // Android library types

        // TODO Add test for persistable bundles, and maybe pass a classloader when deserializing a bundle?
        Info("android.os.Bundle", PrimitiveParcelSerializer(symbols.parcelReadBundle, symbols.parcelWriteBundle)),
        Info("android.os.PersistableBundle", PrimitiveParcelSerializer(symbols.parcelReadBundle, symbols.parcelWriteBundle)),
        Info(
            "android.util.SparseBooleanArray", PrimitiveParcelSerializer(
                symbols.parcelReadSparseBooleanArray,
                symbols.parcelWriteSparseBooleanArray
            )
        )
    )

    // Map from simple name -> package name -> pair of non-null and nullable serializer
    private val serializerMap: MutableMap<String, MutableMap<FqName, Pair<IrParcelSerializer, IrParcelSerializer>>> =
        mutableMapOf()

    init {
        for (info in serializerInfos) {
            val fqName = FqName(info.fqName)
            val shortName = fqName.shortName().asString()
            val fqNameMap = serializerMap.getOrPut(shortName) { mutableMapOf() }
            fqNameMap[fqName.parent()] = info.serializer to (if (info.nullable != false) info.serializer else
                NullAwareParcelSerializer(info.serializer.parcelType.makeNullable(), info.serializer, symbols, builtIns))
        }
    }

    val IrTypeArgument.upperBound: IrType
        get() = when (this) {
            is IrStarProjection -> builtIns.anyNType
            is IrTypeProjection -> {
                if (variance == Variance.OUT_VARIANCE || variance == Variance.INVARIANT)
                    type
                else
                    builtIns.anyNType
            }
            else -> error("Unknown type argument: ${render()}")
        }

    private val IrType.erasure: IrClass
        get() {
            val upperBound = erasedUpperBound
            // TODO: Should we unwrap inline class types at all? That does not seem very consistent.
            return ((context.ir.unfoldInlineClassType(upperBound.defaultType) as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                ?: upperBound
        }

    private fun IrClass.isSubclassOfFqName(fqName: String): Boolean =
        fqNameWhenAvailable?.asString() == fqName || superTypes.any { it.erasedUpperBound.isSubclassOfFqName(fqName) }

    fun get(irType: IrType): IrParcelSerializer {
        val classifier = irType.erasure

        serializerMap[classifier.name.asString()]?.let { fqNameMap ->
            classifier.fqNameWhenAvailable?.parent()?.let { fqName ->
                fqNameMap[fqName]?.let { serializers ->
                    return if (irType.isNullable())
                        serializers.second
                    else
                        serializers.first
                }
            }
        }

        if (classifier.isSubclassOfFqName("android.os.IBinder")) {
            return iBinderSerializer
        } else if (classifier.isSubclassOfFqName("android.os.IInterface")) {
            return iInterfaceSerializer
        }

        // TODO: This case should only apply to named objects!
        if (classifier.isObject) {
            return NullAwareParcelSerializer(classifier.defaultType, ObjectSerializer(classifier.defaultType), symbols, builtIns)
        } else if (classifier.isEnumClass) {
            val enumSerializer = EnumSerializer(classifier.defaultType, stringSerializer)
            return if (irType.isNullable()) {
                NullAwareParcelSerializer(classifier.defaultType, enumSerializer, symbols, builtIns)
            } else {
                enumSerializer
            }
        }

        // TODO: Handle arrays in inline classes/as upper bounds
        if (irType.isArray() || irType.isNullableArray()) {
            val elementType = (irType as IrSimpleType).arguments.single().upperBound
            if (elementType.isStringClassType()) {
                return stringArraySerializer
            } else {
                val elementFqName = elementType.erasedUpperBound.fqNameWhenAvailable
                if (elementFqName?.asString() == "android.os.IBinder") {
                    return iBinderArraySerializer
                } else {
                    val elementSerializer = get(elementType)
                    return ArraySerializer(irType, elementSerializer, intSerializer, context)
                }
            }
        }

        if (classifier.isSubclassOfFqName("java.io.Serializable")) {
            return serializableSerializer
        }

        return GenericSerializer(irType, symbols)
//        error("Cannot find serializer for ${irType.render()}")
    }
}
