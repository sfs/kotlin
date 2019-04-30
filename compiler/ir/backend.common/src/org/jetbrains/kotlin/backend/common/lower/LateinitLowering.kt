/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.ir.Symbols
import org.jetbrains.kotlin.backend.common.phaser.makeIrFilePhase
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

val jvmLateinitPhase = makeIrFilePhase(
    ::LateinitLowering,
    name = "Lateinit",
    description = "Insert checks for lateinit field references"
)

class LateinitLowering(val context: CommonBackendContext) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitProperty(declaration: IrProperty): IrStatement {
                declaration.transformChildrenVoid(this)
                if (declaration.isLateinit) {
                    val backingField = declaration.backingField!!
                    val type = backingField.type
                    assert(!type.isPrimitiveType()) { "'lateinit' modifier is not allowed on primitive types" }
                    backingField.type = type.makeNullable()
                    if (declaration.origin != IrDeclarationOrigin.FAKE_OVERRIDE) {
                        transformGetter(backingField, declaration.getter!!)
                        transformSetter(backingField, declaration.setter!!)
                    }
                }
                return declaration
            }

            override fun visitVariable(declaration: IrVariable): IrStatement {
                declaration.transformChildrenVoid(this)

                if (!declaration.isLateinit) return declaration

                declaration.run {
                    type = type.makeNullable()
                    initializer = IrConstImpl.constNull(startOffset, endOffset, type)
                }

                return declaration
            }

            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val irVar = expression.symbol.owner as? IrVariable ?: return expression
                if (!irVar.isLateinit) return expression

                val parent = irVar.parent as IrSymbolOwner

                val irBuilder = context.createIrBuilder(parent.symbol, expression.startOffset, expression.endOffset)

                return irBuilder.run {
                    irIfNull(
                        expression.type,
                        irGet(irVar),
                        throwUninitializedPropertyAccessException(irVar.name.asString()),
                        irImplicitCast(irGet(irVar), expression.type)
                    )
                }
            }

            override fun visitSetVariable(expression: IrSetVariable): IrExpression {
                expression.transformChildrenVoid(this)
                val irVar = expression.symbol.owner
                if (!irVar.isLateinit) return expression

                val parent = irVar.parent as IrSymbolOwner

                val irBuilder = context.createIrBuilder(parent.symbol, expression.startOffset, expression.endOffset)

                return irBuilder.run {
                    irSetVar(irVar.symbol, irImplicitCast(expression.value, irVar.type))
                }
            }

            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                if (!Symbols.isLateinitIsInitializedPropertyGetter(expression.symbol)) return expression

                val receiver = expression.extensionReceiver as IrPropertyReference

                val field = receiver.getter?.owner?.correspondingPropertySymbol?.owner?.backingField!!

                return expression.run { context.createIrBuilder(symbol, startOffset, endOffset) }.run {
                    irNotEquals(
                        IrGetFieldImpl(startOffset, endOffset, field.symbol, field.type.makeNullable(), receiver.dispatchReceiver),
                        irNull()
                    )
                }
            }

            private fun transformGetter(backingField: IrField, getter: IrFunction) {
                val startOffset = getter.startOffset
                val endOffset = getter.endOffset
                val irBuilder = context.createIrBuilder(getter.symbol, startOffset, endOffset)
                irBuilder.run {
                    val body = IrBlockBodyImpl(startOffset, endOffset)
                    val resultVar = scope.createTemporaryVariable(
                        irGetField(getter.dispatchReceiverParameter?.let { irGet(it) }, backingField)
                    )
                    resultVar.parent = getter
                    body.statements.add(resultVar)
                    val throwIfNull = irIfNull(
                        context.irBuiltIns.nothingType,
                        irGet(resultVar),
                        throwUninitializedPropertyAccessException(backingField.name.asString()),
                        irReturn(irImplicitCast(irGet(resultVar), getter.returnType))
                    )
                    body.statements.add(throwIfNull)
                    getter.body = body
                }
            }

            private fun transformSetter(backingField: IrField, setter: IrFunction) {
                val startOffset = setter.startOffset
                val endOffset = setter.endOffset
                val irBuilder = context.createIrBuilder(setter.symbol, startOffset, endOffset)
                irBuilder.run {
                    val body = IrBlockBodyImpl(startOffset, endOffset)
                    body.statements.add(
                        irSetField(
                            setter.dispatchReceiverParameter?.let { irGet(it) },
                            backingField,
                            irImplicitCast(irGet(setter.valueParameters[0]), backingField.type)
                        )
                    )
                    setter.body = body
                }

            }
        })
    }

    private fun IrBuilderWithScope.throwUninitializedPropertyAccessException(name: String) =
        irCall(throwErrorFunction).apply {
            putValueArgument(
                0,
                IrConstImpl.string(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    context.irBuiltIns.stringType,
                    name
                )
            )
        }

    private val throwErrorFunction by lazy { context.ir.symbols.ThrowUninitializedPropertyAccessException.owner }
}
