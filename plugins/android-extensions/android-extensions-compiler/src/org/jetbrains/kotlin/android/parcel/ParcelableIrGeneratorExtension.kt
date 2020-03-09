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
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.extensions.PureIrGenerationExtension
import org.jetbrains.kotlin.backend.common.ir.addChild
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.codegen.state.GenerationState
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
import org.jetbrains.kotlin.ir.symbols.impl.IrExternalPackageFragmentSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class ParcelableIrGeneratorExtension : PureIrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, context: CommonBackendContext) {
        moduleFragment.transform(ParcelableIrTransformer(context, AndroidSymbols(context, moduleFragment)), null)
    }
}

class ParcelableIrTransformer(private val context: CommonBackendContext, private val androidSymbols: AndroidSymbols) : ParcelableExtensionBase, IrElementTransformerVoidWithContext() {
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
                for (property in parcelableProperties) {
                    with(property.parceler) {
                        +writeParcel(
                            parcelParameter,
                            irGetField(irGet(receiverParameter), property.field)
                        )
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
//                kind = ClassKind.OBJECT
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
                    val sizeParameter = addValueParameter("size", context.irBuiltIns.intType)
                    body = context.createIrBuilder(symbol).run {
                        irExprBody(irCall(this@ParcelableIrTransformer.context.ir.symbols.arrayOfNulls, arrayType).apply {
                            putTypeArgument(0, arrayType)
                            putValueArgument(0, irGet(sizeParameter))
                        })
                    }
                }

                addFunction("createFromParcel", declaration.defaultType).apply {
                    val parcelParameter = addValueParameter("parcel", androidSymbols.parcelClass.defaultType)
                    body = context.createIrBuilder(symbol).run {
                        irExprBody(irCall(declaration.primaryConstructor!!).apply {
                            for ((index, property) in parcelableProperties.withIndex()) {
                                with(property.parceler) {
                                    putValueArgument(index, readParcel(parcelParameter))
                                }
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

    private val IrType.primitiveSerializer: IrParcelSerializer?
        get() = PrimitiveParcelSerializer.instance(androidSymbols, this)

    private val IrType.serializer: IrParcelSerializer
        get() = primitiveSerializer
            ?: NullAwareParcelSerializer(this, makeNotNull().primitiveSerializer!!, androidSymbols, context.irBuiltIns)

    private val IrClass.parcelableProperties: List<ParcelableProperty>
        get() {
            if (kind != ClassKind.CLASS) return emptyList()

            val constructor = primaryConstructor ?: return emptyList()

            return constructor.valueParameters.map { parameter ->
                val property = properties.first { it.name == parameter.name }
                ParcelableProperty(property.backingField!!, parameter.type.serializer)
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

    private val androidPackage = createPackage(FqName("android.os"))

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
                "android.os" -> androidPackage
                else -> error("Other packages are not supported yet: $fqName")
            }
            createImplicitParameterDeclarationWithWrappedDescriptor()
            block(this)
        }.symbol

    val parcelClass: IrClassSymbol = createClass(FqName("android.os.Parcel"))

    private fun parcelWrite(name: String, type: IrType): IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("write" + name, context.irBuiltIns.unitType).apply {
            addValueParameter("val", type)
        }.symbol

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

    private val parcelableInterface: IrClassSymbol =
        createClass(FqName("android.os.Parcelable"), ClassKind.INTERFACE, Modality.ABSTRACT)

    val parcelableCreator: IrClassSymbol =
        buildClass {
            name = Name.identifier("Creator")
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
        }.apply {
            addTypeParameter("T", context.irBuiltIns.anyNType)
            parent = parcelableInterface.owner
            createImplicitParameterDeclarationWithWrappedDescriptor()
        }.symbol
}

interface IrParcelSerializer {
    fun IrBuilderWithScope.readParcel(parcel: IrValueDeclaration): IrExpression
    fun IrBuilderWithScope.writeParcel(parcel: IrValueDeclaration, value: IrExpression): IrExpression
}

class PrimitiveParcelSerializer(val parcelType: IrType, val reader: IrFunctionSymbol, val writer: IrFunctionSymbol) : IrParcelSerializer {
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

    companion object {
        fun instance(symbols: AndroidSymbols, irType: IrType): PrimitiveParcelSerializer? =
            when {
                irType.isStringClassType() ->
                    PrimitiveParcelSerializer(irType, symbols.parcelReadString, symbols.parcelWriteString)
                irType.isByte() ->
                    PrimitiveParcelSerializer(irType, symbols.parcelReadByte, symbols.parcelWriteByte)
                irType.isBoolean() ->
                    PrimitiveParcelSerializer(irType, symbols.parcelReadInt, symbols.parcelWriteInt)
                irType.isChar() ->
                    PrimitiveParcelSerializer(irType, symbols.parcelReadInt, symbols.parcelWriteInt)
                irType.isShort() ->
                    PrimitiveParcelSerializer(irType, symbols.parcelReadInt, symbols.parcelWriteInt)
                irType.isInt() ->
                    PrimitiveParcelSerializer(irType, symbols.parcelReadInt, symbols.parcelWriteInt)
                irType.isLong() ->
                    PrimitiveParcelSerializer(irType, symbols.parcelReadLong, symbols.parcelWriteLong)
                irType.isFloat() ->
                    PrimitiveParcelSerializer(irType, symbols.parcelReadFloat, symbols.parceWriteFloat)
                irType.isDouble() ->
                    PrimitiveParcelSerializer(irType, symbols.parcelReadDouble, symbols.parcelWriteDouble)
                else ->
                    null
            }
    }
}

class NullAwareParcelSerializer(val parcelType: IrType, val serializer: IrParcelSerializer, val symbols: AndroidSymbols, val irBuiltIns: IrBuiltIns) : IrParcelSerializer {
    override fun IrBuilderWithScope.readParcel(parcel: IrValueDeclaration): IrExpression =
        irIfThenElse(
            parcelType,
            irEquals(irCall(symbols.parcelReadInt).apply {
                putValueArgument(0, irGet(parcel))
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
                    putValueArgument(0, irGet(parcel))
                    putValueArgument(1, irInt(0))
                },
                irBlock {
                    +irCall(symbols.parcelWriteInt).apply {
                        putValueArgument(0, irGet(parcel))
                        putValueArgument(1, irInt(1))
                    }
                    +with(serializer) { writeParcel(parcel, irGet(irValueSymbol.owner)) }
                }
            )
        }
}
