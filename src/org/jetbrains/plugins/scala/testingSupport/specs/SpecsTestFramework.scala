package org.jetbrains.plugins.scala
package testingSupport
package specs

import javax.swing.Icon
import com.intellij.ide.fileTemplates.FileTemplateDescriptor
import lang.psi.api.toplevel.typedef.ScTypeDefinition
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testIntegration.JavaTestFramework
import icons.Icons
import com.intellij.psi.{PsiElement, PsiMethod, PsiClass, JavaPsiFacade}
import lang.psi.ScalaPsiUtil
import com.intellij.lang.Language
import lang.psi.impl.ScalaPsiManager

class SpecsTestFramework extends JavaTestFramework {
  def isTestMethod(element: PsiElement): Boolean = false

  def getTestMethodFileTemplateDescriptor: FileTemplateDescriptor = null

  def getTearDownMethodFileTemplateDescriptor: FileTemplateDescriptor = null

  def getSetUpMethodFileTemplateDescriptor: FileTemplateDescriptor = null

  def getDefaultSuperClass: String = "org.specs.Specification"

  def getLibraryPath: String = ""

  def getIcon: Icon = Icons.SCALA_TEST

  def getName: String = "Specs"

  def findOrCreateSetUpMethod(clazz: PsiClass): PsiMethod = null

  def findTearDownMethod(clazz: PsiClass): PsiMethod = null

  def findSetUpMethod(clazz: PsiClass): PsiMethod = null

  def isTestClass(clazz: PsiClass, canBePotential: Boolean): Boolean = {
    val parent: ScTypeDefinition = PsiTreeUtil.getParentOfType(clazz, classOf[ScTypeDefinition], false)
    if (parent == null) return false
    val suiteClazz: PsiClass = ScalaPsiManager.instance(parent.getProject).
      getCachedClass(getMarkerClassFQName, clazz.getResolveScope, ScalaPsiManager.ClassCategory.TYPE)
    if (suiteClazz == null) return false
    ScalaPsiUtil.cachedDeepIsInheritor(parent, suiteClazz)
  }

  def getMarkerClassFQName: String = "org.specs.Specification"

  override def getLanguage: Language = ScalaFileType.SCALA_LANGUAGE
}