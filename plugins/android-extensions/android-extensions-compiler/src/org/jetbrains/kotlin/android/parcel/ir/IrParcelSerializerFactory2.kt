/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel.ir

import org.jetbrains.kotlin.android.parcel.serializers.RAWVALUE_ANNOTATION_FQNAME
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class IrParcelSerializerFactory2(private val builtIns: IrBuiltIns, symbols: AndroidSymbols2) {
    private data class Info(val fqName: String, val serializer: IrParcelSerializer2, val nullable: Boolean? = null)

    // Map from simple name -> package name -> pair of serializers for non-nullable and nullable types
    private val serializerMap: MutableMap<String, MutableMap<FqName, Pair<IrParcelSerializer2, IrParcelSerializer2>>> =
        mutableMapOf()

    private val stringArraySerializer = SimpleParcelSerializer(symbols.parcelCreateStringArray, symbols.parcelWriteStringArray)
    // TODO Add tests for IBinder, IInterface, and IBinder arrays. The IInterface support is broken and will crash with a NoSuchMethodError.
    private val iBinderSerializer = SimpleParcelSerializer(symbols.parcelReadStrongBinder, symbols.parcelWriteStrongBinder)
    // private val iInterfaceSerializer = SimpleParcelSerializer(symbols.inter, symbols.parcelWriteIInterface)
    private val iBinderArraySerializer = SimpleParcelSerializer(symbols.parcelCreateBinderArray, symbols.parcelWriteBinderArray)
    private val serializableSerializer = SimpleParcelSerializer(symbols.parcelReadSerializable, symbols.parcelWriteSerializable)

    init {
        val stringSerializer = SimpleParcelSerializer(symbols.parcelReadString, symbols.parcelWriteString)
        val byteSerializer = SimpleParcelSerializer(symbols.parcelReadByte, symbols.parcelWriteByte)
        val intSerializer = SimpleParcelSerializer(symbols.parcelReadInt, symbols.parcelWriteInt)
        val longSerializer = SimpleParcelSerializer(symbols.parcelReadLong, symbols.parcelWriteLong)
        val floatSerializer = SimpleParcelSerializer(symbols.parcelReadFloat, symbols.parcelWriteFloat)
        val doubleSerializer = SimpleParcelSerializer(symbols.parcelReadDouble, symbols.parcelWriteDouble)

        val intArraySerializer = SimpleParcelSerializer(symbols.parcelCreateIntArray, symbols.parcelWriteIntArray)
        val booleanArraySerializer = SimpleParcelSerializer(symbols.parcelCreateBooleanArray, symbols.parcelWriteBooleanArray)
        val byteArraySerializer = SimpleParcelSerializer(symbols.parcelCreateByteArray, symbols.parcelWriteByteArray)
        val charArraySerializer = SimpleParcelSerializer(symbols.parcelCreateCharArray, symbols.parcelWriteCharArray)
        val doubleArraySerializer = SimpleParcelSerializer(symbols.parcelCreateDoubleArray, symbols.parcelWriteDoubleArray)
        val floatArraySerializer = SimpleParcelSerializer(symbols.parcelCreateFloatArray, symbols.parcelWriteFloatArray)
        val longArraySerializer = SimpleParcelSerializer(symbols.parcelCreateLongArray, symbols.parcelWriteLongArray)

        // Primitive types without dedicated read/write methods need an additional cast.
        val booleanSerializer = WrappedParcelSerializer(builtIns.booleanType, intSerializer)
        val shortSerializer = WrappedParcelSerializer(builtIns.shortType, intSerializer)
        val charSerializer = WrappedParcelSerializer(builtIns.charType, intSerializer)

        val charSequenceSerializer = CharSequenceSerializer2()

        // TODO The old backend uses the hidden "read/writeRawFileDescriptor" methods. Why?
        val fileDescriptorSerializer = SimpleParcelSerializer(symbols.parcelReadFileDescriptor, symbols.parcelWriteFileDescriptor)

        val sizeSerializer = SimpleParcelSerializer(symbols.parcelReadSize, symbols.parcelWriteSize)
        val sizeFSerializer = SimpleParcelSerializer(symbols.parcelReadSizeF, symbols.parcelWriteSizeF)

        val bundleSerializer = SimpleParcelSerializer(symbols.parcelReadBundle, symbols.parcelWriteBundle)
        val persistableBundleSerializer = SimpleParcelSerializer(symbols.parcelReadPersistableBundle, symbols.parcelWritePersistableBundle)

        val sparseBooleanArraySerializer =
            SimpleParcelSerializer(symbols.parcelReadSparseBooleanArray, symbols.parcelWriteSparseBooleanArray)

        val serializerInfos = listOf(
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

            // TODO Add test for persistable bundles
            Info("android.os.Bundle", bundleSerializer),
            Info("android.os.PersistableBundle", persistableBundleSerializer),
            Info("android.util.SparseBooleanArray", sparseBooleanArraySerializer),
            Info("android.util.Size", sizeSerializer),
            Info("android.util.SizeF", sizeFSerializer)
        )

        for (info in serializerInfos) {
            val fqName = FqName(info.fqName)
            val shortName = fqName.shortName().asString()
            val fqNameMap = serializerMap.getOrPut(shortName) { mutableMapOf() }
            fqNameMap[fqName.parent()] =
                info.serializer to (if (info.nullable != false) info.serializer else NullAwareParcelSerializer2(info.serializer))
        }
    }

    val listFqNames = setOf(
        "kotlin.collections.MutableList", "kotlin.collections.List", "java.util.List",
        "kotlin.collections.ArrayList", "java.util.ArrayList",
        // FIXME: Is the support for ArrayDeque missing in the old BE?
        "kotlin.collections.ArrayDeque", "java.util.ArrayDeque",
        "kotlin.collections.MutableSet", "kotlin.collections.Set", "java.util.Set",
        "kotlin.collections.HashSet", "java.util.HashSet",
        "kotlin.collections.LinkedHashSet", "java.util.LinkedHashSet",
        "java.util.NavigableSet", "java.util.SortedSet"
        // TODO: More java collections?
        // TODO: Add tests for all of these types, not just some common ones...
    )

    val mapFqNames = setOf(
        "kotlin.collections.MutableMap", "kotlin.collections.Map", "java.util.Map",
        "kotlin.collections.HashMap", "java.util.HashMap",
        "kotlin.collections.LinkedHashMap", "java.util.LinkedHashMap",
        "java.util.SortedMap", "java.util.NavigableMap", "java.util.TreeMap",
        "java.util.concurrent.ConcurrentHashMap"
    )

    private fun wrapNullableSerializerIfNeeded(irType: IrType, serializer: IrParcelSerializer2) =
        if (irType.isNullable()) NullAwareParcelSerializer2(serializer) else serializer

    fun get(irType: IrType, strict: Boolean = false): IrParcelSerializer2 {
        fun strict() = strict && !irType.hasAnnotation(RAWVALUE_ANNOTATION_FQNAME)

        // TODO inline classes
        val classifier = irType.erasedUpperBound

        // TODO custom serializers

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
        }

        if (classifier.isObject) {
            return ObjectSerializer2(classifier)
        } else if (classifier.isEnumClass) {
            return wrapNullableSerializerIfNeeded(irType, EnumSerializer2(classifier))
        }

        // TODO custom serializers for element types, primitive arrays with custom serializers
        if (irType.isArray() || irType.isNullableArray()) {
            val elementType = (irType as IrSimpleType).arguments.single().upperBound(builtIns)
            val elementFqName = elementType.erasedUpperBound.fqNameWhenAvailable
            return when (elementFqName?.asString()) {
                "java.lang.String", "kotlin.String" -> stringArraySerializer
                "android.os.IBinder" -> iBinderArraySerializer
                else -> ArraySerializer2(/*TODO*/irType, elementType, get(elementType, strict()))
            }
        } else if (classifier.name.asString() == "SparseIntArray" && classifier.fqNameWhenAvailable?.parent()?.asString() == "android.util") {
            return SparseArraySerializer2(classifier.defaultType, builtIns.intType, get(builtIns.intType, strict()))
        } else if (classifier.name.asString() == "SparseLongArray" && classifier.fqNameWhenAvailable?.parent()?.asString() == "android.util") {
            return SparseArraySerializer2(classifier.defaultType, builtIns.longType, get(builtIns.longType, strict()))
        } else if (classifier.name.asString() == "SparseArray" && classifier.fqNameWhenAvailable?.parent()?.asString() == "android.util") {
            val elementType = (irType as IrSimpleType).arguments.single().upperBound(builtIns)
            return SparseArraySerializer2(/*TODO*/irType, elementType, get(elementType, strict()))
        }

        if (classifier.fqNameWhenAvailable?.asString() in listFqNames) {
            val elementType = (irType as IrSimpleType).arguments.single().upperBound(builtIns)
            // TODO: Special cases for various list types
            return wrapNullableSerializerIfNeeded(irType, ListParceler(classifier, get(elementType, strict())))
        } else if (classifier.fqNameWhenAvailable?.asString() in mapFqNames) {
            // FIXME: Are there special cases for map types in Parcel?
            val keyType = (irType as IrSimpleType).arguments[0].upperBound(builtIns)
            val valueType = irType.arguments[1].upperBound(builtIns)
            return wrapNullableSerializerIfNeeded(irType, MapParceler(classifier, get(keyType, strict()), get(valueType, strict())))
        }

        if (classifier.isSubclassOfFqName("android.os.Parcelable")) {
            if (classifier.modality == Modality.FINAL) {
                // Try to use the CREATOR field directly, if it exists.
                // In Java classes or compiled Kotlin classes annotated with @Parcelize, we'll have a field in the class itself.
                // With Parcelable instances which were manually implemented in Kotlin, we'll instead have an @JvmField property
                // getter in the companion object.
                val creatorField = classifier.fields.find { field -> field.name.asString() == "CREATOR" }
                val creatorGetter = classifier.companionObject()?.safeAs<IrClass>()?.getPropertyGetter("CREATOR")?.takeIf {
                    it.owner.hasAnnotation(FqName("kotlin.jvm.JvmField"))
                }
                // TODO: For @Parcelize classes in the same module, the creator field may not exist yet, so we'll miss them here.

                if (creatorField != null || creatorGetter != null) {
                    return wrapNullableSerializerIfNeeded(irType, EfficientParcelableSerializer(classifier, creatorField, creatorGetter))
                }
            }
            return GenericParcelableSerializer(irType)
        } else if (classifier.isSubclassOfFqName("java.io.Serializable")) {
            return serializableSerializer
        }

        if (strict()) {
            throw IllegalArgumentException("Illegal type, could not find a specific serializer for ${irType.render()}")
        } else {
            return GenericSerializer2(irType)
        }
    }
}
