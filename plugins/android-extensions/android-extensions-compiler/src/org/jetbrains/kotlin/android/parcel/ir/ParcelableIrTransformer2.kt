/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel.ir

import org.jetbrains.kotlin.android.parcel.PARCELIZE_CLASS_FQNAME
import org.jetbrains.kotlin.android.parcel.serializers.ParcelableExtensionBase
import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name

class ParcelableIrTransformer2(private val context: CommonBackendContext, private val androidSymbols: AndroidSymbols2) :
    ParcelableExtensionBase, IrElementTransformerVoidWithContext() {
    private val serializerFactory = IrParcelSerializerFactory2(context.irBuiltIns, androidSymbols)

    // TODO: The CREATOR field and writeToParcel functions have to be lazily generated so that we can call them on
    //       other types in the current module.
    override fun visitClassNew(declaration: IrClass): IrStatement {
        declaration.transformChildrenVoid()
        if (!declaration.isParcelize)
            return declaration

        val parcelableProperties = declaration.parcelableProperties

        if (declaration.descriptor.hasSyntheticDescribeContents()) {
            declaration.addFunction("describeContents", context.irBuiltIns.intType, modality = Modality.OPEN).apply {
                val flags = if (parcelableProperties.any { it.field.type.containsFileDescriptors }) 1 else 0
                body = context.createIrBuilder(symbol).run {
                    irExprBody(irInt(flags))
                }
            }
        }

        if (declaration.descriptor.hasSyntheticWriteToParcel()) {
            declaration.addFunction("writeToParcel", context.irBuiltIns.unitType, modality = Modality.OPEN).apply {
                val receiverParameter = dispatchReceiverParameter!!
                val parcelParameter = addValueParameter("out", androidSymbols.androidOsParcel.defaultType)
                val flagsParameter = addValueParameter("flags", context.irBuiltIns.intType)

                body = androidSymbols.createBuilder(symbol).run {
                    irBlockBody {
                        if (parcelableProperties.isNotEmpty()) {
                            for (property in parcelableProperties) {
                                +writeParcelWith(
                                    property.parceler,
                                    parcelParameter,
                                    flagsParameter,
                                    irGetField(irGet(receiverParameter), property.field)
                                )
                            }
                        } else {
                            +writeParcelWith(declaration.classParceler, parcelParameter, flagsParameter, irGet(receiverParameter))
                        }
                    }
                }
            }
        }

        val creatorType = androidSymbols.androidOsParcelableCreator.typeWith(declaration.defaultType)

        // TODO: hasCreatorField
        declaration.addField {
            name = ParcelableExtensionBase.CREATOR_NAME
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
                    overriddenSymbols = listOf(androidSymbols.androidOsParcelableCreator.getSimpleFunction(name.asString())!!)
                    val sizeParameter = addValueParameter("size", context.irBuiltIns.intType)
                    body = context.createIrBuilder(symbol).run {
                        irExprBody(irCall(androidSymbols.irSymbols.arrayOfNulls, arrayType).apply {
                            putTypeArgument(0, arrayType)
                            putValueArgument(0, irGet(sizeParameter))
                        })
                    }
                }

                addFunction("createFromParcel", declaration.defaultType).apply {
                    overriddenSymbols = listOf(androidSymbols.androidOsParcelableCreator.getSimpleFunction(name.asString())!!)
                    val parcelParameter = addValueParameter("parcel", androidSymbols.androidOsParcel.defaultType)
                    body = androidSymbols.createBuilder(symbol).run {
                        irExprBody(if (parcelableProperties.isNotEmpty()) {
                            irCall(declaration.primaryConstructor!!).apply {
                                for ((index, property) in parcelableProperties.withIndex()) {
                                    putValueArgument(index, readParcelWith(property.parceler, parcelParameter))
                                }
                            }
                        } else {
                            readParcelWith(declaration.classParceler, parcelParameter)
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


    private data class ParcelableProperty(val field: IrField, val parceler: IrParcelSerializer2)

    private val IrClass.classParceler: IrParcelSerializer2
        get() = if (kind == ClassKind.CLASS) {
            NoParameterClassSerializer2(this)
        } else {
            serializerFactory.get(defaultType, strict = true)
        }

    private val IrClass.parcelableProperties: List<ParcelableProperty>
        get() {
            if (kind != ClassKind.CLASS) return emptyList()

            val constructor = primaryConstructor ?: return emptyList()

            return constructor.valueParameters.map { parameter ->
                val property = properties.first { it.name == parameter.name }
                ParcelableProperty(property.backingField!!, serializerFactory.get(parameter.type, strict = true))
            }
        }

    private val IrClass.isParcelize: Boolean
        get() = kind in ParcelableExtensionBase.ALLOWED_CLASS_KINDS && hasAnnotation(PARCELIZE_CLASS_FQNAME)

    private val IrType.containsFileDescriptors: Boolean
        get() =
            erasedUpperBound.fqNameWhenAvailable == ParcelableExtensionBase.FILE_DESCRIPTOR_FQNAME ||
                    // This is a heuristic which makes sense for container types.
                    (this as? IrSimpleType)?.arguments?.any { argument ->
                        argument.typeOrNull?.containsFileDescriptors == true
                    } == true
}
