/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel.ir

import org.jetbrains.kotlin.android.parcel.ParcelableAnnotationChecker.Companion.WRITE_WITH_FQNAME
import org.jetbrains.kotlin.android.parcel.serializers.RAWVALUE_ANNOTATION_FQNAME
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.backend.jvm.ir.getArrayElementType
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.resolve.source.PsiSourceElement

// Custom parcelers are resolved in *reverse* lexical scope order
class ParcelerScope(val parent: ParcelerScope? = null) {
    private val typeParcelers = mutableMapOf<IrType, IrClass>()

    fun add(type: IrType, parceler: IrClass) {
        typeParcelers.putIfAbsent(type, parceler)
    }

    fun get(type: IrType): IrClass? =
        parent?.get(type) ?: typeParcelers[type]
}

fun ParcelerScope?.getCustomSerializer(irType: IrType): IrClass? =
    irType.getAnnotation(WRITE_WITH_FQNAME)?.let { writeWith ->
        (writeWith.type as IrSimpleType).arguments.single().typeOrNull!!.getClass()!!
    } ?: this?.get(irType)

fun ParcelerScope?.hasCustomSerializer(irType: IrType): Boolean =
    getCustomSerializer(irType) != null

class IrParcelSerializerFactory(symbols: AndroidSymbols) {
    /**
     * Resolve the given [irType] to a corresponding [IrParcelSerializer]. This depends on the TypeParcelers which
     * are currently in [scope], as well as the type of the enclosing Parceleable class [parcelizeType], which is needed
     * to get a class loader for reflection based serialization. Beyond this, we need to know whether to allow
     * using read/writeValue for serialization (if [strict] is false). Beyond this, we need to know whether we are
     * producing parcelers for properties of a Parcelable (if [toplevel] is true), or for a complete Parcelable.
     */
    fun get(
        irType: IrType,
        scope: ParcelerScope?,
        parcelizeType: IrType,
        strict: Boolean = false,
        toplevel: Boolean = false
    ): IrParcelSerializer {
        fun strict() = strict && !irType.hasAnnotation(RAWVALUE_ANNOTATION_FQNAME)

        scope.getCustomSerializer(irType)?.let { parceler ->
            return CustomParcelSerializer(parceler)
        }

        // TODO inline classes
        val classifier = irType.erasedUpperBound
        val classifierFqName = classifier.fqNameWhenAvailable?.asString()
        when (classifierFqName) {
            // Built-in parcel serializers
            "kotlin.String", "java.lang.String" ->
                return stringSerializer
            "kotlin.CharSequence", "java.lang.CharSequence" ->
                return charSequenceSerializer
            "android.os.Bundle" ->
                return bundleSerializer
            "android.os.PersistableBundle" ->
                return persistableBundleSerializer

            // Non-nullable built-in serializers
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
            "java.io.FileDescriptor" ->
                return wrapNullableSerializerIfNeeded(irType, fileDescriptorSerializer)
            "android.util.Size" ->
                return wrapNullableSerializerIfNeeded(irType, sizeSerializer)
            "android.util.SizeF" ->
                return wrapNullableSerializerIfNeeded(irType, sizeFSerializer)

            // Built-in non-parameterized container types.
            "kotlin.IntArray" ->
                if (!scope.hasCustomSerializer(irBuiltIns.intType))
                    return intArraySerializer
            "kotlin.BooleanArray" ->
                if (!scope.hasCustomSerializer(irBuiltIns.booleanType))
                    return booleanArraySerializer
            "kotlin.ByteArray" ->
                if (!scope.hasCustomSerializer(irBuiltIns.byteType))
                    return byteArraySerializer
            "kotlin.CharArray" ->
                if (!scope.hasCustomSerializer(irBuiltIns.charType))
                    return charArraySerializer
            "kotlin.FloatArray" ->
                if (!scope.hasCustomSerializer(irBuiltIns.floatType))
                    return floatArraySerializer
            "kotlin.DoubleArray" ->
                if (!scope.hasCustomSerializer(irBuiltIns.doubleType))
                    return doubleArraySerializer
            "kotlin.LongArray" ->
                if (!scope.hasCustomSerializer(irBuiltIns.longType))
                    return longArraySerializer
            "android.util.SparseBooleanArray" ->
                if (!scope.hasCustomSerializer(irBuiltIns.booleanType))
                    return sparseBooleanArraySerializer
        }

        // Generic container types
        when (classifierFqName) {
            // Apart from kotlin.Array and kotlin.ShortArray, these will only be hit if we have a
            // special parceler for the element type.
            "kotlin.Array", "kotlin.ShortArray", "kotlin.IntArray",
            "kotlin.BooleanArray", "kotlin.ByteArray", "kotlin.CharArray",
            "kotlin.FloatArray", "kotlin.DoubleArray", "kotlin.LongArray" -> {
                val elementType = irType.getArrayElementType(irBuiltIns)

                if (!scope.hasCustomSerializer(elementType)) {
                    when (elementType.erasedUpperBound.fqNameWhenAvailable?.asString()) {
                        "java.lang.String", "kotlin.String" ->
                            return stringArraySerializer
                        "android.os.IBinder" ->
                            return iBinderArraySerializer
                    }
                }

                val arrayType =
                    if (classifier.defaultType.isPrimitiveArray()) classifier.defaultType else irBuiltIns.arrayClass.typeWith(elementType)
                return ArraySerializer(arrayType, elementType, get(elementType, scope, parcelizeType, strict()))
            }

            // This will only be hit if we have a custom serializer for booleans
            "android.util.SparseBooleanArray" ->
                return SparseArraySerializer(classifier, irBuiltIns.booleanType, get(irBuiltIns.booleanType, scope, parcelizeType, strict()))
            "android.util.SparseIntArray" ->
                return SparseArraySerializer(classifier, irBuiltIns.intType, get(irBuiltIns.intType, scope, parcelizeType, strict()))
            "android.util.SparseLongArray" ->
                return SparseArraySerializer(classifier, irBuiltIns.longType, get(irBuiltIns.longType, scope, parcelizeType, strict()))
            "android.util.SparseArray" -> {
                val elementType = (irType as IrSimpleType).arguments.single().upperBound(irBuiltIns)
                return SparseArraySerializer(classifier, elementType, get(elementType, scope, parcelizeType, strict()))
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
                val elementType = (irType as IrSimpleType).arguments.single().upperBound(irBuiltIns)
                if (!scope.hasCustomSerializer(elementType) && classifierFqName in setOf(
                        "kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.collections.ArrayList",
                        "java.util.List", "java.util.ArrayList"
                    )
                ) {
                    when (elementType.erasedUpperBound.fqNameWhenAvailable?.asString()) {
                        "android.os.IBinder" ->
                            return iBinderListSerializer
                        "kotlin.String", "java.lang.String" ->
                            return stringListSerializer
                    }
                }
                return wrapNullableSerializerIfNeeded(irType, ListParceler(classifier, get(elementType, scope, parcelizeType, strict())))
            }

            "kotlin.collections.MutableMap", "kotlin.collections.Map", "java.util.Map",
            "kotlin.collections.HashMap", "java.util.HashMap",
            "kotlin.collections.LinkedHashMap", "java.util.LinkedHashMap",
            "java.util.SortedMap", "java.util.NavigableMap", "java.util.TreeMap",
            "java.util.concurrent.ConcurrentHashMap" -> {
                val keyType = (irType as IrSimpleType).arguments[0].upperBound(irBuiltIns)
                val valueType = irType.arguments[1].upperBound(irBuiltIns)
                val parceler =
                    MapParceler(classifier, get(keyType, scope, parcelizeType, strict()), get(valueType, scope, parcelizeType, strict()))
                return wrapNullableSerializerIfNeeded(irType, parceler)
            }
        }

        // Generic parceleable types
        when {
            classifier.isSubclassOfFqName("android.os.Parcelable")
                    // Avoid infinite loops when deriving parcelers for enum or object classes.
                    && !(toplevel && (classifier.isObject || classifier.isEnumClass)) -> {
                // We try to use writeToParcel/createFromParcel directly whenever possible, but there are some caveats.
                //
                // According to the JLS, changing a class from final to non-final is a binary compatible change, hence we
                // cannot use the writeToParcel/createFromParcel methods directly when serializing classes from external
                // dependencies. This issue was originally reported in KT-20029.
                //
                // Conversely, we can and should apply this optimization to all classes in the current module which
                // implement Parcelable (KT-20030). There are two cases to consider. If the class is annotated with
                // @Parcelize, we will create the corresponding methods/fields ourselves, before we generate the code
                // for writeToParcel/createFromParcel. For Java classes (or compiled Kotlin classes annotated with
                // @Parcelize), we'll have a field in the class itself. Finally, with Parcelable instances which were
                // manually implemented in Kotlin, we'll instead have an @JvmField property getter in the companion object.
                return if (classifier.modality == Modality.FINAL && classifier.descriptor.source is PsiSourceElement
                    && (classifier.isParcelize || classifier.hasCreatorField)
                ) {
                    wrapNullableSerializerIfNeeded(irType, EfficientParcelableSerializer(classifier))
                } else {
                    // In all other cases, we have to use the generic methods in Parcel, which use reflection internally.
                    GenericParcelableSerializer(parcelizeType)
                }
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
                return GenericSerializer(parcelizeType)
        }
    }

    private fun wrapNullableSerializerIfNeeded(irType: IrType, serializer: IrParcelSerializer) =
        if (irType.isNullable()) NullAwareParcelSerializer(serializer) else serializer

    private val irBuiltIns: IrBuiltIns = symbols.irBuiltIns

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
    private val booleanSerializer = WrappedParcelSerializer(irBuiltIns.booleanType, intSerializer)
    private val shortSerializer = WrappedParcelSerializer(irBuiltIns.shortType, intSerializer)
    private val charSerializer = WrappedParcelSerializer(irBuiltIns.charType, intSerializer)

    private val charSequenceSerializer = CharSequenceSerializer()

    // TODO The old backend uses the hidden "read/writeRawFileDescriptor" methods.
    private val fileDescriptorSerializer = SimpleParcelSerializer(symbols.parcelReadFileDescriptor, symbols.parcelWriteFileDescriptor)

    private val sizeSerializer = SimpleParcelSerializer(symbols.parcelReadSize, symbols.parcelWriteSize)
    private val sizeFSerializer = SimpleParcelSerializer(symbols.parcelReadSizeF, symbols.parcelWriteSizeF)
    private val bundleSerializer = SimpleParcelSerializer(symbols.parcelReadBundle, symbols.parcelWriteBundle)
    private val persistableBundleSerializer =
        SimpleParcelSerializer(symbols.parcelReadPersistableBundle, symbols.parcelWritePersistableBundle)
    private val sparseBooleanArraySerializer =
        SimpleParcelSerializer(symbols.parcelReadSparseBooleanArray, symbols.parcelWriteSparseBooleanArray)
}
