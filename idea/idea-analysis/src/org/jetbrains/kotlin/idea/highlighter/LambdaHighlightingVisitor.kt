/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.highlighter

import com.intellij.lang.annotation.AnnotationHolder
import org.jetbrains.kotlin.builtins.isBuiltinExtensionFunctionalType
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.resolve.BindingContext

internal class LambdaHighlightingVisitor(holder: AnnotationHolder, bindingContext: BindingContext) :
    AfterAnalysisHighlightingVisitor(holder, bindingContext) {

    override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
        val typeInfo = bindingContext[BindingContext.EXPRESSION_TYPE_INFO, lambdaExpression] ?: return
        val key = if (typeInfo.type?.isBuiltinExtensionFunctionalType == true)
            KotlinHighlightingColors.FUNCTION_LITERAL_WITH_RECEIVER_BRACES_AND_ARROW
        else
            KotlinHighlightingColors.FUNCTION_LITERAL_BRACES_AND_ARROW

        val functionLiteral = lambdaExpression.functionLiteral
        createInfoAnnotation(functionLiteral.lBrace, null).textAttributes = key

        val closingBrace = functionLiteral.rBrace
        if (closingBrace != null) {
            createInfoAnnotation(closingBrace, null).textAttributes = key
        }

        val arrow = functionLiteral.arrow
        if (arrow != null) {
            createInfoAnnotation(arrow, null).textAttributes = key
        }
    }
}
