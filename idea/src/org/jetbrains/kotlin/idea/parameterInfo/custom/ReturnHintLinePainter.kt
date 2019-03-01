/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.parameterInfo.custom

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.idea.KotlinLanguage
import java.awt.Color
import kotlin.math.roundToInt

class ReturnHintLinePainter : EditorLinePainter() {
    companion object {
        val SPACE_LINE_EXTENSION_INFO = LineExtensionInfo(" ", TextAttributes())

        private fun isInsufficientContrast(
            attributes: TextAttributes,
            surroundingAttributes: TextAttributes
        ): Boolean {
            val backgroundUnderHint = surroundingAttributes.backgroundColor
            if (backgroundUnderHint != null && attributes.foregroundColor != null) {
                val backgroundBlended = srcOverBlend(attributes.backgroundColor, backgroundUnderHint, 0.5f)

                val backgroundBlendedGrayed = backgroundBlended.toGray()
                val textGrayed = attributes.foregroundColor.toGray()
                val delta = Math.abs(backgroundBlendedGrayed - textGrayed)
                return delta < 10
            }
            return false
        }

        private fun srcOverBlend(foreground: Color, background: Color, foregroundAlpha: Float): Color {
            val r = foreground.red * foregroundAlpha + background.red * (1.0f - foregroundAlpha)
            val g = foreground.green * foregroundAlpha + background.green * (1.0f - foregroundAlpha)
            val b = foreground.blue * foregroundAlpha + background.blue * (1.0f - foregroundAlpha)
            return Color(r.roundToInt(), g.roundToInt(), b.roundToInt())
        }

        private fun Color.toGray(): Double {
            return (0.30 * red) + (0.59 * green) + (0.11 * blue)
        }

        protected fun getTextAttributes(editor: Editor): TextAttributes? {
            return editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLINE_PARAMETER_HINT)
        }
    }

    override fun getLineExtensions(project: Project, file: VirtualFile, lineNumber: Int): List<LineExtensionInfo>? {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        if (psiFile.language != KotlinLanguage.INSTANCE) {
            return null
        }

        val hint = getLineHint(project, file, lineNumber)
            ?: return null

        val editor = FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<TextEditor>().firstOrNull()?.editor ?: return null

        val attributes = EditorColorsManager.getInstance().globalScheme.getAttributes(DefaultLanguageHighlighterColors.INLINE_PARAMETER_HINT)

        val editorTextAttributes = getTextAttributes(editor)
        if (editorTextAttributes != null && isInsufficientContrast(attributes, editorTextAttributes)) {
            attributes.backgroundColor = Color(
                attributes.backgroundColor.red,
                attributes.backgroundColor.green,
                attributes.backgroundColor.blue,
                128)
        }

        val hintLineInfo = LineExtensionInfo(hint, attributes)

        return listOf(SPACE_LINE_EXTENSION_INFO, hintLineInfo)
    }

    private fun getLineHint(project: Project, file: VirtualFile, lineNumber: Int): String? {
        val doc = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (lineNumber >= doc.lineCount) {
            return null
        }
        val lineEndOffset = doc.getLineEndOffset(lineNumber)

        return KotlinCodeHintsModel.getInstance(project).getExtensionInfo(doc, lineEndOffset)
    }
}