package org.jetbrains.plugins.scala
package lang
package psi
package impl


import api.expr.ScExpression
import api.statements.{ScFunction, ScValue, ScTypeAlias, ScVariable}
import expr.ScReferenceExpressionImpl
import lexer.ScalaTokenTypes
import psi.stubs.ScFileStub
import com.intellij.extapi.psi.PsiFileBase
import org.jetbrains.plugins.scala.lang.psi.controlFlow.Instruction
import org.jetbrains.plugins.scala.ScalaFileType
import org.jetbrains.plugins.scala.icons.Icons
import org.jetbrains.plugins.scala.lang.parser.ScalaElementTypes
import psi.api.toplevel.packaging._
import com.intellij.openapi.roots._
import com.intellij.psi._
import com.intellij.psi.impl.migration.PsiMigrationManager
import com.intellij.psi.impl.source.codeStyle.CodeEditUtil
import org.jetbrains.annotations.Nullable
import api.toplevel.ScToplevelElement
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import api.{ScControlFlowOwner, ScalaFile}
import psi.controlFlow.impl.ScalaControlFlowBuilder
import api.base.ScStableCodeReferenceElement
import scope.PsiScopeProcessor
import decompiler.{DecompilerUtil, CompiledFileAdjuster}
import collection.mutable.ArrayBuffer
import com.intellij.psi.search.GlobalSearchScope
import finder.ScalaSourceFilterScope
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.scala.extensions._
import config.ScalaFacet
import com.intellij.openapi.util.{TextRange, Key}
import caches.CachesUtil
import lang.resolve.ResolveUtils
import lang.resolve.processor.{ImplicitProcessor, ResolveProcessor, ResolverEnv}
import com.intellij.psi.impl.ResolveScopeManager
import com.intellij.util.indexing.FileBasedIndex
import util.{PsiUtilCore, PsiModificationTracker, PsiTreeUtil}
import com.intellij.openapi.diagnostic.Logger
import java.lang.String
import api.toplevel.typedef.{ScClass, ScTrait, ScObject}

class ScalaFileImpl(viewProvider: FileViewProvider)
        extends PsiFileBase(viewProvider, ScalaFileType.SCALA_FILE_TYPE.getLanguage)
                with ScalaFile with ScImportsHolder with ScDeclarationSequenceHolder
                with CompiledFileAdjuster with ScControlFlowOwner with FileResolveScopeProvider {
  override def getViewProvider = viewProvider

  override def getFileType = ScalaFileType.SCALA_FILE_TYPE

  override def toString = "ScalaFile:" + getName

  protected def findChildrenByClassScala[T >: Null <: ScalaPsiElement](clazz: Class[T]): Array[T] =
    findChildrenByClass[T](clazz)

  protected def findChildByClassScala[T >: Null <: ScalaPsiElement](clazz: Class[T]): T = findChildByClass[T](clazz)

  def isCompiled = compiled

  def sourceName: String = {
    if (isCompiled) {
      val stub = getStub
      if (stub != null) {
        return stub.asInstanceOf[ScFileStub].getFileName
      }
      val virtualFile = getVirtualFile
      DecompilerUtil.decompile(virtualFile, virtualFile.contentsToByteArray).sourceName
    }
    else ""
  }

  override def getName: String = {
    if (virtualFile != null) virtualFile.getName
    else super.getName
  }

  override def getVirtualFile: VirtualFile = {
    if (virtualFile != null) virtualFile
    else super.getVirtualFile
  }

  override def getNavigationElement: PsiElement = {
    if (!isCompiled) this
    else {
      val inner: String = getPackageNameInner
      val pName = inner + typeDefinitions.find(_.isPackageObject).map((if (inner.length > 0) "." else "") + _.name).getOrElse("")
      val sourceFile = sourceName
      val relPath = if (pName.length == 0) sourceFile else pName.replace(".", "/") + "/" + sourceFile

      // Look in libraries' sources
      val vFile = getContainingFile.getVirtualFile
      val index = ProjectRootManager.getInstance(getProject).getFileIndex
      val entries = index.getOrderEntriesForFile(vFile).toArray(OrderEntry.EMPTY_ARRAY)
      var entryIterator = entries.iterator
      while (entryIterator.hasNext) {
        val entry = entryIterator.next()
        // Look in sources of an appropriate entry
        val files = entry.getFiles(OrderRootType.SOURCES)
        val filesIterator = files.iterator
        while (!filesIterator.isEmpty) {
          val file = filesIterator.next()
          val source = file.findFileByRelativePath(relPath)
          if (source != null) {
            val psiSource = getManager.findFile(source)
            psiSource match {
              case o: PsiClassOwner => return o
              case _ =>
            }
          }
        }
      }
      entryIterator = entries.iterator
      //Look in libraries sources if file not relative to path
      while (entryIterator.hasNext) {
        val entry = entryIterator.next()
        // Look in sources of an appropriate entry
        val files = entry.getFiles(OrderRootType.SOURCES)
        val filesIterator = files.iterator
        while (filesIterator.hasNext) {
          val file = filesIterator.next()
          if (typeDefinitions.length == 0) return this
          val qual = typeDefinitions.apply(0).qualifiedName
          def scanFile(file: VirtualFile): Option[PsiElement] = {
            val children: Array[VirtualFile] = file.getChildren
            if (children != null) {
              val childIterator = children.iterator
              while (childIterator.hasNext) {
                val child = childIterator.next()
                if (child.getName == sourceFile) {
                  val psiSource = getManager.findFile(child)
                  psiSource match {
                    case o: ScalaFile =>
                      val clazzIterator = o.typeDefinitions.iterator
                      while (clazzIterator.hasNext) {
                        val clazz = clazzIterator.next()
                        if (qual == clazz.qualifiedName) {
                          return Some(o)
                        }
                      }
                    case o: PsiClassOwner =>
                      val clazzIterator = o.getClasses.iterator
                      while (clazzIterator.hasNext) {
                        val clazz = clazzIterator.next()
                        if (qual == clazz.qualifiedName) {
                          return Some(o)
                        }
                      }
                    case _ =>
                  }
                }
                scanFile(child) match {
                  case Some(s) => return Some(s)
                  case _ =>
                }
              }
            }
            None
          }
          scanFile(file) match {
            case Some(x) => return x
            case None =>
          }
        }
      }
      this
    }
  }

  private def isScriptFileImpl: Boolean = {
    val stub = getStub
    if (stub == null) {
      val empty = children.forall {
        case _: PsiWhiteSpace => true
        case _: PsiComment => true
        case _ => false
      }
      if (empty) return true // treat empty or commented files as scripts to avoid project recompilations
      val childrenIterator = getNode.getChildren(null).iterator
      while (childrenIterator.hasNext) {
        val n = childrenIterator.next()
        n.getPsi match {
          case _: ScPackaging => return false
          case _: ScValue | _: ScVariable | _: ScFunction | _: ScExpression | _: ScTypeAlias => return true
          case _ => if (n.getElementType == ScalaTokenTypes.tSH_COMMENT) return true
        }
      }
      false
    } else {
      stub.isScript
    }
  }

  def isScriptFile: Boolean = isScriptFile(true)

  def isScriptFile(withCashing: Boolean): Boolean = {
    if (!withCashing) return isScriptFileImpl
    CachesUtil.get(this, CachesUtil.IS_SCRIPT_FILE_KEY,
      new CachesUtil.MyProvider(this, (file: ScalaFile) => isScriptFileImpl)(this))
  }

  def setPackageName(name: String) {
    val basePackage = for {
      m <- Option(ScalaPsiUtil.getModule(this))
      facet <- ScalaFacet.findIn(m)
      p <- facet.basePackage
    } yield p

    setPackageName(basePackage.mkString, name)
  }

  def setPackageName(base: String, name: String) {
    if (packageName == null) return

    val splits = ScalaFileImpl.toVector(base) :: ScalaFileImpl.splitsIn(ScalaFileImpl.pathIn(this))

    val prefix: Option[Array[PsiElement]] = {
      val elements = children.takeWhile(!_.isInstanceOf[ScPackaging]).toArray
      val hasCommentsOnly = elements.forall {
        case _: PsiComment => true
        case _: PsiWhiteSpace => true
        case _ => false
      }
      if (elements.length > 0 && hasCommentsOnly) Some(elements) else None
    }

    ScalaFileImpl.stripPackagesIn(this)

    val vector = ScalaFileImpl.toVector(name)

    if (vector.nonEmpty) {
      val path = splits.foldLeft(List(vector))(ScalaFileImpl.splitAt)
      ScalaFileImpl.addPathTo(this, path)
    }

    prefix.foreach { it =>
      getNode.addChildren(it.head.getNode, it.last.getNode.getTreeNext, getNode.getFirstChildNode)
    }

    depthFirst.foreach(it => CodeEditUtil.disablePostponedFormatting(it.getNode))

    CodeEditUtil.markToReformat(getNode, true)
  }

  override def getStub: ScFileStub = super[PsiFileBase].getStub match {
    case null => null
    case s: ScFileStub => s
    case _ =>
      val faultyContainer: VirtualFile = PsiUtilCore.getVirtualFile(this)
      ScalaFileImpl.LOG.error("Scala File has wrong stub file: " + faultyContainer)
      if (faultyContainer != null && faultyContainer.isValid) {
        FileBasedIndex.getInstance.requestReindex(faultyContainer)
      }
      null
  }

  def getPackagings: Array[ScPackaging] = {
    val stub = getStub
    if (stub != null) {
      stub.getChildrenByType(ScalaElementTypes.PACKAGING, JavaArrayFactoryUtil.ScPackagingFactory)
    } else findChildrenByClass(classOf[ScPackaging])
  }

  def getPackageName: String = {
    val res = packageName
    if (res == null) ""
    else res
  }

  @Nullable
  def packageName: String = {
    if (isScriptFile) return null
    var res: String = ""
    var x: ScToplevelElement = this
    while (true) {
      val packs: Seq[ScPackaging] = x.packagings
      if (packs.length > 1) return null
      else if (packs.length == 0) return if (res.length == 0) res else res.substring(1)
      res += "." + packs(0).getPackageName
      x = packs(0)
    }
    null //impossible line
  }

  private def getPackageNameInner: String = {
    val ps = getPackagings

    def inner(p: ScPackaging, prefix: String): String = {
      val subs = p.packagings
      if (subs.length > 0 && !subs(0).isExplicit) inner(subs(0), prefix + "." + subs(0).getPackageName)
      else prefix
    }

    if (ps.length > 0 && !ps(0).isExplicit) {
      val prefix = ps(0).getPackageName
      inner(ps(0), prefix)
    } else ""
  }

  override def getClasses: Array[PsiClass] = {
    if (!isScriptFile) {
      val arrayBuffer = new ArrayBuffer[PsiClass]()
      for (definition <- typeDefinitions) {
        arrayBuffer += definition
        definition match {
          case o: ScObject =>
            o.fakeCompanionClass match {
              case Some(clazz) => arrayBuffer += clazz
              case _ =>
            }
          case t: ScTrait => arrayBuffer += t.fakeCompanionClass
          case c: ScClass =>
            c.fakeCompanionModule match {
              case Some(m) => arrayBuffer += m
              case _ =>
            }
          case _ =>
        }
      }
      arrayBuffer.toArray
    } else PsiClass.EMPTY_ARRAY
  }

  def icon = Icons.FILE_TYPE_LOGO

  override def processDeclarations(processor: PsiScopeProcessor,
                                   state: ResolveState,
                                   lastParent: PsiElement,
                                   place: PsiElement): Boolean = {
    if (isScriptFile && !super[ScDeclarationSequenceHolder].processDeclarations(processor,
      state, lastParent, place)) return false

    if (!super[ScImportsHolder].processDeclarations(processor,
      state, lastParent, place)) return false

    if (context != null) {
      return true
    }

    val scope = place.getResolveScope

    place match {
      case ref: ScStableCodeReferenceElement if ref.refName == "_root_" && ref.qualifier == None => {
        val top = ScPackageImpl(JavaPsiFacade.getInstance(getProject).findPackage(""))
        if (top != null && !processor.execute(top, state.put(ResolverEnv.nameKey, "_root_"))) return false
        state.put(ResolverEnv.nameKey, null)
      }
      case ref: ScReferenceExpressionImpl if ref.refName == "_root_" && ref.qualifier == None => {
        val top = ScPackageImpl(JavaPsiFacade.getInstance(getProject).findPackage(""))
        if (top != null && !processor.execute(top, state.put(ResolverEnv.nameKey, "_root_"))) return false
        state.put(ResolverEnv.nameKey, null)
      }
      case _ => {
        val defaultPackage = ScPackageImpl(JavaPsiFacade.getInstance(getProject).findPackage(""))
        if (place != null && PsiTreeUtil.getParentOfType(place, classOf[ScPackaging]) == null) {
          if (defaultPackage != null &&
            !ResolveUtils.packageProcessDeclarations(defaultPackage, processor, state, null, place)) return false
        }
        else if (defaultPackage != null && !processor.isInstanceOf[ImplicitProcessor]) { //we will add only packages
          //only packages resolve, no classes from default package
          val name = processor match {case rp: ResolveProcessor => rp.ScalaNameHint.getName(state) case _ => null}
          val facade = JavaPsiFacade.getInstance(getProject).asInstanceOf[com.intellij.psi.impl.JavaPsiFacadeImpl]
          if (name == null) {
            val packages = defaultPackage.getSubPackages(scope)
            val iterator = packages.iterator
            while (iterator.hasNext) {
              val pack = iterator.next()
              if (!processor.execute(pack, state)) return false
            }
            val migration = PsiMigrationManager.getInstance(getProject).getCurrentMigration
            if (migration != null) {
              val list = migration.getMigrationPackages("")
              val packages = list.toArray(new Array[PsiPackage](list.size)).map(ScPackageImpl(_))
              val iterator = packages.iterator
              while (iterator.hasNext) {
                val pack = iterator.next()
                if (!processor.execute(pack, state)) return false
              }
            }
          } else {
            val aPackage: PsiPackage = ScPackageImpl(facade.findPackage(name))
            if (aPackage != null && !processor.execute(aPackage, state)) return false
          }
        }
      }
    }

    if (!SbtFile.processDeclarations(this, processor, state, lastParent, place)) return false

    val implObjIter = ImplicitlyImported.allImplicitlyImportedObjects(getManager, scope).iterator
    while (implObjIter.hasNext) {
      val clazz = implObjIter.next()
      ProgressManager.checkCanceled()

      if (clazz != null && !isScalaPredefinedClass &&
        !clazz.processDeclarations(processor, state, null, place)) return false
    }

    import toplevel.synthetic.SyntheticClasses

    val classes = SyntheticClasses.get(getProject)
    val synthIterator = classes.getAll.iterator
    while (synthIterator.hasNext) {
      val synth = synthIterator.next()
      ProgressManager.checkCanceled()
      if (!processor.execute(synth, state)) return false
    }

    val synthObjectsIterator = classes.syntheticObjects.iterator
    while (synthObjectsIterator.hasNext) {
      val synth = synthObjectsIterator.next()
      ProgressManager.checkCanceled()
      if (!processor.execute(synth, state)) return false
    }

    if (isScriptFile) {
      val syntheticValueIterator = SyntheticClasses.get(getProject).getScriptSyntheticValues.iterator
      while (syntheticValueIterator.hasNext) {
        val syntheticValue = syntheticValueIterator.next()
        ProgressManager.checkCanceled()
        if (!processor.execute(syntheticValue, state)) return false
      }
    }

    val implPIterator = ImplicitlyImported.packages.iterator
    while (implPIterator.hasNext) {
      val implP = implPIterator.next()
      ProgressManager.checkCanceled()
      val pack: PsiPackage = JavaPsiFacade.getInstance(getProject).findPackage(implP)
      if (pack != null && !ResolveUtils.packageProcessDeclarations(pack, processor, state, null, place)) return false
    }

    true
  }
  
  private def isScalaPredefinedClass = {
    def inner(file: ScalaFile): java.lang.Boolean = {
      java.lang.Boolean.valueOf(file.typeDefinitions.length == 1 &&
        Set("scala", "scala.Predef").contains(file.typeDefinitions.apply(0).qualifiedName))
    }
    CachesUtil.get[ScalaFile, java.lang.Boolean](this, CachesUtil.SCALA_PREDEFINED_KEY,
      new CachesUtil.MyProvider[ScalaFile, java.lang.Boolean](this, e => inner(e))
      (PsiModificationTracker.OUT_OF_CODE_BLOCK_MODIFICATION_COUNT)).booleanValue()
  } 
  
  
  def isScalaPredefinedClassInner = typeDefinitions.length == 1 &&
    Set("scala", "scala.Predef").contains(typeDefinitions.apply(0).qualifiedName)


  override def findReferenceAt(offset: Int): PsiReference = super.findReferenceAt(offset)

  private var myControlFlow: Seq[Instruction] = null

  def getControlFlow(cached: Boolean) = {
    if (!cached || myControlFlow == null) {
      val builder = new ScalaControlFlowBuilder(null, null)
      myControlFlow = builder.buildControlflow(this)
    }
    myControlFlow
  }

  import java.util.Set
  import java.util.HashSet
  def getClassNames: Set[String] = {
    val res = new HashSet[String]
    typeDefinitions.foreach {
      case clazz: ScClass => res.add(clazz.getName)
      case o: ScObject =>
        res.add(o.getName)
        o.fakeCompanionClass match {
          case Some(clazz) => res.add(clazz.getName)
          case _ =>
        }
      case t: ScTrait =>
      res.add(t.getName)
      res.add(t.fakeCompanionClass.getName)
    }
    res
  }

  def getPackagingRange: TextRange = {
    def getRange: TextRange = {
      new TextRange(0, getText.indexOf('\n') match {
        case x if x < 0 => getText.length
        case y => y
      })
    }
    getPackagings.toList match {
      case Nil => getRange
      case h :: t => h.reference match {
        case Some(ref) => ref.getTextRange
        case _ => getRange
      }
    }
  }

  // Special case for SBT 0.10 "build.sbt" files: they should be typed as though they are in the "project" module,
  // even though they are located in the other modules.
  def getFileResolveScope: GlobalSearchScope = {
    def default: GlobalSearchScope = {
      val vFile = getOriginalFile.getVirtualFile
      if (vFile == null) GlobalSearchScope.allScope(getProject)
      else {
        val resolveScopeManager = ResolveScopeManager.getInstance(getProject)
        resolveScopeManager.getDefaultResolveScope(vFile)
      }
    }
    if (SbtFile.isSbtFile(this)) {
      SbtFile.findSbtProjectModule(getProject) match {
        case Some(module) => module.getModuleRuntimeScope(false)
        case None => default
      }
    } else default
  }

  def ignoreReferencedElementAccessibility(): Boolean = true //todo: ?

  override def setContext(element: PsiElement, child: PsiElement) {
    putCopyableUserData(ScalaFileImpl.CONTEXT_KEY, element)
    putCopyableUserData(ScalaFileImpl.CHILD_KEY, child)
  }

  override def getContext: PsiElement = {
    getCopyableUserData(ScalaFileImpl.CONTEXT_KEY) match {
      case null => super.getContext
      case _ => getCopyableUserData(ScalaFileImpl.CONTEXT_KEY)
    }
  }

  override def getPrevSibling: PsiElement = {
    getCopyableUserData(ScalaFileImpl.CHILD_KEY) match {
      case null => super.getPrevSibling
      case _ => getCopyableUserData(ScalaFileImpl.CHILD_KEY).getPrevSibling
    }
  }

  override def getNextSibling: PsiElement = {
    child match {
      case null => super.getNextSibling
      case _ => getCopyableUserData(ScalaFileImpl.CHILD_KEY).getNextSibling
    }
  }
}

object ImplicitlyImported {
  val packages = Array("scala", "java.lang")
  val objects = Array("scala.Predef", "scala" /* package object*/)


  import collection.mutable.WeakHashMap
  private val importedObjects: WeakHashMap[Project, Seq[PsiClass]] = new WeakHashMap[Project, Seq[PsiClass]]
  private val modCount: WeakHashMap[Project, Long] = new WeakHashMap[Project, Long]

  def implicitlyImportedObject(manager: PsiManager, scope: GlobalSearchScope,
                                fqn: String): Option[PsiClass] = {
    implicitlyImportedObjects(manager, scope).filter(_.qualifiedName == fqn).headOption
  }

  def allImplicitlyImportedObjects(manager: PsiManager, scope: GlobalSearchScope): Seq[PsiClass] = {
    val buf: ArrayBuffer[PsiClass] = new ArrayBuffer[PsiClass]()
    for (obj <- objects) {
      implicitlyImportedObject(manager, scope, obj).foreach(buf += _)
    }
    buf.toSeq
  }

  private def implicitlyImportedObjects(manager: PsiManager, scope: GlobalSearchScope): Seq[PsiClass] = {
    var res: Seq[PsiClass] = importedObjects.get(manager.getProject).getOrElse(null)
    val count = manager.getModificationTracker.getJavaStructureModificationCount
    val count1: Option[Long] = modCount.get(manager.getProject)
    if (res != null && count1 != null && count == count1.get) {
      val filter = new ScalaSourceFilterScope(scope, manager.getProject)
      return res.filter(c => filter.contains(c.getContainingFile.getVirtualFile))
    }
    res = implicitlyImportedObjectsImpl(manager)
    importedObjects(manager.getProject) = res
    modCount(manager.getProject) = count
    val filter = new ScalaSourceFilterScope(scope, manager.getProject)
    res.filter(c => filter.contains(c.getContainingFile.getVirtualFile))
  }

  private def implicitlyImportedObjectsImpl(manager: PsiManager): Seq[PsiClass] = {
    val res = new ArrayBuffer[PsiClass]
    for (obj <- objects) {
      res ++= ScalaPsiManager.instance(manager.getProject).
              getCachedClasses(GlobalSearchScope.allScope(manager.getProject), obj)
    }
    res.toSeq
  }

}

object ScalaFileImpl {
  private var LOG: Logger = Logger.getInstance("#org.jetbrains.plugins.scala.lang.psi.impl.ScalaFileImpl")
  val SCRIPT_KEY = new Key[java.lang.Boolean]("Is Script Key")
  val CONTEXT_KEY = new Key[PsiElement]("context.key")
  val CHILD_KEY = new Key[PsiElement]("child.key")

  def pathIn(root: PsiElement): List[List[String]] =
    packagingsIn(root).map(packaging => toVector(packaging.getPackageName))

  private def packagingsIn(root: PsiElement): List[ScPackaging] = {
    root.children.findByType(classOf[ScPackaging]).headOption match {
      case Some(packaging) => packaging :: packagingsIn(packaging)
      case _ => Nil
    }
  }

  def splitsIn(path: List[List[String]]): List[List[String]] =
    path.scanLeft(List[String]())((vs, v) => vs ::: v).tail.dropRight(1)

  def splitAt(path: List[List[String]], vector: List[String]): List[List[String]] = {
    if (vector.isEmpty) path else path match {
      case h :: t if h == vector => h :: t
      case h :: t if vector.startsWith(h) => h :: splitAt(t, vector.drop(h.size))
      case h :: t if h.startsWith(vector) => h.take(vector.size) :: h.drop(vector.size) :: t
      case it => it
    }
  }
  
  def addPathTo(file: ScalaFile, path: List[List[String]]) {
    val innermost = path.lastOption
    path.reverse.foreach { packaging =>
      addOuterPackagingTo(file, packaging.mkString("."), Some(packaging) == innermost)
    }  
  }

  private def addOuterPackagingTo(file: ScalaFile, name: String, separate: Boolean) {
    val packaging = ScalaPsiElementFactory.createPackaging(name, file.getManager)

    if (file.children.isEmpty) {
      file.add(packaging)
    } else {
      val delimiter = if (separate) "\n\n" else "\n"
      file.addBefore(ScalaPsiElementFactory.parseElement(delimiter, file.getManager), file.getFirstChild)
      file.wrapChildrenIn(packaging)
    }
  }
  
  def stripPackagesIn(file: ScalaFile) {
    file.depthFirst.filterByType(classOf[ScPackaging]).toList.reverse.foreach(_.strip())
  }
  
  def toVector(name: String): List[String] = if (name.isEmpty) Nil else name.split('.').toList
}
