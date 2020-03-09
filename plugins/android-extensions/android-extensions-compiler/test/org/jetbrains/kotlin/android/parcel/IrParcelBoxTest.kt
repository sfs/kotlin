/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel

import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.extensions.SyntheticResolveExtension
import org.jetbrains.kotlin.test.JUnit3RunnerWithInners
import org.jetbrains.kotlin.test.TargetBackend
import org.junit.runner.RunWith

// TODO: Autogenerate this file along with ParcelBoxTest
@RunWith(JUnit3RunnerWithInners::class)
class IrParcelBoxTest : AbstractParcelBoxTest() {
    override fun getBackend() = TargetBackend.JVM_IR

    fun testSimple() = doTest("simple")
    fun testPrimitiveTypes() = doTest("primitiveTypes")
    fun testBoxedTypes() = doTest("boxedTypes")
    fun testNullableTypesSimple() = doTest("nullableTypesSimple")
    fun testNullableTypes() = doTest("nullableTypes")
    fun testListSimple() = doTest("listSimple")
    fun testLists() = doTest("lists")
    fun testListKinds() = doTest("listKinds")
    fun testArraySimple() = doTest("arraySimple")
    fun testArrays() = doTest("arrays")
    fun testMapSimple() = doTest("mapSimple")
    fun testMaps() = doTest("maps")
    fun testMapKinds() = doTest("mapKinds")
    fun testSparseBooleanArray() = doTest("sparseBooleanArray")
    fun testBundle() = doTest("bundle")
    fun testSparseArrays() = doTest("sparseArrays")
    fun testCustomSimple() = doTest("customSimple")
    fun testCharSequence() = doTest("charSequence")
    fun testEnums() = doTest("enums")
    fun testObjects() = doTest("objects")
    fun testNestedParcelable() = doTest("nestedParcelable")
    fun testKt19749() = doTest("kt19749")
    fun testKt19747() = doTest("kt19747")
    fun testKt19747_2() = doTest("kt19747_2")
    fun test20002() = doTest("kt20002")
    fun test20021() = doTest("kt20021")
    fun testCustomSerializerSimple() = doTest("customSerializerSimple")
    fun testCustomSerializerWriteWith() = doTest("customSerializerWriteWith")
    fun testCustomSerializerBoxing() = doTest("customSerializerBoxing")
    fun testKt20717() = doTest("kt20717")
    fun testEnumObject() = doTest("enumObject")
    fun testIntArray() = doTest("intArray")
    fun testOpenParcelize() = doTest("openParcelize")
    fun testKt25839() = doTest("kt25839")
}

class IrParcelBoxTestWithSerializableLikeExtension : AbstractParcelBoxTest() {
    override fun getBackend() = TargetBackend.JVM_IR

    fun testSimple() = doTest("simple")

    override fun setupEnvironment(environment: KotlinCoreEnvironment) {
        super.setupEnvironment(environment)
        SyntheticResolveExtension.registerExtension(environment.project, SerializableLike())
    }

    private class SerializableLike : SyntheticResolveExtension {
        override fun getSyntheticCompanionObjectNameIfNeeded(thisDescriptor: ClassDescriptor): Name? {
            fun ClassDescriptor.isSerializableLike() = annotations.hasAnnotation(FqName("test.SerializableLike"))

            return when {
                thisDescriptor.kind == ClassKind.CLASS && thisDescriptor.isSerializableLike() -> Name.identifier("Companion")
                else -> return null
            }
        }
    }
}