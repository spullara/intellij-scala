package org.jetbrains.plugins.scala
package codeInsight.intention.expression

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.util.TextRange
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import lang.psi.api.expr.ScInfixExpr
import lang.psi.impl.ScalaPsiElementFactory
import extensions._
import com.intellij.psi.{PsiDocumentManager, PsiElement}

/**
 * @author Ksenia.Sautina
 * @since 4/23/12
 */

object ReplaceEqualsOrQualityInInfixExprIntention {
  def familyName = "Replace equals or quality in infix expression"
}

class ReplaceEqualsOrQualityInInfixExprIntention extends PsiElementBaseIntentionAction {
  def getFamilyName = ReplaceEqualsOrQualityInInfixExprIntention.familyName
  override def getText: String = "Replace \'" + oper + "\' with \'" + target + "\'"

  var target: String = ""
  var oper: String = ""

  def isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean = {
    val infixExpr: ScInfixExpr = PsiTreeUtil.getParentOfType(element, classOf[ScInfixExpr], false)
    if (infixExpr == null) return false

    oper = infixExpr.operation.nameId.getText
    if (oper == "equals") {
      target = " == "
    } else if (oper == "==") {
      target = " equals "
    } else {
      return false
    }


    val range: TextRange = infixExpr.operation.nameId.getTextRange
    val offset = editor.getCaretModel.getOffset
    if (!(range.getStartOffset <= offset && offset <= range.getEndOffset)) return false

    true
  }

  override def invoke(project: Project, editor: Editor, element: PsiElement) {
    val infixExpr: ScInfixExpr = PsiTreeUtil.getParentOfType(element, classOf[ScInfixExpr], false)
    if (infixExpr == null || !infixExpr.isValid) return

    val start = infixExpr.getTextRange.getStartOffset

    val expr = new StringBuilder
    expr.append(infixExpr.getBaseExpr.getText).append(target).append(infixExpr.getArgExpr.getText)

    val newInfixExpr = ScalaPsiElementFactory.createExpressionFromText(expr.toString(), element.getManager)

    val size = newInfixExpr.asInstanceOf[ScInfixExpr].operation.nameId.getTextRange.getStartOffset -
            newInfixExpr.getTextRange.getStartOffset

    inWriteAction {
      infixExpr.replace(newInfixExpr)
      editor.getCaretModel.moveToOffset(start + size)
      PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument)
    }

  }
}
