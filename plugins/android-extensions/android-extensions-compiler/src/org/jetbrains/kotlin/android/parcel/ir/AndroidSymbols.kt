/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.parcel.ir

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrExternalPackageFragmentSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class AndroidSymbols(private val context: CommonBackendContext, private val moduleFragment: IrModuleFragment) {
    private fun createPackage(fqName: FqName): IrPackageFragment =
        IrExternalPackageFragmentImpl(
            IrExternalPackageFragmentSymbolImpl(
                EmptyPackageFragmentDescriptor(
                    moduleFragment.descriptor,
                    fqName
                )
            )
        )

    private val androidOsPackage = createPackage(FqName("android.os"))
    private val androidTextPackage = createPackage(FqName("android.text"))
    private val androidUtilPackage = createPackage(FqName("android.util"))

    private inline fun createClass(
        fqName: FqName,
        classKind: ClassKind = ClassKind.CLASS,
        classModality: Modality = Modality.FINAL,
        block: (IrClass) -> Unit = {}
    ): IrClassSymbol =
        buildClass {
            name = fqName.shortName()
            kind = classKind
            modality = classModality
        }.apply {
            parent = when (fqName.parent().asString()) {
                "android.os" -> androidOsPackage
                "android.text" -> androidTextPackage
                "android.util" -> androidUtilPackage
                else -> error("Other packages are not supported yet: $fqName")
            }
            createImplicitParameterDeclarationWithWrappedDescriptor()
            block(this)
        }.symbol

    private val androidOsBundle: IrClassSymbol = createClass(FqName("android.os.Bundle"))
    private val sparseBooleanArray: IrClassSymbol = createClass(FqName("android.util.SparseBooleanArray"))

    val parcelClass: IrClassSymbol = createClass(FqName("android.os.Parcel"))

    private fun parcelUnary(name: String, type: IrType): IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction(name, context.irBuiltIns.unitType).apply {
            addValueParameter("val", type)
        }.symbol

    private fun parcelWrite(name: String, type: IrType): IrSimpleFunctionSymbol =
        parcelUnary("write$name", type)

    private fun parcelReadArray(name: String, type: IrType): IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("create${name}Array", context.irBuiltIns.arrayClass.typeWith(type)).symbol

    private fun parcelWriteArray(name: String, type: IrType): IrSimpleFunctionSymbol =
        parcelUnary("write${name}Array", context.irBuiltIns.arrayClass.typeWith(type))

    val parcelReadByte: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readByte", context.irBuiltIns.byteType).symbol

    val parcelReadInt: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readInt", context.irBuiltIns.intType).symbol

    val parcelReadLong: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readLong", context.irBuiltIns.longType).symbol

    val parcelReadFloat: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readFloat", context.irBuiltIns.floatType).symbol

    val parcelReadDouble: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readDouble", context.irBuiltIns.doubleType).symbol

    val parcelReadString: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readString", context.irBuiltIns.stringType.makeNullable()).symbol

    val parcelWriteByte: IrSimpleFunctionSymbol =
        parcelWrite("Byte", context.irBuiltIns.byteType)

    val parcelWriteInt: IrSimpleFunctionSymbol =
        parcelWrite("Int", context.irBuiltIns.intType)

    val parcelWriteLong: IrSimpleFunctionSymbol =
        parcelWrite("Long", context.irBuiltIns.longType)

    val parceWriteFloat: IrSimpleFunctionSymbol =
        parcelWrite("Float", context.irBuiltIns.floatType)

    val parcelWriteDouble: IrSimpleFunctionSymbol =
        parcelWrite("Double", context.irBuiltIns.doubleType)

    val parcelWriteString: IrSimpleFunctionSymbol =
        parcelWrite("String", context.irBuiltIns.stringType)

    val parcelReadBooleanArray: IrSimpleFunctionSymbol =
        parcelReadArray("Boolean", context.irBuiltIns.booleanType)

    val parcelReadByteArray: IrSimpleFunctionSymbol =
        parcelReadArray("Byte", context.irBuiltIns.booleanType)

    val parcelReadCharArray: IrSimpleFunctionSymbol =
        parcelReadArray("Char", context.irBuiltIns.charType)

    val parcelReadIntArray: IrSimpleFunctionSymbol =
        parcelReadArray("Int", context.irBuiltIns.intType)

    val parcelReadLongArray: IrSimpleFunctionSymbol =
        parcelReadArray("Long", context.irBuiltIns.longType)

    val parcelReadFloatArray: IrSimpleFunctionSymbol =
        parcelReadArray("Float", context.irBuiltIns.floatType)

    val parcelReadDoubleArray: IrSimpleFunctionSymbol =
        parcelReadArray("Double", context.irBuiltIns.doubleType)

    val parcelReadStringArray: IrSimpleFunctionSymbol =
        parcelReadArray("String", context.irBuiltIns.stringType)

    val parcelWriteBooleanArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Boolean", context.irBuiltIns.booleanType)

    val parcelWriteByteArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Byte", context.irBuiltIns.booleanType)

    val parcelWriteCharArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Char", context.irBuiltIns.charType)

    val parcelWriteIntArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Int", context.irBuiltIns.intType)

    val parcelWriteLongArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Long", context.irBuiltIns.longType)

    val parcelWriteFloatArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Float", context.irBuiltIns.floatType)

    val parcelWriteDoubleArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Double", context.irBuiltIns.doubleType)

    val parcelWriteStringArray: IrSimpleFunctionSymbol =
        parcelWriteArray("String", context.irBuiltIns.stringType)

    val parcelReadBundle: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readBundle", androidOsBundle.defaultType).symbol

    val parcelWriteBundle: IrSimpleFunctionSymbol =
        parcelUnary("writeBundle", androidOsBundle.defaultType)

    val parcelReadSparseBooleanArray: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readSparseBooleanArray", sparseBooleanArray.defaultType).symbol

    val parcelWriteSparseBooleanArray: IrSimpleFunctionSymbol =
        parcelUnary("writeSparseBooleanArray", sparseBooleanArray.defaultType)

    private val androidOsIBinder: IrClassSymbol =
        createClass(FqName("android.os.IBinder"))

    private val androidOsIInterface: IrClassSymbol =
        createClass(FqName("android.os.IInterface"))

    val parcelReadIBinder: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readStrongBinder", androidOsIBinder.defaultType).symbol

    val parcelWriteIBinder: IrSimpleFunctionSymbol =
        parcelUnary("writeStrongBinder", androidOsIBinder.defaultType)

    val parcelReadIBinderArray: IrSimpleFunctionSymbol =
        parcelReadArray("Binder", androidOsIBinder.defaultType)

    val parcelWriteIBinderArray: IrSimpleFunctionSymbol =
        parcelWriteArray("Binder", androidOsIBinder.defaultType)

    val parcelReadIInterface: IrSimpleFunctionSymbol =
        parcelClass.owner.addFunction("readStrongInterface", androidOsIInterface.defaultType).symbol

    val parcelWriteIInterface: IrSimpleFunctionSymbol =
        parcelUnary("writeStrongInterface", androidOsIInterface.defaultType)

    private val parcelableInterface: IrClassSymbol =
        createClass(FqName("android.os.Parcelable"), ClassKind.INTERFACE, Modality.ABSTRACT)

    val parcelableCreator: IrClassSymbol =
        buildClass {
            name = Name.identifier("Creator")
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
        }.apply {
            val t = addTypeParameter("T", context.irBuiltIns.anyNType)
            parent = parcelableInterface.owner
            createImplicitParameterDeclarationWithWrappedDescriptor()

            // FIXME: isStatic here is a workaround for a bug in remapTypeParameters which breaks on the receiver type.
            addFunction("createFromParcel", t.defaultType, Modality.ABSTRACT, isStatic = true).apply {
                dispatchReceiverParameter = thisReceiver!!.copyTo(this, type = thisReceiver!!.type)
                addValueParameter("source", parcelClass.defaultType)
            }

            addFunction("newArray", context.irBuiltIns.arrayClass.typeWith(t.defaultType.makeNullable()), Modality.ABSTRACT, isStatic = true).apply {
                dispatchReceiverParameter = thisReceiver!!.copyTo(this, type = thisReceiver!!.type)
                addValueParameter("size", context.irBuiltIns.intType)
            }
        }.symbol

    // Utilities for parceling CharSequences

    private val androidTextUtils = createClass(FqName("android.text.TextUtils"))

    val parcelWriteCharSequence: IrSimpleFunctionSymbol =
        androidTextUtils.owner.addFunction("writeToParcel", context.irBuiltIns.unitType, isStatic = true).apply {
            addValueParameter("seq", context.ir.symbols.charSequence.defaultType)
            addValueParameter("parcel", parcelClass.defaultType)
            addValueParameter("flags", context.irBuiltIns.intType)
        }.symbol

    val parcelCharSequenceCreator: IrFieldSymbol =
        androidTextUtils.owner.addField {
            name = Name.identifier("CHAR_SEQUENCE_CREATOR")
            type = parcelableCreator.defaultType
            isStatic = true
        }.symbol
}
