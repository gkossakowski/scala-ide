/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse.javaelements


import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants

import scala.tools.nsc.symtab.Flags
import scala.tools.eclipse.ScalaPresentationCompiler
import ch.epfl.lamp.fjbg.{ JObjectType, JType }

trait ScalaJavaMapper { self : ScalaPresentationCompiler => 

  def mapType(t : Tree) : String = {
    (t match {
      case tt : TypeTree => {
        if(tt.symbol == null || tt.symbol == NoSymbol || tt.symbol.isRefinementClass || tt.symbol.owner.isRefinementClass)
          "scala.AnyRef"
        else
          tt.symbol.fullName
      }
      case Select(_, name) => name.toString
      case Ident(name) => name.toString
      case _ => "scala.AnyRef"
    }) match {
      case "scala.AnyRef" => "java.lang.Object"
      case "scala.Unit" => "void"
      case "scala.Boolean" => "boolean"
      case "scala.Byte" => "byte"
      case "scala.Short" => "short"
      case "scala.Int" => "int"
      case "scala.Long" => "long"
      case "scala.Float" => "float"
      case "scala.Double" => "double"
      case "<NoSymbol>" => "void"
      case n => n
    }
  }

  /** Compatible with both 2.8 and 2.9 (interface HasFlags appears in 2.9).
   * 
   *  COMPAT: Once we drop 2.8, rewrite to use the HasFlags trait in scala.reflect.generic
   */
  
  
/* Re-add when ticket #4560 is fixed.
  type HasFlags = {
      /** Whether this entity has ANY of the flags in the given mask. */
      def hasFlag(flag: Long): Boolean
      def isFinal: Boolean
      def isTrait: Boolean
  }
*/  
  
  def mapModifiers(owner: Symbol) : Int = {
    var jdtMods = 0
    if(owner.hasFlag(Flags.PRIVATE))
      jdtMods = jdtMods | ClassFileConstants.AccPrivate
    else if(owner.hasFlag(Flags.PROTECTED))
      jdtMods = jdtMods | ClassFileConstants.AccProtected
    else
      jdtMods = jdtMods | ClassFileConstants.AccPublic
    
    if(owner.hasFlag(Flags.ABSTRACT) || owner.hasFlag(Flags.DEFERRED))
      jdtMods = jdtMods | ClassFileConstants.AccAbstract

    if(owner.isFinal || owner.hasFlag(Flags.MODULE))
      jdtMods = jdtMods | ClassFileConstants.AccFinal
    
    if(owner.isTrait)
      jdtMods = jdtMods | ClassFileConstants.AccInterface
    
    jdtMods
  }

  /** Overload that needs to go away when 'HasFlag' can be used, either as a
   *  structural type -- see #4560, or by sticking to 2.9.0 that has this trait
   */
  def mapModifiers(owner: Modifiers) : Int = {
    var jdtMods = 0
    if(owner.hasFlag(Flags.PRIVATE))
      jdtMods = jdtMods | ClassFileConstants.AccPrivate
    else if(owner.hasFlag(Flags.PROTECTED))
      jdtMods = jdtMods | ClassFileConstants.AccProtected
    else
      jdtMods = jdtMods | ClassFileConstants.AccPublic
    
    if(owner.hasFlag(Flags.ABSTRACT) || owner.hasFlag(Flags.DEFERRED))
      jdtMods = jdtMods | ClassFileConstants.AccAbstract

    if(owner.isFinal || owner.hasFlag(Flags.MODULE))
      jdtMods = jdtMods | ClassFileConstants.AccFinal
    
    if(owner.isTrait)
      jdtMods = jdtMods | ClassFileConstants.AccInterface
    
    jdtMods
  }

  
  def mapType(s : Symbol) : String = {
    (if(s == null || s == NoSymbol || s.isRefinementClass || s.owner.isRefinementClass)
        "scala.AnyRef"
      else
        s.fullName
    ) match {
      case "scala.AnyRef" => "java.lang.Object"
      case "scala.Unit" => "void"
      case "scala.Boolean" => "boolean"
      case "scala.Byte" => "byte"
      case "scala.Short" => "short"
      case "scala.Int" => "int"
      case "scala.Long" => "long"
      case "scala.Float" => "float"
      case "scala.Double" => "double"
      case "<NoSymbol>" => "void"
      case n => n
    }
  }
  
  def mapParamTypePackageName(t : Type) : String = {
    if (t.typeSymbolDirect.isTypeParameter)
      ""
    else {
      val jt = javaType(t)
      if (jt.isValueType)
        ""
      else
        t.typeSymbol.enclosingPackage.fullName
    }
  }

  def isScalaSpecialType(t : Type) = {
    import definitions._
    t.typeSymbol match {
      case AnyClass | AnyRefClass | AnyValClass | NothingClass | NullClass => true
      case _ => false
    }
  }
  
  def mapParamTypeName(t : Type) : String = {
    if (t.typeSymbolDirect.isTypeParameter)
      t.typeSymbolDirect.name.toString
    else if (isScalaSpecialType(t))
      "java.lang.Object"
    else {
      val jt = javaType(t)
      if (jt.isValueType)
        jt.toString
      else
        mapTypeName(t.typeSymbol)
    }
  }
  
  def mapParamTypeSignature(t : Type) : String = {
    if (t.typeSymbolDirect.isTypeParameter)
      "T"+t.typeSymbolDirect.name.toString+";"
    else if (isScalaSpecialType(t))
      "Ljava.lang.Object;"
    else {
      val jt = javaType(t)
      val fjt = if (jt == JType.UNKNOWN)
        JObjectType.JAVA_LANG_OBJECT
      else
        jt
      fjt.getSignature.replace('/', '.')
    }
  }
  
  def mapTypeName(s : Symbol) : String =
    if (s == NoSymbol || s.hasFlag(Flags.PACKAGE)) ""
    else {
      val owner = s.owner
      val prefix = if (owner != NoSymbol && !owner.hasFlag(Flags.PACKAGE)) mapTypeName(s.owner)+"." else ""
      val suffix = if (s.hasFlag(Flags.MODULE) && !s.hasFlag(Flags.JAVA)) "$" else ""
      prefix+s.nameString+suffix
    }

  def enclosingTypeNames(sym : Symbol): List[String] = {
    def enclosing(sym : Symbol) : List[String] =
      if (sym == NoSymbol || sym.owner.hasFlag(Flags.PACKAGE))
        Nil
      else {
        val owner = sym.owner 
        val name0 = owner.simpleName.toString
        val name = if (owner.isModuleClass) name0+"$" else name0
        name :: enclosing(owner)
      }
        
    enclosing(sym).reverse
  }
  
  /** Return the enclosing package. Correctly handle the empty package, by returning
   *  the empty string, instead of <empty>. */
  def enclosingPackage(sym: Symbol): String = {
    val enclPackage = sym.enclosingPackage
    if (enclPackage == definitions.EmptyPackage || enclPackage == definitions.RootPackage)
      ""
    else
      enclPackage.fullName
  }
  
  import org.eclipse.jdt.core._
  import org.eclipse.jdt.internal.core._
  import scala.tools.nsc.symtab.Flags
  
  /** Return the Java Element corresponding to the given Java Symbol.
   * 
   *  The given symbol has to be a Java symbol.
   */
  def getJavaElement2(sym: Symbol): Option[IMember] = {
    assert((sym ne null) /* && sym.hasFlag(Flags.JAVA) */)
    
    def matchesMethod(meth: IMethod): Boolean = {
      import Signature._
      askOption { () =>
        ((meth.getElementName == sym.name.toString)
          && meth.getParameterTypes.map(tp => getTypeErasure(getElementType(tp)))
                                   .sameElements(sym.tpe.paramTypes.map(mapParamTypeSignature)))
      }.getOrElse(false)
    }
    
    val javaModel = JavaModelManager.getJavaModelManager.getJavaModel
    if (sym.isClass) {
      val fullClassName = mapType(sym)
      val projs = javaModel.getJavaProjects
      projs.map(p => Option(p.findType(fullClassName))).find(_.isDefined).flatten
    } else if (sym ne NoSymbol) 
      getJavaElement2(sym.owner) match {
        case Some(ownerClass: IType) => 
          if (sym.isMethod) ownerClass.getMethods.find(matchesMethod)
          else ownerClass.getFields.find(_.getElementName == sym.name.toString)
        case _ => None
    } else
      None;
  }
  
  /*
  def getJavaElement(sym: Symbol): Option[IMember] = {
    if (sym == null)
      return None;
    val javaModel = JavaModelManager.getJavaModelManager.getJavaModel    
    if (sym.isClass) {
      val fullClassName = mapType(sym)
      val projs = javaModel.getJavaProjects
      projs.foreach(p => {
        val tpe = p.findType(fullClassName)
        if (tpe != null)
          return Some(tpe);
      })
    } else if (sym.isMethod) {
      val clsSymbol = sym.owner
      getJavaElement(clsSymbol) match {
        case Some(tpe: IType) =>
          val sameNameMethods = tpe.getMethods.filter(m =>
            m.getElementName == sym.name.toString)
          if (sameNameMethods.size == 1)
            return Some(sameNameMethods(0))
          val methodType = sym.tpe
          val sameParNumberMethods = sameNameMethods.filter(m =>
            m.getNumberOfParameters == methodType.params.size)
          if (sameParNumberMethods.size == 1)
            return Some(sameParNumberMethods(0))
          val referencedMethodParTypes = methodType.paramTypes.map(pt => mapParamTypeSignature(pt)).toArray
          return sameParNumberMethods.find(m => {
            val fullyNamedParamTypes = m.getParameterTypes.map(pt => {
              val elemType = Signature.getElementType(pt)
              Signature.getTypeErasure(elemType) //ToDo: improve by considering the type parameters 
            })
            fullyNamedParamTypes.sameElements(referencedMethodParTypes)
          })
        case _ =>
      }
    } else if (sym.isVariable || sym.isValue) {
      if (sym.owner.isClass) {
        getJavaElement(sym.owner) match {
          case Some(tpe: IType) =>
            return tpe.getFields.find(f => f.getElementName ==
              sym.name.toString)
          case _ =>
        }
      }
    }
    return None;
  } */
}
