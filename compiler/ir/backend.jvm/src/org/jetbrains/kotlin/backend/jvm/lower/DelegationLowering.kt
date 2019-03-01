/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.deepCopyWithVariables
import org.jetbrains.kotlin.backend.common.descriptors.WrappedFieldDescriptor
import org.jetbrains.kotlin.backend.common.descriptors.synthesizedName
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.phaser.makeIrFilePhase
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

internal val propertyDelegationPhase = makeIrFilePhase(
    ::PropertyDelegationLowering,
    name = "PropertyDelegation",
    description = "Construct KProperty instances to be used when accessing delegated properties"
)

internal class PropertyDelegationLowering(val context: JvmBackendContext) : ClassLoweringPass {
    private object DECLARATION_ORIGIN_KPROPERTIES_FOR_DELEGATION :
        IrDeclarationOriginImpl("KPROPERTIES_FOR_DELEGATION")

    private val plainJavaClass =
        context.getIrClass(FqName("java.lang.Class")).owner

    private val reflectionClass =
        context.getIrClass(FqName("kotlin.jvm.internal.Reflection")).owner

    private val getOrCreateKotlinClass =
        reflectionClass.declarations.find { it.descriptor.name == Name.identifier("getOrCreateKotlinClass") }

    private val getOrCreateKotlinPackage =
        reflectionClass.declarations.find { it.descriptor.name == Name.identifier("getOrCreateKotlinPackage") }

    private fun propertyTypeAndFactory(prefix: String, i: Int) =
        context.getIrClass(FqName("kotlin.jvm.internal.${prefix.capitalize()}Reference${i}Impl")) to
                reflectionClass.declarations.find { it.descriptor.name == Name.identifier("${prefix}${i}") }

    private val properties =
        arrayOf(0, 1, 2).map { propertyTypeAndFactory("property", it) }

    private val mutableProperties =
        arrayOf(0, 1, 2).map { propertyTypeAndFactory("mutableProperty", it) }

    private val arrayItemGetter =
        context.ir.symbols.array.owner.functions.single { it.name == Name.identifier("get") }

    private val kPropertyType =
        context.reflectionTypes.kProperty.toIrType()

    private val kPropertiesFieldType =
        context.ir.symbols.array.createType(false, listOf(makeTypeProjection(kPropertyType, Variance.OUT_VARIANCE)))

    override fun lower(irClass: IrClass) {
        // Maintain an array of `KProperty` instances, one per delegated property, as the delegate target is allowed to use identity
        // comparison of these references for differentiating properties.
        val kProperties = mutableMapOf<CallableDescriptor, Pair<IrExpression, Int>>()
        // TODO: annotate as shared-immutable? The old backend does not do so.
        val kPropertiesField = WrappedFieldDescriptor(Annotations.create(listOf())).let { descriptor ->
            IrFieldImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                DECLARATION_ORIGIN_KPROPERTIES_FOR_DELEGATION,
                IrFieldSymbolImpl(descriptor),
                "\$delegatedProperties".synthesizedName,
                kPropertiesFieldType,
                Visibilities.PRIVATE,
                isFinal = true,
                isExternal = false,
                isStatic = true
            ).apply { descriptor.bind(this) }
        }

        irClass.transformChildrenVoid(object : IrElementTransformerVoidWithContext() {
            override fun visitPropertyReference(expression: IrPropertyReference): IrExpression =
                // This actually generates KProperties for all property references, not just delegated ones.
                cachedKProperty(expression, expression.getter!!, expression.setter != null)

            override fun visitLocalDelegatedPropertyReference(expression: IrLocalDelegatedPropertyReference): IrExpression =
                cachedKProperty(expression, expression.getter, expression.setter != null)

            override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty): IrStatement =
                // Even though we discard accessors here, they should still be transformed because they
                // are inlined by visitCall below, thus their contents *are* used.
                (super.visitLocalDelegatedProperty(declaration) as IrLocalDelegatedProperty).delegate

            private fun cachedKProperty(expression: IrMemberAccessExpression, getter: IrFunctionSymbol, mutable: Boolean): IrExpression {
                val (_, kPropertyIndex) = kProperties.getOrPut(expression.descriptor) {
                    createKProperty(expression, getter, mutable) to kProperties.size
                }
                return context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset).run {
                    irCall(arrayItemGetter).apply {
                        dispatchReceiver = irGetField(null, kPropertiesField)
                        putValueArgument(0, irInt(kPropertyIndex))
                    }
                }
            }

            private fun createKProperty(expression: IrMemberAccessExpression, getter: IrFunctionSymbol, mutable: Boolean): IrExpression {
                // `irClass` is the class in which the reference is located; we need the one in which the property is.
                // The distinction is irrelevant for delegated properties, the references to which are in the same class'
                // methods, but important for all other property references, which are located elsewhere.
                var container = getter.owner.parent
                while (container is IrFunction)
                    container = container.parent
                if (container !is IrClass)
                    // Package-level properties are in an implicit class corresponding to the entire file.
                    throw AssertionError("reference to a property of what?")

                val boundReceivers = listOf(expression.dispatchReceiver, expression.extensionReceiver).count { it != null }
                val needReceivers =
                    listOf(getter.owner.dispatchReceiverParameter, getter.owner.extensionReceiverParameter).count { it != null }
                val (propertyReference, factoryMethod) = if (mutable)
                    mutableProperties[needReceivers - boundReceivers]
                else
                    properties[needReceivers - boundReceivers]
                val typeMapper = this@PropertyDelegationLowering.context.state.typeMapper
                return context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset).run {
                    val javaClassRef = IrClassReferenceImpl(
                        expression.startOffset,
                        expression.endOffset,
                        plainJavaClass.defaultType,
                        plainJavaClass.symbol,
                        // This hack is used here and in CallableReferenceLowering to obtain a a plain Java class. TODO: remove.
                        CrIrType(typeMapper.mapType(container.defaultType.toKotlinType()))
                    )
                    val kotlinClassRef = if (container.origin == IrDeclarationOrigin.FILE_CLASS)
                        irCall(getOrCreateKotlinPackage as IrFunction).apply {
                            putValueArgument(0, javaClassRef)
                            putValueArgument(1, irString(this@PropertyDelegationLowering.context.state.moduleName))
                        }
                    else
                        irCall(getOrCreateKotlinClass as IrFunction).apply {
                            putValueArgument(0, javaClassRef)
                        }
                    irCall(factoryMethod as IrFunction).apply {
                        putValueArgument(0, irCall(propertyReference.constructors.single().owner).apply {
                            putValueArgument(0, kotlinClassRef)
                            putValueArgument(1, irString(expression.descriptor.name.asString()))
                            putValueArgument(2, irString(typeMapper.mapSignatureSkipGeneric(getter.owner.descriptor).toString()))
                        })
                    }
                }
            }
        })

        if (kProperties.isNotEmpty()) {
            val initializers = kProperties.values.sortedBy { it.second }.map { it.first }
            // Note the above comment above non-delegate properties -- strictly speaking, for non-delegate property references this
            // will attach the array to the class in which the property is referenced, not the one in which it is declared.
            // However, unlike delegate properties, there seem to be no identity constraints in that case, as the non-IR
            // backend generates a separate KProperty instance for each reference, so this is fine.
            irClass.declarations.add(0, kPropertiesField.apply {
                parent = irClass
                initializer = IrExpressionBodyImpl(startOffset, endOffset, IrCallImpl(
                    startOffset,
                    endOffset,
                    type,
                    context.ir.symbols.arrayOf,
                    context.ir.symbols.arrayOf.descriptor,
                    0
                ).apply {
                    putValueArgument(0, IrVarargImpl(startOffset, endOffset, kPropertiesFieldType, kPropertyType, initializers))
                })
            })
        }

        // Can't define functions within functions in the IR (ExpressionCodegen does not implement a visitor method for functions),
        // so force-inline local property accessors. Basically, replace `local` with `local$delegate.getValue(null, kProperty)`
        // and `local = value` with `local$delegate.setValue(null, kProperty, value)`, where `null` stands for the object
        // on which the property is defined (locals have none). This is a separate pass because KProperties need to exist already.
        //
        // TODO: lambdas do not close over delegated locals (neither the variables nor the backing objects), so this does not work
        //       in them. Probably need to change psi2ir.
        irClass.transformChildrenVoid(object : IrElementTransformerVoidWithContext() {
            override fun visitCall(expression: IrCall): IrExpression {
                val function = expression.symbol.owner
                if (function.origin != IrDeclarationOrigin.DELEGATED_PROPERTY_ACCESSOR)
                    return super.visitCall(expression)
                if (expression.dispatchReceiver != null || expression.extensionReceiver != null)
                    // An object's delegated property; accessors are in a class, no need to inline.
                    return super.visitCall(expression)

                val block = IrBlockImpl(
                    function.startOffset, function.endOffset, function.returnType, expression.origin, listOf(
                        when (val functionBody = function.body) {
                            // This could be done more generally with IrReturnableBlock, but it is not supported by JVM codegen
                            // at the moment, so assume the accessors (generated by psi2ir) consist of a single return.
                            is IrBlockBody -> (functionBody.statements.single() as IrReturn).value.deepCopyWithVariables()
                            is IrExpressionBody -> functionBody.expression
                            else -> throw AssertionError("delegated property accessor body neither a block nor an expression")
                        }
                    )
                )

                function.valueParameters.singleOrNull()?.let {
                    // This is a setter, need to remap its value argument. The value should actually only be used once
                    // (as an argument to setValue) so storing it in a temporary variable might be a bit wasteful,
                    // but let's play safe here.
                    val newValue = currentScope!!.scope.createTemporaryVariable(expression.getValueArgument(0)!!)
                    block.statements.add(0, newValue)
                    block.transformChildrenVoid(object : IrElementTransformerVoid() {
                        override fun visitGetValue(expression: IrGetValue): IrExpression = super.visitGetValue(
                            if (expression.symbol == it.symbol)
                                IrGetValueImpl(expression.startOffset, expression.endOffset, expression.type, newValue.symbol)
                            else
                                expression
                        )
                    })
                }
                return block
            }

        })
    }
}
