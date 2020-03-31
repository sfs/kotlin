/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel.ir

import kotlinx.android.parcel.WriteWith
import org.jetbrains.kotlin.android.parcel.serializers.RAWVALUE_ANNOTATION_FQNAME
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class IrParcelSerializerFactory(private val builtIns: IrBuiltIns, symbols: AndroidSymbols) {
    fun get(irType: IrType, scope: ParcelerScope?, strict: Boolean = false, toplevel: Boolean = false): IrParcelSerializer {
        fun strict() = strict && !irType.hasAnnotation(RAWVALUE_ANNOTATION_FQNAME)

        irType.getAnnotation(FqName(WriteWith::class.java.name))?.let { writeWith ->
            val parcelerClass = (writeWith.type as IrSimpleType).arguments.single().typeOrNull!!.getClass()!!
            return CustomParcelSerializer(IrParcelerObject(parcelerClass))
        }
        scope?.get(irType)?.let { parceler -> return CustomParcelSerializer(parceler) }

        // TODO inline classes
        val classifier = irType.erasedUpperBound

        when (val classifierFqName = classifier.fqNameWhenAvailable?.asString()) {
            "kotlin.String", "java.lang.String" ->
                return stringSerializer

            "kotlin.Byte", "java.lang.Byte" ->
                return wrapNullableSerializerIfNeeded(irType, byteSerializer)
            "kotlin.Boolean", "java.lang.Boolean" ->
                return wrapNullableSerializerIfNeeded(irType, booleanSerializer)
            "kotlin.Char", "java.lang.Character" ->
                return wrapNullableSerializerIfNeeded(irType, charSerializer)
            "kotlin.Short", "java.lang.Short" ->
                return wrapNullableSerializerIfNeeded(irType, shortSerializer)
            "kotlin.Int", "java.lang.Integer" ->
                return wrapNullableSerializerIfNeeded(irType, intSerializer)
            "kotlin.Long", "java.lang.Long" ->
                return wrapNullableSerializerIfNeeded(irType, longSerializer)
            "kotlin.Float", "java.lang.Float" ->
                return wrapNullableSerializerIfNeeded(irType, floatSerializer)
            "kotlin.Double", "java.lang.Double" ->
                return wrapNullableSerializerIfNeeded(irType, doubleSerializer)

            // FIXME: Only use this if we don't have a custom parceler for the element type
            "kotlin.IntArray" ->
                return intArraySerializer
            "kotlin.BooleanArray" ->
                return booleanArraySerializer
            "kotlin.ByteArray" ->
                return byteArraySerializer
            "kotlin.CharArray" ->
                return charArraySerializer
            "kotlin.FloatArray" ->
                return floatArraySerializer
            "kotlin.DoubleArray" ->
                return doubleArraySerializer
            "kotlin.LongArray" ->
                return longArraySerializer

            "kotlin.CharSequence", "java.lang.CharSequence" ->
                return charSequenceSerializer

            "java.io.FileDescriptor" ->
                return wrapNullableSerializerIfNeeded(irType, fileDescriptorSerializer)

            // TODO Add test for persistable bundles
            "android.os.Bundle" ->
                return bundleSerializer
            "android.os.PersistableBundle" ->
                return persistableBundleSerializer
            // TODO: Custom element serializer?
            "android.util.SparseBooleanArray" ->
                return sparseBooleanArraySerializer
            "android.util.Size" ->
                return wrapNullableSerializerIfNeeded(irType, sizeSerializer)
            "android.util.SizeF" ->
                return wrapNullableSerializerIfNeeded(irType, sizeFSerializer)

            // TODO custom serializers for element types, primitive arrays with custom serializers
            "kotlin.Array", "kotlin.ShortArray" -> {
                val elementType = (irType as IrSimpleType).arguments.single().upperBound(builtIns)
                val elementFqName = elementType.erasedUpperBound.fqNameWhenAvailable
                return when (elementFqName?.asString()) {
                    "java.lang.String", "kotlin.String" -> stringArraySerializer
                    "android.os.IBinder" -> iBinderArraySerializer
                    else -> ArraySerializer(/*TODO*/irType, elementType, get(elementType, scope, strict()))
                }
            }

            "android.util.SparseIntArray" ->
                return SparseArraySerializer(classifier.defaultType, builtIns.intType, get(builtIns.intType, scope, strict()))
            "android.util.SparseLongArray" ->
                return SparseArraySerializer(classifier.defaultType, builtIns.longType, get(builtIns.longType, scope, strict()))
            "android.util.SparseArray" -> {
                val elementType = (irType as IrSimpleType).arguments.single().upperBound(builtIns)
                return SparseArraySerializer(/*TODO*/irType, elementType, get(elementType, scope, strict()))
            }

            // TODO: More java collections?
            // TODO: Add tests for all of these types, not just some common ones...
            // FIXME: Is the support for ArrayDeque missing in the old BE?
            "kotlin.collections.MutableList", "kotlin.collections.List", "java.util.List",
            "kotlin.collections.ArrayList", "java.util.ArrayList",
            "kotlin.collections.ArrayDeque", "java.util.ArrayDeque",
            "kotlin.collections.MutableSet", "kotlin.collections.Set", "java.util.Set",
            "kotlin.collections.HashSet", "java.util.HashSet",
            "kotlin.collections.LinkedHashSet", "java.util.LinkedHashSet",
            "java.util.NavigableSet", "java.util.SortedSet" -> {
                val elementType = (irType as IrSimpleType).arguments.single().upperBound(builtIns)
                if (classifierFqName == "kotlin.collections.List" || classifierFqName == "kotlin.collections.List" || classifierFqName == "java.util.List") {
                    // TODO: Custom parcelers for element types
                    when (elementType.erasedUpperBound.fqNameWhenAvailable?.asString()) {
                        "android.os.IBinder" ->
                            return iBinderListSerializer
                        "kotlin.String", "java.lang.String" ->
                            return stringListSerializer
                    }
                }
                return wrapNullableSerializerIfNeeded(irType, ListParceler(classifier, get(elementType, scope, strict())))
            }

            "kotlin.collections.MutableMap", "kotlin.collections.Map", "java.util.Map",
            "kotlin.collections.HashMap", "java.util.HashMap",
            "kotlin.collections.LinkedHashMap", "java.util.LinkedHashMap",
            "java.util.SortedMap", "java.util.NavigableMap", "java.util.TreeMap",
            "java.util.concurrent.ConcurrentHashMap" -> {
                val keyType = (irType as IrSimpleType).arguments[0].upperBound(builtIns)
                val valueType = irType.arguments[1].upperBound(builtIns)
                val parceler = MapParceler(classifier, get(keyType, scope, strict()), get(valueType, scope, strict()))
                return wrapNullableSerializerIfNeeded(irType, parceler)
            }
        }

        // Generic parceler case
        when {
            classifier.isSubclassOfFqName("android.os.Parcelable")
                    // Avoid infinite loops when deriving parcelers for enum or object classes.
                    && !(toplevel && (classifier.isObject || classifier.isEnumClass)) -> {
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
                        return wrapNullableSerializerIfNeeded(
                            irType,
                            EfficientParcelableSerializer(classifier, creatorField, creatorGetter)
                        )
                    }
                }
                return GenericParcelableSerializer(irType)
            }

            classifier.isSubclassOfFqName("android.os.IBinder") ->
                return iBinderSerializer

            classifier.isObject ->
                return ObjectSerializer(classifier)

            classifier.isEnumClass ->
                return wrapNullableSerializerIfNeeded(irType, EnumSerializer(classifier))

            classifier.isSubclassOfFqName("java.io.Serializable") ->
                return serializableSerializer

            strict() ->
                throw IllegalArgumentException("Illegal type, could not find a specific serializer for ${irType.render()}")

            else ->
                return GenericSerializer(irType)
        }
    }

    private fun wrapNullableSerializerIfNeeded(irType: IrType, serializer: IrParcelSerializer) =
        if (irType.isNullable()) NullAwareParcelSerializer(serializer) else serializer

    // TODO Add tests for IBinder, IInterface, and IBinder arrays. The IInterface support is broken and will crash with a NoSuchMethodError.
    private val stringArraySerializer = SimpleParcelSerializer(symbols.parcelCreateStringArray, symbols.parcelWriteStringArray)
    private val stringListSerializer = SimpleParcelSerializer(symbols.parcelCreateStringArrayList, symbols.parcelWriteStringList)
    private val iBinderSerializer = SimpleParcelSerializer(symbols.parcelReadStrongBinder, symbols.parcelWriteStrongBinder)
    private val iBinderArraySerializer = SimpleParcelSerializer(symbols.parcelCreateBinderArray, symbols.parcelWriteBinderArray)
    private val iBinderListSerializer = SimpleParcelSerializer(symbols.parcelCreateBinderArrayList, symbols.parcelWriteBinderList)
    private val serializableSerializer = SimpleParcelSerializer(symbols.parcelReadSerializable, symbols.parcelWriteSerializable)
    private val stringSerializer = SimpleParcelSerializer(symbols.parcelReadString, symbols.parcelWriteString)
    private val byteSerializer = SimpleParcelSerializer(symbols.parcelReadByte, symbols.parcelWriteByte)
    private val intSerializer = SimpleParcelSerializer(symbols.parcelReadInt, symbols.parcelWriteInt)
    private val longSerializer = SimpleParcelSerializer(symbols.parcelReadLong, symbols.parcelWriteLong)
    private val floatSerializer = SimpleParcelSerializer(symbols.parcelReadFloat, symbols.parcelWriteFloat)
    private val doubleSerializer = SimpleParcelSerializer(symbols.parcelReadDouble, symbols.parcelWriteDouble)
    private val intArraySerializer = SimpleParcelSerializer(symbols.parcelCreateIntArray, symbols.parcelWriteIntArray)
    private val booleanArraySerializer = SimpleParcelSerializer(symbols.parcelCreateBooleanArray, symbols.parcelWriteBooleanArray)
    private val byteArraySerializer = SimpleParcelSerializer(symbols.parcelCreateByteArray, symbols.parcelWriteByteArray)
    private val charArraySerializer = SimpleParcelSerializer(symbols.parcelCreateCharArray, symbols.parcelWriteCharArray)
    private val doubleArraySerializer = SimpleParcelSerializer(symbols.parcelCreateDoubleArray, symbols.parcelWriteDoubleArray)
    private val floatArraySerializer = SimpleParcelSerializer(symbols.parcelCreateFloatArray, symbols.parcelWriteFloatArray)
    private val longArraySerializer = SimpleParcelSerializer(symbols.parcelCreateLongArray, symbols.parcelWriteLongArray)

    // Primitive types without dedicated read/write methods need an additional cast.
    private val booleanSerializer = WrappedParcelSerializer(builtIns.booleanType, intSerializer)
    private val shortSerializer = WrappedParcelSerializer(builtIns.shortType, intSerializer)
    private val charSerializer = WrappedParcelSerializer(builtIns.charType, intSerializer)

    private val charSequenceSerializer = CharSequenceSerializer()

    // TODO The old backend uses the hidden "read/writeRawFileDescriptor" methods. Why?
    private val fileDescriptorSerializer = SimpleParcelSerializer(symbols.parcelReadFileDescriptor, symbols.parcelWriteFileDescriptor)

    private val sizeSerializer = SimpleParcelSerializer(symbols.parcelReadSize, symbols.parcelWriteSize)
    private val sizeFSerializer = SimpleParcelSerializer(symbols.parcelReadSizeF, symbols.parcelWriteSizeF)
    private val bundleSerializer = SimpleParcelSerializer(symbols.parcelReadBundle, symbols.parcelWriteBundle)
    private val persistableBundleSerializer =
        SimpleParcelSerializer(symbols.parcelReadPersistableBundle, symbols.parcelWritePersistableBundle)
    private val sparseBooleanArraySerializer =
        SimpleParcelSerializer(symbols.parcelReadSparseBooleanArray, symbols.parcelWriteSparseBooleanArray)
}
