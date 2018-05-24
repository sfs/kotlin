/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.bytecode
//
//import com.intellij.byteCodeViewer.ClassSearcher
//import com.intellij.psi.PsiClass
//import com.intellij.psi.PsiElement
//import com.intellij.psi.PsiElementFactory
//import com.intellij.psi.impl.compiled.ClsFileImpl
//import org.jetbrains.kotlin.idea.decompiler.classFile.KtClsFile
//
//class KotlinBytecodeClassSearcher : ClassSearcher {
//    override fun findClass(place: PsiElement): PsiClass? {
//        if (place.containingFile !is KtClsFile) return null
//
//        val virtualFile = place.containingFile?.virtualFile ?: return null
//
//        // It's not actually used. Only virtual file is needed to show bytecode
//        val dummyClass = PsiElementFactory.SERVICE.getInstance(place.project).createClass("dummy")
//        return PsiClassForBytecodeView(dummyClass)
//    }
//
//    private class PsiClassForBytecodeView(val dummy: PsiClass): PsiClass by dummy {
//
//    }
//}
//
