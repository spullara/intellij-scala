package org.jetbrains.plugins.scala
package codeInsight.intention.expression

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.psi.util.PsiTreeUtil
import extensions._
import com.intellij.psi.{PsiDocumentManager, PsiElement}
import com.intellij.openapi.util.TextRange
import lang.psi.api.expr._
import collection.mutable.HashMap
import com.intellij.codeInsight.hint.HintManager
import lang.psi.impl.ScalaPsiElementFactory
import lang.psi.api.statements.params.ScParameter
import lang.psi.api.ScalaRecursiveElementVisitor
import com.intellij.openapi.application.ApplicationManager
import codeInspection.InspectionBundle
import lang.refactoring.util.ScalaRefactoringUtil

/**
 * @author Ksenia.Sautina
 * @since 4/18/12
 */

object IntroduceImplicitParameterIntention {
  def familyName = "Introduce implicit parameter"
}

class IntroduceImplicitParameterIntention extends PsiElementBaseIntentionAction {
  def getFamilyName = IntroduceImplicitParameterIntention.familyName

  override def getText: String = getFamilyName

  def isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean = {
    val expr: ScFunctionExpr = PsiTreeUtil.getParentOfType(element, classOf[ScFunctionExpr], false)
    if (expr == null) return false

    val range: TextRange = expr.params.getTextRange
    val offset = editor.getCaretModel.getOffset
    if ((range.getStartOffset <= offset && offset <= range.getEndOffset + 3)) return true

    false
  }

  override def invoke(project: Project, editor: Editor, element: PsiElement) {
    val expr: ScFunctionExpr = PsiTreeUtil.getParentOfType(element, classOf[ScFunctionExpr], false)
    if (expr == null || !expr.isValid) return

    val buf = new StringBuilder
    buf.append(expr.result.get.getText)

    val startOffset = expr.getTextRange.getStartOffset
    val diff = expr.result.get.getTextRange.getStartOffset
    var previousOffset = -1
    var occurances: HashMap[String, Int] = new HashMap[String, Int]
    occurances = seekParams(expr)

    if (occurances.size == 0 || occurances.size != expr.parameters.size) {
      showErrorHint(InspectionBundle.message("introduce.implicit.incorrect.count"))
      return
    }

    for (p <- expr.parameters) {
      if (!occurances.keySet.contains(p.name) || occurances(p.name) < previousOffset) {
        showErrorHint(InspectionBundle.message("introduce.implicit.incorrect.order"))
        return
      }
      previousOffset = occurances(p.name)
    }

    for (p <- expr.parameters.reverse) {
      var newParam = "_"
      if (p.typeElement != None) {
        newParam = "(_: " + p.typeElement.get.getText + ")"
      }
      val offset = occurances(p.name) - diff
      buf.replace(offset, offset + p.name.length, newParam)
    }

    inWriteAction {
      val newExpr = ScalaPsiElementFactory.createExpressionFromText(buf.toString(), element.getManager)

      if (!isValidExpr(newExpr, expr.parameters.length)) {
        showErrorHint(InspectionBundle.message("introduce.implicit.not.allowed.here"))
        return
      }

      expr.replace(newExpr)
      editor.getCaretModel.moveToOffset(startOffset)
      PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument)
    }

    def seekParams(fun: ScExpression): HashMap[String, Int] = {
      val map: HashMap[String, Int] = new HashMap[String, Int]()
      var clearMap = false
      val visitor = new ScalaRecursiveElementVisitor {
        override def visitReferenceExpression(expr: ScReferenceExpression) {
          expr.resolve() match {
            case p: ScParameter => {
              if (!map.keySet.contains(expr.getText)) {
                map.put(expr.getText, expr.getTextRange.getStartOffset)
              } else {
                clearMap = true
              }
            }
            case _ =>
          }
          super.visitReferenceExpression(expr)
        }
      }
      fun.accept(visitor)
      if (clearMap) map.clear()
      map
    }

    def showErrorHint(hint: String) {
      if (ApplicationManager.getApplication.isUnitTestMode) {
        throw new RuntimeException(hint)
      } else {
        HintManager.getInstance().showErrorHint(editor, hint)
      }
    }

    def isValidExpr(expr: ScExpression, paramCount: Int): Boolean = {
      if (ScUnderScoreSectionUtil.underscores(expr).length == paramCount) return true
      expr match {
        case e: ScBlockExpr if (e.exprs.size == 1) =>
          isValidExpr(e.exprs(0), paramCount)
        case e: ScParenthesisedExpr =>
          isValidExpr(ScalaRefactoringUtil.unparExpr(e), paramCount)
        case _ => false
      }
    }
  }
}
