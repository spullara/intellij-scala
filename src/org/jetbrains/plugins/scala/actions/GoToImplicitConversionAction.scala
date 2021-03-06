package org.jetbrains.plugins.scala.actions

import com.intellij.psi.util.PsiUtilBase
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import com.intellij.openapi.actionSystem.{PlatformDataKeys, AnActionEvent, AnAction}
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaRefactoringUtil
import com.intellij.openapi.ui.popup.JBPopupFactory
import javax.swing.{DefaultListModel, JList}
import org.jetbrains.plugins.scala.lang.psi.presentation.ScImplicitFunctionListCellRenderer
import com.intellij.psi.{PsiWhiteSpace, PsiElement, NavigatablePsiElement, PsiNamedElement}
import collection.mutable.ArrayBuffer
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScUnderScoreSectionUtil, ScExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction

/**
 * User: Alexander Podkhalyuzin
 * Date: 02.06.2010
 */

class GoToImplicitConversionAction extends AnAction("Go to implicit conversion action") {
  override def update(e: AnActionEvent) {
    ScalaActionUtil.enableAndShowIfInScalaFile(e)
  }

  def actionPerformed(e: AnActionEvent) {
    val context = e.getDataContext
    val project = PlatformDataKeys.PROJECT.getData(context)
    val editor = PlatformDataKeys.EDITOR.getData(context)
    if (project == null || editor == null) return
    val file = PsiUtilBase.getPsiFileInEditor(editor, project)
    if (!file.isInstanceOf[ScalaFile]) return

    def forExpr(expr: ScExpression): Boolean = {
      val fromUnder = 
        if (ScUnderScoreSectionUtil.isUnderscoreFunction(expr)) {
          val conv1 = expr.getImplicitConversions(false)
          val conv2 = expr.getImplicitConversions(true)
          val cond = conv1._2 != None ^ conv2._2 != None
          if (cond) conv2._2 != None
          else false
        } else false
      val implicitConversions = expr.getImplicitConversions(fromUnder)
      val functions = implicitConversions._1
      if (functions.length == 0) return true
      val conversionFun = implicitConversions._2.getOrElse(null)
      val model: DefaultListModel = new DefaultListModel
      if (conversionFun != null) {
        model.addElement(conversionFun)
      }
      for (element <- functions if element ne conversionFun) {
        model.addElement(element)
      }
      val list: JList = new JList(model)
      list.setCellRenderer(new ScImplicitFunctionListCellRenderer(conversionFun))

      val builder = JBPopupFactory.getInstance.createListPopupBuilder(list)
      builder.setTitle("Choose implicit conversion method:").
        setMovable(false).setResizable(false).setRequestFocus(true).
        setItemChoosenCallback(new Runnable {
        def run() {
          val method = list.getSelectedValue.asInstanceOf[PsiNamedElement]
          method match {
            case f: ScFunction =>
              f.getSyntheticNavigationElement match {
                case Some(n: NavigatablePsiElement) => n.navigate(true)
                case _ => f.navigate(true)
              }
            case n: NavigatablePsiElement => n.navigate(true)
            case _ => //do nothing
          }
        }
      }).createPopup.showInBestPositionFor(editor)
      false
    }

    if (editor.getSelectionModel.hasSelection) {
      val selectionStart = editor.getSelectionModel.getSelectionStart
      val selectionEnd = editor.getSelectionModel.getSelectionEnd
      val opt = ScalaRefactoringUtil.getExpression(project, editor, file, selectionStart, selectionEnd)
      opt match {
        case Some((expr, _)) =>
          if (forExpr(expr)) return
        case _ => return
      }
    } else {
      val offset = editor.getCaretModel.getOffset
      val element: PsiElement = file.findElementAt(offset) match {
        case w: PsiWhiteSpace if w.getTextRange.getStartOffset == offset &&
          w.getText.contains("\n") => file.findElementAt(offset - 1)
        case p => p
      }
      def getExpressions(guard: Boolean): Array[ScExpression] = {
        val res = new ArrayBuffer[ScExpression]
        var parent = element
        while (parent != null) {
          parent match {
            case expr: ScExpression if guard || expr.getImplicitConversions(false)._2 != None ||
              (ScUnderScoreSectionUtil.isUnderscoreFunction(expr) &&
                expr.getImplicitConversions(true)._2 != None) => res += expr
            case _ =>
          }
          parent = parent.getParent
        }
        res.toArray
      }
      val expressions = {
        val falseGuard = getExpressions(false)
        if (falseGuard.length != 0) falseGuard
        else getExpressions(true)
      }
      def chooseExpression(expr: ScExpression) {
        editor.getSelectionModel.setSelection(expr.getTextRange.getStartOffset,
          expr.getTextRange.getEndOffset)
        forExpr(expr)
      }
      if (expressions.length == 0)
        editor.getSelectionModel.selectLineAtCaret()
      else if (expressions.length == 1) {
        chooseExpression(expressions(0))
      } else {
        ScalaRefactoringUtil.showChooser(editor, expressions, elem =>
          chooseExpression(elem.asInstanceOf[ScExpression]), "Expressions", (expr: ScExpression) => {
          ScalaRefactoringUtil.getShortText(expr)
        })
      }
    }
  }
}