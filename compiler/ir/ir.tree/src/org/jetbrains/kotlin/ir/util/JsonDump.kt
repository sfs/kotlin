/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.util

import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.utils.Printer
import org.jetbrains.kotlin.utils.addIfNotNull
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility

//fun IrElement.json() = accept(DumpJsonVisitor, null)

//fun <T : IrElement> List<T>.json(): Json = Json.Array(map(IrElement::json))

//fun <T : IrElement> Json.ObjectBuilder.elems(field: String, elements: List<T>) =
//    json(field, elements.json())

fun Json.ObjectBuilder.texts(field: String, values: List<Any>) =
    json(field, Json.Array(values.map { Json.Text(it.toString()) }))

//fun Json.ObjectBuilder.elem(field: String, element: IrElement) =
//    json(field, element.json())

//fun Json.ObjectBuilder.maybeElem(field: String, element: IrElement?) = when(element) {
//    is IrElement -> elem(field, element)
//    else -> empty(field)
//}

//fun Json.ListBuilder.elem(element: IrElement) =
//    json(element.json())

fun Json.ObjectBuilder.type(type: String) =
    text("type", type)

class JsonSerializer {
    private val objects: MutableSet<Any> = mutableSetOf()

    fun serializeMaybeNull(obj: Any?): Json {
        if (obj == null) return Json.Null()
        return serialize(obj)
    }

    fun <T> serialize(obj: T): Json {
        if (obj == null)
            return Json.Null()

        if ((obj as Any) in objects)
            return Json.Null()
        objects.add(obj as Any)

        try {
            val klass = obj!!::class
            val name = klass.simpleName
            return Json.obj {
                type(name ?: "<null>")
                try {
                    for (member in klass.members) {
                        if (member.visibility != KVisibility.PUBLIC)
                            continue

                        when (member) {
                            is KProperty1<*, *> ->
                                json(member.name, serializeMaybeNull((member as KProperty1<T, Any?>).get(obj)))
                        }
                    }
                } catch (e: Throwable) {}
            }
        } catch (e: Error) {
            return Json.Null()
        }
    }
}

fun Any.serializeJson(): Json =
    JsonSerializer().serialize(this)

/*object DumpJsonVisitor : IrElementVisitor<Json, Nothing?> {
    override fun visitElement(element: IrElement, data: Nothing?) =
        Json.obj {
            type("${element::class.simpleName}")
        }

    override fun visitDeclaration(declaration: IrDeclaration, data: Nothing?) =
        Json.obj {
            type("${declaration::class.simpleName}")
            text("ref", declaration.descriptor.ref())
        }

    override fun visitModuleFragment(declaration: IrModuleFragment, data: Nothing?) =
        Json.obj {
            type("IrModuleFragment")
            text("name", declaration.name)
            elems("files", declaration.files)
        }

    override fun visitExternalPackageFragment(declaration: IrExternalPackageFragment, data: Nothing?) =
        Json.obj {
            type("IrExternalPackageFragment")
            text("fqName", declaration.fqName)
            elems("declarations", declaration.declarations)
        }

    override fun visitFile(declaration: IrFile, data: Nothing?) =
        Json.obj {
            type("IrFile")
            text("fqName", declaration.fqName)
            text("path", declaration.path)
            elems("declarations", declaration.declarations)
        }

    override fun visitFunction(declaration: IrFunction, data: Nothing?) =
        Json.obj {
            type("IrFunction")
            text("origin", declaration.origin)
            text("declaration", declaration.renderDeclared())
            maybeElem("body", declaration.body)
        }

    override fun visitSimpleFunction(declaration: IrSimpleFunction, data: Nothing?) =
        Json.obj {
            type("IrSimpleFunction")
            text("origin", declaration.origin)
            text("name", declaration.name)
            text("visibility", declaration.visibility)
            text("modality", declaration.modality)
            texts("typeParameters", declaration.typeParameters)
            text("dispatchReceiverParameter", declaration.dispatchReceiverParameter?.run {
                "\$this:${type.render()}"
            } ?: "null")
            text("extensionReceiverParameter", declaration.extensionReceiverParameter?.run {
                "\$receiver:${type.render()}"
            } ?: "null")
            list("valueTypeParameters") {
                declaration.valueParameters.forEach { text("${it.name}:${it.type.render()}") }
            }
            text("returnType", declaration.returnType.render())
            list("flags") {
                if (declaration.isTailrec) text("tailrec")
                if (declaration.isInline) text("inline")
                if (declaration.isExternal) text("external")
                if (declaration.isSuspend) text("suspend")
            }
            maybeElem("body", declaration.body)
        }

    override fun visitConstructor(declaration: IrConstructor, data: Nothing?) =
        Json.obj {
            type("IrConstructor")
            text("origin", declaration.origin)
            text("visibility", declaration.visibility)
            texts("typeParameters", declaration.typeParameters)
            text("dispatchReceiverParameter", declaration.dispatchReceiverParameter?.run {
                "\$this:${type.render()}"
            } ?: "null")
            text("extensionReceiverParameter", declaration.extensionReceiverParameter?.run {
                "\$receiver:${type.render()}"
            } ?: "null")
            list("valueTypeParameters") {
                declaration.valueParameters.forEach { text("${it.name}:${it.type.render()}") }
            }
            text("returnType", declaration.returnType.render())
            list("flags") {
                if (declaration.isInline) text("inline")
                if (declaration.isExternal) text("external")
                if (declaration.isPrimary) text("primary")
            }
            maybeElem("body", declaration.body)
        }

    override fun visitProperty(declaration: IrProperty, data: Nothing?) =
        Json.obj {
            type("IrProperty")
            text("origin", declaration.origin)
            text("name", declaration.name)
            text("visibility", declaration.visibility)
            text("modality", declaration.modality)
            bool("mutable", declaration.isVar)
            list("flags") {
                if (declaration.isExternal) text("external")
                if (declaration.isConst) text("const")
                if (declaration.isLateinit) text("lateinit")
                if (declaration.isDelegated) text("delegated")
            }
            maybeElem("backingField", declaration.backingField)
            maybeElem("getter", declaration.getter)
            maybeElem("setter", declaration.setter)
        }

    override fun visitField(declaration: IrField, data: Nothing?) =
        Json.obj {
            type("IrField")
            text("origin", declaration.origin)
            text("name", declaration.name)
            text("type", declaration.type.render())
            text("visibility", declaration.visibility)
            list("flags") {
                if (declaration.isFinal) text("final")
                if (declaration.isExternal) text("external")
                if (declaration.isStatic) text("static")
            }
            maybeElem("initializer", declaration.initializer)
        }

    override fun visitClass(declaration: IrClass, data: Nothing?) =
        Json.obj {
            type("IrClass")
            text("origin", declaration.origin)
            text("kind", declaration.kind)
            text("name", declaration.name)
            text("modality", declaration.modality)
            text("visibility", declaration.visibility)
            list("flags") {
                if (declaration.isCompanion) text("companion")
                if (declaration.isInner) text("inner")
                if (declaration.isData) text("data")
                if (declaration.isExternal) text("external")
                if (declaration.isInline) text("inline")
            }
            list("superTypes") {
                for (type in declaration.superTypes) {
                    text(type.render())
                }
            }
            elems("declarations", declaration.declarations)
        }

    override fun visitTypeAlias(declaration: IrTypeAlias, data: Nothing?) =
        Json.obj {
            type("IrTypeAlias")
            text("origin", declaration.origin)
            text("descriptor", declaration.descriptor.ref())
            text("type", declaration.descriptor.underlyingType.render())
        }

    override fun visitVariable(declaration: IrVariable, data: Nothing?) =
        Json.obj {
            type("IrVariable")
            text("origin", declaration.origin)
            text("name", declaration.name)
            text("type", declaration.type.render())
            bool("mutable", declaration.isVar)
            list("flags") {
                if (declaration.isConst) text("const")
                if (declaration.isLateinit) text("lateinit")
            }
            maybeElem("initializer", declaration.initializer)
        }

    override fun visitEnumEntry(declaration: IrEnumEntry, data: Nothing?) =
        Json.obj {
            type("IrEnumEntry")
            text("origin", declaration.origin)
            text("name", declaration.name)
            maybeElem("initializer", declaration.initializerExpression)
        }

    override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer, data: Nothing?) =
        Json.obj {
            type("IrAnonymousInitializer")
            text("descriptor", declaration.descriptor.ref())
            bool("isStatic", declaration.isStatic)
            elem("body", declaration.body)
        }

    override fun visitTypeParameter(declaration: IrTypeParameter, data: Nothing?) =
        Json.obj {
            type("IrTypeParameter")
            text("origin", declaration.origin)
            text("name", declaration.name)
            number("index", declaration.index.toDouble())
            text("variance", declaration.variance)
            bool("isReified", declaration.isReified)
            list("superTypes") {
                for (type in declaration.superTypes)
                    text(type.render())
            }
        }

    override fun visitValueParameter(declaration: IrValueParameter, data: Nothing?) =
        Json.obj {
            type("IrValueParameter")
            text("origin", declaration.origin)
            text("name", declaration.name)
            if (declaration.index >= 0)
                number("index", declaration.index.toDouble())
            text("type", declaration.type.render())
            declaration.varargElementType?.let {
                text("varargElementType", it.render())
            }
            list("flags") {
                if (declaration.varargElementType != null) text("vararg")
                if (declaration.isCrossinline) text("crossinline")
                if (declaration.isNoinline) text("noinline")
            }
            maybeElem("defaultValue", declaration.defaultValue)
        }

    /*
    override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty, data: Nothing?): String =
        declaration.run {
            "LOCAL_DELEGATED_PROPERTY ${declaration.renderOriginIfNonTrivial()}" +
                    "name:$name type:${type.render()} flags:${renderLocalDelegatedPropertyFlags()}"
        }

    private fun IrLocalDelegatedProperty.renderLocalDelegatedPropertyFlags() =
        if (isVar) "var" else "val"

    override fun visitExpressionBody(body: IrExpressionBody, data: Nothing?): String =
        "EXPRESSION_BODY"

    override fun visitBlockBody(body: IrBlockBody, data: Nothing?): String =
        "BLOCK_BODY"

    override fun visitSyntheticBody(body: IrSyntheticBody, data: Nothing?): String =
        "SYNTHETIC_BODY kind=${body.kind}"

    override fun visitExpression(expression: IrExpression, data: Nothing?): String =
        "? ${expression::class.java.simpleName} type=${expression.type.render()}"

    override fun <T> visitConst(expression: IrConst<T>, data: Nothing?): String =
        "CONST ${expression.kind} type=${expression.type.render()} value=${expression.value?.escapeIfRequired()}"

    private fun Any.escapeIfRequired() =
        when (this) {
            is String -> "\"${StringUtil.escapeStringCharacters(this)}\""
            is Char -> "'${StringUtil.escapeStringCharacters(this.toString())}'"
            else -> this
        }

    override fun visitVararg(expression: IrVararg, data: Nothing?): String =
        "VARARG type=${expression.type.render()} varargElementType=${expression.varargElementType.render()}"

    override fun visitSpreadElement(spread: IrSpreadElement, data: Nothing?): String =
        "SPREAD_ELEMENT"

    override fun visitBlock(expression: IrBlock, data: Nothing?): String =
        "BLOCK type=${expression.type.render()} origin=${expression.origin}"

    override fun visitComposite(expression: IrComposite, data: Nothing?): String =
        "COMPOSITE type=${expression.type.render()} origin=${expression.origin}"

    override fun visitReturn(expression: IrReturn, data: Nothing?): String =
        "RETURN type=${expression.type.render()} from='${expression.returnTarget.ref()}'"

    override fun visitCall(expression: IrCall, data: Nothing?): String =
        "CALL '${expression.descriptor.ref()}' ${expression.renderSuperQualifier()}" +
                "type=${expression.type.render()} origin=${expression.origin}"

    private fun IrCall.renderSuperQualifier(): String =
        superQualifier?.let { "superQualifier=${it.name} " } ?: ""

    override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall, data: Nothing?): String =
        "DELEGATING_CONSTRUCTOR_CALL '${expression.descriptor.ref()}'"

    override fun visitEnumConstructorCall(expression: IrEnumConstructorCall, data: Nothing?): String =
        "ENUM_CONSTRUCTOR_CALL '${expression.descriptor.ref()}'"

    override fun visitInstanceInitializerCall(expression: IrInstanceInitializerCall, data: Nothing?): String =
        "INSTANCE_INITIALIZER_CALL classDescriptor='${expression.classDescriptor.ref()}'"

    override fun visitGetValue(expression: IrGetValue, data: Nothing?): String =
        "GET_VAR '${expression.descriptor.ref()}' type=${expression.type.render()} origin=${expression.origin}"

    override fun visitSetVariable(expression: IrSetVariable, data: Nothing?): String =
        "SET_VAR '${expression.descriptor.ref()}' type=${expression.type.render()} origin=${expression.origin}"

    override fun visitGetField(expression: IrGetField, data: Nothing?): String =
        "GET_FIELD '${expression.descriptor.ref()}' type=${expression.type.render()} origin=${expression.origin}"

    override fun visitSetField(expression: IrSetField, data: Nothing?): String =
        "SET_FIELD '${expression.descriptor.ref()}' type=${expression.type.render()} origin=${expression.origin}"

    override fun visitGetObjectValue(expression: IrGetObjectValue, data: Nothing?): String =
        "GET_OBJECT '${expression.descriptor.ref()}' type=${expression.type.render()}"

    override fun visitGetEnumValue(expression: IrGetEnumValue, data: Nothing?): String =
        "GET_ENUM '${expression.descriptor.ref()}' type=${expression.type.render()}"

    override fun visitStringConcatenation(expression: IrStringConcatenation, data: Nothing?): String =
        "STRING_CONCATENATION type=${expression.type.render()}"

    override fun visitTypeOperator(expression: IrTypeOperatorCall, data: Nothing?): String =
        "TYPE_OP type=${expression.type.render()} origin=${expression.operator} typeOperand=${expression.typeOperand.render()}"

    override fun visitWhen(expression: IrWhen, data: Nothing?): String =
        "WHEN type=${expression.type.render()} origin=${expression.origin}"

    override fun visitBranch(branch: IrBranch, data: Nothing?): String =
        "BRANCH"

    override fun visitWhileLoop(loop: IrWhileLoop, data: Nothing?): String =
        "WHILE label=${loop.label} origin=${loop.origin}"

    override fun visitDoWhileLoop(loop: IrDoWhileLoop, data: Nothing?): String =
        "DO_WHILE label=${loop.label} origin=${loop.origin}"

    override fun visitBreak(jump: IrBreak, data: Nothing?): String =
        "BREAK label=${jump.label} loop.label=${jump.loop.label}"

    override fun visitContinue(jump: IrContinue, data: Nothing?): String =
        "CONTINUE label=${jump.label} loop.label=${jump.loop.label}"

    override fun visitThrow(expression: IrThrow, data: Nothing?): String =
        "THROW type=${expression.type.render()}"

    override fun visitFunctionReference(expression: IrFunctionReference, data: Nothing?): String =
        "FUNCTION_REFERENCE '${expression.descriptor.ref()}' type=${expression.type.render()} origin=${expression.origin}"

    override fun visitPropertyReference(expression: IrPropertyReference, data: Nothing?): String =
        buildString {
            append("PROPERTY_REFERENCE ")
            append("'${expression.descriptor.ref()}' ")
            appendNullableAttribute("field=", expression.field) { "'${it.descriptor.ref()}'" }
            appendNullableAttribute("getter=", expression.getter) { "'${it.descriptor.ref()}'" }
            appendNullableAttribute("setter=", expression.setter) { "'${it.descriptor.ref()}'" }
            append("type=${expression.type.render()} ")
            append("origin=${expression.origin}")
        }

    private inline fun <T : Any> StringBuilder.appendNullableAttribute(prefix: String, value: T?, toString: (T) -> String) {
        append(prefix)
        if (value != null) {
            append(toString(value))
        } else {
            append("null")
        }
        append(" ")
    }

    override fun visitLocalDelegatedPropertyReference(expression: IrLocalDelegatedPropertyReference, data: Nothing?): String =
        buildString {
            append("LOCAL_DELEGATED_PROPERTY_REFERENCE ")
            append("'${expression.descriptor.ref()}' ")
            append("delegate='${expression.delegate.descriptor.ref()}' ")
            append("getter='${expression.getter.descriptor.ref()}' ")
            appendNullableAttribute("setter=", expression.setter) { "'${it.descriptor.ref()}'" }
            append("type=${expression.type.render()} ")
            append("origin=${expression.origin}")
        }

    override fun visitClassReference(expression: IrClassReference, data: Nothing?): String =
        "CLASS_REFERENCE '${expression.descriptor.ref()}' type=${expression.type.render()}"

    override fun visitGetClass(expression: IrGetClass, data: Nothing?): String =
        "GET_CLASS type=${expression.type.render()}"

    override fun visitTry(aTry: IrTry, data: Nothing?): String =
        "TRY type=${aTry.type.render()}"

    override fun visitCatch(aCatch: IrCatch, data: Nothing?): String =
        "CATCH parameter=${aCatch.parameter.ref()}"

    override fun visitDynamicOperatorExpression(expression: IrDynamicOperatorExpression, data: Nothing?): String =
        "DYN_OP operator=${expression.operator} type=${expression.type.render()}"

    override fun visitDynamicMemberExpression(expression: IrDynamicMemberExpression, data: Nothing?): String =
        "DYN_MEMBER memberName='${expression.memberName}' type=${expression.type.render()}"

    override fun visitErrorDeclaration(declaration: IrErrorDeclaration, data: Nothing?): String =
        "ERROR_DECL ${declaration.descriptor::class.java.simpleName} ${declaration.descriptor.ref()}"

    override fun visitErrorExpression(expression: IrErrorExpression, data: Nothing?): String =
        "ERROR_EXPR '${expression.description}' type=${expression.type.render()}"

    override fun visitErrorCallExpression(expression: IrErrorCallExpression, data: Nothing?): String =
        "ERROR_CALL '${expression.description}' type=${expression.type.render()}"
        */
}
*/

// Utilities for generating json

sealed class Json {
    class Object(val elements: Map<String,Json>) : Json()
    class Array(val elements: List<Json>) : Json()
    class Text(val text: String) : Json()
    class Number(val number: Double) : Json()
    class Bool(val bool: Boolean) : Json()
    class Null() : Json()

    override fun toString(): String {
        val sb = StringBuilder()
        val printer = Printer(sb, "  ")
        printer.json(this)
        return sb.toString()
    }

    class ObjectBuilder {
        private val fields: MutableMap<String,Json> = mutableMapOf()
        fun empty(name: String) { fields[name] = Null() }
        fun bool(name: String, bool: Boolean) { fields[name] = Json.Bool(bool) }
        fun text(name: String, value: Any) { fields[name] = Json.Text(value.toString()) }
        fun number(name: String, value: Double) { fields[name] = Json.Number(value) }
        fun obj(name: String, body: ObjectBuilder.() -> Unit) { fields[name] = Json.obj(body) }
        fun list(name: String, body: ListBuilder.() -> Unit) { fields[name] = Json.list(body) }
        fun json(name: String, value: Json) { fields[name] = value }
        fun toJson(): Json = Object(fields)
    }

    class ListBuilder {
        private val elements: MutableList<Json> = mutableListOf()
        fun empty() = elements.add(Null())
        fun bool(b: Boolean) = elements.add(Bool(b))
        fun text(s: Any) = elements.add(Text(s.toString()))
        fun number(x: Double) = elements.add(Number(x))
        fun obj(body: ObjectBuilder.() -> Unit) = elements.add(Json.obj(body))
        fun list(body: ListBuilder.() -> Unit) = elements.add(Json.list(body))
        fun json(value: Json) = elements.add(value)
        fun toJson() = Array(elements)
    }

    companion object {
        fun obj(body: ObjectBuilder.() -> Unit) =
            ObjectBuilder().also(body).toJson()

        fun list(body: ListBuilder.() -> Unit) =
            ListBuilder().also(body).toJson()
    }
}

fun Printer.json(value: Json) {
    when (value) {
        is Json.Null -> print("null")
        is Json.Bool -> print(if (value.bool) "true" else "false")
        is Json.Number -> print(value.number.toString())
        is Json.Text -> print("\"${value.text}\"")
        is Json.Array ->
            if (value.elements.isEmpty()) {
                print("[]")
            } else {
                println('[')
                indented {
                    for ((index, elem) in value.elements.withIndex()) {
                        if (index > 0) println(",")
                        json(elem)
                    }
                }
                print(']')
            }
        is Json.Object ->
            if (value.elements.isEmpty()) {
                print("{}")
            } else {
                println("{")
                indented {
                    var separator = false
                    for ((field, elem) in value.elements) {
                        if (separator) println(",")
                        separator = true
                        print("\"$field\": ")
                        json(elem)
                    }
                }
                print("}")
            }
    }
}

private fun Printer.indented(block: () -> Unit) {
    pushIndent()
    block()
    popIndent()
}