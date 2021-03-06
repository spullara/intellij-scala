package org.jetbrains.plugins.scala
package codeInsight.intention.expression

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.util.TextRange
import lang.psi.impl.ScalaPsiElementFactory
import extensions._
import com.intellij.psi.{PsiDocumentManager, PsiElement}
import com.intellij.openapi.editor.Editor
import lang.psi.api.base.ScLiteral
import lang.psi.api.expr.xml.ScXmlExpr
import lang.psi.api.expr._

/**
 * @author Ksenia.Sautina
 * @since 4/20/12
 */

object FlipComparisonInMethodCallExprIntention {
  def familyName = "Swap the operands of a comparison expression."
}

class FlipComparisonInMethodCallExprIntention extends PsiElementBaseIntentionAction {
  def getFamilyName = FlipComparisonInMethodCallExprIntention.familyName

  override def getText: String = getFamilyName

  def isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean = {
    val methodCallExpr: ScMethodCall = PsiTreeUtil.getParentOfType(element, classOf[ScMethodCall], false)
    if (methodCallExpr == null) return false
    if (!methodCallExpr.getInvokedExpr.isInstanceOf[ScReferenceExpression]) return false

    val oper = ((methodCallExpr.getInvokedExpr).asInstanceOf[ScReferenceExpression]).nameId.getText

    if (oper != "equals" && oper != "==" && oper != "!=" && oper != "eq" && oper != "ne" &&
            oper != ">" && oper != "<" && oper != ">=" && oper != "<=")
      return false

    val range: TextRange = methodCallExpr.getInvokedExpr.asInstanceOf[ScReferenceExpression].nameId.getTextRange
    val offset = editor.getCaretModel.getOffset
    if (!(range.getStartOffset <= offset && offset <= range.getEndOffset)) return false
    if (((methodCallExpr.getInvokedExpr).asInstanceOf[ScReferenceExpression]).isQualified) return true

    false
  }

  override def invoke(project: Project, editor: Editor, element: PsiElement) {
    val methodCallExpr : ScMethodCall = PsiTreeUtil.getParentOfType(element, classOf[ScMethodCall], false)
    if (methodCallExpr == null || !methodCallExpr.isValid) return

    val start = methodCallExpr.getTextRange.getStartOffset
    val diff = editor.getCaretModel.getOffset - ((methodCallExpr.getInvokedExpr).asInstanceOf[ScReferenceExpression]).
            nameId.getTextRange.getStartOffset
    val expr = new StringBuilder
    val qualBuilder = new StringBuilder
    val argsBuilder = new StringBuilder

    val oper = ((methodCallExpr.getInvokedExpr).asInstanceOf[ScReferenceExpression]).nameId.getText match {
      case "equals" => "equals"
      case "==" => "=="
      case "!=" => "!="
      case "eq" => "eq"
      case "ne" => "ne"
      case ">" => "<"
      case "<" => ">"
      case ">=" => "<="
      case "<=" => ">="
    }

    argsBuilder.append(methodCallExpr.args.getText)
    if (methodCallExpr.args.exprs.length == 1) {
      methodCallExpr.args.exprs.head match {
        case _: ScLiteral => argsBuilder.replace(argsBuilder.length - 1, argsBuilder.length, "").replace(0, 1, "")
        case _: ScTuple => argsBuilder.replace(argsBuilder.length - 1, argsBuilder.length, "").replace(0, 1, "")
        case _: ScReferenceExpression => argsBuilder.replace(argsBuilder.length - 1, argsBuilder.length, "").replace(0, 1, "")
        case _: ScGenericCall => argsBuilder.replace(argsBuilder.length - 1, argsBuilder.length, "").replace(0, 1, "")
        case _: ScXmlExpr => argsBuilder.replace(argsBuilder.length - 1, argsBuilder.length, "").replace(0, 1, "")
        case _: ScMethodCall => argsBuilder.replace(argsBuilder.length - 1, argsBuilder.length, "").replace(0, 1, "")
        case infix: ScInfixExpr if (infix.getBaseExpr.isInstanceOf[ScUnderscoreSection]) =>
          argsBuilder.insert(0, "(").append(")")
        case _ =>  argsBuilder
      }
    }

    val qual = methodCallExpr.asInstanceOf[ScMethodCall].getInvokedExpr.asInstanceOf[ScReferenceExpression].qualifier.get
    qualBuilder.append(qual.getText)
    var newArgs = qual.getText
    if (!(newArgs.startsWith("(") && newArgs.endsWith(")"))) {
      newArgs = qualBuilder.insert(0, "(").append(")").toString()
    }

    var newQual = argsBuilder.toString()
    if (newQual.startsWith("(") && newQual.endsWith(")")) {
      newQual = argsBuilder.toString().drop(1).dropRight(1)
    }

    val newQualExpr : ScExpression = ScalaPsiElementFactory.createExpressionFromText(newQual, element.getManager)

    expr.append(methodCallExpr.args.getText).append(".").append(oper).append(newArgs)

    val newMethodCallExpr = ScalaPsiElementFactory.createExpressionFromText(expr.toString(), element.getManager)

    newMethodCallExpr.asInstanceOf[ScMethodCall].getInvokedExpr.asInstanceOf[ScReferenceExpression].qualifier.get.replaceExpression(newQualExpr, true)

    val size = newMethodCallExpr.asInstanceOf[ScMethodCall].getInvokedExpr.asInstanceOf[ScReferenceExpression].nameId.
            getTextRange.getStartOffset - newMethodCallExpr.getTextRange.getStartOffset

    inWriteAction {
      methodCallExpr.replaceExpression(newMethodCallExpr, true)
      editor.getCaretModel.moveToOffset(start + diff + size)
      PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument)
    }
  }

}
