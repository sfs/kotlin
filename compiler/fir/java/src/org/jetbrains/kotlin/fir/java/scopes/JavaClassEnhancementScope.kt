/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java.scopes

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirMemberFunctionImpl
import org.jetbrains.kotlin.fir.declarations.impl.FirValueParameterImpl
import org.jetbrains.kotlin.fir.expressions.FirAnnotationContainer
import org.jetbrains.kotlin.fir.java.declarations.FirJavaMethod
import org.jetbrains.kotlin.fir.java.declarations.FirJavaValueParameter
import org.jetbrains.kotlin.fir.java.enhancement.EnhancementSignatureParts
import org.jetbrains.kotlin.fir.java.enhancement.FirAnnotationTypeQualifierResolver
import org.jetbrains.kotlin.fir.java.enhancement.FirJavaEnhancementContext
import org.jetbrains.kotlin.fir.java.enhancement.copyWithNewDefaultTypeQualifiers
import org.jetbrains.kotlin.fir.java.types.FirJavaTypeRef
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.symbols.ConeCallableSymbol
import org.jetbrains.kotlin.fir.symbols.ConeFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.load.java.AnnotationTypeQualifierResolver
import org.jetbrains.kotlin.load.java.structure.JavaPrimitiveType
import org.jetbrains.kotlin.load.java.typeEnhancement.PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE
import org.jetbrains.kotlin.load.java.typeEnhancement.PredefinedFunctionEnhancementInfo
import org.jetbrains.kotlin.load.kotlin.SignatureBuildingComponents
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.Jsr305State

class JavaClassEnhancementScope(
    session: FirSession,
    private val useSiteScope: JavaClassUseSiteScope
) : FirScope {
    private val owner: FirRegularClass get() = useSiteScope.symbol.fir

    private val jsr305State: Jsr305State = session.jsr305State ?: Jsr305State.DISABLED

    private val typeQualifierResolver = FirAnnotationTypeQualifierResolver(jsr305State)

    private val context: FirJavaEnhancementContext =
        FirJavaEnhancementContext(session) { null }

    private val enhancements = mutableMapOf<ConeCallableSymbol, ConeCallableSymbol>()

    override fun processFunctionsByName(name: Name, processor: (ConeFunctionSymbol) -> ProcessorAction): ProcessorAction {
        useSiteScope.processFunctionsByName(name) process@{ original ->

            val function = enhancements.getOrPut(original) { enhance(original, name) }
            processor(function as ConeFunctionSymbol)
        }

        return super.processFunctionsByName(name, processor)
    }

    private fun enhance(
        original: ConeFunctionSymbol,
        name: Name
    ): FirFunctionSymbol {
        val firMethod = (original as FirBasedSymbol<*>).fir as? FirJavaMethod ?: error("Can't fake override for $original")

        val memberContext = context.copyWithNewDefaultTypeQualifiers(
            typeQualifierResolver, jsr305State, firMethod.annotations
        )
        // TODO: When loading method as an override for a property, all annotations are stick to its getter
//            if (this is FirProperty && getter !is FirDefaultPropertyAccessor)
//                getter
//            else
//                this

        val predefinedEnhancementInfo =
            SignatureBuildingComponents.signature(owner.symbol.classId, firMethod.computeJvmDescriptor()).let { signature ->
                PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE[signature]
            }

        predefinedEnhancementInfo?.let {
            assert(it.parametersInfo.size == firMethod.valueParameters.size) {
                "Predefined enhancement info for $this has ${it.parametersInfo.size}, but ${firMethod.valueParameters.size} expected"
            }
        }

        val newReceiverTypeRef = if (firMethod.receiverTypeRef != null) enhanceReceiverType(firMethod, memberContext) else null
        val newReturnTypeRef = enhanceReturnType(firMethod, memberContext, predefinedEnhancementInfo)

        val newValueParameterTypeRefs = mutableListOf<FirResolvedTypeRef>()

        for ((index, valueParameter) in firMethod.valueParameters.withIndex()) {
            newValueParameterTypeRefs += enhanceValueParameterType(
                firMethod, memberContext, predefinedEnhancementInfo, valueParameter as FirJavaValueParameter, index
            )
        }


        val symbol = FirFunctionSymbol(original.callableId)
        with(firMethod) {
            FirMemberFunctionImpl(
                session, null, symbol, name,
                newReceiverTypeRef, newReturnTypeRef
            ).apply {
                status = firMethod.status
                annotations += firMethod.annotations
                valueParameters += firMethod.valueParameters.zip(newValueParameterTypeRefs) { valueParameter, newTypeRef ->
                    with(valueParameter) {
                        FirValueParameterImpl(
                            session, psi,
                            this.name, newTypeRef,
                            defaultValue, isCrossinline, isNoinline, isVararg
                        ).apply {
                            annotations += valueParameter.annotations
                        }
                    }
                }
            }
        }
        return symbol
    }

    private fun FirJavaMethod.computeJvmDescriptor(): String = buildString {
        append(name.asString()) // TODO: Java constructors

        append("(")
        for (parameter in valueParameters) {
            // TODO: appendErasedType(parameter.returnTypeRef)
        }
        append(")")

        if ((returnTypeRef as FirJavaTypeRef).isVoid()) {
            append("V")
        } else {
            // TODO: appendErasedType(returnTypeRef)
        }
    }

    private fun FirJavaTypeRef.isVoid(): Boolean {
        return type is JavaPrimitiveType && type.type == null
    }

    // ================================================================================================

    private fun enhanceReceiverType(
        ownerFunction: FirJavaMethod,
        memberContext: FirJavaEnhancementContext
    ): FirResolvedTypeRef {
        val signatureParts = ownerFunction.partsForValueParameter(
            typeQualifierResolver,
            // TODO: check me
            parameterContainer = ownerFunction,
            methodContext = memberContext
        ) {
            it.receiverTypeRef!!
        }.enhance(jsr305State)
        return signatureParts.type
    }

    private val FirTypedDeclaration.valueParameters: List<FirValueParameter> get() = (this as? FirFunction)?.valueParameters.orEmpty()

    private fun enhanceValueParameterType(
        ownerFunction: FirJavaMethod,
        memberContext: FirJavaEnhancementContext,
        predefinedEnhancementInfo: PredefinedFunctionEnhancementInfo?,
        ownerParameter: FirJavaValueParameter,
        index: Int
    ): FirResolvedTypeRef {
        val signatureParts = ownerFunction.partsForValueParameter(
            typeQualifierResolver,
            parameterContainer = ownerParameter,
            methodContext = memberContext
        ) {
            it.valueParameters[index].returnTypeRef
        }.enhance(jsr305State, predefinedEnhancementInfo?.parametersInfo?.getOrNull(index))
        return signatureParts.type
    }

    private fun enhanceReturnType(
        ownerFunction: FirJavaMethod,
        memberContext: FirJavaEnhancementContext,
        predefinedEnhancementInfo: PredefinedFunctionEnhancementInfo?
    ): FirResolvedTypeRef {
        val signatureParts = ownerFunction.parts(
            typeQualifierResolver,
            typeContainer = ownerFunction, isCovariant = true,
            containerContext = memberContext,
            containerApplicabilityType =
            if (false) // TODO: this.safeAs<FirProperty>()?.isJavaField == true
                AnnotationTypeQualifierResolver.QualifierApplicabilityType.FIELD
            else
                AnnotationTypeQualifierResolver.QualifierApplicabilityType.METHOD_RETURN_TYPE
        ) { it.returnTypeRef }.enhance(jsr305State, predefinedEnhancementInfo?.returnTypeInfo)
        return signatureParts.type
    }

    private fun FirCallableMember.partsForValueParameter(
        typeQualifierResolver: FirAnnotationTypeQualifierResolver,
        // TODO: investigate if it's really can be a null (check properties' with extension overrides in Java)
        parameterContainer: FirAnnotationContainer?,
        methodContext: FirJavaEnhancementContext,
        collector: (FirCallableMember) -> FirTypeRef
    ) = parts(
        typeQualifierResolver,
        parameterContainer, false,
        parameterContainer?.let {
            methodContext.copyWithNewDefaultTypeQualifiers(typeQualifierResolver, jsr305State, it.annotations)
        } ?: methodContext,
        AnnotationTypeQualifierResolver.QualifierApplicabilityType.VALUE_PARAMETER,
        collector
    )

    private val overriddenMemberCache = mutableMapOf<FirCallableMember, List<FirCallableMember>>()

    private fun FirCallableMember.overriddenMembers(): List<FirCallableMember> {
        return overriddenMemberCache.getOrPut(this) {
            val result = mutableListOf<FirCallableMember>()
            if (this is FirNamedFunction) {
                val superTypesScope = useSiteScope.superTypesScope
                superTypesScope.processFunctionsByName(this.name) { basicFunctionSymbol ->
                    val overriddenBy = with(useSiteScope) {
                        basicFunctionSymbol.getOverridden(setOf(this@overriddenMembers.symbol as ConeFunctionSymbol))
                    }
                    val overriddenByFir = (overriddenBy as? FirFunctionSymbol)?.fir
                    if (overriddenByFir === this@overriddenMembers) {
                        result += (basicFunctionSymbol as FirFunctionSymbol).fir
                    }
                    ProcessorAction.NEXT
                }
            }
            result
        }
    }

    private fun FirCallableMember.parts(
        typeQualifierResolver: FirAnnotationTypeQualifierResolver,
        typeContainer: FirAnnotationContainer?,
        isCovariant: Boolean,
        containerContext: FirJavaEnhancementContext,
        containerApplicabilityType: AnnotationTypeQualifierResolver.QualifierApplicabilityType,
        collector: (FirCallableMember) -> FirTypeRef
    ): EnhancementSignatureParts {
        return EnhancementSignatureParts(
            typeQualifierResolver,
            typeContainer,
            collector(this) as FirJavaTypeRef,
            this.overriddenMembers().map {
                collector(it)
            },
            isCovariant,
            // recompute default type qualifiers using type annotations
            containerContext.copyWithNewDefaultTypeQualifiers(
                typeQualifierResolver, jsr305State, collector(this).annotations
            ),
            containerApplicabilityType
        )
    }

}