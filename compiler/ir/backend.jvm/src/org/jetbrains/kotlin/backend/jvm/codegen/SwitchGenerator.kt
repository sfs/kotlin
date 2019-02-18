/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.codegen

import org.jetbrains.kotlin.codegen.*
import org.jetbrains.kotlin.codegen.StackValue.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.isTrueConst
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Type
import java.util.*

// TODO: Char, Byte, Short, String, enum, eliminate the temporary variable
class SwitchGenerator(private val expression: IrWhen, private val data: BlockInfo, private val codegen: ExpressionCodegen) {
    private val endLabel = Label()
    private var defaultLabel = endLabel
    private lateinit var elseExpression: IrExpression
    private val cases = ArrayList<Pair<Int, Label>>()
    private val thenExpressions = ArrayList<Pair<IrExpression, Label>>()
    private var subject: IrGetValue? = null

    private val mv = codegen.mv

    // @return true if the IrWhen can be emitted as lookupswitch or tableswitch.
    fun match(): Boolean {
        for (branch in expression.branches) {
            if (branch is IrElseBranch) {
                elseExpression = branch.result
                defaultLabel = Label()
            } else {
                val thenLabel = Label()
                thenExpressions.add(Pair(branch.result, thenLabel))
                if (!matchConditions(branch.condition, thenLabel))
                    return false
            }
        }

        // IF is more compact when there are only 1 or fewer branches, in addition to else.
        if (cases.size <= 1)
            return false

        return true
    }

    // psi2ir lowers multiple cases to nested conditions. For example,
    //
    // when (subject) {
    //   a, b, c -> action
    // }
    //
    // is lowered to
    //
    // if (if (subject == a)
    //       true
    //     else
    //       if (subject == b)
    //         true
    //       else
    //         subject == c) {
    //     action
    // }
    //
    // @return true if the conditions are equality checks of constants.
    private fun matchConditions(condition: IrExpression, thenLabel: Label): Boolean {
        if (condition is IrCall) {
            // Leaf nodes: a lookup/table switch can be used if:
            // 1. All branches are CALL 'EQEQ(Any?, Any?)': Boolean
            // 2. All types of variables involved in comparison are in the same group of Char/Byte/Short/Int, String or enum.
            // 3. All arg0 refer to the same value.
            // 4. All arg1 are IrConst<*>.

            if (condition.symbol != codegen.classCodegen.context.irBuiltIns.eqeqSymbol)
                return false

            // TODO: The check relies on the implementation that subject is always lowered to a local variable.
            val candidate = condition.getValueArgument(0) as? IrGetValue ?: return false
            subject = subject ?: candidate
            if (candidate.symbol != subject!!.symbol)
                return false
            if (!candidate.type.isInt())
                return false

            val case = condition.getValueArgument(1) as? IrConst<*> ?: return false
            if (case.kind != IrConstKind.Int)
                return false

            cases.add(Pair(case.value as Int, thenLabel))

            return true
        } else if (condition is IrWhen && condition.origin == IrStatementOrigin.WHEN_COMMA) {
            // WHEN_COMMA should always be a Boolean.
            assert(condition.type.isBoolean())

            // Match the following structure:
            //
            // when() {
            //   cond_1 -> true
            //   cond_2 -> true
            //   ...
            //   else -> cond_N
            // }
            //
            // Namely, the structure which returns true if any one of the condition is true.
            for (branch in condition.branches) {
                if (branch is IrElseBranch) {
                    if (!branch.condition.isTrueConst())
                        return false
                    if (!matchConditions(branch.result, thenLabel))
                        return false
                } else {
                    if (!branch.result.isTrueConst())
                        return false
                    if (!matchConditions(branch.condition, thenLabel))
                        return false
                }
            }

            return true
        }

        return false
    }

    private fun gen(expression: IrElement, data: BlockInfo): StackValue = codegen.gen(expression, data)

    private fun coerceNotToUnit(fromType: Type, fromKotlinType: KotlinType?, toKotlinType: KotlinType): StackValue =
        codegen.coerceNotToUnit(fromType, fromKotlinType, toKotlinType)

    fun gen(): StackValue {
        cases.sortBy { it.first }

        // Emit the temporary variable for subject.
        gen(subject!!, data)

        // Emit either tableswitch or lookupswitch, depending on the code size.
        //
        // lookupswitch is 2X as large as tableswitch with the same entries. However, lookupswitch is sparse while tableswitch must
        // enumerate all the entries in the range.
        val caseMin = cases.first().first
        val caseMax = cases.last().first
        val rangeLength = caseMax - caseMin + 1L
        if (rangeLength > 2L * cases.size) {
            mv.lookupswitch(defaultLabel, cases.map { it.first }.toIntArray(), cases.map { it.second }.toTypedArray())
        } else {
            val labels = Array(rangeLength.toInt()) { defaultLabel }
            for (case in cases)
                labels[case.first - caseMin] = case.second
            mv.tableswitch(caseMin, caseMax, defaultLabel, *labels)
        }

        // all entries except else
        for (thenExpression in thenExpressions) {
            mv.visitLabel(thenExpression.second)
            val stackValue = thenExpression.first.run { gen(this, data) }
            coerceNotToUnit(stackValue.type, stackValue.kotlinType, expression.type.toKotlinType())
            mv.goTo(endLabel)
        }

        // else
        val result = if (defaultLabel === endLabel) {
            // There's no else part. No stack value will be generated.
            StackValue.putUnitInstance(mv)
            onStack(Type.VOID_TYPE)
        } else {
            // Generate the else part.
            mv.visitLabel(defaultLabel)
            val stackValue = elseExpression.run { gen(this, data) }
            coerceNotToUnit(stackValue.type, stackValue.kotlinType, expression.type.toKotlinType())
        }

        mv.mark(endLabel)
        return result
    }
}

