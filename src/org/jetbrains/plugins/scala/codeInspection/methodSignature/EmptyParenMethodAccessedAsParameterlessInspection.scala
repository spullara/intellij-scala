package org.jetbrains.plugins.scala.codeInspection.methodSignature

import com.intellij.codeInspection._
import org.intellij.lang.annotations.Language
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.extensions.Parent
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import quickfix.{AddGenericCallParentheses, AddCallParentheses}
import org.jetbrains.plugins.scala.lang.psi.types.{ScFunctionType, ScType}
import org.jetbrains.plugins.scala.lang.psi.types.result.{TypeResult, TypingContext}

/**
 * Pavel Fatin
 *
 * TODO test:
 * {{{
 *   object A {
 *     def foo(): Int = 1
 *     foo // warn
 *
 *     def goo(x: () => Int) = 1
 *     goo(foo) // okay
 *
 *     foo : () => Int // okay
 *
 *     def bar[A]() = 0
 *     bar[Int] // warn
 *     bar[Int]: () => Any // okay
 *   }
 * }}}
 */
class EmptyParenMethodAccessedAsParameterlessInspection extends AbstractMethodSignatureInspection(
  "ScalaEmptyParenMethodAccessedAsParameterless", "Empty-paren method accessed as parameterless") {

  def actionFor(holder: ProblemsHolder) = {
    case e: ScReferenceExpression if e.isValid =>
      e.getParent match {
        case gc: ScGenericCall =>
          ScalaPsiUtil.findCall(gc) match {
            case None => check(e, holder, gc.getType(TypingContext.empty))
            case Some(_) =>
          }
        case _: ScMethodCall | _: ScInfixExpr | _: ScPrefixExpr | _: ScUnderscoreSection => // okay
        case _ => check(e, holder, e.getType(TypingContext.empty))
      }
  }

  private def check(e: ScReferenceExpression, holder: ProblemsHolder, callType: TypeResult[ScType]) {
    e.resolve() match {
      case (f: ScFunction) if f.isEmptyParen =>
        callType.toOption.flatMap(ScType.extractFunctionType) match {
          case Some(ScFunctionType(_, Seq())) =>
          // might have been eta-expanded to () => A, so don't worn.
          // this avoids false positives. To be more accurate, we would need an 'etaExpanded'
          // flag in ScalaResolveResult.
          case None =>
            holder.registerProblem(e.nameId, getDisplayName, new AddCallParentheses(e))
        }
      case _ =>
    }
  }
}