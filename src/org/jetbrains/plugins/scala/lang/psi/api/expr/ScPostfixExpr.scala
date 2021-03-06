package org.jetbrains.plugins.scala
package lang
package psi
package api
package expr

import org.jetbrains.plugins.scala.lang.psi.ScalaPsiElement

/** 
* @author Alexander Podkhalyuzin
* Date: 06.03.2008
*/

trait ScPostfixExpr extends ScExpression with MethodInvocation with ScSugarCallExpr {
  def operand = findChildrenByClassScala(classOf[ScExpression]).apply(0)
  def operation : ScReferenceExpression = findChildrenByClassScala(classOf[ScExpression]).apply(1) match {
    case re : ScReferenceExpression => re
    case _ =>
      throw new UnsupportedOperationException("Postfix Expr Operation is not reference expression: " + this.getText)
  }

  def getBaseExpr: ScExpression = operand
}