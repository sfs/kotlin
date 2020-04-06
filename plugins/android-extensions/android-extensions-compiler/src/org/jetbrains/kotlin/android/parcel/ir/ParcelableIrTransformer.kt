/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel.ir

import org.jetbrains.kotlin.android.parcel.ANDROID_PARCELABLE_CLASS_FQNAME
import org.jetbrains.kotlin.android.parcel.PARCELER_FQNAME
import org.jetbrains.kotlin.android.parcel.ParcelableAnnotationChecker.Companion.TYPE_PARCELER_FQNAME
import org.jetbrains.kotlin.android.parcel.serializers.ParcelableExtensionBase
import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class ParcelableIrTransformer(private val context: CommonBackendContext, private val androidSymbols: AndroidSymbols) :
    ParcelableExtensionBase, IrElementVisitorVoid {
    private val serializerFactory = IrParcelSerializerFactory(context.irBuiltIns, androidSymbols)

    private val deferredOperations = mutableListOf<() -> Unit>()
    private fun defer(block: () -> Unit) = deferredOperations.add(block)

    fun transform(moduleFragment: IrModuleFragment) {
        moduleFragment.accept(this, null)
        deferredOperations.forEach { it() }
    }

    override fun visitElement(element: IrElement) = element.acceptChildren(this, null)

    override fun visitClass(declaration: IrClass) {
        declaration.acceptChildren(this, null)
        if (!declaration.isParcelize)
            return

        val parcelableSuperclasses: List<IrClassSymbol> = declaration.superTypes.map { it.classOrNull!! }.filter { superClass ->
            superClass.owner.isSubclassOfFqName(ANDROID_PARCELABLE_CLASS_FQNAME.asString())
        }

        val parcelableProperties = declaration.parcelableProperties

        // If the companion extends Parceler, it can override parts of the generated implementation.
        val parcelerObject = declaration.companionObject()?.safeAs<IrClass>()?.takeIf {
            it.isSubclassOfFqName(PARCELER_FQNAME.asString())
        }

        if (declaration.descriptor.hasSyntheticDescribeContents()) {
            declaration.addFunction("describeContents", context.irBuiltIns.intType, modality = Modality.OPEN).apply {
                overriddenSymbols = parcelableSuperclasses.mapNotNull { it.getSimpleFunction(name.asString()) }
                val flags = if (parcelableProperties.any { it.field.type.containsFileDescriptors }) 1 else 0
                body = context.createIrBuilder(symbol).run {
                    irExprBody(irInt(flags))
                }
            }
        }

        if (declaration.descriptor.hasSyntheticWriteToParcel()) {
            declaration.addFunction("writeToParcel", context.irBuiltIns.unitType, modality = Modality.OPEN).apply {
                overriddenSymbols = parcelableSuperclasses.mapNotNull { it.getSimpleFunction(name.asString()) }
                val receiverParameter = dispatchReceiverParameter!!
                val parcelParameter = addValueParameter("out", androidSymbols.androidOsParcel.defaultType)
                val flagsParameter = addValueParameter("flags", context.irBuiltIns.intType)

                // We need to defer the construction of the writer, since it may refer to the [writeToParcel] methods in other
                // @Parcelize classes in the current module, which might not be constructed yet at this point.
                defer {
                    body = androidSymbols.createBuilder(symbol).run {
                        irBlockBody {
                            when {
                                parcelerObject != null ->
                                    +parcelerWrite(parcelerObject, parcelParameter, flagsParameter, irGet(receiverParameter))

                                parcelableProperties.isNotEmpty() ->
                                    for (property in parcelableProperties) {
                                        +writeParcelWith(
                                            property.parceler,
                                            parcelParameter,
                                            flagsParameter,
                                            irGetField(irGet(receiverParameter), property.field)
                                        )
                                    }

                                else ->
                                    +writeParcelWith(declaration.classParceler, parcelParameter, flagsParameter, irGet(receiverParameter))
                            }
                        }
                    }
                }
            }
        }

        val creatorType = androidSymbols.androidOsParcelableCreator.typeWith(declaration.defaultType)

        if (!declaration.descriptor.hasCreatorField()) {
            declaration.addField {
                name = ParcelableExtensionBase.CREATOR_NAME
                type = creatorType
                isStatic = true
                isFinal = true
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
                            irExprBody(
                                parcelerNewArray(parcelerObject, sizeParameter)
                                    ?: irCall(androidSymbols.irSymbols.arrayOfNulls, arrayType).apply {
                                        putTypeArgument(0, arrayType)
                                        putValueArgument(0, irGet(sizeParameter))
                                    }
                            )
                        }
                    }

                    addFunction("createFromParcel", declaration.defaultType).apply {
                        overriddenSymbols = listOf(androidSymbols.androidOsParcelableCreator.getSimpleFunction(name.asString())!!)
                        val parcelParameter = addValueParameter("parcel", androidSymbols.androidOsParcel.defaultType)

                        // We need to defer the construction of the create method, since it may refer to the [Parcelable.Creator]
                        // instances in other @Parcelize classes in the current module, which may not exist yet.
                        defer {
                            body = androidSymbols.createBuilder(symbol).run {
                                irExprBody(
                                    when {
                                        parcelerObject != null ->
                                            parcelerCreate(parcelerObject, parcelParameter)

                                        parcelableProperties.isNotEmpty() ->
                                            irCall(declaration.primaryConstructor!!).apply {
                                                for ((index, property) in parcelableProperties.withIndex()) {
                                                    putValueArgument(index, readParcelWith(property.parceler, parcelParameter))
                                                }
                                            }

                                        else ->
                                            readParcelWith(declaration.classParceler, parcelParameter)
                                    }
                                )
                            }
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
        }
    }

    private data class ParcelableProperty(val field: IrField, val parceler: IrParcelSerializer)

    private val IrClass.classParceler: IrParcelSerializer
        get() = if (kind == ClassKind.CLASS) {
            NoParameterClassSerializer(this)
        } else {
            serializerFactory.get(defaultType, parcelizeType = defaultType, strict = true, toplevel = true, scope = getParcelerScope())
        }

    private val IrClass.parcelableProperties: List<ParcelableProperty>
        get() {
            if (kind != ClassKind.CLASS) return emptyList()

            val constructor = primaryConstructor ?: return emptyList()
            val toplevelScope = getParcelerScope()

            return constructor.valueParameters.map { parameter ->
                val property = properties.first { it.name == parameter.name }
                val localScope = property.getParcelerScope(toplevelScope)
                val parceler = serializerFactory.get(parameter.type, parcelizeType = defaultType, strict = true, scope = localScope)
                ParcelableProperty(property.backingField!!, parceler)
            }
        }

    private val IrType.containsFileDescriptors: Boolean
        get() = erasedUpperBound.fqNameWhenAvailable == ParcelableExtensionBase.FILE_DESCRIPTOR_FQNAME ||
                // This is a heuristic which makes sense for container types.
                (this as? IrSimpleType)?.arguments?.any { argument ->
                    argument.typeOrNull?.containsFileDescriptors == true
                } == true

    private fun IrAnnotationContainer.getParcelerScope(parent: ParcelerScope? = null): ParcelerScope? {
        val typeParcelerAnnotations = annotations.filterTo(mutableListOf()) {
            it.symbol.owner.constructedClass.fqNameWhenAvailable == TYPE_PARCELER_FQNAME
        }

        if (typeParcelerAnnotations.isEmpty())
            return parent

        val scope = ParcelerScope(parent)

        for (anno in typeParcelerAnnotations) {
            val (mappedType, parcelerType) = (anno.type as IrSimpleType).arguments.map { it.typeOrNull!! }
            scope.add(mappedType, parcelerType.getClass()!!)
        }

        return scope
    }
}
