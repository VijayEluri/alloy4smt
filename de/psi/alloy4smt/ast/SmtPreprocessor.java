package de.psi.alloy4smt.ast;


import de.psi.alloy4smt.smt.SExpr;
import de.psi.alloy4smt.smt.SMTSolver;
import edu.mit.csail.sdg.alloy4.*;
import edu.mit.csail.sdg.alloy4compiler.ast.*;
import edu.mit.csail.sdg.alloy4compiler.translator.*;
import kodkod.ast.Formula;
import kodkod.ast.Relation;
import kodkod.engine.fol2sat.Translation;
import kodkod.engine.fol2sat.Translator;
import kodkod.engine.fol2sat.TrivialFormulaException;
import kodkod.engine.satlab.SATFactory;
import kodkod.engine.satlab.SATSolver;
import kodkod.instance.Tuple;
import kodkod.instance.TupleSet;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class SmtPreprocessor {
    public static Result build(Command c, ConstList<Sig> allReachableSigs) throws Err {
        ConversionInput input = new ConversionInput(c, allReachableSigs);
        FieldRewritePhase.Result frpr = FieldRewritePhase.run(input);
        FormulaRewritePhase.Result formr = FormulaRewritePhase.run(frpr);
        ComputeScopePhase.Result cspr = ComputeScopePhase.run(formr);
        SmtTranslationPhase.run(cspr);
        return new Result(formr, cspr);
    }

    public static class Result {
        public final Sig.PrimSig sintref;
        public final Sig.Field equalsf;
        public final ConstList<Sig> sigs;
        public final Command command;
        public final A4Solution solution;
        public final ConstList<SExpr<String>> smtExprs;

        public Result(FormulaRewritePhase.Result formr, ComputeScopePhase.Result csp) {
            this.sintref = formr.frp.sigSintref;
            this.equalsf = formr.frp.equalsf;
            this.sigs = formr.allsigs;
            this.command = csp.command;
            this.solution = csp.solution;
            this.smtExprs = csp.smtExprs;
        }
    }

    private static class ConversionInput {
        public final Sig.PrimSig sigSint;
        public final Sig.PrimSig sigSintref;
        public final Sig.Field aqclass;
        public final Sig.Field equalsf;
        public final ConstList<Sig> allReachableSigs;
        public final int defaultScope;
        public final Command command;
        public final String nameSuffix;

        ConversionInput(Command command, ConstList<Sig> allReachableSigs) {
            sigSint = (Sig.PrimSig) Helpers.getSigByName(allReachableSigs, "smtint/Sint");
            sigSintref = (Sig.PrimSig) Helpers.getSigByName(allReachableSigs, "smtint/SintRef");
            aqclass = Helpers.getFieldByName(sigSintref.getFields(), "aqclass");
            equalsf = Helpers.getFieldByName(sigSintref.getFields(), "equals");
            if (sigSint == null || sigSintref == null || aqclass == null || equalsf == null)
                throw new AssertionError();
            this.allReachableSigs = allReachableSigs;
            defaultScope = command.overall < 0 ? 1 : command.overall;
            this.command = command;
            this.nameSuffix = "_c";
        }
    }


    private static class FieldRewritePhase {
        public final ConversionInput in;
        private final Map<Sig, Sig> newsigmap = new HashMap<Sig, Sig>();
        private final Map<Sig.Field, Sig.Field> newfieldmap = new HashMap<Sig.Field, Sig.Field>();
        private final ConstList.TempList<Sig> allsigs = new ConstList.TempList<Sig>();
        private final ConstList.TempList<FieldRewriter.Result> newrefs = new ConstList.TempList<FieldRewriter.Result>();

        public static class Result {
            public final Sig.PrimSig sigSint;
            public final Sig.PrimSig sigSintref;
            public final Sig.Field equalsf;
            public final Sig.Field aqclass;
            public final ConstList<Sig> allsigs;
            public final ConstList<FieldRewriter.Result> sigrefs;
            public final ConstMap<Sig, Sig> sigmap;
            public final ConstMap<Sig.Field, Sig.Field> fieldmap;
            public final ConversionInput input;

            private Result(ConstList<Sig> allsigs, ConstList<FieldRewriter.Result> sigrefs, ConstMap<Sig, Sig> sigmap, ConstMap<Sig.Field, Sig.Field> fieldmap, ConversionInput input) {
                this.sigSint = input.sigSint;
                this.sigSintref = input.sigSintref;
                this.equalsf = input.equalsf;
                this.aqclass = input.aqclass;
                this.allsigs = allsigs;
                this.sigrefs = sigrefs;
                this.sigmap = sigmap;
                this.fieldmap = fieldmap;
                this.input = input;
            }

            public Sig mapSig(Sig old) throws Err {
                if (old.builtin) return old;
                Sig res = sigmap.get(old);
                if (res == null) throw new AssertionError();
                return res;
            }

            public Sig.Field mapField(Sig.Field old) {
                Sig.Field field = fieldmap.get(old);
                if (field == null) throw new AssertionError();
                return field;
            }
        }

        private void addSigMapping(Sig oldsig, Sig newsig) {
            if (oldsig == in.sigSint) throw new AssertionError();
            newsigmap.put(oldsig, newsig);
            allsigs.add(newsig);
        }

        public Sig mapSig(Sig old) throws Err {
            Sig result;
            if (!newsigmap.containsKey(old)) {
                if (old.builtin) {
                    result = old;
                    addSigMapping(old, old);
                } else if (old instanceof Sig.PrimSig) {
                    Attr[] attrs = new Attr[1];
                    result = new Sig.PrimSig(old.label + in.nameSuffix, old.attributes.toArray(attrs));
                    addSigMapping(old, result);
                    for (Sig.Field field : old.getFields()) {
                        final FieldRewriter.Result rewriteResult = FieldRewriter.rewrite(this, old, field);
                        final Sig.Field[] newField = result.addTrickyField(field.pos, field.isPrivate, null, null, field.isMeta, new String[] {field.label + in.nameSuffix}, rewriteResult.field);
                        newfieldmap.put(field, newField[0]);
                        if (rewriteResult.ref != null) {
                            newrefs.add(rewriteResult);
                            allsigs.add(rewriteResult.ref);
                        }
                    }
                } else if (old instanceof Sig.SubsetSig) {
                    throw new AssertionError("not handled yet");
                } else {
                    throw new AssertionError();
                }
            } else {
                result = newsigmap.get(old);
            }
            return result;
        }

        public Sig.Field mapField(Sig.Field old) {
            Sig.Field field = newfieldmap.get(old);
            if (field == null) throw new AssertionError();
            return field;
        }

        private FieldRewritePhase(ConversionInput in) throws Err {
            this.in = in;
            addSigMapping(in.sigSintref, in.sigSintref);
            for (Sig.Field f : in.sigSintref.getFields())
                newfieldmap.put(f, f);
            for (Sig s : in.allReachableSigs)
                if (s != in.sigSint)
                    mapSig(s);
        }

        public static Result run(ConversionInput in) throws Err {
            FieldRewritePhase p = new FieldRewritePhase(in);
            return new Result(p.allsigs.makeConst(), p.newrefs.makeConst(), ConstMap.make(p.newsigmap), ConstMap.make(p.newfieldmap), in);
        }
    }

    private static class FieldRewriter extends VisitReturn<Expr> {
        private final FieldRewritePhase ctx;
        private final Sig sig;
        private final Sig.Field field;
        private final ConstList.TempList<Type> visitedsigs = new ConstList.TempList<Type>();
        private Sig.PrimSig ref = null;

        public static class Result {
            public final Sig.PrimSig ref;
            public final Expr field;
            public final ConstList<Type> refdeps;

            public Result(Sig.PrimSig ref, Expr field, ConstList<Type> refdeps) {
                this.ref = ref;
                this.field = field;
                this.refdeps = refdeps;
            }
        }

        public static Result rewrite(FieldRewritePhase ctx, Sig sig, Sig.Field field) throws Err {
            FieldRewriter rewriter = new FieldRewriter(ctx, sig, field);
            Expr expr = rewriter.visitThis(field.decl().expr);
            return new Result(rewriter.ref, expr, rewriter.visitedsigs.makeConst());
        }

        private FieldRewriter(FieldRewritePhase ctx, Sig sig, Sig.Field field) throws Err {
            this.ctx = ctx;
            this.sig = sig;
            this.field = field;
            visitedsigs.add(ctx.mapSig(sig).type());
        }

        private Expr unexpected() {
            throw new AssertionError("Unexpected field expression!");
        }

        @Override public Expr visit(ExprList x) throws Err {
            return unexpected();
        }
        @Override public Expr visit(ExprCall x) throws Err {
            return unexpected();
        }
        @Override public Expr visit(ExprConstant x) throws Err {
            return unexpected();
        }
        @Override public Expr visit(ExprITE x) throws Err {
            return unexpected();
        }
        @Override public Expr visit(ExprLet x) throws Err {
            return unexpected();
        }
        @Override public Expr visit(ExprQt x) throws Err {
            return unexpected();
        }
        @Override public Expr visit(ExprVar x) throws Err {
            return unexpected();
        }

        @Override public Expr visit(ExprUnary x) throws Err {
            return x.op.make(x.pos, visitThis(x.sub));
        }

        @Override public Expr visit(ExprBinary x) throws Err {
            // FIXME: Handle cases like A+B -> Sint (compared to A->B->Sint)
            if (!x.op.isArrow) throw new AssertionError();
            return x.op.make(x.pos, x.closingBracket, visitThis(x.left), visitThis(x.right));
        }

        @Override
        public Expr visit(Sig x) throws Err {
            Sig s;
            if (x == ctx.in.sigSint) {
                if (ref != null) throw new AssertionError();
                String label = sig.label + "_" + field.label + "_SintRef";
                ref = new Sig.PrimSig(label, ctx.in.sigSintref);
                s = ref;
            } else {
                s = ctx.mapSig(x);
                visitedsigs.add(s.type());
            }
            return s;
        }

        @Override
        public Expr visit(Sig.Field x) throws Err {
            return ctx.mapField(x);
        }
    }


    private static class FormulaRewritePhase {
        public final FieldRewritePhase.Result in;
        private final ConstList.TempList<SintExprDef> sintExprDefs = new ConstList.TempList<SintExprDef>();
        private final ConstList.TempList<SExpr<Sig>> sexprs = new ConstList.TempList<SExpr<Sig>>();
        private final List<Sig> allsigs;
        private final Map<ExprVar, ExprVar> freevarmap = new HashMap<ExprVar, ExprVar>();

        private int exprcnt = 0;

        public static class SintExprDef {
            public final Sig.PrimSig sig;
            public final Sig.Field mapField;
            public final Iterable<Type> dependencies;

            public SintExprDef(Sig.PrimSig sig, Sig.Field mapField, Iterable<Type> dependencies) {
                this.sig = sig;
                this.mapField = mapField;
                this.dependencies = dependencies;
            }
        }

        public static class Result {
            public final ConstList<SintExprDef> sintExprDefs;
            public final ConstList<Sig> allsigs;
            public final ConstList<SExpr<Sig>> sexprs;
            public final Expr newformula;
            public final FieldRewritePhase.Result frp;

            public Result(ConstList<SintExprDef> sintExprDefs, ConstList<Sig> allsigs, ConstList<SExpr<Sig>> sexprs, Expr newformula, FieldRewritePhase.Result frp) {
                this.sintExprDefs = sintExprDefs;
                this.allsigs = allsigs;
                this.sexprs = sexprs;
                this.newformula = newformula;
                this.frp = frp;
            }
        }

        private FormulaRewritePhase(FieldRewritePhase.Result in) {
            this.in = in;
            this.allsigs = new Vector<Sig>(in.allsigs);
        }

        public static Result run(FieldRewritePhase.Result in) throws Err {
            FormulaRewritePhase p = new FormulaRewritePhase(in);
            Expr expr = FormulaRewriter.rewrite(p, in.input.command.formula);
            if (in.sigSintref.children().isEmpty()) {
                p.allsigs.remove(in.sigSintref);
            }
            return new Result(p.sintExprDefs.makeConst(), ConstList.make(p.allsigs), p.sexprs.makeConst(), expr, in);
        }

        public Sig mapSig(Sig old) throws Err {
            return in.mapSig(old);
        }

        public Sig.Field mapField(Sig.Field old) {
            return in.mapField(old);
        }

        public ExprVar mapVar(ExprVar var) {
            ExprVar result = freevarmap.get(var);
            if (result == null) throw new AssertionError();
            return result;
        }

        public void addVarMapping(ExprVar old, ExprVar newvar) {
            freevarmap.put(old, newvar);
        }

        public void addRefSig(Sig.PrimSig ref, Sig.Field mapField, Iterable<Type> dependencies) throws Err {
            allsigs.add(ref);
            sintExprDefs.add(new SintExprDef(ref, mapField, dependencies));
        }

        public void addGlobalFact(SExpr<Sig> sexpr) {
            sexprs.add(sexpr);
        }

        public Expr makeRefSig(SExpr<Sig> sexpr) throws Err {
            StringBuilder sb = new StringBuilder();
            sb.append("SintExpr");
            sb.append(exprcnt++);
            Sig.PrimSig ref = new Sig.PrimSig(sb.toString(), in.sigSintref);
            addRefSig(ref, null, new Vector<Type>());
            SExpr<Sig> symb = SExpr.<Sig>leaf(ref);
            addGlobalFact(SExpr.eq(symb, sexpr));
            return ref;
        }

        /**
         * Creates an alias for an arbitrary complex SintRef expression w.r.t.
         * free variables.
         * @param expr Alloy Expression which must be of type SintRef
         * @return A pair (smtvar, subst) where smtvar contains a reference to the
         *         SMT variable as a SExpr. subst is the substitution for expr which
         *         references the newly generated SintExpr signature relation.
         * @throws Err
         */
        public Pair<SExpr<Sig>, Expr> makeAlias(Expr expr) throws Err {
            if (!isSintRefExpr(expr)) throw new AssertionError();
            final Set<ExprVar> usedquantifiers = FreeVarFinder.find(expr);
            final List<Type> dependencies = new Vector<Type>();

            Sig.PrimSig ref = new Sig.PrimSig("SintExpr" + exprcnt, in.sigSintref);
            Sig.Field mapField = null;
            exprcnt++;

            Expr left;
            if (usedquantifiers.isEmpty()) {
                left = ref;
            } else {
                Type type = null;
                for (ExprVar var : usedquantifiers) {
                    if (!var.type().hasArity(1))
                        throw new AssertionError("Quantified variables with arity > 1 are not supported");
                    dependencies.add(var.type());
                    if (type == null)
                        type = var.type();
                    else
                        type = var.type().product(type);
                }

                left = mapField = ref.addField("map", type.toExpr());
                for (ExprVar var : usedquantifiers) {
                    left = left.join(var);
                }
            }
            addRefSig(ref, mapField, dependencies);
            SExpr<Sig> var = SExpr.<Sig>leaf(ref);
            return new Pair<SExpr<Sig>, Expr>(var, left.join(in.aqclass).equal(expr.join(in.aqclass)));
        }

        public boolean isSintRefExpr(Expr expr) {
            return expr.type().isSubtypeOf(in.sigSintref.type());
        }

        public boolean isSintExpr(Expr expr) {
            return expr.type().equals(in.sigSint.type());
        }
    }


    private static class FreeVarFinder extends VisitQuery<Object> {
        private Set<ExprVar> freeVars = new LinkedHashSet<ExprVar>();

        @Override
        public Object visit(ExprVar x) throws Err {
            freeVars.add(x);
            return super.visit(x);
        }

        private FreeVarFinder() {
        }

        public static Set<ExprVar> find(Expr x) throws Err {
            FreeVarFinder finder = new FreeVarFinder();
            finder.visitThis(x);
            return finder.freeVars;
        }
    }




    private static class ExprRewriter extends VisitReturn<Expr> {

        public static Pair<Expr, AndExpr> rewrite(FormulaRewritePhase ctx, Expr expr) throws Err {
            ExprRewriter rewriter = new ExprRewriter(ctx);
            return new Pair<Expr, AndExpr>(rewriter.apply(expr), rewriter.result);
        }

        private final FormulaRewritePhase ctx;
        private final AndExpr result = new AndExpr();

        private ExprRewriter(FormulaRewritePhase ctx) {
            this.ctx = ctx;
        }

        private Expr apply(Expr expr) throws Err {
            if (ctx.isSintExpr(expr)) {
                Pair<Expr, AndExpr> rewrite = SintExprRewriter.rewrite(ctx, expr);
                result.add(rewrite.b);
                return rewrite.a;
            } else {
                return visitThis(expr);
            }
        }

        private Expr unexpected() {
            throw new AssertionError("unexpected node");
        }

        @Override public Expr visit(ExprBinary x) throws Err {
            return x.op.make(x.pos, x.closingBracket, apply(x.left), apply(x.right));
        }
        @Override public Expr visit(ExprUnary x) throws Err {
            return x.op.make(x.pos, apply(x.sub));
        }
        @Override public Expr visit(ExprITE x) throws Err {
            return ExprITE.make(x.pos, apply(x.cond), apply(x.left), apply(x.right));
        }
        @Override public Expr visit(ExprList x) throws Err {
            ConstList.TempList<Expr> args = new ConstList.TempList<Expr>();
            for (Expr e: x.args) {
                args.add(apply(e));
            }
            return ExprList.make(x.pos, x.closingBracket, x.op, args.makeConst());
        }
        @Override public Expr visit(ExprConstant x) throws Err {
            return x;
        }
        @Override public Expr visit(ExprVar x) throws Err {
            return ctx.mapVar(x);
        }
        @Override public Expr visit(ExprLet x) throws Err {
            return ExprLet.make(x.pos, x.var, apply(x.expr), apply(x.sub));
        }
        @Override public Expr visit(Sig x) throws Err {
            return ctx.mapSig(x);
        }
        @Override public Expr visit(Sig.Field x) throws Err {
            return ctx.mapField(x);
        }

        @Override public Expr visit(ExprCall x) throws Err {
            ConstList.TempList<Expr> args = new ConstList.TempList<Expr>();
            for (Expr e : x.args) {
                args.add(visitThis(e));
            }
            return ExprCall.make(x.pos, x.closingBracket, x.fun, args.makeConst(), x.extraWeight);
        }

        @Override
        public Expr visit(ExprQt x) throws Err {
            return unexpected();
        }

    }


    private static class AndExpr {
        private final ConstList.TempList<Expr> result = new ConstList.TempList<Expr>();

        public void add(Expr expr) {
            if (expr.equals(ExprConstant.TRUE))
                return;
            result.add(expr);
        }

        public void add(AndExpr andExpr) {
            result.addAll(andExpr.result.makeConst());
        }

        public Expr getExpr() {
            if (result.size() == 0)
                return ExprConstant.TRUE;
            if (result.size() == 1)
                return result.get(0);

            Expr last = result.get(result.size() - 1);
            return ExprList.make(last.pos, last.closingBracket, ExprList.Op.AND, result.makeConst());
        }

    }


    private static class FormulaRewriter extends VisitReturn<Expr> {

        public static Expr rewrite(FormulaRewritePhase ctx, Expr formula) throws Err {
            if (!formula.type().is_bool) throw new AssertionError();
            FormulaRewriter rewriter = new FormulaRewriter(ctx);
            // We don't use `applyFormula` here, because FormulaRewriter is also used by
            // SintExprRewriter to rewrite subexpressions.
            return rewriter.visitThis(formula);
        }

        private final FormulaRewritePhase ctx;

        private FormulaRewriter(FormulaRewritePhase ctx) {
            this.ctx = ctx;
        }

        private Expr unexpected() {
            throw new AssertionError("unexpected node");
        }
       
        private Expr applyFormula(Expr expr) throws Err {
            if (!expr.type().is_bool) throw new AssertionError();
            return visitThis(expr);
        }

        @Override public Expr visit(ExprBinary x) throws Err {
            Pair<Expr, AndExpr> left = ExprRewriter.rewrite(ctx, x.left);
            Pair<Expr, AndExpr> right = ExprRewriter.rewrite(ctx, x.right);
            Expr newx = x.op.make(x.pos, x.closingBracket, left.a, right.a);
            AndExpr result = new AndExpr();
            result.add(left.b);
            result.add(right.b);
            result.add(newx);
            return result.getExpr();
        }
        @Override public Expr visit(ExprUnary x) throws Err {
            if (x.sub.type().is_bool)
                return x.op.make(x.pos, applyFormula(x.sub));
            else {
                Pair<Expr, AndExpr> rewritten = ExprRewriter.rewrite(ctx, x.sub);
                AndExpr result = new AndExpr();
                result.add(rewritten.b);
                result.add(x.op.make(x.pos, rewritten.a));
                return result.getExpr();
            }
        }
        @Override public Expr visit(ExprITE x) throws Err {
            return ExprITE.make(x.pos, applyFormula(x.cond), applyFormula(x.left), applyFormula(x.right));
        }
        @Override public Expr visit(ExprList x) throws Err {
            ConstList.TempList<Expr> args = new ConstList.TempList<Expr>();
            for (Expr e: x.args) {
                args.add(applyFormula(e));
            }
            return ExprList.make(x.pos, x.closingBracket, x.op, args.makeConst());
        }
        @Override public Expr visit(ExprConstant x) throws Err {
            return x;
        }
        @Override public Expr visit(ExprVar x) throws Err {
            return ctx.mapVar(x);
        }
        @Override public Expr visit(ExprLet x) throws Err {
            Pair<Expr, AndExpr> rewritten = ExprRewriter.rewrite(ctx, x.expr);
            AndExpr result = new AndExpr();
            result.add(rewritten.b);
            result.add(ExprLet.make(x.pos, x.var, rewritten.a, applyFormula(x.sub)));
            return result.getExpr();
        }
        @Override public Expr visit(Sig x) throws Err {
            return unexpected();
        }
        @Override public Expr visit(Sig.Field x) throws Err {
            return unexpected();
        }

        @Override public Expr visit(ExprCall x) throws Err {
            if (x.fun.label.equals("smtint/gt")) {
                return SintExprRewriter.rewriteFun(ctx, x, ">");
            } else if (x.fun.label.equals("smtint/eq")) {
                return SintExprRewriter.rewriteFun(ctx, x, "=");
            } else {
                ConstList.TempList<Expr> args = new ConstList.TempList<Expr>();
                for (Expr e : x.args) {
                    args.add(visitThis(e));
                }
                return ExprCall.make(x.pos, x.closingBracket, x.fun, args.makeConst(), x.extraWeight);
            }
        }

        @Override
        public Expr visit(ExprQt x) throws Err {
            AndExpr result = new AndExpr();
            ConstList.TempList<Decl> decls = new ConstList.TempList<Decl>();
            for (Decl d : x.decls) {
                Pair<Expr, AndExpr> rewritten = ExprRewriter.rewrite(ctx, d.expr);
                Expr expr = rewritten.a;
                result.add(rewritten.b);
                ConstList.TempList<ExprHasName> names = new ConstList.TempList<ExprHasName>();
                for (ExprHasName ehn : d.names) {
                    ExprVar var = ExprVar.make(ehn.pos, ehn.label, expr.type());
                    ctx.addVarMapping((ExprVar) ehn, var);
                    names.add(var);
                }
                decls.add(new Decl(d.isPrivate, d.disjoint, d.disjoint2, names.makeConst(), expr));
            }
            result.add(x.op.make(x.pos, x.closingBracket, decls.makeConst(), rewrite(ctx, x.sub)));
            return result.getExpr();
        }

    }


    private static class SintExprRewriter extends VisitReturn<SExpr<Sig>> {

        public static Pair<Expr, AndExpr> rewrite(FormulaRewritePhase ctx, Expr expr) throws Err {
            SintExprRewriter rewriter = new SintExprRewriter(ctx);
            SExpr<Sig> result = rewriter.visitThis(expr);
            return new Pair<Expr, AndExpr>(ctx.makeRefSig(result).join(ctx.in.aqclass), rewriter.result);
        }

        public static Expr rewriteFun(FormulaRewritePhase ctx, ExprCall x, String smtOp) throws Err {
            SintExprRewriter rewriter = new SintExprRewriter(ctx);
            ConstList.TempList<SExpr<Sig>> sexprs = new ConstList.TempList<SExpr<Sig>>();
            sexprs.add(SExpr.<Sig>sym(smtOp));
            for (Expr arg : x.args) {
                sexprs.add(rewriter.visitThis(arg));
            }
            ctx.addGlobalFact(new SExpr.SList<Sig>(sexprs.makeConst()));
            return rewriter.result.getExpr();
        }

        private final FormulaRewritePhase ctx;
        private final AndExpr result = new AndExpr();

        private SintExprRewriter(FormulaRewritePhase ctx) {
            this.ctx = ctx;
        }

        private SExpr<Sig> unexpected() {
            throw new AssertionError("unexpected node");
        }

        @Override
        public SExpr<Sig> visit(ExprCall x) throws Err {
            SExpr<Sig> result;
            if (x.fun.label.equals("smtint/gt")) {
                result = SExpr.<Sig>call(">", visitThis(x.args.get(0)), visitThis(x.args.get(1)));
            } else if (x.fun.label.equals("smtint/plus")) {
                result = SExpr.<Sig>call("+", visitThis(x.args.get(0)), visitThis(x.args.get(1)));
            } else if (x.fun.label.equals("smtint/const")) {
                Expr arg = x.args.get(0);
                int c;
                if (arg instanceof ExprConstant)
                    c = ((ExprConstant) arg).num();
                else if (arg instanceof ExprUnary) {
                    ExprUnary cast = (ExprUnary) arg;
                    if (cast.op != ExprUnary.Op.CAST2SIGINT) throw new AssertionError();
                    c = ((ExprConstant) cast.sub).num();
                } else {
                    throw new AssertionError();
                }
                result = SExpr.<Sig>num(c);
            } else {
                throw new AssertionError("User defined Sint functions not yet supported");
            }
            return result;
        }

        @Override public SExpr<Sig> visit(ExprBinary x) throws Err {
            // Relational expression in alloy which results in a Sint, e.g. a . (this/A <: v)
            Pair<Expr, AndExpr> left = ExprRewriter.rewrite(ctx, x.left);
            Pair<Expr, AndExpr> right = ExprRewriter.rewrite(ctx, x.right);
            Pair<SExpr<Sig>, Expr> alias = ctx.makeAlias(x.op.make(x.pos, x.closingBracket, left.a, right.a));
            result.add(left.b);
            result.add(right.b);
            result.add(alias.b);
            return alias.a;
        }
        @Override public SExpr<Sig> visit(ExprList x) throws Err {
            return unexpected();
        }
        @Override public SExpr<Sig> visit(ExprConstant x) throws Err {
            return unexpected();
        }
        @Override public SExpr<Sig> visit(ExprITE x) throws Err {
            return unexpected();
        }
        @Override public SExpr<Sig> visit(ExprLet x) throws Err {
            return unexpected();
        }
        @Override public SExpr<Sig> visit(ExprQt x) throws Err {
            return unexpected();
        }
        @Override public SExpr<Sig> visit(ExprUnary x) throws Err {
            return unexpected();
        }
        @Override public SExpr<Sig> visit(ExprVar x) throws Err {
            return unexpected();
        }
        @Override public SExpr<Sig> visit(Sig x) throws Err {
            return unexpected();
        }
        @Override public SExpr<Sig> visit(Sig.Field x) throws Err {
            return unexpected();
        }
    }



    private static class ComputeScopePhase {
        private final FormulaRewritePhase.Result frpr;
        private final ConstList.TempList<CommandScope> scopes = new ConstList.TempList<CommandScope>();
        private final Map<Sig, CommandScope> scopemap = new HashMap<Sig, CommandScope>();
        private final Command command;
        private final A4Solution solution;
        private final List<SExpr<String>> sexprs = new Vector<SExpr<String>>();
        private final A4Options options;
        private final Relation equalsrel;
        private final ConstList.TempList<SExpr.Leaf<String>> smtvars = new ConstList.TempList<SExpr.Leaf<String>>();
        private final Map<Sig.PrimSig, List<SExpr.Leaf<String>>> sig2smtvars = new HashMap<Sig.PrimSig, List<SExpr.Leaf<String>>>();
        private final Map<Object, SExpr.Leaf<String>> atom2smtvar = new HashMap<Object, SExpr.Leaf<String>>();
        private final List<Pair<SExpr.Leaf<String>, SExpr.Leaf<String>>> equalsSmtBounds = new Vector<Pair<SExpr.Leaf<String>, SExpr.Leaf<String>>>();

        public static class Result {
            public final Command command;
            public final A4Solution solution;
            public final A4Options options;
            public final Relation equalsrel;
            public final ConstList<SExpr<String>> smtExprs;
            public final ConstList<SExpr.Leaf<String>> smtVars;
            public final ConstList<Pair<SExpr.Leaf<String>, SExpr.Leaf<String>>> equalsSmtBounds;

            public Result(Command command, A4Solution solution, A4Options options, Relation equalsrel, ConstList<SExpr<String>> smtExprs, ConstList<SExpr.Leaf<String>> smtVars, ConstList<Pair<SExpr.Leaf<String>, SExpr.Leaf<String>>> equalsSmtBounds) {
                this.command = command;
                this.solution = solution;
                this.options = options;
                this.equalsrel = equalsrel;
                this.smtExprs = smtExprs;
                this.smtVars = smtVars;
                this.equalsSmtBounds = equalsSmtBounds;
            }
        }

        private void addScope(CommandScope scope) {
            scopemap.put(scope.sig, scope);
            scopes.add(scope);
        }

        private int computeScope(Iterable<Type> dependencies) throws Err {
            int result = 1;
            for (Type type : dependencies) {
                if (!type.hasArity(1)) throw new AssertionError();
                int unionscope = 0;
                for (List<Sig.PrimSig> l : type.fold()) {
                    if (l.size() != 1) throw new AssertionError();
                    Sig.PrimSig depsig = l.get(0);
                    CommandScope scope = scopemap.get(depsig);
                    if (scope != null) {
                        unionscope += scope.endingScope;
                    } else if (depsig.isOne != null || depsig.isLone != null) {
                        unionscope++;
                    } else {
                        unionscope += frpr.frp.input.defaultScope;
                    }
                }
                result *= unionscope;
            }
            return result;
        }

        private static A4Options makeA4Options() {
            final A4Options opt = new A4Options();
            opt.recordKodkod = true;
            opt.tempDirectory = "/tmp";
            opt.solverDirectory = "/tmp";
            opt.solver = A4Options.SatSolver.SAT4J;
            opt.skolemDepth = 4;
            return opt;
        }

        private ComputeScopePhase(FormulaRewritePhase.Result in) throws Err {
            this.frpr = in;

            final List<Sig.PrimSig> sintrefs = new Vector<Sig.PrimSig>();

            // Handle scopes of original signatures
            for (CommandScope scope : in.frp.input.command.scope) {
                addScope(new CommandScope(in.frp.mapSig(scope.sig), scope.isExact, scope.endingScope));
            }
            // Handle scopes of SintRef fields
            for (FieldRewriter.Result rr : in.frp.sigrefs) {
                sintrefs.add(rr.ref);
                addScope(new CommandScope(rr.ref, true, computeScope(rr.refdeps)));
            }
            // Handle scopes of SintExprs
            for (FormulaRewritePhase.SintExprDef sed : in.sintExprDefs) {
                sintrefs.add(sed.sig);
                addScope(new CommandScope(sed.sig, true, computeScope(sed.dependencies)));
            }

            // Build A4Solution
            command = in.frp.input.command.change(scopes.makeConst()).change(in.newformula);
            options = makeA4Options();
            Pair<A4Solution, ScopeComputer> solsc = ScopeComputer.compute(A4Reporter.NOP, options, in.allsigs, command);
            solution = solsc.a;
            BoundsComputer.compute(A4Reporter.NOP, solution, solsc.b, in.allsigs);

            // Populate SintRef atom -> SMT variable mapping
            for (Sig.PrimSig sig : sintrefs) {
                List<SExpr.Leaf<String>> vars = new Vector<SExpr.Leaf<String>>();
                for (Object atom : getAtoms(sig)) {
                    SExpr.Leaf<String> var = new SExpr.Leaf<String>(atom.toString().replace("$", "_"));
                    vars.add(var);
                    atom2smtvar.put(atom, var);
                    smtvars.add(var);
                }
                sig2smtvars.put(sig, vars);
            }

            // set bounds for equals field
            equalsrel = (Relation) solution.a2k(in.frp.equalsf);
            if (equalsrel != null) {
                final List<Object> sintrefAtoms = new Vector<Object>();
                for (Sig.PrimSig s : sintrefs) {
                    sintrefAtoms.addAll(getAtoms(s));
                }

                final TupleSet equalsBound = new TupleSet(solution.getBounds().universe(), 2);
                for (int i = 0; i < sintrefAtoms.size(); ++i) {
                    for (int j = i + 1; j < sintrefAtoms.size(); ++j) {
                        Object atomA = sintrefAtoms.get(i);
                        Object atomB = sintrefAtoms.get(j);
                        SExpr.Leaf<String> varA = atom2smtvar.get(atomA);
                        SExpr.Leaf<String> varB = atom2smtvar.get(atomB);
                        equalsBound.add(solution.getFactory().tuple(atomA, atomB));
                        equalsSmtBounds.add(new Pair<SExpr.Leaf<String>, SExpr.Leaf<String>>(varA, varB));
                    }
                }

                solution.shrink(equalsrel, new TupleSet(solution.getBounds().universe(), 2), equalsBound);
            }

            // set bounds for SintExpr maps
            for (FormulaRewritePhase.SintExprDef sed : in.sintExprDefs) {
                if (sed.mapField == null) continue;
                final List<List<Object>> depAtoms = getDependentAtoms(sed.dependencies);
                final int depSize = depAtoms.size();
                final List<Object[]> sourceTuples = new Vector<Object[]>();
                buildMapTupleSet(sourceTuples, depAtoms, new Object[depSize+1], 0);
                final List<Object> sintExprAtoms = getAtoms(sed.sig);
                if (sintExprAtoms.size() != sourceTuples.size())
                    throw new AssertionError();
                final Iterator<Object> atomIt = sintExprAtoms.iterator();
                final TupleSet mapTuple = new TupleSet(solution.getBounds().universe(), depSize+1);
                for (Object[] tpl : sourceTuples) {
                    tpl[0] = atomIt.next();
                    mapTuple.add(solution.getFactory().tuple(tpl));
                }
                final Relation rel = (Relation) solution.a2k(sed.mapField);
                solution.shrink(rel, mapTuple, mapTuple);
            }

            // convert sexpr-sig tree to a sexpr-string tree, which consists of every combination
            // of atoms of the leaf signature nodes.
            SExprConverter sec = new SExprConverter(this);
            for (SExpr<Sig> sexpr : in.sexprs) {
                sexprs.addAll(sec.visitThis(sexpr));
            }
        }

        private static class SExprConverter extends SExpr.Visitor<Sig, List<SExpr<String>>> {
            public SExprConverter(ComputeScopePhase csp) {
                this.csp = csp;
            }

            private final ComputeScopePhase csp;

            @Override
            public List<SExpr<String>> visit(SExpr.Symbol<Sig> sigSymbol) {
                final Vector<SExpr<String>> result = new Vector<SExpr<String>>();
                result.add(new SExpr.Symbol<String>(sigSymbol.getName()));
                return result;
            }

            @Override
            public List<SExpr<String>> visit(SExpr.Leaf<Sig> sigLeaf) {
                return new Vector<SExpr<String>>(csp.sig2smtvars.get((Sig.PrimSig) sigLeaf.getValue()));
            }

            @Override
            public List<SExpr<String>> visit(SExpr.SList<Sig> sigSList) {
                final Vector<SExpr<String>> result = new Vector<SExpr<String>>();
                final List<List<SExpr<String>>> converted = new Vector<List<SExpr<String>>>();
                for (SExpr<Sig> sub : sigSList.getItems()) {
                    converted.add(visitThis(sub));
                }
                build(result, converted, new SExpr[converted.size()], 0);
                return result;
            }

            static void build(List<SExpr<String>> result, List<List<SExpr<String>>> input, SExpr<String>[] selected, int depth) {
                if (depth == input.size()) {
                    result.add(new SExpr.SList<String>(Arrays.asList(selected.clone())));
                } else {
                    for (SExpr<String> expr : input.get(depth)) {
                        selected[depth] = expr;
                        build(result, input, selected, depth + 1);
                    }
                }
            }
        }

        private static void buildMapTupleSet(List<Object[]> output, List<List<Object>> sourceAtoms, Object[] selected, int depth) {
            if (depth == sourceAtoms.size()) {
                output.add(selected.clone());
            } else {
                for (Object obj : sourceAtoms.get(depth)) {
                    selected[depth+1] = obj;
                    buildMapTupleSet(output, sourceAtoms, selected, depth + 1);
                }
            }
        }

        private List<Object> getAtoms(Sig.PrimSig sig) {
            final List<Object> result = new Vector<Object>();
            final Relation srel = (Relation) solution.a2k(sig);
            final TupleSet tuples = solution.getBounds().upperBound(srel);
            if (tuples.arity() != 1) throw new AssertionError();
            for (Tuple t : tuples) {
                result.add(t.atom(0));
            }
            return result;
        }

        private List<List<Object>> getDependentAtoms(Iterable<Type> dependencies) {
            List<List<Object>> result = new Vector<List<Object>>();

            for (Type dep : dependencies) {
                if (!dep.hasArity(1)) throw new AssertionError();
                for (List<Sig.PrimSig> l : dep.fold()) {
                    if (l.size() != 1) throw new AssertionError();
                    result.add(getAtoms(l.get(0)));
                }
            }

            return result;
        }

        public static Result run(FormulaRewritePhase.Result in) throws Err {
            ComputeScopePhase p = new ComputeScopePhase(in);
            return new Result(p.command, p.solution, p.options, p.equalsrel, ConstList.make(p.sexprs), p.smtvars.makeConst(), ConstList.make(p.equalsSmtBounds));
        }
    }


    private static class SmtTranslationPhase extends TranslateAlloyToKodkod {

        protected SmtTranslationPhase(A4Reporter rep, A4Options opt, A4Solution frame, Command cmd) {
            super(rep, opt, frame, cmd);
        }

        public static void run(ComputeScopePhase.Result csp) throws Err {
            final SMTSolver solver = new SMTSolver();

            csp.solution.solver.options().setSolver(new SATFactory() {
                @Override
                public SATSolver instance() {
                    return solver;
                }

                @Override public boolean prover() {
                    return false;
                }
                @Override public boolean minimizer() {
                    return false;
                }
                @Override public boolean incremental() {
                    return false;
                }
                @Override public String toString() {
                    return "SMT Backend";
                }
            });

            SmtTranslationPhase stp = new SmtTranslationPhase(A4Reporter.NOP, csp.options, csp.solution, csp.command);
            stp.makeFacts(csp.command.formula);
            final Formula kformula = stp.frame.makeFormula(A4Reporter.NOP, new Simplifier());
            final Translation tl;
            try {
                tl = Translator.translate(kformula, stp.frame.getBounds(), stp.frame.solver.options());
            } catch (TrivialFormulaException e) {
                e.printStackTrace();
                throw new ErrorFatal(e.toString());
            }

            for (SExpr.Leaf<String> var : csp.smtVars) {
                solver.addIntVariable(var.getValue());
            }

            if (csp.equalsrel != null) {
                int[] relvars = tl.primaryVariables(csp.equalsrel).toArray();
                for (int i = 0; i < relvars.length; ++i) {
                    final Pair<SExpr.Leaf<String>, SExpr.Leaf<String>> pair = csp.equalsSmtBounds.get(i);
                    solver.addEquality(relvars[i], SExpr.<String>eq(pair.a, pair.b));
                }
            }

            kodkodDebug(csp, stp, kformula, solver);
        }

        private static void kodkodDebug(ComputeScopePhase.Result csp, SmtTranslationPhase stp, Formula kformula, SMTSolver solver) {
            // KODKOD DEBUG OUTPUT
            List<String> kkatoms = new Vector<String>();
            for (Object atom : stp.frame.getFactory().universe()) {
                kkatoms.add((String) atom);
            }
            String kkout = TranslateKodkodToJava.convert(kformula, stp.frame.getBitwidth(), kkatoms, stp.frame.getBounds(), null);
            try {
                File tmpout = File.createTempFile("kodkodout", ".txt");
                FileWriter writer = new FileWriter(tmpout);
                writer.write(csp.command.formula.toString());
                writer.write("\n=======================================\n");
                writer.write(kkout);
                writer.write("\n=======================================\n");
                writer.write(solver.makeSMTFormula().toString());
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
