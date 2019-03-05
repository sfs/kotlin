/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.PsiFileFactoryImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.fir.FirRenderer
import org.jetbrains.kotlin.fir.createSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.java.declarations.FirJavaClass
import org.jetbrains.kotlin.fir.java.declarations.FirJavaMethod
import org.jetbrains.kotlin.fir.java.scopes.JavaClassEnhancementScope
import org.jetbrains.kotlin.fir.resolve.FirScopeProvider
import org.jetbrains.kotlin.fir.resolve.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.impl.FirCompositeSymbolProvider
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.scopes.impl.FirCompositeScope
import org.jetbrains.kotlin.fir.service
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.test.ConfigurationKind
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.KotlinTestUtils.getAnnotationsJar
import org.jetbrains.kotlin.test.KotlinTestUtils.newConfiguration
import org.jetbrains.kotlin.test.TestJdkKind
import org.jetbrains.kotlin.test.testFramework.KtUsefulTestCase
import java.io.File
import java.io.IOException

abstract class AbstractFirTypeEnhancementTest : KtUsefulTestCase() {
    private lateinit var javaFilesDir: File

    private lateinit var environment: KotlinCoreEnvironment

    val project: Project
        get() {
            return environment.project
        }

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        javaFilesDir = KotlinTestUtils.tmpDirForTest(this)
    }

    override fun tearDown() {
        FileUtil.delete(javaFilesDir)
        super.tearDown()
    }

    private fun createEnvironment(): KotlinCoreEnvironment {
        return KotlinCoreEnvironment.createForTests(
            testRootDisposable,
            newConfiguration(
                ConfigurationKind.JDK_NO_RUNTIME, TestJdkKind.FULL_JDK, listOf(getAnnotationsJar()), listOf(javaFilesDir)
            ),
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        ).apply {
            Extensions.getArea(project)
                .getExtensionPoint(PsiElementFinder.EP_NAME)
                .unregisterExtension(JavaElementFinder::class.java)
        }
    }

    fun doTest(path: String) {
        val javaFile = File(path)
        val javaLines = javaFile.readLines()
        val packageFqName =
            javaLines.firstOrNull { it.startsWith("package") }?.substringAfter("package")?.trim()?.substringBefore(";")?.let { name ->
                FqName(name)
            } ?: FqName.ROOT
//        LoadDescriptorUtil.compileJavaWithAnnotationsJar(listOf(javaFile), compiledDir)

        val srcFiles = KotlinTestUtils.createTestFiles<Void, File>(
            javaFile.name, FileUtil.loadFile(javaFile, true),
            object : KotlinTestUtils.TestFileFactoryNoModules<File>() {
                override fun create(fileName: String, text: String, directives: Map<String, String>): File {
                    var currentDir = javaFilesDir
                    for (segment in packageFqName.pathSegments()) {
                        currentDir = File(currentDir, segment.asString()).apply { mkdir() }
                    }
                    val targetFile = File(currentDir, fileName)
                    try {
                        FileUtil.writeToFile(targetFile, text)
                    } catch (e: IOException) {
                        throw AssertionError(e)
                    }

                    return targetFile
                }
            }, ""
        )
        environment = createEnvironment()
        val virtualFiles = srcFiles.map {
            object : LightVirtualFile(
                it.name, JavaLanguage.INSTANCE, StringUtilRt.convertLineSeparators(it.readText())
            ) {
                override fun getPath(): String {
                    //TODO: patch LightVirtualFile
                    return "/$name"
                }
            }
        }
        val factory = PsiFileFactory.getInstance(project) as PsiFileFactoryImpl
        val psiFile = virtualFiles.map { factory.trySetupPsiForFile(it, JavaLanguage.INSTANCE, true, false)!! }.first()

        val scope = GlobalSearchScope.filesScope(project, virtualFiles)
            .uniteWith(TopDownAnalyzerFacadeForJVM.AllJavaSourcesInProjectScope(project))
        val session = createSession(project, scope)

        val classFqNames = psiFile.getChildrenOfType<PsiClass>()
            .filter { it.name == javaFile.nameWithoutExtension }
            .map { FqName(it.name!!) }

        val javaFirDump = StringBuilder().also { builder ->
            val renderer = FirRenderer(builder)
            val symbolProvider = session.service<FirSymbolProvider>() as FirCompositeSymbolProvider
            val javaProvider = symbolProvider.providers.filterIsInstance<JavaSymbolProvider>().first()
            for (classFqName in classFqNames) {
                javaProvider.getClassLikeSymbolByFqName(ClassId(packageFqName, classFqName, false))!!
            }

            val processedJavaClasses = mutableSetOf<FirJavaClass>()
            for (javaClass in javaProvider.getJavaTopLevelClasses().sortedBy { it.name }) {
                if (javaClass !is FirJavaClass || javaClass in processedJavaClasses) continue
                val enhancementScope = session.service<FirScopeProvider>().getDeclaredMemberScope(javaClass, session).let {
                    when (it) {
                        is FirCompositeScope -> it.scopes.filterIsInstance<JavaClassEnhancementScope>().first()
                        is JavaClassEnhancementScope -> it
                        else -> null
                    }
                }
                if (enhancementScope == null) {
                    javaClass.accept(renderer, null)
                } else {
                    renderer.visitMemberDeclaration(javaClass)
                    renderer.renderSupertypes(javaClass)
                    renderer.renderInBraces {
                        val renderedDeclarations = mutableListOf<FirDeclaration>()
                        for (declaration in javaClass.declarations) {
                            if (declaration in renderedDeclarations) continue
                            if (declaration !is FirJavaMethod) {
                                declaration.accept(renderer, null)
                                renderer.newLine()
                                renderedDeclarations += declaration
                            } else {
                                enhancementScope.processFunctionsByName(declaration.name) { symbol ->
                                    val enhanced = (symbol as? FirFunctionSymbol)?.fir
                                    if (enhanced != null && enhanced !in renderedDeclarations) {
                                        enhanced.accept(renderer, null)
                                        renderer.newLine()
                                        renderedDeclarations += enhanced
                                    }
                                    ProcessorAction.NEXT
                                }
                            }
                        }
                    }
                }
                processedJavaClasses += javaClass
            }
        }.toString()

        val expectedFile = File(javaFile.absolutePath.replace(".java", ".txt"))
        KotlinTestUtils.assertEqualsToFile(expectedFile, javaFirDump)
    }
}