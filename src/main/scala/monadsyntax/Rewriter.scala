package monadsyntax

import language.experimental.macros
import scala.language.higherKinds
import scala.reflect.macros.Context

import scalaz._

/**
 * Transforms the AST of an argument to `monadically`, rewriting `unwrap` calls
 * using `>>=` and `pure`. 
 */
private abstract class Rewriter {

  val c: Context

  import c.universe._

  val TMPVAR_PREFIX = "$tmc$"

  var mc: MonadContext = {
    val tree = c.macroApplication
    var found: Option[MonadContext] = resolveMonadContext(tree)
    new Traverser() {
      override def traverse(tree: Tree) = tree match {
        case Apply(fun, List(arg)) if isUnwrap(fun) =>
          for (newFound <- resolveMonadContext(arg))
            if (found.isDefined) {
              val tpe = found.get.tpe
              val newTpe = newFound.tpe
              if (tpe != newTpe)
                c.abort(arg.pos, s"Cannot mix monads! ($tpe vs. $newTpe)")
            } else found = Some(newFound)
        case _ => super.traverse(tree)
      }
    }.traverse(tree)
    if (!found.isDefined)
      c.abort(tree.pos, "Could not infer the monad being used here")
    found.get
  }

  /**
   * The entry point of the algorithm, the meat of the work is done in `transform`.
   */
  def rewrite(tree: Tree): Tree = {
    var newTree = c.resetLocalAttrs(tree)
    newTree = transform(newTree)
    // println(show(newTree))
    newTree = c.typeCheck(newTree)//, WildcardType, true)
    val TypeRef(pre, sym, args) = c.macroApplication.tpe
    if (sym == typeOf[Nothing].typeSymbol)
      // fix up type inference
      c.macroApplication.setType(newTree.tpe)
    newTree
  }

  /**
   * Takes a tree representing an expression of type `A`, possibly containing `unwrap` calls,
   * and transforms it into a tree representing an expression of type `M[A]`, using monadic
   * operations.
   */
  def transform(tree: Tree): Tree = transform(extractBindings(tree))

  def transform(group: BindGroup, isPure: Boolean = true): Tree = group match { case(binds, tree) =>
    binds match {
      case Nil => 
        if (isPure) Apply(mc.pure, List(tree)) // make monadic
        else tree
      case (name, unwrappedFrom) :: moreBinds => 
        val innerTree = transform((moreBinds, tree), isPure)
        // q"$unwrappedFrom.flatMap($name => $innerTree)"
        val fun = Function(List(ValDef(Modifiers(Flag.PARAM), name, TypeTree(), EmptyTree)), innerTree)
        Apply(Apply(mc.bind, List(unwrappedFrom)), List(fun))
    }
  }

  def pkg = rootMirror.staticPackage("monadsyntax").asModule.moduleClass.asType.toType
  def wrapSymbol = pkg.member(newTermName(MONADICALLY))
  def unwrapSymbol = pkg.member(newTermName(UNWRAP))
  def isWrap(tree: Tree): Boolean = tree.symbol == wrapSymbol
  def isUnwrap(tree: Tree): Boolean = tree.symbol == unwrapSymbol

  type Binding = (TermName, Tree)

  /**
   * A `BindGroup` represents a sequence of monadic bindings and the Tree in which they
   * are to be bound.
   */
  type BindGroup = (List[Binding], Tree)

  /**
   * - Takes a tree for an expression of type A
   * - Makes a new tree in which all invocations of `unwrap` are replaced with
   *   fresh identifiers
   * - Returns all new bindings created, along with the new tree.
   * - The new tree still represents an expression of type A
   * - The terms of the bindings represent expressions of type M[A]
   */
  def extractBindings(tree: Tree): BindGroup = tree match {
  
    case Apply(fun, args) => 
      if (isUnwrap(fun)) {
        val (binds, newArg) = extractBindings(args(0))
        extractUnwrap(binds, newArg)
      } else {
        val (funBinds, newFun) = extractBindings(fun)
        val (argBindss, newArgs) = (args map extractBindings).unzip
        (funBinds ++ argBindss.flatten, Apply(newFun, newArgs))
      }
    
    case Select(tree, name) =>
      val (binds, newTree) = extractBindings(tree)
      (binds, Select(newTree, name))
    
    case ValDef(mod, lhs, typ, rhs) =>
      val (binds, newRhs) = extractBindings(rhs)
      (binds, ValDef(mod, lhs, typ, newRhs))
    
    case Block(stats, expr) => 
      val (binds, newBlock) = extractBlock(stats :+ expr)
      extractUnwrap(binds, newBlock)

    case If(cond, branch1, branch2) =>
      val (condBinds, newCond) = extractBindings(cond)
      val wrapped1 = transform(branch1)
      val wrapped2 = transform(branch2)
      extractUnwrap(condBinds, If(newCond, wrapped1, wrapped2))
    
    case _ => (Nil, tree)
  }

  /**
   * Takes a list of bindings and a monadic tree, binds the tree to a new identifier and adds
   * that binding to the list; the tree in the resulting group is just the newly-bound identifier.
   */
  def extractUnwrap(binds: List[Binding], tree: Tree): BindGroup = {
    // TODO make this generate guaranteed collision-free names
    val freshName = newTermName(c.fresh(TMPVAR_PREFIX))
    (binds :+ ((freshName, tree)), Ident(freshName))
  }

  /**
   * Takes a list of statements, transforms them and then sequences them monadically.
   */
  def extractBlock(stmts: List[Tree]): BindGroup = stmts match {
    case expr :: Nil  => (Nil, Block(Nil, transform(expr)))
    case stmt :: rest =>
      val (bindings, newStmt) = extractBindings(stmt)
      val restGrp@(restBindings, Block(restStmts, expr)) = extractBlock(rest)
      val newBlock = 
        if (restBindings.isEmpty) Block(newStmt :: restStmts, expr)
        else Block(List(newStmt), transform(restGrp, isPure = false))
      (bindings, newBlock)
  }

  /**
   * Called on a tree with an applied monadic type `M[A]`, extracts the type constructor 
   * `M` and determines the `Monad` instance to use. This technique lifted from the scala-idioms 
   * library.
   */
  def resolveMonadContext(tree: Tree): Option[MonadContext] = tree.tpe match {
    case TypeRef(_, sym, _) =>
      if (sym == typeOf[Nothing].typeSymbol)
        return None
  
      val tpe = TypeRef(NoPrefix, sym, Nil)

      val monadTypeRef = TypeRef(NoPrefix, typeOf[Monad[Any]].typeSymbol, List(tpe))
      val monadInstance = c.inferImplicitValue(monadTypeRef)

      if (monadInstance == EmptyTree)
        c.abort(tree.pos, s"Unable to find $monadTypeRef instance in implicit scope")

      val pure = Select(monadInstance, newTermName("pure"))
      val bind = Select(monadInstance, newTermName("bind"))

      Some(MonadContext(tpe, pure, bind))
    
      case otherTpe => 
        c.abort(tree.pos, s"Not a monadic type")
  }

  /**
   * A combination of a type constructor `M[_]` for which there exists a `Monad[M]`, 
   * along with its associated `pure` and `bind` identifiers.
   */
  case class MonadContext(tpe: Type, pure: Tree, bind: Tree)
}