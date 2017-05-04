package smalltalk.compiler;

import org.antlr.symtab.Scope;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import smalltalk.compiler.symbols.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fill STBlock, STMethod objects in Symbol table with bytecode,
 * {@link STCompiledBlock}.
 */
public class CodeGenerator extends SmalltalkBaseVisitor<Code> {
    public static final boolean dumpCode = false;

    public STClass currentClassScope;
    public STMethod currentMethod;
    public Scope currentScope;

    /**
     * With which compiler are we generating code?
     */
    public final Compiler compiler;

    public CodeGenerator(Compiler compiler) {
        this.compiler = compiler;
    }

    /**
     * This and defaultResult() critical to getting code to bubble up the
     * visitor call stack when we don't implement every method.
     */
    @Override
    protected Code aggregateResult(Code aggregate, Code nextResult) {
        if (aggregate != Code.None) {
            if (nextResult != Code.None) {
                return aggregate.join(nextResult);
            }
            return aggregate;
        } else {
            return nextResult;
        }
    }

    @Override
    protected Code defaultResult() {
        return Code.None;
    }

    @Override
    public Code visitFile(SmalltalkParser.FileContext ctx) {
        pushScope(compiler.symtab.GLOBALS);
        visitChildren(ctx);
        popScope();
        return Code.None;
    }

    @Override
    public Code visitMain(SmalltalkParser.MainContext ctx) {
        pushScope(ctx.scope);
        currentMethod = ctx.scope;
        currentClassScope = ctx.classScope;
        Code code = visit(ctx.body()).join(Code.of(Bytecode.SELF, Bytecode.RETURN));
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, ctx.scope);
        ctx.scope.compiledBlock.bytecode = code.bytes();
        popScope();
        currentMethod = null;
        return code;
    }

    @Override
    public Code visitLocalVars(SmalltalkParser.LocalVarsContext ctx) {
        ctx.ID().stream()
                .map(ParseTree::getText)
                .forEach(currentMethod::addLocalVariable);
        return Code.None;
    }

    @Override
    public Code visitAssign(SmalltalkParser.AssignContext ctx) {
        Code code = visit(ctx.messageExpression());
        String varName = ctx.lvalue().sym.getName();
        int localIndex = currentMethod.getLocalIndex(varName);
        int delta =  currentMethod.getRelativeScopeCount(varName);
        if (localIndex != -1) {
            return code.join(Code.of(Bytecode.STORE_LOCAL, 0, delta, 0, localIndex, Bytecode.POP));
        } else {
            int fieldIndex = currentMethod.getFieldIndex(varName);
            return code.join(Code.of(Bytecode.STORE_FIELD, 0, delta, 0, fieldIndex, Bytecode.POP));
        }
    }

    @Override
    public Code visitInstanceVars(SmalltalkParser.InstanceVarsContext ctx) {
        ctx.localVars().ID().stream()
                .map(ParseTree::getText)
                .forEach(currentMethod::addField);
        return Code.None;
    }

    @Override
    public Code visitBlock(SmalltalkParser.BlockContext ctx) {
        pushScope(ctx.scope);
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, ctx.scope);
        addBlockToParent(ctx.scope, ctx.scope.compiledBlock);
        Code code = Code.None;
        if (ctx.body().isEmpty()) {
            code = code.join(Code.of(Bytecode.NIL));
        } else {
            if (ctx.blockArgs() != null) {
                visit(ctx.blockArgs());
            }
            code = code.join(visit(ctx.body()));
        }
        code = code.join(Code.of(Bytecode.BLOCK_RETURN));

        ctx.scope.compiledBlock.bytecode = code.bytes();

        int blockIndex = ctx.scope.index;
        popScope();

        return Code.of(Bytecode.BLOCK, 0, blockIndex);
    }

    private void addBlockToParent(Scope childScope, STCompiledBlock childBlock) {
        Scope enclosingScope = childScope.getEnclosingScope();
        if (enclosingScope instanceof STBlock) {
            STCompiledBlock[] parentBlocks = ((STBlock) enclosingScope).compiledBlock.blocks;
            if (parentBlocks == null) {
                parentBlocks = new STCompiledBlock[1];
                parentBlocks[0] = childBlock;
            } else {
                STCompiledBlock[] newBlocks = Arrays.copyOf(parentBlocks, parentBlocks.length + 1);
                ((STBlock) enclosingScope).compiledBlock.blocks = newBlocks;
                newBlocks[newBlocks.length-1] = childBlock;
            }
        }
    }

    @Override
    public Code visitBlockArgs(SmalltalkParser.BlockArgsContext ctx) {
        // TODO Define locals?
        for (TerminalNode idNode : ctx.ID()) {

        }
        return Code.None;
    }

    @Override
    public Code visitSmalltalkMethodBlock(SmalltalkParser.SmalltalkMethodBlockContext ctx) {
        return visit(ctx.body());
    }

    @Override
    public Code visitPrimitiveMethodBlock(SmalltalkParser.PrimitiveMethodBlockContext ctx) {
        return Code.None;
    }

    @Override
    public Code visitOperatorMethod(SmalltalkParser.OperatorMethodContext ctx) {
        pushScope(ctx.scope);
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, ctx.scope);
        ctx.scope.compiledBlock.bytecode = visit(ctx.methodBlock())
                .join(Code.of(Bytecode.POP, Bytecode.SELF, Bytecode.BLOCK_RETURN))
                .bytes();
        popScope();
        return Code.None;
    }

    @Override
    public Code visitKeywordMethod(SmalltalkParser.KeywordMethodContext ctx) {
        pushScope(ctx.scope);
        for (TerminalNode keywordNode : ctx.KEYWORD()) {
            String keyword = keywordNode.getText();
            addLiteral(keyword);
        }
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, ctx.scope);
        ctx.scope.compiledBlock.bytecode = visit(ctx.methodBlock())
                .join(Code.of(Bytecode.POP, Bytecode.SELF, Bytecode.BLOCK_RETURN))
                .bytes();
        popScope();
        return Code.None;
    }

    @Override
    public Code visitClassMethod(SmalltalkParser.ClassMethodContext ctx) {
        SmalltalkParser.MethodContext methodContext = ctx.method();
        methodContext.scope.isClassMethod=true;
        visit(methodContext);
        return Code.None;
    }

    @Override
    public Code visitNamedMethod(SmalltalkParser.NamedMethodContext ctx) {
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, ctx.scope);
        if (ctx.methodBlock().isEmpty()) {
            ctx.scope.compiledBlock.bytecode = Code.of(Bytecode.SELF, Bytecode.BLOCK_RETURN).bytes();
        } else {
            ctx.scope.compiledBlock.bytecode = visit(ctx.methodBlock())
                    .join(Code.of(Bytecode.POP, Bytecode.SELF, Bytecode.BLOCK_RETURN))
                    .bytes();
        }
        return Code.None;
    }

    @Override
    public Code visitClassDef(SmalltalkParser.ClassDefContext ctx) {
        currentClassScope = ctx.scope;
        pushScope(ctx.scope);
        Code code = visitChildren(ctx);
        popScope();
        currentClassScope = null;
        return code;
    }

    public STCompiledBlock getCompiledPrimitive(STPrimitiveMethod primitive) {
        STCompiledBlock compiledMethod = new STCompiledBlock(currentClassScope, primitive);
        return compiledMethod;
    }

    /**
     * All expressions have values. Must pop each expression value off, except
     * last one, which is the block return value. Visit method for blocks will
     * issue block_return instruction. Visit method for method will issue
     * pop self return.  If last expression is ^expr, the block_return or
     * pop self return is dead code but it is always there as a failsafe.
     * <p>
     * localVars? expr ('.' expr)* '.'?
     */
    @Override
    public Code visitFullBody(SmalltalkParser.FullBodyContext ctx) {
        Code code = Code.None;
        if (ctx.localVars() != null) {
            visitLocalVars(ctx.localVars());
        }
        for (int i = 0; i < ctx.stat().size(); i++) {
            if (i != 0) {
                code = code.join(Code.of(Bytecode.POP));
            }
            code = code.join(visit(ctx.stat(i)));
        }
        for (SmalltalkParser.StatContext statContext : ctx.stat()) {
            code = code.join(visit(statContext));
        }
        return code;
    }

    @Override
    public Code visitEmptyBody(SmalltalkParser.EmptyBodyContext ctx) {
        return Code.of(Bytecode.NIL);
    }

    @Override
    public Code visitReturn(SmalltalkParser.ReturnContext ctx) {
        Code e = visit(ctx.messageExpression());
        if (compiler.genDbg) {
            e = Code.join(e, dbg(ctx.start)); // put dbg after expression as that is when it executes
        }
        Code code = e.join(Compiler.method_return());
        return code;
    }

    public void pushScope(Scope scope) {
        currentScope = scope;
    }

    public void popScope() {
//		if ( currentScope.getEnclosingScope()!=null ) {
//			System.out.println("popping from " + currentScope.getScopeName() + " to " + currentScope.getEnclosingScope().getScopeName());
//		}
//		else {
//			System.out.println("popping from " + currentScope.getScopeName() + " to null");
//		}
        currentScope = currentScope.getEnclosingScope();
    }

    public int getLiteralIndex(String s) {
        return currentClassScope.stringTable.toList().indexOf(s);
    }

    @Override
    public Code visitLiteral(SmalltalkParser.LiteralContext ctx) {
        if(ctx.STRING() != null) {
            String str = ctx.STRING().getText();
            addLiteral(str);
            int literalIndex = getLiteralIndex(str);
            return Code.of(Bytecode.PUSH_LITERAL,  literalIndex);
        }
        if (ctx.CHAR() != null) {
            char c = ctx.CHAR().getText().charAt(0);
            return Code.of(Bytecode.PUSH_CHAR,  c);
        }
        if (ctx.NUMBER() != null) {
            String intStr = ctx.NUMBER().getText();
            short s = Short.parseShort(intStr);
            return Code.of(Bytecode.PUSH_INT, s);
        }
        String literal = ctx.getText();
        if (!literalBytecodes.containsKey(literal)) {
            throw new RuntimeException("Unknown literal: " + literal);
        }
        return Code.of(literalBytecodes.get(literal));
    }

    private static final Map<String, Short> literalBytecodes = new HashMap<String, Short>() {{
        put("nil", Bytecode.NIL);
        put("self", Bytecode.SELF);
        put("true", Bytecode.TRUE);
        put("false", Bytecode.FALSE);
    }};

    public void addLiteral(String id) {
        currentClassScope.stringTable.add(id);
    }

    public Code dbgAtEndMain(Token t) {
        int charPos = t.getCharPositionInLine() + t.getText().length();
        return dbg(t.getLine(), charPos);
    }

    public Code dbgAtEndBlock(Token t) {
        int charPos = t.getCharPositionInLine() + t.getText().length();
        charPos -= 1; // point at ']'
        return dbg(t.getLine(), charPos);
    }

    public Code dbg(Token t) {
        return dbg(t.getLine(), t.getCharPositionInLine());
    }

    public Code dbg(int line, int charPos) {
        return Compiler.dbg(getLiteralIndex(compiler.getFileName()), line, charPos);
    }

    public Code store(String id) {
        return null;
    }

    public Code push(String id) {
        return null;
    }

    @Override
    public Code visitSendMessage(SmalltalkParser.SendMessageContext ctx) {
        Code code = Code.None;
        for (ParseTree child : ctx.children) {
            code = code.join(visit(child));
        }
        return code;
    }

    public Code sendKeywordMsg(ParserRuleContext receiver,
                               Code receiverCode,
                               List<SmalltalkParser.BinaryExpressionContext> args,
                               List<TerminalNode> keywords) {
        for (TerminalNode keyword : keywords) {
            addLiteral(keyword.getText());
        }

        int receiverIndex = getLiteralIndex(keywords.get(0).getText());

        Code code = Code.None;
        code = code.join(Code.of(Bytecode.SEND,  0,  keywords.size(),  0,  receiverIndex));
        return code;
    }

    @Override
    public Code visitKeywordSend(SmalltalkParser.KeywordSendContext ctx) {
        Code recvCode = visit(ctx.recv);
        return sendKeywordMsg(ctx.recv, recvCode, ctx.args, ctx.KEYWORD());
    }

    @Override
    public Code visitBinaryExpression(SmalltalkParser.BinaryExpressionContext ctx) {
        for (SmalltalkParser.BopContext bopContext : ctx.bop()) {
            visit(bopContext);
        }
        Code code = Code.None;
        for (SmalltalkParser.UnaryExpressionContext unaryExprCtx : ctx.unaryExpression()) {
            code = code.join(visit(unaryExprCtx));
        }
        return code;
    }

    @Override
    public Code visitUnaryIsPrimary(SmalltalkParser.UnaryIsPrimaryContext ctx) {
        return Code.None; // TODO
    }

    @Override
    public Code visitUnaryMsgSend(SmalltalkParser.UnaryMsgSendContext ctx) {
        Code code = Code.of(Bytecode.SEND);
        String literal = ctx.ID().getText();
        addLiteral(literal);
        code = code.join(Code.of(0, 0, 0,  getLiteralIndex(literal)));
        return code;
    }

    @Override
    public Code visitBop(SmalltalkParser.BopContext ctx) {
        for (SmalltalkParser.OpcharContext opcharContext : ctx.opchar()) {
            addLiteral(opcharContext.getText());
        }
        return Code.None;
    }

    public String getProgramSourceForSubtree(ParserRuleContext ctx) {
        return ctx.toStringTree();
    }

    /*
    To parse: '^' expr

    Code c = visit(ctx.expr());
    return c.join(Compiler.method_return());
     */
}
