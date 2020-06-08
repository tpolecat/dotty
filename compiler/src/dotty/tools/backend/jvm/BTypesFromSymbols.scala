package dotty.tools
package backend
package jvm

import scala.tools.asm
import scala.annotation.threadUnsafe
import scala.collection.mutable
import scala.collection.generic.Clearable

import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.transform.SymUtils._
import dotty.tools.dotc.util.WeakHashSet

/**
 * This class mainly contains the method classBTypeFromSymbol, which extracts the necessary
 * information from a symbol and its type to create the corresponding ClassBType. It requires
 * access to the compiler (global parameter).
 *
 * The mixin CoreBTypes defines core BTypes that are used in the backend. Building these BTypes
 * uses classBTypeFromSymbol, hence requires access to the compiler (global).
 *
 * BTypesFromSymbols extends BTypes because the implementation of BTypes requires access to some
 * of the core btypes. They are declared in BTypes as abstract members. Note that BTypes does
 * not have access to the compiler instance.
 */
class BTypesFromSymbols[I <: DottyBackendInterface](val int: I) extends BTypes {
  import int._
  import DottyBackendInterface.{symExtensions, _}

  lazy val TransientAttr = requiredClass[scala.transient]
  lazy val VolatileAttr = requiredClass[scala.volatile]

  val bCodeAsmCommon: BCodeAsmCommon[int.type ] = new BCodeAsmCommon(int)
  import bCodeAsmCommon._

  // Why the proxy, see documentation of class [[CoreBTypes]].
  val coreBTypes: CoreBTypesProxy[this.type] = new CoreBTypesProxy[this.type](this)
  import coreBTypes._

  final def intializeCoreBTypes(): Unit = {
    coreBTypes.setBTypes(new CoreBTypes[this.type](this))
  }

  private[this] val perRunCaches: Caches = new Caches {
    def newAnyRefMap[K <: AnyRef, V](): mutable.AnyRefMap[K, V] = new mutable.AnyRefMap[K, V]()
    def newWeakMap[K, V](): mutable.WeakHashMap[K, V] = new mutable.WeakHashMap[K, V]()
    def recordCache[T <: Clearable](cache: T): T = cache
    def newWeakSet[K >: Null <: AnyRef](): WeakHashSet[K] = new WeakHashSet[K]()
    def newMap[K, V](): mutable.HashMap[K, V] = new mutable.HashMap[K, V]()
    def newSet[K](): mutable.Set[K] = new mutable.HashSet[K]
  }

  // TODO remove abstraction
  private abstract class Caches {
    def recordCache[T <: Clearable](cache: T): T
    def newWeakMap[K, V](): collection.mutable.WeakHashMap[K, V]
    def newMap[K, V](): collection.mutable.HashMap[K, V]
    def newSet[K](): collection.mutable.Set[K]
    def newWeakSet[K >: Null <: AnyRef](): dotty.tools.dotc.util.WeakHashSet[K]
    def newAnyRefMap[K <: AnyRef, V](): collection.mutable.AnyRefMap[K, V]
  }

  @threadUnsafe protected lazy val classBTypeFromInternalNameMap = {
    perRunCaches.recordCache(collection.concurrent.TrieMap.empty[String, ClassBType])
  }

  /**
   * Cache for the method classBTypeFromSymbol.
   */
  @threadUnsafe private lazy val convertedClasses = perRunCaches.newMap[Symbol, ClassBType]()

  /**
   * The ClassBType for a class symbol `sym`.
   */
  final def classBTypeFromSymbol(classSym: Symbol): ClassBType = {
    assert(classSym != NoSymbol, "Cannot create ClassBType from NoSymbol")
    assert(classSym.isClass, s"Cannot create ClassBType from non-class symbol $classSym")
    assert(
      (!primitiveTypeMap.contains(classSym) || isCompilingPrimitive) &&
      (classSym != defn.NothingClass && classSym != defn.NullClass),
      s"Cannot create ClassBType for special class symbol ${classSym.showFullName}")

    convertedClasses.getOrElse(classSym, {
      val internalName = classSym.fullName.mangledString.replace('.', '/')
      // We first create and add the ClassBType to the hash map before computing its info. This
      // allows initializing cylic dependencies, see the comment on variable ClassBType._info.
      val classBType = new ClassBType(internalName)
      convertedClasses(classSym) = classBType
      setClassInfo(classSym, classBType)
    })
  }

  private def setClassInfo(classSym: Symbol, classBType: ClassBType): ClassBType = {
    val superClassSym: Symbol =  {
      val t = classSym.asClass.superClass
      if (t.exists) t
      else if (classSym.is(Flags.ModuleClass)) {
        // workaround #371

        println(s"Warning: mocking up superclass for $classSym")
        defn.ObjectClass
      }
      else t
    }
    assert(
      if (classSym == defn.ObjectClass)
        superClassSym == NoSymbol
      else if (classSym.isInterface)
        superClassSym == defn.ObjectClass
      else
        // A ClassBType for a primitive class (scala.Boolean et al) is only created when compiling these classes.
        ((superClassSym != NoSymbol) && !superClassSym.isInterface) || (isCompilingPrimitive && primitiveTypeMap.contains(classSym)),
      s"Bad superClass for $classSym: $superClassSym"
    )
    val superClass = if (superClassSym == NoSymbol) None
                     else Some(classBTypeFromSymbol(superClassSym))

    /**
     * All interfaces implemented by a class, except for those inherited through the superclass.
     * Redundant interfaces are removed unless there is a super call to them.
     */
    def (sym: Symbol).superInterfaces: List[Symbol] = {
      val directlyInheritedTraits = sym.directlyInheritedTraits
      val directlyInheritedTraitsSet = directlyInheritedTraits.toSet
      val allBaseClasses = directlyInheritedTraits.iterator.flatMap(_.asClass.baseClasses.drop(1)).toSet
      val superCalls = superCallsMap.getOrElse(sym, Set.empty)
      val additional = (superCalls -- directlyInheritedTraitsSet).filter(_.is(Flags.Trait))
//      if (additional.nonEmpty)
//        println(s"$fullName: adding supertraits $additional")
      directlyInheritedTraits.filter(t => !allBaseClasses(t) || superCalls(t)) ++ additional
    }

    val interfaces = classSym.superInterfaces.map(classBTypeFromSymbol)

    val flags = javaFlags(classSym)

    /* The InnerClass table of a class C must contain all nested classes of C, even if they are only
     * declared but not otherwise referenced in C (from the bytecode or a method / field signature).
     * We collect them here.
     *
     * Nested classes that are also referenced in C will be added to the innerClassBufferASM during
     * code generation, but those duplicates will be eliminated when emitting the InnerClass
     * attribute.
     *
     * Why doe we need to collect classes into innerClassBufferASM at all? To collect references to
     * nested classes, but NOT nested in C, that are used within C.
     */
    val nestedClassSymbols = {
      // The lambdalift phase lifts all nested classes to the enclosing class, so if we collect
      // member classes right after lambdalift, we obtain all nested classes, including local and
      // anonymous ones.
      val nestedClasses = getNestedClasses(classSym)

      // If this is a top-level class, and it has a companion object, the member classes of the
      // companion are added as members of the class. For example:
      //   class C { }
      //   object C {
      //     class D
      //     def f = { class E }
      //   }
      // The class D is added as a member of class C. The reason is that the InnerClass attribute
      // for D will containt class "C" and NOT the module class "C$" as the outer class of D.
      // This is done by buildNestedInfo, the reason is Java compatibility, see comment in BTypes.
      // For consistency, the InnerClass entry for D needs to be present in C - to Java it looks
      // like D is a member of C, not C$.
      val linkedClass = classSym.linkedClass
      val companionModuleMembers = {
        if (classSym.linkedClass.isTopLevelModuleClass) getMemberClasses(classSym.linkedClass)
        else Nil
      }

      nestedClasses ++ companionModuleMembers
    }

    /**
     * For nested java classes, the scala compiler creates both a class and a module (and therefore
     * a module class) symbol. For example, in `class A { class B {} }`, the nestedClassSymbols
     * for A contain both the class B and the module class B.
     * Here we get rid of the module class B, making sure that the class B is present.
     */
    val nestedClassSymbolsNoJavaModuleClasses = nestedClassSymbols.filter(s => {
      if (s.is(Flags.JavaDefined) && s.is(Flags.ModuleClass)) {
        // We could also search in nestedClassSymbols for s.linkedClassOfClass, but sometimes that
        // returns NoSymbol, so it doesn't work.
        val nb = nestedClassSymbols.count(mc => mc.name == s.name && mc.owner == s.owner)
        // this assertion is specific to how ScalaC works. It doesn't apply to dotty, as n dotty there will be B & B$
        // assert(nb == 2, s"Java member module without member class: $s - $nestedClassSymbols")
        false
      } else true
    })

    val memberClasses = nestedClassSymbolsNoJavaModuleClasses.map(classBTypeFromSymbol)

    val nestedInfo = buildNestedInfo(classSym)

    classBType.info = ClassInfo(superClass, interfaces, flags, memberClasses, nestedInfo)
    classBType
  }

  /** For currently compiled classes: All locally defined classes including local classes.
   *  The empty list for classes that are not currently compiled.
   */
  private def getNestedClasses(sym: Symbol): List[Symbol] = definedClasses(sym, ctx.flattenPhase)

  /** For currently compiled classes: All classes that are declared as members of this class
   *  (but not inherited ones). The empty list for classes that are not currently compiled.
   */
  private def getMemberClasses(sym: Symbol): List[Symbol] = definedClasses(sym, ctx.lambdaLiftPhase)

  private def definedClasses(sym: Symbol, phase: Phase) =
    if (sym.isDefinedInCurrentRun)
      ctx.atPhase(phase) {
        toDenot(sym).info.decls.filter(_.isClass)
      }
    else Nil

  private def buildNestedInfo(innerClassSym: Symbol): Option[NestedInfo] = {
    assert(innerClassSym.isClass, s"Cannot build NestedInfo for non-class symbol $innerClassSym")

    val isNested = !innerClassSym.originalOwner.originalLexicallyEnclosingClass.is(Flags.PackageClass)
    if (!isNested) None
    else {
      // See comment in BTypes, when is a class marked static in the InnerClass table.
      val isStaticNestedClass = innerClassSym.originalOwner.originalLexicallyEnclosingClass.isOriginallyStaticOwner

      // After lambdalift (which is where we are), the rawowoner field contains the enclosing class.
      val enclosingClassSym = {
        if (innerClassSym.isClass) {
          val ct = ctx.withPhase(ctx.flattenPhase.prev)
          toDenot(innerClassSym)(ct).owner.enclosingClass(ct)
        }
        else innerClassSym.enclosingClass(ctx.withPhase(ctx.flattenPhase.prev))
      } //todo is handled specially for JavaDefined symbols in scalac

      val enclosingClass: ClassBType = classBTypeFromSymbol(enclosingClassSym)

      val outerName: Option[String] = {
        if (isAnonymousOrLocalClass(innerClassSym)) {
          None
        } else {
          val outerName = innerClassSym.originalOwner.originalLexicallyEnclosingClass.fullName.mangledString.replace('.', '/')
          def dropModule(str: String): String =
            if (!str.isEmpty && str.last == '$') str.take(str.length - 1) else str
          // Java compatibility. See the big comment in BTypes that summarizes the InnerClass spec.
          val outerNameModule =
            if (innerClassSym.originalOwner.originalLexicallyEnclosingClass.isTopLevelModuleClass) dropModule(outerName)
            else outerName
          Some(outerNameModule.toString)
        }
      }

      val innerName: Option[String] = {
        if (innerClassSym.isAnonymousClass || innerClassSym.isAnonymousFunction) None
        else {
          val original = innerClassSym.initial
          Some(innerClassSym.name(ctx.withPhase(original.validFor.phaseId)).mangledString) // moduleSuffix for module classes
        }
      }

      Some(NestedInfo(enclosingClass, outerName, innerName, isStaticNestedClass))
    }
  }

  /**
   * This is basically a re-implementation of sym.isStaticOwner, but using the originalOwner chain.
   *
   * The problem is that we are interested in a source-level property. Various phases changed the
   * symbol's properties in the meantime, mostly lambdalift modified (destructively) the owner.
   * Therefore, `sym.isStatic` is not what we want. For example, in
   *   object T { def f { object U } }
   * the owner of U is T, so UModuleClass.isStatic is true. Phase travel does not help here.
   */
  private def (sym: Symbol).isOriginallyStaticOwner: Boolean =
    sym.is(Flags.PackageClass) || sym.is(Flags.ModuleClass) && sym.originalOwner.originalLexicallyEnclosingClass.isOriginallyStaticOwner

  /**
   * Return the Java modifiers for the given symbol.
   * Java modifiers for classes:
   *  - public, abstract, final, strictfp (not used)
   * for interfaces:
   *  - the same as for classes, without 'final'
   * for fields:
   *  - public, private (*)
   *  - static, final
   * for methods:
   *  - the same as for fields, plus:
   *  - abstract, synchronized (not used), strictfp (not used), native (not used)
   * for all:
   *  - deprecated
   *
   *  (*) protected cannot be used, since inner classes 'see' protected members,
   *      and they would fail verification after lifted.
   */
  final def javaFlags(sym: Symbol): Int = {


    val privateFlag = sym.is(Flags.Private) || (sym.isPrimaryConstructor && sym.owner.isTopLevelModuleClass)

    val finalFlag = sym.is(Flags.Final) &&  !toDenot(sym).isClassConstructor && !(sym.is(Flags.Mutable)) &&  !(sym.enclosingClass.is(Flags.Trait))

    import asm.Opcodes._
    GenBCodeOps.mkFlags(
      if (privateFlag) ACC_PRIVATE else ACC_PUBLIC,
      if (sym.is(Flags.Deferred) || sym.isOneOf(Flags.AbstractOrTrait)) ACC_ABSTRACT else 0,
      if (sym.isInterface) ACC_INTERFACE else 0,

      if (finalFlag &&
        // Primitives are "abstract final" to prohibit instantiation
        // without having to provide any implementations, but that is an
        // illegal combination of modifiers at the bytecode level so
        // suppress final if abstract if present.
        !sym.isOneOf(Flags.AbstractOrTrait) &&
        //  Mixin forwarders are bridges and can be final, but final bridges confuse some frameworks
        !sym.is(Flags.Bridge))
        ACC_FINAL else 0,
      if (sym.isStaticMember) ACC_STATIC else 0,
      if (sym.is(Flags.Bridge)) ACC_BRIDGE | ACC_SYNTHETIC else 0,
      if (sym.is(Flags.Artifact)) ACC_SYNTHETIC else 0,
      if (sym.isClass && !sym.isInterface) ACC_SUPER else 0,
      if (sym.isAllOf(Flags.JavaEnumTrait)) ACC_ENUM else 0,
      if (sym.is(Flags.JavaVarargs)) ACC_VARARGS else 0,
      if (sym.is(Flags.Synchronized)) ACC_SYNCHRONIZED else 0,
      if (false /*sym.isDeprecated*/) asm.Opcodes.ACC_DEPRECATED else 0, // TODO: add an isDeprecated method in SymUtils
      if (sym.is(Flags.Enum)) asm.Opcodes.ACC_ENUM else 0
    )
  }

  def javaFieldFlags(sym: Symbol) = {
    javaFlags(sym) | GenBCodeOps.mkFlags(
      if (sym hasAnnotation TransientAttr) asm.Opcodes.ACC_TRANSIENT else 0,
      if (sym hasAnnotation VolatileAttr)  asm.Opcodes.ACC_VOLATILE  else 0,
      if (sym.is(Flags.Mutable)) 0 else asm.Opcodes.ACC_FINAL
    )
  }
}
