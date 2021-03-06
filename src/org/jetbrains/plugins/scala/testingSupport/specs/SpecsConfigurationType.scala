package org.jetbrains.plugins.scala
package testingSupport
package specs

import _root_.java.lang.String
import _root_.javax.swing.Icon
import com.intellij.execution._
import com.intellij.openapi.module.Module
import com.intellij.psi._
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import configurations.{ConfigurationType, JavaRunConfigurationModule, RunConfiguration, ConfigurationFactory}
import icons.Icons
import lang.psi.api.toplevel.typedef.ScTypeDefinition
import lang.psi.ScalaPsiUtil
import lang.psi.api.base.ScLiteral
import lang.psi.impl.ScalaPsiManager

/**
 * User: Alexander Podkhalyuzin
 * Date: 03.05.2009
 */


/**
 * User: Alexander Podkhalyuzin
 * Date: 22.02.2009
 */

class SpecsConfigurationType extends ConfigurationType {
  val confFactory = new SpecsRunConfigurationFactory(this)

  def getIcon: Icon = Icons.SCALA_TEST

  def getDisplayName: String = "Specs"

  def getConfigurationTypeDescription: String = "Specs testing framework run configuration"

  def getConfigurationFactories: Array[ConfigurationFactory] = Array[ConfigurationFactory](confFactory)

  def getId: String = "SpecsRunConfiguration" //if you want to change id, change it in Android plugin too


  def createConfigurationByLocation(location: Location[_ <: PsiElement]): RunnerAndConfigurationSettings = {
    val element = location.getPsiElement
    if (element.isInstanceOf[PsiPackage] || element.isInstanceOf[PsiDirectory]) {
      val pack: PsiPackage = element match {
        case dir: PsiDirectory => JavaDirectoryService.getInstance.getPackage(dir)
        case pack: PsiPackage => pack
      }
      if (pack == null) return null
      val displayName = ScalaBundle.message("test.in.scope.specs.presentable.text", pack.getQualifiedName)
      val settings = RunManager.getInstance(location.getProject).createRunConfiguration(displayName, confFactory)
      val configuration = settings.getConfiguration.asInstanceOf[SpecsRunConfiguration]
      configuration.setTestPackagePath(pack.getQualifiedName)
      configuration.setGeneratedName(displayName)
      JavaRunConfigurationExtensionManager.getInstance.extendCreatedConfiguration(configuration, location)
      return settings
    }
    val parent: ScTypeDefinition = PsiTreeUtil.getParentOfType(element, classOf[ScTypeDefinition], false)
    val parentLiteral: ScLiteral = PsiTreeUtil.getParentOfType(element, classOf[ScLiteral], false)
    if (parent == null) return null
    val suiteClazz = ScalaPsiManager.instance(parent.getProject).getCachedClass("org.specs.Specification",
      element.getResolveScope, ScalaPsiManager.ClassCategory.TYPE)
    if (suiteClazz == null) return null
    if (!ScalaPsiUtil.cachedDeepIsInheritor(parent, suiteClazz)) return null
    val settings = RunManager.getInstance(location.getProject).createRunConfiguration(parent.name, confFactory)
    val runConfiguration = settings.getConfiguration.asInstanceOf[SpecsRunConfiguration]
    val testClassPath = parent.qualifiedName
    runConfiguration.setTestClassPath(testClassPath)

    // If the selected element is a non-empty string literal, we assume that this
    // is the name of an example to be filtered.
    Option(parentLiteral) match {
      case Some(x) if x.isString =>
        x.getValue match {
          case exampleName: String if exampleName.nonEmpty =>
            val options = runConfiguration.getJavaOptions
            val name = testClassPath + "::" + exampleName
            runConfiguration.setGeneratedName(name)
            runConfiguration.setName(name)
            runConfiguration.setExampleFilter(exampleName)
          case _ =>
        }
      case _ =>
    }
    try {
      val module = ScalaPsiUtil.getModule(element)
      if (module != null) {
        runConfiguration.setModule(module)
      }
    }
    catch {
      case e =>
    }
    JavaRunConfigurationExtensionManager.getInstance.extendCreatedConfiguration(runConfiguration, location)
    settings
  }

  def isConfigurationByLocation(configuration: RunConfiguration, location: Location[_ <: PsiElement]): Boolean = {
    val element = location.getPsiElement
    if (element.isInstanceOf[PsiPackage] || element.isInstanceOf[PsiDirectory]) {
      val pack: PsiPackage = element match {
        case dir: PsiDirectory => JavaDirectoryService.getInstance.getPackage(dir)
        case pack: PsiPackage => pack
      }
      if (pack == null) return false
      configuration match {
        case configuration: SpecsRunConfiguration => {
          return configuration.getTestPackagePath == pack.getQualifiedName
        }
        case _ => return false
      }
    }
    val parent: ScTypeDefinition = PsiTreeUtil.getParentOfType(element, classOf[ScTypeDefinition])
    if (parent == null) return false
    val suiteClazz = ScalaPsiManager.instance(parent.getProject).getCachedClass("org.specs.Specification",
      element.getResolveScope, ScalaPsiManager.ClassCategory.TYPE)
    if (suiteClazz == null) return false
    if (!ScalaPsiUtil.cachedDeepIsInheritor(parent, suiteClazz)) return false
    configuration match {
      case configuration: SpecsRunConfiguration => parent.qualifiedName == configuration.getTestClassPath
      case _ => false
    }
  }
}