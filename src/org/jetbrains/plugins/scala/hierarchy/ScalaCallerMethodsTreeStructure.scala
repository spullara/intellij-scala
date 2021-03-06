package org.jetbrains.plugins.scala.hierarchy

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.openapi.project.Project
import com.intellij.psi._
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.util.ArrayUtil
import com.intellij.util.Processor
import com.intellij.ide.hierarchy.call.CallHierarchyNodeDescriptor
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import collection.mutable.{HashMap, HashSet}
import util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScMember
import org.jetbrains.plugins.scala.extensions.toPsiMemberExt

/**
 * @author Alexander Podkhalyuzin
 */

final class ScalaCallerMethodsTreeStructure(project: Project, method: PsiMethod, scopeType: String)
  extends HierarchyTreeStructure(project, new CallHierarchyNodeDescriptor(project, null, method, true, false)) {

  protected def buildChildren(descriptor: HierarchyNodeDescriptor): Array[AnyRef] = {
    val enclosingElement: PsiMember = (descriptor.asInstanceOf[CallHierarchyNodeDescriptor]).getEnclosingElement
    if (!(enclosingElement.isInstanceOf[PsiMethod])) {
      return ArrayUtil.EMPTY_OBJECT_ARRAY
    }
    val method: PsiMethod = enclosingElement.asInstanceOf[PsiMethod]
    val baseMethod: PsiMethod = (getBaseDescriptor.asInstanceOf[CallHierarchyNodeDescriptor]).getTargetElement.asInstanceOf[PsiMethod]
    val containing = baseMethod match {
      case mem: ScMember => mem.getContainingClassLoose
      case x => x.containingClass
    }
    val searchScope: SearchScope = getSearchScope(scopeType, containing)
    val originalClass: PsiClass = method.containingClass
    assert(originalClass != null)
    val originalType: PsiClassType = JavaPsiFacade.getElementFactory(myProject).createType(originalClass)
    val methodsToFind = new HashSet[PsiMethod]
    methodsToFind += method
    methodsToFind ++= {
      method match {
        case fun: ScFunction => fun.superMethods
        case _ => method.findDeepestSuperMethods
      }
    }
    val methodToDescriptorMap = new HashMap[PsiMember, CallHierarchyNodeDescriptor]
    for (methodToFind <- methodsToFind) {
      MethodReferencesSearch.search(methodToFind, searchScope, true).forEach(new Processor[PsiReference] {
        def process(reference: PsiReference): Boolean = {
          val element: PsiElement = reference.getElement
          val key: PsiMember = PsiTreeUtil.getNonStrictParentOfType(element, classOf[PsiMethod], classOf[PsiClass])
          methodToDescriptorMap synchronized {
            var d: CallHierarchyNodeDescriptor = methodToDescriptorMap.get(key) match {
              case Some(call) =>
                if (!call.hasReference(reference)) {
                  call.incrementUsageCount
                }
                call
              case _ =>
                val newD = new CallHierarchyNodeDescriptor(myProject, descriptor, element, false, true)
                methodToDescriptorMap.put(key, newD)
                newD
            }
            d.addReference(reference)
          }
          return true
        }
      })
    }
    return methodToDescriptorMap.values.toArray
  }

  override def isAlwaysShowPlus: Boolean = {
    return true
  }

}

