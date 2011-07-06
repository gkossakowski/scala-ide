/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse.javaelements

import scala.tools.nsc.interactive.compat.Info
import scala.tools.eclipse.util.Defensive
import scala.tools.eclipse.ScalaPlugin
import scala.tools.eclipse.util.Tracer

import java.io.{ PrintWriter, StringWriter }
import java.util.{ Map => JMap }

import org.eclipse.core.resources.IFile
import org.eclipse.jdt.core.{ IAnnotation, ICompilationUnit, IJavaElement, IMemberValuePair, Signature }
import org.eclipse.jdt.core.compiler.CharOperation
import org.eclipse.jdt.internal.core.{
  Annotation, AnnotationInfo => JDTAnnotationInfo, AnnotatableInfo, CompilationUnit => JDTCompilationUnit, ImportContainer,
  ImportContainerInfo, ImportDeclaration, ImportDeclarationElementInfo, JavaElement, JavaElementInfo,
  MemberValuePair, OpenableElementInfo, SourceRefElement, TypeParameter, TypeParameterElementInfo }
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants
import org.eclipse.jdt.ui.JavaElementImageDescriptor

import scala.collection.Map
import scala.collection.mutable.HashMap

import scala.tools.nsc.symtab.Flags
import scala.tools.nsc.util.{ NoPosition, Position }

import scala.tools.eclipse.ScalaPresentationCompiler
import scala.tools.eclipse.util.ReflectionUtils

trait ScalaStructureBuilder { self : ScalaPresentationCompiler =>

  class StructureBuilderTraverser(scu : ScalaCompilationUnit, unitInfo : OpenableElementInfo, newElements0 : JMap[AnyRef, AnyRef], sourceLength : Int) {
    private def companionClassOf(s: Symbol): Symbol =
      try {
        s.companionClass
      } catch {
        case e => {
          ScalaPlugin.plugin.logError(e)
          NoSymbol
        }
      }

    type OverrideInfo = Int
//    val overrideInfos = (new collection.mutable.HashMap[Symbol, OverrideInfo]).withDefaultValue(0)
    // COMPAT: backwards compatible with 2.8. Remove once we drop 2.8 (and use withDefaultValue).
    val overrideInfos = new collection.mutable.HashMap[Symbol, OverrideInfo] {
      override def get(key: Symbol) = super.get(key) match {
        case None => Some(0)
        case v => v
      }
    } 
    
    def fillOverrideInfos(c : Symbol) {
      if (c ne NoSymbol) {
        val base = c.allOverriddenSymbols
        if (!base.isEmpty) {
          if (c.isDeferred)
            overrideInfos += c -> JavaElementImageDescriptor.OVERRIDES
          else
            overrideInfos += c -> (if(base.exists(!_.isDeferred)) JavaElementImageDescriptor.OVERRIDES else JavaElementImageDescriptor.IMPLEMENTS)
        }
      }
    }

    /**
     * Returns a type name for an untyped tree which the JDT should be able to consume,
     * in particular org.eclipse.jdt.internal.compiler.parser.TypeConverter
     */
    def unresolvedType(tree: Tree): String = "null-Type"
    
    trait Owner {
      def parent : Owner
      def jdtOwner = this

      def element : JavaElement
      def elementInfo : JavaElementInfo
      def compilationUnitBuilder : CompilationUnitBuilder = parent.compilationUnitBuilder
      
      def isPackage = false
      def isCtor = false
      def isTemplate = false
      def template : Owner = if (parent != null) parent.template else null

      def addPackage(p : PackageDef) : Owner = this
      def addImport(i : Import) : Owner = this
      def addClass(c : ClassDef) : Owner = this
      def addModule(m : ModuleDef) : Owner = this
      def addVal(v : ValDef) : Owner = this
      def addType(t : TypeDef) : Owner = this
      
      // TODO: need to rewrite everything to use symbols rather than trees, only DefDef for now.
      def addDef(sym: Symbol) : Owner = this
      def addDef(d: DefDef) : Owner = this
      
      def addFunction(f : Function) : Owner = this
      
      def resetImportContainer {}

      def addChild(child : JavaElement) =
        elementInfo match {
          case scalaMember : ScalaMemberElementInfo => scalaMember.addChild0(child)
          case openable : OpenableElementInfo => openable.addChild(child)
        }
      
      def modules : Map[Symbol, ScalaElementInfo] = Map.empty 
      def classes : Map[Symbol, (ScalaElement, ScalaElementInfo)] = Map.empty
      
      def complete {
        def addForwarders(classElem : ScalaElement, classElemInfo : ScalaElementInfo, module: Symbol) {
          def conflictsIn(cls: Symbol, name: Name) =
            if (cls != NoSymbol)
              cls.info.nonPrivateMembers.exists(_.name == name)
            else
              false
          
          /** List of parents shared by both class and module, so we don't add forwarders
           *  for methods defined there - bug #1804 */
          lazy val commonParents = {
            val cps = module.info.baseClasses
            val mps = {
                val comp = companionClassOf(module)
                if (comp == NoSymbol) List() else comp.info.baseClasses
            }
            cps.filter(mps contains)
          }
          /* the setter doesn't show up in members so we inspect the name */
          def conflictsInCommonParent(name: Name) =
            commonParents exists { cp => name startsWith (cp.name + "$") }
                 
          /** Should method `m' get a forwarder in the mirror class? */
          def shouldForward(m: Symbol): Boolean =
            m.isMethod &&
            !m.isConstructor &&
            !m.isStaticMember &&
            !(m.owner == definitions.ObjectClass) && 
            !(m.owner == definitions.AnyClass) &&
            !m.hasFlag(Flags.CASE | Flags.PROTECTED | Flags.DEFERRED) &&
            !module.isSubClass(companionClassOf(module)) &&
            !conflictsIn(definitions.ObjectClass, m.name) &&
            !conflictsInCommonParent(m.name) && 
            !conflictsIn(companionClassOf(module), m.name)
          
          if(Defensive.check(module.isModuleClass, "module %s isModuleClass", module)) {
            for (m <- module.info.nonPrivateMembers; if shouldForward(m)) {
              addForwarder(classElem, classElemInfo, module, m)
            }
          }
        }

        def addForwarder(classElem: ScalaElement, classElemInfo : ScalaElementInfo, module: Symbol, d: Symbol) {
          //val moduleName = javaName(module) // + "$"
          //val className = moduleName.substring(0, moduleName.length() - 1)

          val nm = d.name
          
          val fps = for(vps <- d.tpe.paramss; vp <- vps) yield vp
          
          def paramType(sym : Symbol) = {
            val tpe = sym.tpe
            if (sym.isType || tpe != null)
              uncurry.transformInfo(sym, tpe).typeSymbol
            else {
              NoSymbol
            }
          }
          
          val paramTypes = Array(fps.map(v => Signature.createTypeSignature(mapType(paramType(v)), false)) : _*)
          val paramNames = Array(fps.map(n => nme.getterName(n.name).toChars) : _*)
          
          val defElem = 
            if(d.hasFlag(Flags.ACCESSOR))
              new ScalaAccessorElement(classElem, nm.toString, paramTypes)
            else
              new ScalaDefElement(classElem, nm.toString, paramTypes, true, nm.toString, overrideInfos(d))
          resolveDuplicates(defElem)
          classElemInfo.addChild0(defElem)
          
          val defElemInfo = new ScalaSourceMethodInfo
          
          defElemInfo.setArgumentNames(paramNames)
          defElemInfo.setExceptionTypeNames(Array.empty)
          val tn = mapType(d.tpe.finalResultType.typeSymbol).toArray
          defElemInfo.asInstanceOf[FnInfo].setReturnType(tn)
  
          val annotsPos = addAnnotations(d, defElemInfo, defElem)
  
          defElemInfo.setFlags0(ClassFileConstants.AccPublic|ClassFileConstants.AccFinal|ClassFileConstants.AccStatic)
          
          val (start, point, end) =
            d.pos match {
              case NoPosition =>
                (module.pos.point, module.pos.point, module.pos.point)
              case pos =>
                (d.pos.startOrPoint, d.pos.point, d.pos.endOrPoint)
            }
            
          val nameEnd = point+defElem.labelName.length-1
            
          defElemInfo.setNameSource0(point, nameEnd)
          defElemInfo.setSourceRange0(start, end)
          
          newElements0.put(defElem, defElemInfo)
        } 
        
        for ((m, mInfo) <- modules) {
          val c = companionClassOf(m)
          if (c != NoSymbol) {
            classes.get(c) match {
              case Some((classElem, classElemInfo)) =>
                addForwarders(classElem, classElemInfo, m.moduleClass)
              case _ =>
            }
          } else {
            val className = m.nameString
            
            val classElem = new ScalaClassElement(element, className, true)
            resolveDuplicates(classElem)
            addChild(classElem)
            
            val classElemInfo = new ScalaElementInfo
            classElemInfo.setHandle(classElem)
            classElemInfo.setFlags0(ClassFileConstants.AccSuper|ClassFileConstants.AccFinal|ClassFileConstants.AccPublic)
            classElemInfo.setSuperclassName("java.lang.Object".toArray)
            classElemInfo.setSuperInterfaceNames(null)
            classElemInfo.setNameSource0(mInfo.getNameSourceStart, mInfo.getNameSourceEnd)
            classElemInfo.setSourceRange0(mInfo.getDeclarationSourceStart, mInfo.getDeclarationSourceEnd)
            
            newElements0.put(classElem, classElemInfo)
            
            addForwarders(classElem, classElemInfo, m.moduleClass)
          }
        }
      }
    }
    
    trait PackageOwner extends Owner { self =>
      override def addPackage(p : PackageDef) : Owner = {
        new Builder {
          val parent = self
          val element = compilationUnitBuilder.element
          val elementInfo = compilationUnitBuilder.elementInfo
          
          override def isPackage = true
          var completed = !compilationUnitBuilder.element.isInstanceOf[JDTCompilationUnit]
          override def addChild(child : JavaElement) = {
            if (!completed) {
              completed = true
              
              val pkgElem = JavaElementFactory.createPackageDeclaration(compilationUnitBuilder.element.asInstanceOf[JDTCompilationUnit], p.symbol.fullName)
              resolveDuplicates(pkgElem)
              compilationUnitBuilder.addChild(pkgElem)

              val pkgElemInfo = JavaElementFactory.createSourceRefElementInfo
              newElements0.put(pkgElem, pkgElemInfo)
            }
            
            compilationUnitBuilder.addChild(child)
          }
        }
      }
    }
    
    trait ImportContainerOwner extends Owner { self =>
      import SourceRefElementInfoUtils._
      import ImportContainerInfoUtils._
    
      var currentImportContainer : Option[(ImportContainer, ImportContainerInfo)] = None
      
      override def resetImportContainer : Unit = currentImportContainer = None
      
      override def addImport(i : Import) : Owner = {
        val prefix = i.expr.symbol.fullName
        val pos = i.pos

        def isWildcard(s: ImportSelector) : Boolean = s.name == nme.WILDCARD

        def addImport(name : String, isWildcard : Boolean) {
          val path = prefix+"."+(if(isWildcard) "*" else name)
          
          val (importContainer, importContainerInfo) = currentImportContainer match {
            case Some(ci) => ci
            case None =>
              val importContainerElem = JavaElementFactory.createImportContainer(element)
              val importContainerElemInfo = new ImportContainerInfo
                
              resolveDuplicates(importContainerElem)
              addChild(importContainerElem)
              newElements0.put(importContainerElem, importContainerElemInfo)
              
              val ci = (importContainerElem, importContainerElemInfo)
              currentImportContainer = Some(ci)
              ci
          }
          
          val importElem = JavaElementFactory.createImportDeclaration(importContainer, path, isWildcard)
          resolveDuplicates(importElem)
        
          val importElemInfo = new ImportDeclarationElementInfo
          setSourceRange0(importElemInfo, pos.startOrPoint, pos.endOrPoint/*-1*/)
        
          val children = getChildren(importContainerInfo)
          if (children.isEmpty)
            setChildren(importContainerInfo, Array[IJavaElement](importElem))
          else
            setChildren(importContainerInfo, children ++ Seq(importElem))
            
          newElements0.put(importElem, importElemInfo)
        }

        i.selectors.foreach(s => addImport(s.name.toString, isWildcard(s)))
        
        self
      }
    }
    
    trait ClassOwner extends Owner { self =>
      override val classes = new HashMap[Symbol, (ScalaElement, ScalaElementInfo)]
    
      override def addClass(c : ClassDef) : Owner = {
        val sym = c.symbol
        if (sym eq NoSymbol) return self  // Local class hasn't been attributed yet, can't show anything meaningful.
        // make sure classes are completed
        sym.initialize
        
        val name = c.name.toString
        val parentTree = c.impl.parents.head
        val isAnon = sym.isAnonymousClass
        val superClass = sym.superClass
        val superName = if (superClass ne NoSymbol) superClass.toString else "Object"
        val classElem =
          if(sym hasFlag Flags.TRAIT)
            new ScalaTraitElement(element, name)
          else if (isAnon) {
          new ScalaAnonymousClassElement(element, superName)
          }
          else
            new ScalaClassElement(element, name, false)
        
        resolveDuplicates(classElem)
        addChild(classElem)
        
        val classElemInfo = new ScalaElementInfo
        classes(sym) = (classElem, classElemInfo)
        if (!sym.typeParams.isEmpty) {
          val typeParams = sym.typeParams.map { tp =>
            val typeParameter = new TypeParameter(classElem, tp.name.toString)
            val tpElementInfo = new TypeParameterElementInfo
            val parents = tp.info.parents
            if (!parents.isEmpty) {
              //BACK-e35 : value boundsSignatures is not a member of org.eclipse.jdt.internal.core.TypeParameterElementInfo
              // tpElementInfo.boundsSignatures = parents.map(_.typeSymbol.fullName.toCharArray).toArray 
              tpElementInfo.bounds = parents.map(_.typeSymbol.name.toChars).toArray
            }
            newElements0.put(typeParameter, tpElementInfo)
            typeParameter
          }
          classElemInfo setTypeParameters typeParams.toArray
        }
        
        classElemInfo.setHandle(classElem)
        val mask = ~(if (isAnon) ClassFileConstants.AccPublic else 0)
        classElemInfo.setFlags0(mapModifiers(sym) & mask)
        
        val annotsPos = addAnnotations(sym, classElemInfo, classElem)

        classElemInfo.setSuperclassName(mapType(superClass).toCharArray)
        
        val interfaceNames = sym.mixinClasses.map { m => 
          mapType(m).toCharArray
        }
        classElemInfo.setSuperInterfaceNames(interfaceNames.toArray)
        
        val (start, end) = if (!isAnon) {
          Defensive.notEmpty(name, "name of Class")
          val start0 = c.pos.point 
          (start0, start0 + name.length - 1)
        } else {
          val start0 = parentTree.pos.point
          (start0, start0/*-1*/)
        }
        
        classElemInfo.setNameSource0(start, end)
        setSourceRange(classElemInfo, sym, annotsPos)
        newElements0.put(classElem, classElemInfo)

        fillOverrideInfos(sym)
        
        new Builder {
          val parent = self
          val element = classElem
          val elementInfo = classElemInfo
          
          override def isTemplate = true
          override def template = this
        }
      }
    }
    
    trait ModuleOwner extends Owner { self =>
      override val modules = new HashMap[Symbol, ScalaElementInfo]

      override def addModule(m : ModuleDef) : Owner = {
        val sym = m.symbol
      val isSynthetic = sym.hasFlag(Flags.SYNTHETIC)
        val moduleElem = new ScalaModuleElement(element, m.name.toString, isSynthetic)
        resolveDuplicates(moduleElem)
        addChild(moduleElem)
        
        val moduleElemInfo = new ScalaElementInfo
        sym match {
          case NoSymbol => ()
          case s => if (s.owner.isPackageClass) modules(s) = moduleElemInfo
        }
        
        moduleElemInfo.setHandle(moduleElem)
        moduleElemInfo.setFlags0(mapModifiers(sym)|ClassFileConstants.AccFinal)
        
        val annotsPos = addAnnotations(sym, moduleElemInfo, moduleElem)

        val start = m.pos.point
        val end = start+m.name.length-1
        
        moduleElemInfo.setNameSource0(start, end)
        if (!isSynthetic)
          setSourceRange(moduleElemInfo, sym, annotsPos)
        else {
          moduleElemInfo.setSourceRange0(end, end)
        }
        newElements0.put(moduleElem, moduleElemInfo)
        
        val parentTree = m.impl.parents.head
        val superclassType = parentTree.tpe
        val superclassName = (if (superclassType ne null) superclassType.typeSymbol.fullName 
          else unresolvedType(parentTree)).toCharArray
        moduleElemInfo.setSuperclassName(superclassName)
        
        val interfaceTrees = m.impl.parents.drop(1)
        val interfaceNames = interfaceTrees.map { t =>
          val tpe = t.tpe
          (if (tpe ne null) tpe.typeSymbol.fullName else unresolvedType(t)).toCharArray 
        }
        moduleElemInfo.setSuperInterfaceNames(interfaceNames.toArray)

        fillOverrideInfos(sym)
        
        val mb = new Builder {
          val parent = self
          val element = moduleElem
          val elementInfo = moduleElemInfo
          
          override def isTemplate = true
          override def template = this
        }

        val instanceElem = new ScalaModuleInstanceElement(moduleElem)
        resolveDuplicates(instanceElem)
        mb.addChild(instanceElem)
        
        val instanceElemInfo = new ScalaSourceFieldElementInfo
        instanceElemInfo.setFlags0(ClassFileConstants.AccPublic | ClassFileConstants.AccStatic | ClassFileConstants.AccFinal)
        instanceElemInfo.setTypeName(moduleElem.getFullyQualifiedName('.').toCharArray)
        setSourceRange(instanceElemInfo, sym, annotsPos)
        instanceElemInfo.setNameSource0(start, end)
        
        newElements0.put(instanceElem, instanceElemInfo)
        
        mb
      }
    }
    
    trait ValOwner extends Owner { self =>
      override def addVal(v : ValDef) : Owner = {
        val elemName = nme.getterName(v.name)
        val sym = v.symbol
        val display = elemName.toString+" : "+sym.tpe.toString
        
        val valElem =
          if(sym.hasFlag(Flags.MUTABLE))
            new ScalaVarElement(element, elemName.toString, display)
          else
            new ScalaValElement(element, elemName.toString, display)
        resolveDuplicates(valElem)
        addChild(valElem)
        
        val valElemInfo = new ScalaSourceFieldElementInfo
        val jdtFinal = if(sym.hasFlag(Flags.MUTABLE)) 0 else ClassFileConstants.AccFinal
        valElemInfo.setFlags0(mapModifiers(sym)|jdtFinal)
        
        val annotsPos = addAnnotations(sym, valElemInfo, valElem)
        
        val start = v.pos.point
        val end = start+elemName.length-1
        
        valElemInfo.setNameSource0(start, end)
        setSourceRange(valElemInfo, sym, annotsPos)
        newElements0.put(valElem, valElemInfo)

        val tn = mapType(sym.info.typeSymbol).toArray
        valElemInfo.setTypeName(tn)
        
        // TODO: this is a hack needed until building is rewritten to traverse scopes rather than trees.
        // When done, remove.
        if (sym ne NoSymbol) {
          sym.initialize
          val getter = sym.getter(sym.owner)
          if (getter hasFlag Flags.ACCESSOR) addDef(getter)
          val setter = sym.setter(sym.owner)
          if (setter hasFlag Flags.ACCESSOR) addDef(setter)
          addBeanAccessors(sym)
        }

        self
      }
      
      def addBeanAccessors(sym: Symbol) = Defensive.tryOrLog {
        
        var beanName = try {
          nme.localToGetter(sym.name).toString.capitalize
        } catch {
          case t if Info.scalaVersion.startsWith("2.8")=> {
            //BACK-2.8 nme.localToGetter raise AssertionError: assertion failed
            sym.name.toString.capitalize
          }
        }
        val ownerInfo = sym.owner.info
        val accessors = List(ownerInfo.decl("get" + beanName), ownerInfo.decl("is" + beanName), ownerInfo.decl("set" + beanName)).filter(_ ne NoSymbol)
        accessors.foreach(addDef)
      }
    }
    
    trait TypeOwner extends Owner { self =>
      override def addType(t : TypeDef) : Owner = {
        //println("Type defn: >"+t.name.toString+"< ["+this+"]")
        
        val sym = t.symbol
        val name = t.name.toString

        val typeElem = new ScalaTypeElement(element, name, name)
        resolveDuplicates(typeElem)
        addChild(typeElem)

        val typeElemInfo = new ScalaSourceFieldElementInfo
        typeElemInfo.setFlags0(mapModifiers(sym))

        val annotsPos = addAnnotations(sym, typeElemInfo, typeElem)
        
        val start = t.pos.point
        val end = start + t.name.length -1

        typeElemInfo.setNameSource0(start, end)
        setSourceRange(typeElemInfo, sym, annotsPos)
        newElements0.put(typeElem, typeElemInfo)
        
        if(t.rhs.symbol == NoSymbol) {
          //println("Type is abstract")
          val tn = "java.lang.Object".toArray
          typeElemInfo.setTypeName(tn)
        } else {
          //println("Type has type: "+t.rhs.symbol.fullName)
          val tn = mapType(t.rhs.symbol).toArray
          typeElemInfo.setTypeName(tn)
        }
        
        new Builder {
          val parent = self
          val element = typeElem
          val elementInfo = typeElemInfo
        } 
      }
    }

    trait DefOwner extends Owner { self =>
      override def addDef(d: DefDef): Owner = addDef(d.symbol)

      override def addDef(sym: Symbol): Owner = {
        val isCtor0 = sym.isConstructor
        val nameString =
          if(isCtor0)
            sym.owner.simpleName + (if (sym.owner.isModuleClass) "$" else "")
          else
            sym.name.toString
       
        // make sure the method type has been uncurried
        uncurry.transformInfo(sym, sym.info)
        
        val fps = sym.paramss.flatten
        
        val paramTypes = Array(fps.map(v => Signature.createTypeSignature(mapType(v.info.typeSymbol), false)) : _*)
        val paramNames = Array(fps.map(n => nme.getterName(n.name).toChars) : _*)
        
        val display = if (sym ne NoSymbol) sym.nameString + sym.infoString(sym.info) else sym.name.toString + " (no info)"

        val defElem = 
          if(sym hasFlag Flags.ACCESSOR)
            new ScalaAccessorElement(element, nameString, paramTypes)
          else if (isTemplate)
            new ScalaDefElement(element, nameString, paramTypes, sym hasFlag Flags.SYNTHETIC, display, overrideInfos(sym))
          else if (template ne null)
            new ScalaFunctionElement(template.element, element, nameString, paramTypes, display)
          else {
            //FIXME see #1000338
            // by default template == null (if parent == null) and isTemplate is false, maybe in this case return this is a better solution ?
            ScalaPlugin.plugin.logError(throw new IllegalStateException("no rules to defElem for symbol " + sym  + " and owner " + this))
            return super.addDef(sym)
          }
            
        resolveDuplicates(defElem)
        addChild(defElem)
        
        val defElemInfo: FnInfo =
          if(isCtor0)
            new ScalaSourceConstructorInfo
          else
            new ScalaSourceMethodInfo
        
        defElemInfo.setArgumentNames(paramNames)
        defElemInfo.setExceptionTypeNames(Array.empty)
        val tn = mapType(sym.info.resultType.typeSymbol).toArray
        defElemInfo.asInstanceOf[FnInfo].setReturnType(tn)

        val annotsPos = addAnnotations(sym, defElemInfo, defElem)

        val mods =
          if(isTemplate)
            mapModifiers(sym)
          else
            ClassFileConstants.AccPrivate

        defElemInfo.setFlags0(mods)
        
        if (isCtor0) {
          elementInfo match {
            case smei : ScalaMemberElementInfo =>
              defElemInfo.setNameSource0(smei.getNameSourceStart0, smei.getNameSourceEnd0)
              if (sym.isPrimaryConstructor) {
                //FIXME ? in original code range is set to (smei.getNameSourceEnd0, smei.getDeclarationSourceEnd0)(why ?)  
                defElemInfo.setSourceRange0(smei.getNameSourceStart0, smei.getNameSourceEnd0)
              } else {
                defElemInfo.setSourceRange0(smei.getDeclarationSourceStart0, smei.getDeclarationSourceEnd0)
              }
            case _ => Tracer.println("WARN constructor of " + defElem.labelName + " can't handle elementInfo (define start/end) : " + elementInfo)
          }
        } else {
          val start = sym.pos.pointOrElse(-1)
          val end = if (Defensive.check(start >= 0 , "defElem.labelName notEmpty : %s", defElem)) {
              // disable subtraction if iSetter, can introduce end < start, why 4 ??
              start+defElem.labelName.length-1//-(if (sym.isSetter) 4 else 0)
          } else {
              start // -1
          }
          defElemInfo.setNameSource0(start, end)
          setSourceRange(defElemInfo, sym, annotsPos)
        }
        
        newElements0.put(defElem, defElemInfo)
        
        new Builder {
          val parent = self
          val element = defElem
          val elementInfo = defElemInfo

          override def isCtor = isCtor0
          override def addVal(v : ValDef) = this
          override def addType(t : TypeDef) = this
        }
      }
    }
      
    def resolveDuplicates(handle : SourceRefElement) {
      while (newElements0.containsKey(handle)) {
        handle.occurrenceCount += 1
      }
    }
    
    def addAnnotations(sym : Symbol, parentInfo : AnnotatableInfo, parentHandle : JavaElement) : Position =
      addAnnotations(try { sym.annotations } catch { case _ => Nil }, parentInfo, parentHandle)
    
    def addAnnotations(annots : List[AnnotationInfo], parentInfo : AnnotatableInfo, parentHandle : JavaElement) : Position = {
      import SourceRefElementInfoUtils._
      import JDTAnnotationUtils._
      
      def getMemberValuePairs(owner : JavaElement, memberValuePairs : List[(Name, ClassfileAnnotArg)]) : Array[IMemberValuePair] = {
        def getMemberValue(value : ClassfileAnnotArg) : (Int, Any) = {
          value match {
            case LiteralAnnotArg(const) => 
              const.tag match {
                case BooleanTag => (IMemberValuePair.K_BOOLEAN, const.booleanValue)
                case ByteTag    => (IMemberValuePair.K_BYTE, const.byteValue)
                case ShortTag   => (IMemberValuePair.K_SHORT, const.shortValue)
                case CharTag    => (IMemberValuePair.K_CHAR, const.charValue)
                case IntTag     => (IMemberValuePair.K_INT, const.intValue)
                case LongTag    => (IMemberValuePair.K_LONG, const.longValue)
                case FloatTag   => (IMemberValuePair.K_FLOAT, const.floatValue)
                case DoubleTag  => (IMemberValuePair.K_DOUBLE, const.doubleValue)
                case StringTag  => (IMemberValuePair.K_STRING, const.stringValue)
                case ClassTag   => (IMemberValuePair.K_CLASS, const.typeValue.typeSymbol.fullName)
                case EnumTag    => (IMemberValuePair.K_QUALIFIED_NAME, const.tpe.typeSymbol.fullName+"."+const.symbolValue.name.toString)
              }
            case ArrayAnnotArg(args) =>
              val taggedValues = args.map(getMemberValue)
              val firstTag = taggedValues.head._1
              val tag = if (taggedValues.exists(_._1 != firstTag)) IMemberValuePair.K_UNKNOWN else firstTag
              val values = taggedValues.map(_._2)
              (tag, values)
            case NestedAnnotArg(annInfo) =>
              (IMemberValuePair.K_ANNOTATION, addAnnotations(List(annInfo), null, owner))
          }
        }
        
        for ((name, value) <- memberValuePairs.toArray) yield { 
          val (kind, jdtValue) = getMemberValue(value)
          new MemberValuePair(name.toString, jdtValue, kind)
        }
      }

      annots.foldLeft(NoPosition : Position) { (pos, annot) => {
        if (!annot.pos.isOpaqueRange)
          pos
        else {
          //val nameString = annot.atp.typeSymbol.fullName
          val nameString = annot.atp.typeSymbol.nameString
          val handle = new Annotation(parentHandle, nameString)
          resolveDuplicates(handle)
  
          val info = new JDTAnnotationInfo
          newElements0.put(handle, info)
  
          // pos.startOrPoint can == pos.endOrPoint == pos.point
          info.nameStart = annot.pos.startOrPoint
          info.nameEnd = annot.pos.endOrPoint /*-1*/
          setSourceRange0(info, info.nameStart/*-1*/, info.nameEnd)
  
          val memberValuePairs = annot.assocs
          val membersLength = memberValuePairs.length
          if (membersLength == 0)
            info.members = Annotation.NO_MEMBER_VALUE_PAIRS
          else
            info.members = getMemberValuePairs(handle, memberValuePairs)
        
          if (parentInfo != null) {
            val annotations0 = getAnnotations(parentInfo)
            val length = annotations0.length
            val annotations = new Array[IAnnotation](length+1)
            Array.copy(annotations0, 0, annotations, 0, length)
            annotations(length) = handle
            setAnnotations(parentInfo, annotations)
          }
          
          annot.pos union pos
        }
      }}
    }
    
    class CompilationUnitBuilder extends PackageOwner with ImportContainerOwner with ClassOwner with ModuleOwner {
      val parent = null
      val element = scu
      val elementInfo = unitInfo
      override def compilationUnitBuilder = this 
    }
    
    abstract class Builder extends PackageOwner with ImportContainerOwner with ClassOwner with ModuleOwner with ValOwner with TypeOwner with DefOwner
    
    def setSourceRange(info: ScalaMemberElementInfo, sym: Symbol, annotsPos: Position) {
      import Math.{ max, min }
      
      val pos = sym.pos
      val (start, end) =
        if (pos.isDefined) {
          val pos0 = if (annotsPos.isOpaqueRange) pos union annotsPos else pos
          val start0 = if (sym == NoSymbol) {
            pos0.startOrPoint
          } else {
            try {
              docCommentPos(sym) match {
                case NoPosition => pos0.startOrPoint
                case cpos => cpos.startOrPoint
              }
            } catch {
              case _ => pos0.startOrPoint
            }
          }
          (start0, pos0.endOrPoint)
        } else {
          Tracer.println("WARN set sourceRange(-1, -1) for : " + info) 	
          (-1, -1)
        }
      info.setSourceRange0(start, end)
    }
    
    def traverse(tree: Tree) {
      traverse(tree, new CompilationUnitBuilder)
    }

    def traverse(tree: Tree, builder : Owner) {
      val (newBuilder, children) = {
        tree match {
          case _ : Import =>
          case _ => builder.resetImportContainer
        }
      
        tree match {
          case dt : DefTree if dt.symbol.isSynthetic ||
            // Accessors are added in ValOwner, when they are not, remove. 
            dt.symbol.hasFlag(Flags.ACCESSOR) => (builder, Nil)
          case pd : PackageDef => (builder.addPackage(pd), pd.stats)
          case i : Import => (builder.addImport(i), Nil)
          case cd : ClassDef => (builder.addClass(cd), List(cd.impl))
          case md : ModuleDef => (builder.addModule(md), List(md.impl))
          case vd : ValDef =>  (builder.addVal(vd), List(vd.rhs))
          case td : TypeDef => (builder.addType(td), List(td.rhs))
          case dd : DefDef => {
            if(dd.name != nme.MIXIN_CONSTRUCTOR && (dd.symbol ne NoSymbol)) {
              (builder.addDef(dd), List(dd.tpt, dd.rhs))
            } else (builder, Nil)
          }
          case Template(parents, self,  body) => (builder, body)
          case Function(vparams, body) => (builder, Nil)
          case _ => (builder, tree.children)
        }
      }
      children.foreach {traverse(_, newBuilder)}
      if (newBuilder ne builder) newBuilder.complete
    }
  }
}

object JDTAnnotationUtils extends ReflectionUtils {
  val aiClazz = classOf[AnnotatableInfo]
  val annotationsField = getDeclaredField(aiClazz, "annotations")

  def getAnnotations(ai : AnnotatableInfo) = annotationsField.get(ai).asInstanceOf[Array[IAnnotation]]
  def setAnnotations(ai : AnnotatableInfo, annotations : Array[IAnnotation]) = annotationsField.set(ai, annotations)
}
