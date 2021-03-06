package org.jetbrains.plugins.scala.lang.resolve.processor

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.ScSyntheticClass
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.usages.{ImportExprUsed, ImportSelectorUsed, ImportWildcardSelectorUsed, ImportUsed}
import com.intellij.psi.{PsiElement, PsiMember, PsiPackage, PsiClass}
import org.jetbrains.plugins.scala.lang.resolve.{ResolveUtils, ScalaResolveResult}
import collection.mutable.HashSet
import org.jetbrains.plugins.scala.extensions.toPsiClassExt

/**
 * User: Alexander Podkhalyuzin
 * Date: 01.12.11
 */

trait PrecedenceHelper[T] {
  this: BaseProcessor =>

  protected def getPlace: PsiElement
  protected lazy val placePackageName: String = ResolveUtils.getPlacePackage(getPlace)
  protected val levelSet: java.util.HashSet[ScalaResolveResult] = new java.util.HashSet
  protected val qualifiedNamesSet: HashSet[T] = new HashSet[T]
  protected val levelQualifiedNamesSet: HashSet[T] = new HashSet[T]

  protected def getQualifiedName(result: ScalaResolveResult): T

  /**
   * Returns highest precedence of all resolve results.
   * 1 - import a._
   * 2 - import a.x
   * 3 - definition or declaration
   */
  protected def getTopPrecedence(result: ScalaResolveResult): Int
  protected def setTopPrecedence(result: ScalaResolveResult, i: Int)
  protected def filterNot(p: ScalaResolveResult, n: ScalaResolveResult): Boolean = {
    getPrecedence(p) < getTopPrecedence(n)
  }
  protected def isCheckForEqualPrecedence = true

  /**
   * Do not add ResolveResults through candidatesSet. It may break precedence. Use this method instead.
   */
  protected def addResult(result: ScalaResolveResult): Boolean = addResults(Seq(result))
  protected def addResults(results: Seq[ScalaResolveResult]): Boolean = {
    if (results.length == 0) return true
    lazy val qualifiedName: T = getQualifiedName(results(0))
    def addResults() {
      if (qualifiedName != null) levelQualifiedNamesSet += qualifiedName
      val iterator = results.iterator
      while (iterator.hasNext) {
        levelSet.add(iterator.next())
      }
    }
    val currentPrecedence = getPrecedence(results(0))
    val topPrecedence = getTopPrecedence(results(0))
    if (currentPrecedence < topPrecedence) return false
    else if (currentPrecedence == topPrecedence && levelSet.isEmpty) return false
    else if (currentPrecedence == topPrecedence && !levelSet.isEmpty) {
      if (isCheckForEqualPrecedence && qualifiedName != null &&
        (levelQualifiedNamesSet.contains(qualifiedName) ||
        qualifiedNamesSet.contains(qualifiedName))) {
        return false
      } else if (qualifiedName != null && qualifiedNamesSet.contains(qualifiedName)) return false
      addResults()
    } else {
      if (qualifiedName != null && (levelQualifiedNamesSet.contains(qualifiedName) ||
        qualifiedNamesSet.contains(qualifiedName))) {
        return false
      } else {
        setTopPrecedence(results(0), currentPrecedence)
        val levelSetIterator = levelSet.iterator()
        while (levelSetIterator.hasNext) {
          val next = levelSetIterator.next()
          if (filterNot(next, results(0))) {
            levelSetIterator.remove()
          }
        }
        levelQualifiedNamesSet.clear()
        addResults()
      }
    }
    true
  }

  protected def getPrecedence(result: ScalaResolveResult): Int = result.getPrecedence(getPlace, placePackageName)
}