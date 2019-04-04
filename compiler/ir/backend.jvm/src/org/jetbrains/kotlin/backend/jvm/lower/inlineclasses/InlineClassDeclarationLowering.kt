/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.inlineclasses

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irBlockBody
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class InlineClassDeclarationLowering(context: BackendContext) : ScopedValueMappingTransformer(context) {
    override fun visitClass(declaration: IrClass): IrStatement {
        // Calling super.visitClass would also transform the thisReceiver and
        // type parameters which is not what we want.
        declaration.parent = remapDeclaration(declaration.parent)

        scoped(declaration.symbol) {
            // The arguments to the primary constructor are in scope in the initializers of IrFields.
            declaration.constructors.singleOrNull { it.isPrimary }?.let { primaryConstructor ->
                InlineClassAbi.getReplacementFunction(primaryConstructor)?.let { replacement ->
                    addMappings(replacement.valueParameterMap)
                }
            }

            declaration.transformDeclarationsFlat { memberDeclaration ->
                when (memberDeclaration) {
                    is IrFunction ->
                        transformFunctionFlat(memberDeclaration)
                    else -> {
                        memberDeclaration.accept(this, null)
                        listOf(memberDeclaration)
                    }
                }
            }

            if (declaration.isInline) {
                val irConstructor = declaration.constructors.single { it.isPrimary }
                declaration.declarations.removeIf { it is IrConstructor && it.isPrimary || it is IrFunction && it.isInlineClassFieldGetter }
                buildPrimaryInlineClassConstructor(declaration, irConstructor)
                buildBoxFunction(declaration)
                buildUnboxFunction(declaration)
            }
        }

        return declaration
    }

    private fun transformFunctionFlat(function: IrFunction): List<IrDeclaration> {
        val replacement = InlineClassAbi.getReplacementFunction(function)
        if (replacement == null) {
            if (function is IrConstructor && function.isPrimary && function.constructedClass.isInline)
                return listOf(function)

            scoped(function.symbol) {
                function.transformChildrenVoid()
            }

            return listOf(function)
        }

        return when (function) {
            is IrSimpleFunction -> transformSimpleFunctionFlat(function, replacement)
            is IrConstructor -> transformConstructorFlat(function, replacement)
            else -> throw IllegalStateException()
        }
    }

    // All functions which mention a lower-able type get a corresponding worker.
    // Every function whose signature mentions a boxed type gets replaced with a wrapper
    // calling a static worker taking unboxed arguments and returning unboxed values.
    // Since the worker is put in the same scope as the wrapper it should have access
    // to private variables, etc.
    private fun transformSimpleFunctionFlat(function: IrSimpleFunction, replacement: IrReplacementFunction): List<IrDeclaration> {
        val worker = replacement.function
        scoped(worker.symbol) {
            addMappings(replacement.valueParameterMap)

            // Some functions access this through the class thisReceiver instead of through their
            // dispatchReceiverParameter. This is a workaround for this case - a similar workaround
            // exists in ExpressionCodegen.
            // TODO Figure out if there is a reason for this, or if it is merely a workaround for another bug.
            val dispatchReceiverParameter = function.dispatchReceiverParameter
            if (dispatchReceiverParameter != null) {
                function.parent.safeAs<IrClass>()?.thisReceiver?.let { thisReceiver ->
                    addMapping(thisReceiver.symbol, replacement.valueParameterMap.getValue(dispatchReceiverParameter.symbol))
                }
            }
            // Lower the initializers
            for (parameter in worker.valueParameters)
                parameter.transformChildrenVoid()
            worker.body = function.body?.transform(this, null)
        }

        // Don't create a wrapper for functions which are only used in an unboxed context
        if (function.overriddenSymbols.isEmpty()) // && function.origin != IrDeclarationOrigin.BRIDGE)
            return listOf(worker)

        // Replace the function body with a wrapper
        scoped(function.symbol) {
            builder.run {
                val fullParameterList =
                    listOfNotNull(function.dispatchReceiverParameter, function.extensionReceiverParameter) + function.valueParameters

                val call = irCall(worker).apply {
                    function.typeParameters.forEach { putTypeArgument(it.index, it.defaultType) }
                    for (parameter in fullParameterList) {
                        val newParameter = replacement.valueParameterMap[parameter.symbol] ?: continue
                        putArgument(newParameter, irGet(parameter))
                    }
                }

                function.body = irExprBody(call)
            }
        }

        return listOf(worker, function)
    }

    // Secondary constructors for boxed types get translated to static functions returning
    // unboxed arguments. We remove the original constructor.
    private fun transformConstructorFlat(constructor: IrConstructor, replacement: IrReplacementFunction): List<IrDeclaration> {
        val worker = replacement.function

        // Lower a secondary constructor on a boxed type
        scoped(worker.symbol) {
            addMappings(replacement.valueParameterMap)
            // Lower the initializers
            for (parameter in worker.valueParameters)
                parameter.transformChildrenVoid()

            worker.body = builder.irBlockBody(worker) {
                val thisVar = irTemporaryVarDeclaration(
                    worker.returnType, nameHint = "\$this", isMutable = false
                )
                addMapping(constructor.constructedClass.thisReceiver!!.symbol, thisVar)

                constructor.body?.contents?.forEach { statement ->
                    +statement
                        .transform(object : IrElementTransformerVoid() {
                            // Don't recurse under nested class declarations
                            override fun visitClass(declaration: IrClass): IrStatement {
                                return declaration
                            }

                            // Capture the result of a delegating constructor call in a temporary variable "thisVar".
                            //
                            // Within the constructor we replace references to "this" with references to "thisVar".
                            // This is safe, since the delegating constructor call precedes all references to "this".
                            override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
                                expression.transformChildrenVoid()
                                return irSetVar(thisVar.symbol, expression)
                            }

                            // A constructor body has type unit and may contain explicit return statements.
                            // These early returns may have side-effects however, so we still have to evaluate
                            // the return expression. Afterwards we return "thisVar".
                            // For example, the following is a valid inline class declaration.
                            //
                            //     inline class Foo(val x: String) {
                            //       constructor(y: Int) : this(y.toString()) {
                            //         if (y == 0) return throw java.lang.IllegalArgumentException()
                            //         if (y == 1) return
                            //         return Unit
                            //       }
                            //     }
                            override fun visitReturn(expression: IrReturn): IrExpression {
                                expression.transformChildrenVoid()
                                if (expression.returnTargetSymbol != constructor.symbol)
                                    return expression

                                return irReturn(irBlock(expression.startOffset, expression.endOffset) {
                                    +expression.value
                                    +irGet(thisVar)
                                })
                            }
                        }, null)
                        .transform(this@InlineClassDeclarationLowering, null)
                }

                +irReturn(irGet(thisVar))
            }
        }

        return listOf(worker)
    }

//    override fun visitGetField(expression: IrGetField): IrExpression {
//        val receiver = expression.receiver
//        if (receiver?.type?.isBoxed == true)
//            return receiver.transform(this, null)
//        return super.visitGetField(expression)
//    }

    private fun typedArgumentList(function: IrFunction, expression: IrMemberAccessExpression) =
        listOfNotNull(
            function.dispatchReceiverParameter?.let { it to expression.dispatchReceiver },
            function.extensionReceiverParameter?.let { it to expression.extensionReceiver }
        ) + function.valueParameters.map { it to expression.getValueArgument(it.index) }

    private fun IrMemberAccessExpression.buildReplacement(
        originalFunction: IrFunction,
        original: IrMemberAccessExpression,
        replacement: IrReplacementFunction
    ) {
        // Sometimes there are calls which pass type arguments to functions
        // without type parameters - the parameters in question are then
        // really parameters of the class...
        // TODO Fix this in irCall (constructor call to class with type parameters)
        if (this.typeArgumentsCount == original.typeArgumentsCount)
            copyTypeArgumentsFrom(original)

        for ((parameter, argument) in typedArgumentList(originalFunction, original)) {
            if (argument == null) continue
            val newParameter = replacement.valueParameterMap[parameter.symbol] ?: continue
            putArgument(
                replacement.function,
                newParameter,
                argument.transform(this@InlineClassDeclarationLowering, null)
            )
        }
    }

    private fun buildReplacementCall(
        originalFunction: IrFunction,
        original: IrFunctionAccessExpression,
        replacement: IrReplacementFunction
    ) = context.createIrBuilder(original.symbol)
        .irCall(replacement.function)
        .apply { buildReplacement(originalFunction, original, replacement) }

    override fun visitFunctionReference(expression: IrFunctionReference): IrExpression {
        val replacement = InlineClassAbi.getReplacementFunction(expression.symbol.owner)
            ?: return super.visitFunctionReference(expression)
        val function = replacement.function

        return IrFunctionReferenceImpl(
            expression.startOffset, expression.endOffset, expression.type,
            function.symbol, function.descriptor,
            function.typeParameters.size, function.valueParameters.size,
            expression.origin
        ).apply {
            buildReplacement(expression.symbol.owner, expression, replacement)
        }
    }

    override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
        val function = expression.symbol.owner
        val replacement = InlineClassAbi.getReplacementFunction(function)
            ?: return super.visitFunctionAccess(expression)
        return buildReplacementCall(function, expression, replacement)
    }

    // Usually, a delegating constructor call has to be mapped to another constructor,
    // but since we also remap constructors to static replacements we have to map
    // some of these to calls to static functions.
    override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
        val original = expression.symbol.owner

        val replacement = InlineClassAbi.getReplacementFunction(original)
            ?: return super.visitDelegatingConstructorCall(expression)

        return buildReplacementCall(original, expression, replacement)
    }

    override fun visitReturn(expression: IrReturn): IrExpression {
        expression.returnTargetSymbol.owner.safeAs<IrFunction>()?.let { target ->
            InlineClassAbi.getReplacementFunction(target)?.let {
                return context.createIrBuilder(it.function.symbol).irReturn(
                    expression.value.transform(this, null)
                )
            }
        }
        return super.visitReturn(expression)
    }

    // Recursively transform local declarations
    private fun visitStatementContainer(container: IrStatementContainer) {
        container.statements.transformFlat { statement ->
            if (statement is IrFunction)
                transformFunctionFlat(statement)
            else
                listOf(statement.transform(this, null))
        }
    }

    override fun visitContainerExpression(expression: IrContainerExpression): IrExpression {
        visitStatementContainer(expression)
        return expression
    }

    override fun visitBlockBody(body: IrBlockBody): IrBody {
        visitStatementContainer(body)
        return body
    }

    override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty): IrStatement {
        declaration.delegate.transformChildrenVoid()
        declaration.getter = transformFunctionFlat(declaration.getter)[0] as IrFunction
        declaration.setter?.let { setter ->
            declaration.setter = transformFunctionFlat(setter)[0] as IrFunction
        }
        return declaration
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        if (InlineClassAbi.getReplacementFunction(declaration) != null)
            error("UNTRANSFORMED FUNCTION ${declaration.dump()}")
        return declaration
    }

    // FIXME: ensure that this is not called in the bodies of original declarations
    private fun remapDeclaration(declaration: IrDeclarationParent) =
        InlineClassAbi.getReplacementDeclaration(declaration)?.function ?: declaration

    private fun buildPrimaryInlineClassConstructor(irClass: IrClass, irConstructor: IrConstructor) {
        irClass.declarations += buildConstructor {
            updateFrom(irConstructor)
            visibility = Visibilities.PRIVATE
            origin = IrDeclarationOrigin.SYNTHETIC_INLINE_CLASS_MEMBER
            returnType = irConstructor.returnType
        }.apply {
            parent = irClass
            // annotations += irConstructor.annotations
            valueParameters += irConstructor.valueParameters.map { p ->
                p.copyTo(this)
            }
            body = context.createIrBuilder(this.symbol).irBlockBody(this) {
                +irDelegatingConstructorCall(context.irBuiltIns.anyClass.owner.constructors.single())
                +irSetField(
                    irGet(irClass.thisReceiver!!),
                    getInlineClassBackingField(irClass),
                    irGet(this@apply.valueParameters[0])
                )
            }
        }
    }

    private fun buildBoxFunction(irClass: IrClass) {
        val function = InlineClassAbi.getBoxFunction(irClass)
        val builder = context.createIrBuilder(function.symbol)
        val constructor = irClass.constructors.find { it.isPrimary }!!

        function.body = builder.irBlockBody(function) {
            val valueToBox = function.valueParameters[0]
            +irReturn(irCall(constructor.symbol, IrStatementOrigin.EXPLICIT_INLINE_CLASS_CONSTRUCTOR).also {
                it.putValueArgument(0, irGet(valueToBox))
            })
        }

        irClass.declarations += function
    }

    private fun buildUnboxFunction(irClass: IrClass) {
        val function = InlineClassAbi.getUnboxFunction(irClass)
        val builder = context.createIrBuilder(function.symbol)
        val field = getInlineClassBackingField(irClass)

        function.body = builder.irBlockBody {
            val thisVal = irGet(function.dispatchReceiverParameter!!)
            +irReturn(irGetField(thisVal, field, IrStatementOrigin.EXPLICIT_INLINE_CLASS_CONSTRUCTOR))
        }

        irClass.declarations += function
    }
}

private val IrBody.contents: List<IrStatement>
    get() = when (this) {
        is IrBlockBody -> statements
        is IrExpressionBody -> listOf(expression)
        is IrSyntheticBody -> error("Synthetic body contains no statements $this")
        else -> throw IllegalStateException()
    }
