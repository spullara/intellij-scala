package org.jetbrains.plugins.scala
package lang
package refactoring
package namesSuggester

import _root_.scala.collection.mutable.HashSet
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import psi.types._
import psi.api.expr._
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.jetbrains.plugins.scala.lang.psi.api.base.ScReferenceElement
import _root_.scala.collection.mutable.ArrayBuffer
import result.TypingContext
import util.{NameValidator, ScalaNamesUtil}
import extensions.{toPsiNamedElementExt, toPsiClassExt}

/**
* User: Alexander.Podkhalyuz
* Date: 26.06.2008
*/

object NameSuggester {
  private def emptyValidator(project: Project) = new NameValidator {
    def getProject(): Project = project
    def validateName(name: String, increaseNumber: Boolean): String = name
  }
  def suggestNames(expr: ScExpression): Array[String] = suggestNames(expr, emptyValidator(expr.getProject))
  def suggestNames(expr: ScExpression, validator: NameValidator): Array[String] = {
    val names = new ArrayBuffer[String]

    val types = new ArrayBuffer[ScType]()
    val typez = expr.getType(TypingContext.empty).getOrElse(null)
    if (typez != null && typez != Unit) types += typez
    expr.getTypeWithoutImplicits(TypingContext.empty).foreach(types += _)
    expr.getTypeIgnoreBaseType(TypingContext.empty).foreach(types += _)
    if (typez != null && typez == Unit) types += typez
    
    for (tpe <- types.reverse) {generateNamesByType(tpe)(names, validator)}
    generateNamesByExpr(expr)(names, validator)
    if (names.size == 0) {
      names += validator.validateName("value", true)
    }

    (for (name <- names if name != "" && ScalaNamesUtil.isIdentifier(name) || name == "class") yield {
      if (name != "class") name else "clazz"
    }).toList.reverse.toArray
  }

  def suggestNamesByType(typez: ScType): Array[String] = {
    val names = new ArrayBuffer[String]
    generateNamesByType(typez)(names, emptyValidator(null))
    val result = names.map {
      case "class" => "clazz"
      case s => s
    }.filter(name => name != "" && ScalaNamesUtil.isIdentifier(name))
    if (result.length == 0) {
      Array("value")
    } else result.reverse.toArray
  }
  
  private def add(s: String)(implicit validator: NameValidator, names: ArrayBuffer[String]) {
    val name = validator.validateName(s, true)
    if (!names.contains(name))
      names += name
  }

  private def generateNamesByType(typez: ScType)(implicit names: ArrayBuffer[String], validator: NameValidator) {
    
    typez match {
      case ValType(name) => {
        name match {
          case "Int" => add("i")
          case "Unit" => add("unit")
          case "Byte" => add("byte")
          case "Long" => add("l")
          case "Float" => add("fl")
          case "Double" => add("d")
          case "Short" => add("sh")
          case "Boolean" => add("b")
          case "Char" => add("c")
          case _ =>
        }
      }
      case ScTupleType(comps) => add("tuple")
      case ScFunctionType(ret, params) if params.length == 0 => generateNamesByType(ret)
      case ScFunctionType(ret, params) => add("function");
      case ScDesignatorType(e) => {
        val name = e.name
        if (name != null && name.toUpperCase == name) {
          add(deleteNonLetterFromString(name).toLowerCase)
        } else if (name == "String") {
          add("s")
        } else {
          generateCamelNames(name)
        }
      }
      case ScProjectionType(p, e, s) => {
        val name = e.name
        if (name != null && name.toUpperCase == name) {
          add(deleteNonLetterFromString(name).toLowerCase)
        } else if (name == "String") {
          add("s")
        } else {
          generateCamelNames(name)
        }
      }
      case ScParameterizedType(des@ScDesignatorType(c: PsiClass), Seq(arg)) if c.qualifiedName == "scala.Array" => {
        var s = ""
        arg match {
          case ValType(name) => {
            s = name + "s"
          }
          case ScTupleType(_) => s = "Tuples"
          case ScFunctionType(_,_) => s = "Functions"
          case ScDesignatorType(e) => {
            val seq: Seq[String] = getCamelNames(e.name)
            if (seq.length > 0) {
              s = seq(seq.length - 1).substring(0,1).toUpperCase + seq(seq.length - 1).substring(1, seq(seq.length - 1).length) + "s" 
            }
          }
          case _ => 
        }
        if (s != "") add("arrayOf" + s)
        generateNamesByType(des)
      }
      case JavaArrayType(arg) => {
        //todo: remove duplicate
        var s = ""
        arg match {
          case ValType(name) => {
            s = name + "s"
          }
          case ScTupleType(_) => s = "Tuples"
          case ScFunctionType(_,_) => s = "Functions"
          case ScDesignatorType(e) => {
            val seq: Seq[String] = getCamelNames(e.name)
            if (seq.length > 0) {
              s = seq(seq.length - 1).substring(0,1).toUpperCase + seq(seq.length - 1).substring(1, seq(seq.length - 1).length) + "s"
            }
          }
          case _ =>
        }
        if (s != "") add("arrayOf" + s)
      }
      case ScParameterizedType(des, typeArgs) => {
        generateNamesByType(des)
      }
      case ScCompoundType(comps, _, _, _) => {
        if (comps.size > 0) generateNamesByType(comps(0))
      }
      case _ =>
    }
  }

  private def generateNamesByExpr(expr: ScExpression)(implicit names: ArrayBuffer[String], validator: NameValidator) {
    expr match {
      case _: ScThisReference => add("thisInstance")
      case _: ScSuperReference => add("superInstance")
      case x: ScReferenceElement if x.refName != null => {
        val name = x.refName
        if (name != null && name.toUpperCase == name) {
          add(name.toLowerCase)
        } else {
          generateCamelNames(name)
        }
      }
      case x: ScMethodCall => {
        generateNamesByExpr(x.getEffectiveInvokedExpr)
      }
      case _ => expr.getContext match {
        case x: ScAssignStmt => x.assignName.foreach(add(_))
        case x: ScArgumentExprList => x.matchedParameters.getOrElse(Map.empty).get(expr) match {
          case Some(parameter) => add(parameter.name)
          case _ =>
        }
        case _ =>
      }
    }
  }

  private def generateCamelNames(name: String)(implicit names: ArrayBuffer[String], validator: NameValidator) {
    if (name == "") return
    val s1 = deleteNonLetterFromString(name)
    val s = if (Array("get", "set", "is").map(s1.startsWith(_)).contains(true))
              s1.charAt(0) match {
                case 'g' | 's' => s1.substring(3,s1.length)
                case _ => s1.substring(2,s1.length)
              }
            else s1
    for (i <- 0 to s.length - 1) {
      if (i == 0) {
        val candidate = s.substring(0, 1).toLowerCase + s.substring(1)
        add(candidate)
      }
      else if (s(i) >= 'A' && s(i) <= 'Z') {
        val candidate = s.substring(i, i + 1).toLowerCase + s.substring(i + 1)
        add(candidate)
      }
    }
  }

  private def getCamelNames(name: String): Seq[String] = {
    if (name == "") return Seq.empty
    val s1 = deleteNonLetterFromString(name)
    val names = new ArrayBuffer[String]
    val s = if (Array("get", "set", "is").map(s1.startsWith(_)).contains(true))
              s1.charAt(0) match {
                case 'g' | 's' => s1.substring(3,s1.length)
                case _ => s1.substring(2,s1.length)
              }
            else s1
    for (i <- 0 to s.length - 1) {
      if (i == 0) {
        val candidate = s.substring(0, 1).toLowerCase + s.substring(1)
        names += candidate
      }
      else if (s(i) >= 'A' && s(i) <= 'Z') {
        val candidate = s.substring(i, i + 1).toLowerCase + s.substring(i + 1)
        names += candidate
      }
    }
    names.toSeq
  }

  private def deleteNonLetterFromString(s: String): String = {
    val pattern: Pattern = Pattern.compile("[^a-zA-Z]");
    val matcher: Matcher = pattern.matcher(s);
    matcher.replaceAll("");
  }
}