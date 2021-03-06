import scala.collection.mutable.ListBuffer

/**
  * Created by Fei Peng on 3/30/16.
  */
object NameResolver {
  type Environment = scala.collection.immutable.Map[String, IntroduceVar]

  val initEnv = Map.empty[String, IntroduceVar]
  //Map("global" -> IntroduceVar("global"), "window" -> IntroduceVar("window"))

  def apply(script: Statement) : Unit = resolveStatement(script, initEnv)

  def resolveStatement(stmt : Statement, env: Environment) : Set[String] = stmt match {
    case s@Script(stmts) =>
      val newEnv = env ++ extractDecl(s).toMap
      for(s <- stmts){
        resolveStatement(s, newEnv)
      }
      Set()

    case block : BlockStmt =>
      val fvs = scala.collection.mutable.Set.empty[String]
      for(s <- block.stmts) {
        fvs ++= resolveStatement(s, env)
      }
      fvs.toSet

    case decls : VarDeclListStmt =>
      val fvs = scala.collection.mutable.Set.empty[String]
      for(d <- decls.decls) {
        fvs ++= resolveStatement(d, env)
      }
      fvs.toSet

    case _ : EmptyStmt => Set()

    case exprStmt : ExprStmt => resolveExpression(exprStmt.expr, env)

    case decl : VarDeclStmt => resolveExpression(decl.expr, env)

    case func : FunctionDecl => resolveExpression(func.fun, env)

    case ret : ReturnStmt => resolveExpression(ret.expr, env)

    case cond : IfStmt =>
      resolveExpression(cond.cond, env) ++
      resolveStatement(cond.thenPart, env) ++
      resolveStatement(cond.elsePart, env)

    case sw : SwitchStmt =>
      val fvs = scala.collection.mutable.Set.empty[String]
      fvs ++= resolveExpression(sw.cond, env)
      for(c <- sw.cases) {
        fvs ++= resolveExpression(c.expr, env)
        fvs ++= resolveStatement(c.body, env)
      }
      sw.defaultCase match {
        case None =>
        case Some(caseStmt) => fvs ++= resolveStatement(caseStmt, env)
      }
      fvs.toSet

    case c : CaseStmt =>
      resolveExpression(c.expr, env) ++
      resolveStatement(c.body, env)

    case _ : BreakStmt => Set()

    case _ : ContinueStmt => Set()

    case dw : DoWhileStmt =>
      resolveExpression(dw.cond, env) ++
      resolveStatement(dw.body, env)

    case wh : WhileStmt =>
      resolveExpression(wh.cond, env) ++
      resolveStatement(wh.body, env)

    case forStmt : ForStmt =>
      val fvs = scala.collection.mutable.Set.empty[String]
      fvs ++= resolveForInit(forStmt.init, env)
      forStmt.cond match {
        case None =>
        case Some(expr) => fvs ++= resolveExpression(expr, env)
      }
      forStmt.increment match {
        case None =>
        case Some(inc) => fvs ++= resolveExpression(inc, env)
      }
      fvs ++= resolveStatement(forStmt.body, env)
      fvs.toSet

    case forIn : ForInStmt =>
      resolveExpression(forIn.expr, env) ++
      resolveForInInit(forIn.init, env) ++
      resolveStatement(forIn.body, env)

    case l : LabeledStmt =>
      resolveStatement(l.stmt, env)

    case tr : TryStmt =>
      val fvs = scala.collection.mutable.Set.empty[String]
      fvs ++= resolveStatement(tr.body, env)
      for (c <- tr.catchClause) {
        fvs ++= resolveStatement(c, env)
      }
      tr.finalCatch match {
        case None =>
        case Some(s) => fvs ++= resolveStatement(s, env)
      }
      fvs.toSet

    case c : CatchStmt =>
      val newEnv = env + (c.name.str -> c.name)
      resolveStatement(c.body, newEnv)

    case thr : ThrowStmt =>
      resolveExpression(thr.expr, env)

    case err => throw new RuntimeException("Wrong Pattern: " + err)
  }

  def resolveExpression(expression : Expression, env: Environment) : Set[String] = expression match {
    case f@FunctionExpr(_, ps, body) =>
      val psEnv = ps.map((name) => name.str -> name).toMap
      val localEnv = psEnv ++ extractDecl(body)
      val vs = resolveStatement(body, localEnv)
      val fvs = vs.diff(localEnv.keys.toSet)
      if(fvs.nonEmpty) f.freeVariables = fvs
      fvs

    case x@VarRef(name) => env get name match {
      case None =>
      case Some(v) => x.referTo = v
    }
      Set(name)

    case DotRef(obj, _) => resolveExpression(obj, env)
    case BracketRef(obj, prop) =>
      resolveExpression(obj, env) ++
      resolveExpression(prop, env)

    case MethodCall(receiver, method, args) =>
      val fvs = scala.collection.mutable.Set.empty[String]
      fvs ++= resolveExpression(receiver, env)
      fvs ++ resolveExpression(method, env)
      args.foreach {
        case a => fvs ++= resolveExpression(a, env)
      }
      fvs.toSet

    case FuncCall(func, args) =>
      val fvs = scala.collection.mutable.Set.empty[String]
      fvs ++= resolveExpression(func, env)
      args.foreach{
        fvs ++= resolveExpression(_, env)
      }
      fvs.toSet

    case NewCall(func, args) =>
      val fvs = scala.collection.mutable.Set.empty[String]
      fvs ++= resolveExpression(func, env)
      args.foreach{
        fvs ++= resolveExpression(_, env)
      }
      fvs.toSet

    case AssignExpr(_, lv, expr) =>
      resolveLValue(lv, env) ++
      resolveExpression(expr, env)

    case ObjectLit(obj) =>
      val fvs = scala.collection.mutable.Set.empty[String]
      for(ObjectPair(_, expr) <- obj) {
        fvs ++= resolveExpression(expr, env)
      }
      fvs.toSet

    case ArrayLit(array) =>
      val fvs = scala.collection.mutable.Set.empty[String]
      array.foreach {
        fvs ++= resolveExpression(_, env)
      }
      fvs.toSet

    case UnaryAssignExpr(_, lv) => resolveLValue(lv, env)

    case PrefixExpr(_, expr) => resolveExpression(expr, env)

    case InfixExpr(_, e1, e2) =>
      resolveExpression(e1, env) ++
      resolveExpression(e2, env)

    case CondExpr(e1, e2, e3) =>
      resolveExpression(e1, env) ++
      resolveExpression(e2, env) ++
      resolveExpression(e3, env)

    case ListExpr(lst) =>
      val fvs = scala.collection.mutable.Set.empty[String]
      lst.foreach{
        fvs ++= resolveExpression(_, env)
      }
      fvs.toSet
    case _ => Set()
  }

  def resolveForInit(init : ForInit, env: Environment) : Set[String] = init match {
    case NoneInit() => Set()
    case VarListInit(decls) => resolveStatement(decls, env)
    case VarInit(varDecl) => resolveStatement(varDecl, env)
    case ExprInit(expr) => resolveExpression(expr, env)
  }

  def resolveForInInit(init : ForInInit, env : Environment) : Set[String] = init match {
    case ForInVarDecl(name) => Set()
    case ForInLValue(lv) => resolveLValue(lv, env)
  }

  def resolveLValue(lv : LValue, env : Environment) : Set[String] = lv match {
    case lvar : LVarRef =>
      env get lvar.name match {
        case None =>
        case Some(x) => lvar.referTo = x
      }
      Set(lvar.name)

    case dot : LDot => resolveExpression(dot.obj, env)
    case bracket : LBracket =>
      resolveExpression(bracket.computeField, env) ++
      resolveExpression(bracket.obj, env)
  }

  def extractDecl(stmt : Statement) : List[(String, IntroduceVar)] = stmt match {
    case Script(stmts) =>
      val res = ListBuffer.empty[(String, IntroduceVar)]
      for(s <- stmts) {
        res ++= extractDecl(s)
      }
      res.toList
    case BlockStmt(stmts) =>
      val res = ListBuffer.empty[(String, IntroduceVar)]
      for(s <- stmts) {
        res ++= extractDecl(s)
      }
      res.toList

    case VarDeclListStmt(stmts) =>
      val res = ListBuffer.empty[(String, IntroduceVar)]
      for(s <- stmts) {
        res ++= extractDecl(s)
      }
      res.toList

    case EmptyStmt() => Nil

    case ExprStmt(expr) => extractDeclExpr(expr)

    case VarDeclStmt(name, expr) => List(name.str -> name) ++ extractDeclExpr(expr)

    case FunctionDecl(name, _) => List(name.str -> name)

    case ReturnStmt(expr) => extractDeclExpr(expr)

    case IfStmt(cond, s1, s2) => extractDeclExpr(cond) ++ extractDecl(s1) ++ extractDecl(s2)

    case SwitchStmt(cond, cs, defaultCase) =>
      val res = ListBuffer.empty[(String, IntroduceVar)]
      res ++= extractDeclExpr(cond)
      for (c <- cs) {
        res ++= extractDecl(c)
      }
      defaultCase match {
        case None => res.toList
        case Some(c) => (res ++= extractDecl(c)).toList
      }

    case CaseStmt(expr, body) =>
      extractDeclExpr(expr) ++ extractDecl(body)

    case BreakStmt(_) => Nil

    case ContinueStmt(_) => Nil

    case DoWhileStmt(cond, body) =>
      extractDeclExpr(cond) ++ extractDecl(body)

    case WhileStmt(cond, body) =>
      extractDeclExpr(cond) ++ extractDecl(body)

    case ForStmt(init, cond, inc, body) =>
      val l1 = extractDeclForInit(init)
      val l2 = cond match {
        case None => Nil
        case Some(e) => extractDeclExpr(e)
      }
      val l3 = inc match {
        case None => Nil
        case Some(e) => extractDeclExpr(e)
      }
      l1 ++ l2 ++ l3 ++ extractDecl(body)

    case ForInStmt(init, expr, body) =>
      extractDeclForInInit(init) ++ extractDeclExpr(expr) ++ extractDecl(body)

    case LabeledStmt(_, s) => extractDecl(s)

    case TryStmt(body, cs, fs) =>
      val l1 = extractDecl(body)
      val l2 = ListBuffer.empty[(String, IntroduceVar)]
      for (c <- cs) {
        l2 ++= extractDecl(c)
      }
      val l3 = fs match {
        case None => Nil
        case Some(c) => extractDecl(c)
      }
      l1 ++ l2.toList ++ l3

    case CatchStmt(_, body) => extractDecl(body)

    case ThrowStmt(e) => extractDeclExpr(e)
  }

  def extractDeclExpr(expression: Expression) : List[(String, IntroduceVar)] = expression match {
    case EmptyExpr() => Nil
    case FunctionExpr(None, _, _) => Nil
    case FunctionExpr(Some(name), _, _) => List(name.str -> name)
    case DotRef(obj, _) => extractDeclExpr(obj)
    case BracketRef(obj, prop) => extractDeclExpr(obj) ++ extractDeclExpr(prop)
    case MethodCall(receiver, method, args) =>
      val l1 = extractDeclExpr(receiver) ++ extractDeclExpr(method)
      val l2 = ListBuffer.empty[(String, IntroduceVar)]
      for(a <- args) {
        l2 ++= extractDeclExpr(a)
      }
      l1 ++ l2.toList

    case FuncCall(func, args) =>
      val l1 = extractDeclExpr(func)
      val l2 = ListBuffer.empty[(String, IntroduceVar)]
      for(a <- args) {
        l2 ++= extractDeclExpr(a)
      }
      l1 ++ l2.toList

    case NewCall(cons, args) =>
      val l1 = extractDeclExpr(cons)
      val l2 = ListBuffer.empty[(String, IntroduceVar)]
      for(a <- args) {
        l2 ++= extractDeclExpr(a)
      }
      l1 ++ l2.toList

    case AssignExpr(_, lv, expr) =>
      extractDeclLV(lv) ++ extractDeclExpr(expr)

    case ObjectLit(obj) =>
      val res = ListBuffer.empty[(String, IntroduceVar)]
      for (ObjectPair(_, v) <- obj) {
        res ++= extractDeclExpr(v)
      }
      res.toList

    case ArrayLit(array) =>
      val res = ListBuffer.empty[(String, IntroduceVar)]
      for(a <- array) {
        res ++= extractDeclExpr(a)
      }
      res.toList

    case UnaryAssignExpr(_, lv) => extractDeclLV(lv)
    case PrefixExpr(_, expr) => extractDeclExpr(expr)
    case InfixExpr(_, e1, e2) => extractDeclExpr(e1) ++ extractDeclExpr(e2)
    case CondExpr(e1, e2, e3) => extractDeclExpr(e1) ++ extractDeclExpr(e2) ++ extractDeclExpr(e3)
    case ListExpr(lst) => lst.flatMap(extractDeclExpr(_))
    case _ => Nil
  }

  def extractDeclForInit(init : ForInit) : List[(String, IntroduceVar)] = init match {
    case NoneInit() => Nil
    case VarListInit(lst) => extractDecl(lst)
    case VarInit(i) => extractDecl(i)
    case ExprInit(e) => extractDeclExpr(e)
  }

  def extractDeclForInInit(init : ForInInit) : List[(String, IntroduceVar)] = init match {
    case ForInVarDecl(name) => List(name.str -> name)
    case ForInLValue(lv) => extractDeclLV(lv)
  }

  def extractDeclLV(lv : LValue) : List[(String, IntroduceVar)] = lv match {
    case LVarRef(_) => Nil
    case LDot(obj, _) => extractDeclExpr(obj)
    case LBracket(obj, pro) => extractDeclExpr(obj) ++ extractDeclExpr(pro)
  }

}
