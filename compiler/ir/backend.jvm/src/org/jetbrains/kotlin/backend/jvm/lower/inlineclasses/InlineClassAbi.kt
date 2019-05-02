/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.inlineclasses

import org.jetbrains.kotlin.backend.common.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.backend.common.descriptors.WrappedValueParameterDescriptor
import org.jetbrains.kotlin.backend.common.ir.*
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.utils.DFS
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.security.MessageDigest
import java.util.*

class IrReplacementFunction(
    val function: IrFunction,
    val valueParameterMap: Map<IrValueParameterSymbol, IrValueParameter>
)

/**
 * Replace inline classes by their underlying types.
 */
fun IrType.unbox() = InlineClassAbi.unboxType(this) ?: this

/**
 * Check if the type can be unboxed.
 */
val IrType.isBoxed: Boolean
    get() = InlineClassAbi.unboxType(this) != null

/**
 * Check if a function can be replaced.
 */
val IrFunction.isBoxed: Boolean
    get() = fullParameterList.any { it.type.isBoxed } || (this is IrConstructor && constructedClass.isInline)

object InlineClassAbi {
    private val storageManager = LockBasedStorageManager("inline-class-abi")

    /**
     * Get a replacement for a function or a constructor.
     */
    val getReplacementFunction: (IrFunction) -> IrReplacementFunction? =
        storageManager.createMemoizedFunctionWithNullableValues {
            when {
                !it.isBoxed || it.isSyntheticInlineClassMember -> null
                it.hasMethodReplacement -> createMethodReplacement(it)
                it.hasStaticReplacement -> createStaticReplacement(it)
                else -> null
            }
        }

    /**
     * Get a replacement for a declaration.
     */
    fun getReplacementDeclaration(declaration: IrDeclarationParent) =
        declaration.safeAs<IrFunction>()?.let { getReplacementFunction(it) }

    /**
     * Get the box function for an inline class. Concretely, this is a synthetic
     * static function named "box-impl" which takes an unboxed value and returns
     * a boxed value.
     */
    val getBoxFunction: (IrClass) -> IrSimpleFunction =
        storageManager.createMemoizedFunction { irClass ->
            require(irClass.isInline)
            createBoxFunction(irClass)
        }

    /**
     * Get the unbox function for an inline class. Concretely, this is a synthetic
     * member function named "unbox-impl" which returns an unboxed result.
     */
    val getUnboxFunction: (IrClass) -> IrSimpleFunction =
        storageManager.createMemoizedFunction { irClass ->
            require(irClass.isInline)
            createUnboxFunction(irClass)
        }

    internal fun unboxType(type: IrType): IrType? {
        val klass = type.classOrNull?.owner ?: return null
        if (!klass.isInline) return null

        // We deviate slightly from the spec by unfolding the underlying type eagerly.
        // Non-nullable IC types are always unfolded. Nullable IC types are only unfolded
        // if the underlying type isn't nullable or primitive. If the underlying type
        // is a non-nullable non-primitive type we unfold to its nullable version.
        // At least on the JVM this avoids boxing, since every such type is nullable.
        //
        // This implementation differs from the spec in some corner cases, e.g.,
        // what happens if we have a nullable IC type containing a non-nullable IC field?
        //
        //     inline class A(val x: String)
        //     inline class B(val y: A)
        //
        // We reduce B? to String?, but according to the spec it is reduced to A?
        val underlyingType = getUnderlyingType(klass)
        if (!type.isNullable())
            return underlyingType
        if (underlyingType.isNullable() || underlyingType.isPrimitiveType())
            return null
        return underlyingType.makeNullable()
    }

    // Get the underlying type of an inline class based on the single argument to its
    // primary constructor. This is what the current jvm backend does.
    //
    // Looking for a backing field does not work for built-in inline classes (UInt, etc.),
    // which don't contain a field.
    private val getUnderlyingType: (IrClass) -> IrType =
        storageManager.createMemoizedFunction { irClass ->
            val primaryConstructor = irClass.constructors.single { it.isPrimary }
            primaryConstructor.valueParameters[0].type.unbox()
        }

    private fun createBoxFunction(inlinedClass: IrClass): IrSimpleFunction {
        val unboxedType = getUnderlyingType(inlinedClass)
        val boxedType = inlinedClass.defaultType

        val startOffset = inlinedClass.startOffset
        val endOffset = inlinedClass.endOffset

        val descriptor = WrappedSimpleFunctionDescriptor()
        val symbol = IrSimpleFunctionSymbolImpl(descriptor)
        return IrFunctionImpl(
            startOffset, endOffset, IrDeclarationOrigin.SYNTHETIC_INLINE_CLASS_MEMBER,
            symbol, Name.identifier("box-impl"), Visibilities.PUBLIC,
            Modality.FINAL, boxedType, isInline = false, isExternal = false,
            isTailrec = false, isSuspend = false
        ).also { function ->
            function.copyTypeParametersFrom(inlinedClass)
            function.valueParameters.add(WrappedValueParameterDescriptor().let {
                IrValueParameterImpl(
                    startOffset, endOffset,
                    IrDeclarationOrigin.DEFINED,
                    IrValueParameterSymbolImpl(it),
                    Name.identifier("value"),
                    index = 0,
                    varargElementType = null,
                    isCrossinline = false,
                    type = unboxedType,
                    isNoinline = false
                ).apply {
                    it.bind(this)
                    parent = function
                }
            })
            descriptor.bind(function)
            function.parent = inlinedClass
        }
    }

    private fun createUnboxFunction(inlinedClass: IrClass): IrSimpleFunction {
        //val unboxedType = getInlineClassBackingField(inlinedClass).type.unbox()
        val unboxedType = getUnderlyingType(inlinedClass)
        val boxedType = inlinedClass.defaultType

        val startOffset = inlinedClass.startOffset
        val endOffset = inlinedClass.endOffset

        val descriptor = WrappedSimpleFunctionDescriptor()
        val symbol = IrSimpleFunctionSymbolImpl(descriptor)
        return IrFunctionImpl(
            startOffset, endOffset, IrDeclarationOrigin.SYNTHETIC_INLINE_CLASS_MEMBER,
            symbol, Name.identifier("unbox-impl"), Visibilities.PUBLIC,
            Modality.FINAL, unboxedType, isInline = false, isExternal = false,
            isTailrec = false, isSuspend = false
        ).also { function ->
            function.dispatchReceiverParameter =
                WrappedValueParameterDescriptor().let {
                    IrValueParameterImpl(
                        startOffset, endOffset,
                        IrDeclarationOrigin.DEFINED,
                        IrValueParameterSymbolImpl(it),
                        Name.identifier("value"),
                        index = 0,
                        varargElementType = null,
                        isCrossinline = false,
                        type = boxedType,
                        isNoinline = false
                    ).apply {
                        it.bind(this)
                        parent = function
                    }
                }
            descriptor.bind(function)
            function.parent = inlinedClass
        }
    }

    private fun createMethodReplacement(function: IrFunction): IrReplacementFunction? {
        assert(function.dispatchReceiverParameter != null)

        val descriptor = WrappedSimpleFunctionDescriptor(
            function.descriptor.annotations,
            function.descriptor.source
        )
        val symbol = IrSimpleFunctionSymbolImpl(descriptor)
        val parameterMap = mutableMapOf<IrValueParameterSymbol, IrValueParameter>()

        val replacement = IrFunctionImpl(
            function.startOffset, function.endOffset, function.origin,
            symbol, mangledNameFor(function), function.visibility,
            function.safeAs<IrSimpleFunction>()?.modality ?: Modality.FINAL,
            function.returnType, function.isInline, function.isExternal,
            (function is IrSimpleFunction && function.isTailrec),
            (function is IrSimpleFunction && function.isSuspend)
        ).apply {
            descriptor.bind(this)
            parent = function.parent
            copyTypeParametersFrom(function)
            for ((index, parameter) in function.fullParameterList.withIndex()) {
                val name = if (parameter == function.extensionReceiverParameter) Name.identifier("\$receiver") else parameter.name
                val newParameter = parameter.copyTo(this, index = index - 1, name = name)
                if (parameter == function.dispatchReceiverParameter)
                    dispatchReceiverParameter = newParameter
                else
                    valueParameters.add(newParameter)
                parameterMap[parameter.symbol] = newParameter
            }

            function.safeAs<IrSimpleFunction>()?.overriddenSymbols?.forEach { overriddenSymbol ->
                val replacement = getReplacementFunction(overriddenSymbol.owner)
                if (replacement != null)
                    overriddenSymbols.add(replacement.function.symbol as IrSimpleFunctionSymbol)
            }

            annotations += function.annotations
        }

        if (replacement.origin == IrDeclarationOrigin.FAKE_OVERRIDE && replacement.overriddenSymbols.isEmpty())
            return null

        return IrReplacementFunction(replacement, parameterMap)
    }

    private fun createStaticReplacement(function: IrFunction): IrReplacementFunction? {
        val descriptor = WrappedSimpleFunctionDescriptor()
        val symbol = IrSimpleFunctionSymbolImpl(descriptor)
        val parameterMap = mutableMapOf<IrValueParameterSymbol, IrValueParameter>()

        val replacement = IrFunctionImpl(
            function.startOffset, function.endOffset, function.origin,
            symbol, mangledNameFor(function), function.visibility,
            function.safeAs<IrSimpleFunction>()?.modality ?: Modality.FINAL,
            function.returnType, function.isInline, function.isExternal,
            (function is IrSimpleFunction && function.isTailrec),
            (function is IrSimpleFunction && function.isSuspend)
        ).apply {
            descriptor.bind(this)
            parent = function.parent
            copyTypeParametersFrom(function)
            for ((index, parameter) in function.fullParameterList.withIndex()) {
                val name = when (parameter) {
                    function.dispatchReceiverParameter -> Name.identifier("\$this")
                    function.extensionReceiverParameter -> Name.identifier("\$receiver")
                    else -> parameter.name
                }

                val newParameter = parameter.copyTo(this, index = index, name = name)
                valueParameters.add(newParameter)
                parameterMap[parameter.symbol] = newParameter
            }
        }

        return IrReplacementFunction(replacement, parameterMap)
    }

    // Naming scheme
    // - Statically lowered constructors are named constructor-impl
    // - a function "f" without boxed parameters is named "f-impl"
    // - all other functions "f" are renamed to "f-hash"
    private fun mangledNameFor(irFunction: IrFunction): Name {
        val base = when {
            irFunction is IrConstructor ->
                "constructor"
            irFunction.name.isSpecial ->
                // HACK: strip leading < and trailing >
                irFunction.name.asString().let { it.substring(1 until it.length - 1) }
            else ->
                irFunction.name.asString()
        }

        val suffix = when {
            irFunction.extensionReceiverParameter?.type?.isInlined() == true || irFunction.valueParameters.any { it.type.isInlined() } ->
                hashSuffix(irFunction)
            (irFunction.parent as? IrClass)?.isInline == true -> "impl"
            else -> return irFunction.name
        }

        return Name.identifier("$base-$suffix")
    }

    private fun hashSuffix(irFunction: IrFunction) =
        md5base64(irFunction.fullParameterList.joinToString { it.type.eraseToString() })

    private fun IrType.eraseToString() = buildString {
        append('L')
        append(erasedUpperBound.fqNameSafe)
        if (isNullable()) append('?')
        append(';')
    }

    private fun md5base64(signatureForMangling: String): String {
        val d = MessageDigest.getInstance("MD5")
            .digest(signatureForMangling.toByteArray())
            .copyOfRange(0, 5)
        // base64 URL encoder without padding uses exactly the characters allowed in
        // both JVM bytecode and Dalvik bytecode names
        return Base64.getUrlEncoder().withoutPadding().encodeToString(d)
    }
}

private val IrFunction.hasStaticReplacement: Boolean
    get() = origin != IrDeclarationOrigin.FAKE_OVERRIDE &&
            (this is IrSimpleFunction || this is IrConstructor && returnType.isBoxed && !isPrimary)

private val IrFunction.isAbstract: Boolean
    get() = this is IrSimpleFunction && modality == Modality.ABSTRACT || parent.safeAs<IrClass>()?.isInterface == true

private val IrFunction.hasMethodReplacement: Boolean
    get() = DFS.ifAny(
        listOf(this),
        { current -> (current as? IrSimpleFunction)?.overriddenSymbols?.map { it.owner } ?: listOf() },
        { current -> current.isBoxed && current.isAbstract }
    )

private val IrFunction.fullParameterList: List<IrValueParameter>
    get() = listOfNotNull(dispatchReceiverParameter, extensionReceiverParameter) + valueParameters

val IrFunction.isInlineClassFieldGetter: Boolean
    get() = (parent as? IrClass)?.isInline == true && this is IrSimpleFunction &&
            correspondingPropertySymbol?.let { it.owner.backingField != null && it.owner.getter == this } == true

private val IrFunction.isSyntheticInlineClassMember: Boolean
    get() = origin == IrDeclarationOrigin.SYNTHETIC_INLINE_CLASS_MEMBER || isInlineClassFieldGetter
