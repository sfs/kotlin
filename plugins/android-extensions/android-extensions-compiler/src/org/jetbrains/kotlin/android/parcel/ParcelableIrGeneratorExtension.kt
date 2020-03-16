/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel

import org.jetbrains.kotlin.android.parcel.serializers.ParcelableExtensionBase
import org.jetbrains.kotlin.android.parcel.serializers.ParcelableExtensionBase.Companion.ALLOWED_CLASS_KINDS
import org.jetbrains.kotlin.android.parcel.serializers.ParcelableExtensionBase.Companion.CREATOR_NAME
import org.jetbrains.kotlin.android.parcel.serializers.ParcelableExtensionBase.Companion.FILE_DESCRIPTOR_FQNAME
import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.PureIrGenerationExtension
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrExternalPackageFragmentSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

class ParcelableIrGeneratorExtension : PureIrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, context: CommonBackendContext) {
        moduleFragment.transform(ParcelableIrTransformer(context, AndroidSymbols(context, moduleFragment)), null)
    }
}

class ParcelableIrTransformer(private val context: CommonBackendContext, private val androidSymbols: AndroidSymbols) :
    ParcelableExtensionBase, IrElementTransformerVoidWithContext() {
    private val serializerFactory = IrParcelSerializerFactory(context, androidSymbols)

    override fun visitClassNew(declaration: IrClass): IrStatement {
        declaration.transformChildrenVoid()
        if (!declaration.isParcelize)
            return declaration

        val parcelableProperties = declaration.parcelableProperties

        declaration.addFunction("describeContents", context.irBuiltIns.intType).apply {
            val flags = if (parcelableProperties.any { it.field.type.containsFileDescriptors }) 1 else 0
            body = context.createIrBuilder(symbol).run {
                irExprBody(irInt(flags))
            }
        }

        declaration.addFunction("writeToParcel", context.irBuiltIns.unitType).apply {
            val receiverParameter = dispatchReceiverParameter!!
            val parcelParameter = addValueParameter("out", androidSymbols.parcelClass.defaultType)
            val flagsParameter = addValueParameter("flags", context.irBuiltIns.intType)

            body = context.createIrBuilder(symbol).irBlockBody {
                if (parcelableProperties.isNotEmpty()) {
                    for (property in parcelableProperties) {
                        with(property.parceler) {
                            +writeParcel(
                                parcelParameter,
                                irGetField(irGet(receiverParameter), property.field)
                            )
                        }
                    }
                } else {
                    with(declaration.classParceler) {
                        +writeParcel(parcelParameter, irGet(receiverParameter))
                    }
                }
            }
        }

        val creatorType = androidSymbols.parcelableCreator.typeWith(declaration.defaultType)

        declaration.addField {
            name = CREATOR_NAME
            type = creatorType
            isStatic = true
        }.apply {
            val irField = this
            val creatorClass = buildClass {
                name = Name.identifier("Creator")
                visibility = Visibilities.LOCAL
            }.apply {
                parent = irField
                superTypes = listOf(creatorType)
                createImplicitParameterDeclarationWithWrappedDescriptor()

                addConstructor {
                    isPrimary = true
                }.apply {
                    body = context.createIrBuilder(symbol).irBlockBody {
                        +irDelegatingConstructorCall(context.irBuiltIns.anyClass.owner.constructors.single())
                    }
                }

                val arrayType = context.irBuiltIns.arrayClass.typeWith(declaration.defaultType.makeNullable())
                addFunction("newArray", arrayType).apply {
                    overriddenSymbols += androidSymbols.parcelableCreator.getSimpleFunction(name.asString())!!
                    val sizeParameter = addValueParameter("size", context.irBuiltIns.intType)
                    body = context.createIrBuilder(symbol).run {
                        irExprBody(irCall(this@ParcelableIrTransformer.context.ir.symbols.arrayOfNulls, arrayType).apply {
                            putTypeArgument(0, arrayType)
                            putValueArgument(0, irGet(sizeParameter))
                        })
                    }
                }

                addFunction("createFromParcel", declaration.defaultType).apply {
                    overriddenSymbols += androidSymbols.parcelableCreator.getSimpleFunction(name.asString())!!
                    val parcelParameter = addValueParameter("parcel", androidSymbols.parcelClass.defaultType)
                    body = context.createIrBuilder(symbol).run {
                        irExprBody(if (parcelableProperties.isNotEmpty()) {
                            irCall(declaration.primaryConstructor!!).apply {
                                for ((index, property) in parcelableProperties.withIndex()) {
                                    with(property.parceler) {
                                        putValueArgument(index, readParcel(parcelParameter))
                                    }
                                }
                            }
                        } else {
                            with(declaration.classParceler) {
                                readParcel(parcelParameter)
                            }
                        })
                    }
                }
            }

            initializer = context.createIrBuilder(symbol).run {
                irExprBody(irBlock {
                    +creatorClass
                    +irCall(creatorClass.primaryConstructor!!)
                })
            }
        }

        return declaration
    }


    private data class ParcelableProperty(val field: IrField, val parceler: IrParcelSerializer)

    private val IrClass.classParceler: IrParcelSerializer
        get() = if (kind == ClassKind.CLASS) {
            // TODO Go through the factory instead
            NullAwareParcelSerializer(defaultType, NoParameterClassSerializer(defaultType), androidSymbols, context.irBuiltIns)
        } else {
            serializerFactory.get(defaultType)
        }

    private val IrClass.parcelableProperties: List<ParcelableProperty>
        get() {
            if (kind != ClassKind.CLASS) return emptyList()

            val constructor = primaryConstructor ?: return emptyList()

            return constructor.valueParameters.map { parameter ->
                val property = properties.first { it.name == parameter.name }
                ParcelableProperty(property.backingField!!, serializerFactory.get(parameter.type))
            }
        }

    private val IrClass.isParcelize: Boolean
        get() = kind in ALLOWED_CLASS_KINDS && hasAnnotation(PARCELIZE_CLASS_FQNAME)

    // TODO: Upper bounds of type parameters! (T : FileDescriptor)
    private val IrType.containsFileDescriptors: Boolean
        get() =
            classOrNull?.owner?.fqNameWhenAvailable == FILE_DESCRIPTOR_FQNAME ||
                    (this as? IrSimpleType)?.arguments?.any { argument ->
                        argument.typeOrNull?.containsFileDescriptors == true
                    } == true
}

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

        Info("java.lang.Byte", byteSerializer),
        Info("java.lang.Boolean", booleanSerializer),
        Info("java.lang.Character", charSerializer),
        Info("java.lang.Short", shortSerializer),
        Info("java.lang.Integer", intSerializer),
        Info("java.lang.Long", longSerializer),
        Info("java.lang.Float", floatSerializer),
        Info("java.lang.Double", doubleSerializer),

        // FIXME: Only use this if we don't have a custom parceler for the element type
        Info("kotlin.IntArray", intArraySerializer),
        Info("kotlin.BooleanArray", booleanArraySerializer),
        Info("kotlin.ByteArray", byteArraySerializer),
        Info("kotlin.CharArray", charArraySerializer),
        Info("kotlin.FloatArray", floatArraySerializer),
        Info("kotlin.DoubleArray", doubleArraySerializer),
        Info("kotlin.LongArray", longArraySerializer),

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

    val IrTypeParameter.erasedUpperBound: IrClass
        get() {
            for (type in superTypes) {
                val irClass = type.classOrNull?.owner ?: continue
                if (!irClass.isInterface && !irClass.isAnnotationClass) return irClass
            }
            return superTypes.first().erasedUpperBound
        }

    val IrType.erasedUpperBound: IrClass
        get() = when (val classifier = classifierOrNull) {
            is IrClassSymbol -> classifier.owner
            is IrTypeParameterSymbol -> classifier.owner.erasedUpperBound
            else -> throw IllegalStateException()
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

        if (classifier.isObject) {
            return NullAwareParcelSerializer(classifier.defaultType, ObjectSerializer(classifier.defaultType), symbols, builtIns)
        } else if (classifier.isEnumClass) {
            return NullAwareParcelSerializer(classifier.defaultType, EnumSerializer(classifier.defaultType, stringSerializer), symbols, builtIns)
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
                }
            }
        }


        error("Cannot find serializer for ${irType.render()}")
    }
}

class AndroidSymbols(private val context: CommonBackendContext, private val moduleFragment: IrModuleFragment) {
    private fun createPackage(fqName: FqName): IrPackageFragment =
        IrExternalPackageFragmentImpl(
            IrExternalPackageFragmentSymbolImpl(
                EmptyPackageFragmentDescriptor(
                    moduleFragment.descriptor,
                    fqName
                )
            )
        )

    private val androidOsPackage = createPackage(FqName("android.os"))
    private val androidUtilPackage = createPackage(FqName("android.util"))

    private inline fun createClass(
        fqName: FqName,
        classKind: ClassKind = ClassKind.CLASS,
        classModality: Modality = Modality.FINAL,
        block: (IrClass) -> Unit = {}
    ): IrClassSymbol =
        buildClass {
            name = fqName.shortName()
            kind = classKind
            modality = classModality
        }.apply {
            parent = when (fqName.parent().asString()) {
                "android.os" -> androidOsPackage
                "android.util" -> androidUtilPackage
                else -> error("Other packages are not supported yet: $fqName")
            }
            createImplicitParameterDeclarationWithWrappedDescriptor()
            block(this)
        }.symbol

    private val androidOsBundle: IrClassSymbol = createClass(FqName("android.os.Bundle"))
    private val sparseBooleanArray: IrClassSymbol = createClass(FqName("android.util.SparseBooleanArray"))

    val parcelClass: IrClassSymbol = createClass(FqName("android.os.Parcel"))

    private fun parcelUnary(name: String, type: IrType): IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction(name, context.irBuiltIns.unitType).apply {
            addValueParameter("val", type)
        }.symbol

    private fun parcelWrite(name: String, type: IrType): IrSimpleFunctionSymbol =
        parcelUnary("write$name", type)

    private fun parcelReadArray(name: String, type: IrType): IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("create${name}Array", context.irBuiltIns.arrayClass.typeWith(type)).symbol

    private fun parcelWriteArray(name: String, type: IrType): IrSimpleFunctionSymbol =
        parcelUnary("write${name}Array", context.irBuiltIns.arrayClass.typeWith(type))

    val parcelReadByte: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readByte", context.irBuiltIns.byteType).symbol

    val parcelReadInt: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readInt", context.irBuiltIns.intType).symbol

    val parcelReadLong: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readLong", context.irBuiltIns.longType).symbol

    val parcelReadFloat: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readFloat", context.irBuiltIns.floatType).symbol

    val parcelReadDouble: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readDouble", context.irBuiltIns.doubleType).symbol

    val parcelReadString: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readString", context.irBuiltIns.stringType.makeNullable()).symbol

    val parcelWriteByte: IrSimpleFunctionSymbol =
        parcelWrite("Byte", context.irBuiltIns.byteType)

    val parcelWriteInt: IrSimpleFunctionSymbol =
        parcelWrite("Int", context.irBuiltIns.intType)

    val parcelWriteLong: IrSimpleFunctionSymbol =
        parcelWrite("Long", context.irBuiltIns.longType)

    val parceWriteFloat: IrSimpleFunctionSymbol =
        parcelWrite("Float", context.irBuiltIns.floatType)

    val parcelWriteDouble: IrSimpleFunctionSymbol =
        parcelWrite("Double", context.irBuiltIns.doubleType)

    val parcelWriteString: IrSimpleFunctionSymbol =
        parcelWrite("String", context.irBuiltIns.stringType)

    val parcelReadBooleanArray: IrSimpleFunctionSymbol =
        parcelReadArray("Boolean", context.irBuiltIns.booleanType)

    val parcelReadByteArray: IrSimpleFunctionSymbol =
        parcelReadArray("Byte", context.irBuiltIns.booleanType)

    val parcelReadCharArray: IrSimpleFunctionSymbol =
        parcelReadArray("Char", context.irBuiltIns.charType)

    val parcelReadIntArray: IrSimpleFunctionSymbol =
        parcelReadArray("Int", context.irBuiltIns.intType)

    val parcelReadLongArray: IrSimpleFunctionSymbol =
        parcelReadArray("Long", context.irBuiltIns.longType)

    val parcelReadFloatArray: IrSimpleFunctionSymbol =
        parcelReadArray("Float", context.irBuiltIns.floatType)

    val parcelReadDoubleArray: IrSimpleFunctionSymbol =
        parcelReadArray("Double", context.irBuiltIns.doubleType)

    val parcelReadStringArray: IrSimpleFunctionSymbol =
        parcelReadArray("String", context.irBuiltIns.stringType)

    val parcelWriteBooleanArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Boolean", context.irBuiltIns.booleanType)

    val parcelWriteByteArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Byte", context.irBuiltIns.booleanType)

    val parcelWriteCharArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Char", context.irBuiltIns.charType)

    val parcelWriteIntArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Int", context.irBuiltIns.intType)

    val parcelWriteLongArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Long", context.irBuiltIns.longType)

    val parcelWriteFloatArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Float", context.irBuiltIns.floatType)

    val parcelWriteDoubleArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Double", context.irBuiltIns.doubleType)

    val parcelWriteStringArray: IrSimpleFunctionSymbol =
        parcelWriteArray("String", context.irBuiltIns.stringType)

    val parcelReadBundle: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readBundle", androidOsBundle.defaultType).symbol

    val parcelWriteBundle: IrSimpleFunctionSymbol =
        parcelUnary("writeBundle", androidOsBundle.defaultType)

    val parcelReadSparseBooleanArray: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readSparseBooleanArray", sparseBooleanArray.defaultType).symbol

    val parcelWriteSparseBooleanArray: IrSimpleFunctionSymbol =
        parcelUnary("writeSparseBooleanArray", sparseBooleanArray.defaultType)

    private val androidOsIBinder: IrClassSymbol =
        createClass(FqName("android.os.IBinder"))

    private val androidOsIInterface: IrClassSymbol =
        createClass(FqName("android.os.IInterface"))

    val parcelReadIBinder: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readStrongBinder", androidOsIBinder.defaultType).symbol

    val parcelWriteIBinder: IrSimpleFunctionSymbol =
        parcelUnary("writeStrongBinder", androidOsIBinder.defaultType)

    val parcelReadIBinderArray: IrSimpleFunctionSymbol =
        parcelReadArray("Binder", androidOsIBinder.defaultType)

    val parcelWriteIBinderArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Binder", androidOsIBinder.defaultType)

    val parcelReadIInterface: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readStrongInterface", androidOsIInterface.defaultType).symbol

    val parcelWriteIInterface: IrSimpleFunctionSymbol =
        parcelUnary("writeStrongInterface", androidOsIInterface.defaultType)

    private val parcelableInterface: IrClassSymbol =
        createClass(FqName("android.os.Parcelable"), ClassKind.INTERFACE, Modality.ABSTRACT)

    val parcelableCreator: IrClassSymbol =
        buildClass {
            name = Name.identifier("Creator")
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
        }.apply {
            val t = addTypeParameter("T", context.irBuiltIns.anyNType)
            parent = parcelableInterface.owner
            createImplicitParameterDeclarationWithWrappedDescriptor()

            addFunction("createFromParcel", t.defaultType, Modality.ABSTRACT, isStatic = true).apply {
                dispatchReceiverParameter = thisReceiver!!.copyTo(this, type = thisReceiver!!.type)
                addValueParameter("source", parcelClass.defaultType)
            }

            addFunction("newArray", context.irBuiltIns.arrayClass.typeWith(t.defaultType), Modality.ABSTRACT, isStatic = true).apply {
                dispatchReceiverParameter = thisReceiver!!.copyTo(this, type = thisReceiver!!.type)
                addValueParameter("size", context.irBuiltIns.intType)
            }
        }.symbol
}

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
