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
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name

class ParcelableIrTransformer(private val context: CommonBackendContext, private val androidSymbols: AndroidSymbols) :
    ParcelableExtensionBase, IrElementTransformerVoidWithContext() {
    private val serializerFactory = IrParcelSerializerFactory(context, androidSymbols)

    // TODO: The CREATOR field and writeToParcel functions have to be lazily generated so that we can call them on
    //       other types in the current module.
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
        get() = kind in ParcelableExtensionBase.ALLOWED_CLASS_KINDS && hasAnnotation(PARCELIZE_CLASS_FQNAME)

    // TODO: Upper bounds of type parameters! (T : FileDescriptor)
    private val IrType.containsFileDescriptors: Boolean
        get() =
            classOrNull?.owner?.fqNameWhenAvailable == ParcelableExtensionBase.FILE_DESCRIPTOR_FQNAME ||
                    (this as? IrSimpleType)?.arguments?.any { argument ->
                        argument.typeOrNull?.containsFileDescriptors == true
                    } == true
}
