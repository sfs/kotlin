/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.util

import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.utils.DFS
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.lang.IllegalStateException
import java.util.*

/**
 * Check that the IR is well-formed:
 * - Symbols are unique and bound
 * - Declaration parents are correct
 * - Types are correct, in particular, all implicit coercions are present in the IR
 */
fun verify(element: IrElement, builtIns: IrBuiltIns) {
    val errors = mutableListOf<String>()
    errors += IrSymbolVerifier().verify(element)
    errors += IrDeclarationParentsVerifier().verify(element)
//    errors += IrTypeChecker(builtIns).verify(element)
    if (errors.isNotEmpty()) {
        val errorMessage = errors.joinToString(prefix = "IR verifier errors:\n", separator = "\n")
        error(errorMessage)
    }
}

abstract class IrVerificationVisitor : IrElementVisitorVoid {
    protected val errors = mutableListOf<String>()

    val hasErrors get() = errors.isNotEmpty()

    protected fun error(message: String) {
        errors.add(message)
    }

    protected inline fun require(condition: Boolean, message: () -> String) {
        if (!condition) {
            errors.add(message())
        }
    }

    fun verify(element: IrElement): List<String> {
        element.accept(this, null)
        return errors
    }
}

/**
 * Visitor which checks that all symbols are bound and unique.
 */
class IrSymbolVerifier : IrVerificationVisitor() {
    private val symbolForDeclaration = HashMap<IrElement, IrSymbol>()


    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitDeclaration(declaration: IrDeclaration) {
        if (declaration is IrSymbolOwner) {
            declaration.symbol.checkBinding("decl", declaration)

            require(declaration.symbol.owner == declaration) {
                "Symbol is not bound to declaration: ${declaration.render()}"
            }
        }

        val containingDeclarationDescriptor = declaration.descriptor.containingDeclaration
        if (containingDeclarationDescriptor != null) {
            val parent = declaration.parent
            if (parent is IrDeclaration) {
                require(parent.descriptor == containingDeclarationDescriptor) {
                    "In declaration ${declaration.descriptor}: " +
                            "Mismatching parent descriptor (${parent.descriptor}) " +
                            "and containing declaration descriptor ($containingDeclarationDescriptor)"
                }
            }
        }
    }

    override fun visitFunction(declaration: IrFunction) {
        visitDeclaration(declaration)

        val functionDescriptor = declaration.descriptor

        checkTypeParameters(functionDescriptor, declaration, functionDescriptor.typeParameters)

        val expectedDispatchReceiver = functionDescriptor.dispatchReceiverParameter
        val actualDispatchReceiver = declaration.dispatchReceiverParameter?.descriptor
        require(expectedDispatchReceiver == actualDispatchReceiver) {
            "$functionDescriptor: Dispatch receiver parameter mismatch: " +
                    "expected $expectedDispatchReceiver, actual $actualDispatchReceiver"

        }

        val expectedExtensionReceiver = functionDescriptor.extensionReceiverParameter
        val actualExtensionReceiver = declaration.extensionReceiverParameter?.descriptor
        require(expectedExtensionReceiver == actualExtensionReceiver) {
            "$functionDescriptor: Extension receiver parameter mismatch: " +
                    "expected $expectedExtensionReceiver, actual $actualExtensionReceiver"
        }

        val declaredValueParameters = declaration.valueParameters.map { it.descriptor }
        val actualValueParameters = functionDescriptor.valueParameters
        if (declaredValueParameters.size != actualValueParameters.size) {
            error("$functionDescriptor: Value parameters mismatch: $declaredValueParameters != $actualValueParameters")
        } else {
            declaredValueParameters.zip(actualValueParameters).forEach { (declaredValueParameter, actualValueParameter) ->
                require(declaredValueParameter == actualValueParameter) {
                    "$functionDescriptor: Value parameters mismatch: $declaredValueParameter != $actualValueParameter"
                }
            }
        }
    }

    override fun visitDeclarationReference(expression: IrDeclarationReference) {
        expression.symbol.checkBinding("ref", expression)
    }

    override fun visitFunctionReference(expression: IrFunctionReference) {
        expression.symbol.checkBinding("ref", expression)
    }

    override fun visitPropertyReference(expression: IrPropertyReference) {
        expression.field?.checkBinding("field", expression)
        expression.getter?.checkBinding("getter", expression)
        expression.setter?.checkBinding("setter", expression)
    }

    override fun visitLocalDelegatedPropertyReference(expression: IrLocalDelegatedPropertyReference) {
        expression.delegate.checkBinding("delegate", expression)
        expression.getter.checkBinding("getter", expression)
        expression.setter?.checkBinding("setter", expression)
    }

    private fun IrSymbol.checkBinding(kind: String, irElement: IrElement) {
        if (!isBound) {
            error("${javaClass.simpleName} $descriptor is unbound @$kind ${irElement.render()}")
        } else {
            val irDeclaration = owner as? IrDeclaration
            if (irDeclaration != null) {
                try {
                    irDeclaration.parent
                } catch (e: Throwable) {
                    error("Referenced declaration has no parent: ${irDeclaration.render()}")
                }
            }
        }

        val otherSymbol = symbolForDeclaration.getOrPut(owner) { this }
        if (this != otherSymbol) {
            error("Multiple symbols for $descriptor @$kind ${irElement.render()}")
        }
    }

    override fun visitClass(declaration: IrClass) {
        visitDeclaration(declaration)

        checkTypeParameters(declaration.descriptor, declaration, declaration.descriptor.declaredTypeParameters)
    }

    private fun checkTypeParameters(
        descriptor: DeclarationDescriptor,
        declaration: IrTypeParametersContainer,
        expectedTypeParameters: List<TypeParameterDescriptor>
    ) {
        val declaredTypeParameters = declaration.typeParameters.map { it.descriptor }

        if (declaredTypeParameters.size != expectedTypeParameters.size) {
            error("$descriptor: Type parameters mismatch: $declaredTypeParameters != $expectedTypeParameters")
        } else {
            declaredTypeParameters.zip(expectedTypeParameters).forEach { (declaredTypeParameter, expectedTypeParameter) ->
                require(declaredTypeParameter == expectedTypeParameter) {
                    "$descriptor: Type parameters mismatch: $declaredTypeParameter != $expectedTypeParameter"
                }
            }
        }
    }

    override fun visitTypeOperator(expression: IrTypeOperatorCall) {
        expression.typeOperandClassifier.checkBinding("type operand", expression)
    }
}

/**
 * Visitor which checks that all IR declarations have correct parent fields and that
 * all correspondingPropertySymbols are correctly set.
 */
class IrDeclarationParentsVerifier : IrVerificationVisitor() {
    private val declarationParentsStack = ArrayDeque<IrDeclarationParent>()

    private fun IrDeclarationParent.render() = when (this) {
        is IrElement -> (this as IrElement).render()
        else -> this.toString()
    }

    private fun withParent(declaration: IrElement, body: () -> Unit) {
        if (declaration !is IrDeclarationParent) return body()
        declarationParentsStack.push(declaration)
        try {
            body()
        } finally {
            declarationParentsStack.pop()
        }
    }

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitPackageFragment(declaration: IrPackageFragment) {
        withParent(declaration) { super.visitPackageFragment(declaration) }
    }

    override fun visitDeclaration(declaration: IrDeclaration) {
        val expectedParent = declarationParentsStack.peek()
        val actualParent = try {
            declaration.parent
        } catch (e: UninitializedPropertyAccessException) {
            error("${declaration.name()}: Parent uninitialized, expected ${expectedParent.render()}")
            return
        }
        require(expectedParent == null || actualParent == expectedParent ||
                        declaration is IrField && declaration.isStatic) {
            "${declaration.render()}: Parent mismatch: ${actualParent.render()} != ${expectedParent.render()}"
        }
        withParent(declaration) { super.visitDeclaration(declaration) }
    }

    override fun visitProperty(declaration: IrProperty) {
        fun check(element: IrDeclaration, propertySymbol: IrPropertySymbol?) =
            require(propertySymbol == declaration.symbol) {
                "${element.render()}: Corresponding property mismatch: ${propertySymbol?.descriptor} != ${declaration.descriptor}"
            }

        declaration.getter?.let { check(it, it.correspondingPropertySymbol) }
        declaration.setter?.let { check(it, it.correspondingPropertySymbol) }
        declaration.backingField?.let { check(it, it.correspondingPropertySymbol) }
        super.visitProperty(declaration)
    }
}

/**
 * Visitor which checks that all IrTypes are correctly assigned.
 */
class IrTypeChecker(private val builtIns: IrBuiltIns) : IrVerificationVisitor() {

    private val IrTypeArgument.lowerBound: IrType
        get() = when (this) {
            is IrStarProjection -> builtIns.nothingType
            is IrTypeProjection ->
                if (this.variance == Variance.OUT_VARIANCE)
                    builtIns.nothingType
                else
                    this.type
            else -> throw IllegalStateException("Unexpected type argument: $this")
        }

    private val IrTypeArgument.upperBound: IrType
        get() = when (this) {
            is IrStarProjection -> builtIns.anyNType
            is IrTypeProjection ->
                if (this.variance == Variance.IN_VARIANCE)
                    builtIns.anyNType
                else
                    this.type
            else -> throw IllegalStateException("Unexpected type argument: $this")
        }

    private fun IrTypeArgument.isSubtypeOf(other: IrTypeArgument) =
        other.lowerBound.isSubtypeOf(lowerBound) && upperBound.isSubtypeOf(other.upperBound)

    private fun IrType.isSubtypeOf(other: IrType): Boolean {
        if (this is IrDynamicType && other is IrDynamicType) return true
        if (this is IrErrorType || other is IrErrorType) return false
        if (this === other) return true

        if (isNothing()) return true
        if (isNullableNothing()) return other.isNullable()
        if (other.isNullableAny()) return true
        if (other.isAny()) return !isNullable()

        if (this is IrSimpleType && other is IrSimpleType) {
            if (this.hasQuestionMark && !other.hasQuestionMark)
                return false
            if (FqNameEqualityChecker.areEqual(this.classifier, other.classifier) &&
                this.arguments.zip(other.arguments).all { (ths, tht) -> ths.isSubtypeOf(tht) })
                return true
            return classifier.superTypes().any { it.isSubtypeOf(other) }
        }
        return false
    }

    private fun check(expression: IrExpression, type: IrType) {
        require(expression.type.isSubtypeOf(type)) {
            "Type mismatch: ${expression.type.render()} !<: ${type.render()} in ${expression.render()}"
        }
    }

    private fun IrType.typeParameterSuperTypes(): List<IrType> {
        val classifier = classifierOrNull ?: return emptyList()
        return when (classifier) {
            is IrTypeParameterSymbol -> classifier.owner.superTypes
            is IrClassSymbol -> emptyList()
            else -> throw IllegalStateException()
        }
    }

    private fun IrType.isNullable(): Boolean = DFS.ifAny(listOf(this), { it.typeParameterSuperTypes() }, {
        when (it) {
            is IrSimpleType -> it.hasQuestionMark
            else -> it is IrDynamicType
        }
    })

    private fun checkNonNull(expression: IrExpression) {
        require(!expression.type.isNullable()) {
            "Type should be non-null: ${expression.type.toKotlinType()} in ${expression.render()}"
        }
    }

    private fun checkClass(expression: IrExpression, irClass: IrClass) {
        val parentSymbol = expression.type.classOrNull
        if (parentSymbol == null) {
            error("Expression should have a class type ${(irClass as IrElement).render()} in ${expression.render()}")
        }
        require(parentSymbol?.owner?.isSubclassOf(irClass) == true) {
            "Expression should be an instance of ${(irClass as IrElement).render()} not ${(parentSymbol?.owner as IrElement).render()} in ${expression.render()}"
        }
    }

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    private fun resolveTypeArgument(argument: IrTypeArgument): IrType =
        when (argument) {
            is IrStarProjection -> builtIns.anyNType
            is IrTypeProjection -> argument.type
            else -> throw IllegalStateException("Unexpected type argument $argument")
        }

    private fun resolveType(context: IrType?, type: IrType) = when (type) {
        is IrErrorType -> type
        is IrDynamicType -> type
        is IrSimpleType -> {
            val classifier = type.classifier
            when (classifier) {
                is IrClassSymbol -> type
                is IrTypeParameterSymbol -> context?.safeAs<IrSimpleType>()?.let { context ->
                    val argument = resolveTypeArgument(context.arguments[classifier.owner.index])
                    if (type.isNullable())
                        argument.makeNullable()
                    else
                        argument
                } ?: throw IllegalStateException("IrTypeParameter in an empty context $classifier.")
                else -> throw IllegalStateException("Unexpected classifier symbol $classifier")
            }
        }
        else -> throw IllegalStateException("Unexpected IrType $type")
    }

    private val IrReturn.returnType: IrType
        get() {
            val target = returnTargetSymbol
            return when (target) {
                is IrSimpleFunctionSymbol -> target.owner.returnType
                is IrConstructorSymbol ->  builtIns.unitType
                is IrReturnableBlockSymbol -> target.owner.type
                else -> throw IllegalStateException("Unknown return target ${target.owner.dump()}")
            }
        }

    override fun visitReturn(expression: IrReturn) {
        check(expression.value, expression.returnType)
        super.visitReturn(expression)
    }

    override fun visitGetField(expression: IrGetField) {
        val field = expression.symbol.owner
        check(expression, resolveType(expression.receiver?.type, field.type))
        expression.receiver?.let { receiver ->
            checkNonNull(receiver)
            checkClass(receiver, field.parentAsClass)
        }
        super.visitGetField(expression)
    }
}
