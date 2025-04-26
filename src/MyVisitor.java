import kotlin.Pair;
import kotlin.Unit;
import org.antlr.v4.runtime.tree.*;
import org.bytedeco.llvm.global.LLVM;
import org.llvm4j.llvm4j.*;
import org.llvm4j.llvm4j.Module;
import org.llvm4j.llvm4j.Value; // 确保导入 Value
import org.llvm4j.optional.Option; // 导入 Option

import java.io.File; // 导入 File
import java.util.*;
import java.util.stream.Collectors;
// 导入常量相关的类
import org.llvm4j.llvm4j.Constant;
import org.llvm4j.llvm4j.ConstantInt;
// 导入类型相关的类
import org.llvm4j.llvm4j.IntegerType;
import org.llvm4j.llvm4j.Type;
import org.llvm4j.llvm4j.FunctionType;
// 导入构建器相关的类
import org.llvm4j.llvm4j.IRBuilder;
import org.llvm4j.llvm4j.BasicBlock;
// 导入运算相关的枚举
import org.llvm4j.llvm4j.IntPredicate;
import org.llvm4j.llvm4j.WrapSemantics;
// 导入 Result 相关的类和方法
import org.llvm4j.optional.Result;
import org.llvm4j.optional.Err; // Optional: if needed for specific error handling
import org.llvm4j.optional.Some;


public class MyVisitor extends SysYParserBaseVisitor<Value> {
    private final Context context;
    private final Module module;
    private final IRBuilder builder;
    private final Deque<Map<String, Value>> scopeStack = new LinkedList<>();

    private boolean isConstantEvaluation = false;
    // 循环栈，用于 break 和 continue
    private final Deque<LoopContext> loopStack = new LinkedList<>();
    private FunctionType currentFunctionType;
    private Function currentFunction;

    // 循环上下文，存储 while 的头块和退出块
    private static class LoopContext {
        final BasicBlock headerBlock; // 循环条件块
        final BasicBlock exitBlock;   // 循环退出块

        LoopContext(BasicBlock headerBlock, BasicBlock exitBlock) {
            this.headerBlock = headerBlock;
            this.exitBlock = exitBlock;
        }
    }

    public MyVisitor() {
        context = new Context();
        module = context.newModule("sysy_module");
        builder = context.newIRBuilder();
        // 初始化全局作用域
        scopeStack.push(new HashMap<>());
        //System.err.println("Debug: MyVisitor initialized, scopeStack size: " + scopeStack.size());
    }

    // 辅助方法：获取基本块的终止指令
    private Option<Instruction> getTerminator(BasicBlock block) {
        var termRef = LLVM.LLVMGetBasicBlockTerminator(block.getRef());
        return Option.of(termRef).map(Instruction::new);
    }

    public void writeToFile(String filePath) {
        Result<Unit, AssertionError> result = module.dump(Option.of(new File(filePath)));
        if (result.isErr()) {
            throw new RuntimeException("Failed to write IR", result.err());
        }
    }

    public String getIRString() {
        return module.getAsString();
    }

    public void close() {
        context.close();
    }

    // --- Visitor Methods  ---

    @Override
    public Value visitProgram(SysYParser.ProgramContext ctx) {
        // Default behavior visits children. This will visit CompUnit.
        return super.visitProgram(ctx);
    }

    @Override
    public Value visitCompUnit(SysYParser.CompUnitContext ctx) {
        // 调试：输出 scopeStack 状态
        //System.err.println("Debug: visitCompUnit start, scopeStack size: " + scopeStack.size());

        for (SysYParser.DeclContext decl : ctx.decl()) {
            //System.err.println("Debug: visitCompUnit processing decl at line: " + decl.getStart().getLine());
            visit(decl);
        }
        for (SysYParser.FuncDefContext funcDef : ctx.funcDef()) {
            //System.err.println("Debug: visitCompUnit processing funcDef: " + funcDef.IDENT().getText());
            visit(funcDef);
        }

        //System.err.println("Debug: visitCompUnit end, scopeStack size: " + scopeStack.size());
        return null;
    }


    @Override
    public Value visitFuncDef(SysYParser.FuncDefContext ctx) {
        // 提取函数信息
        String funcName = ctx.IDENT().getText();
        Type returnType = visitFuncTypeCustom(ctx.funcType());
        //System.err.println("Debug: Function '" + funcName + "' returnType: " +
        //        returnType.getClass().getSimpleName() + ", line: " + ctx.getStart().getLine());

        // 验证 main 函数
        if (funcName.equals("main")) {
            if (returnType.isVoidType()) {
                throw new RuntimeException("Error: 'main' must return int at line " + ctx.getStart().getLine());
            }
            if (ctx.funcFParams() != null && !ctx.funcFParams().funcFParam().isEmpty()) {
                throw new RuntimeException("Error: 'main' must have no parameters at line " + ctx.getStart().getLine());
            }
        }

        // 处理函数参数
        List<Type> paramTypes = ctx.funcFParams() != null ?
                ctx.funcFParams().funcFParam().stream()
                        .map(param -> context.getInt32Type())
                        .collect(Collectors.toList()) : List.of();
        boolean isVariadic = false;
        FunctionType funcType = context.getFunctionType(returnType, paramTypes.toArray(new Type[0]), isVariadic);
        //System.err.println("Debug: visitFuncDef funcType: " + funcType.getReturnType().getAsString());

        Function func = module.addFunction(funcName, funcType);
        //System.err.println("Debug: visitFuncDef function created: " + func.getName() +
        //        ", module: " + module.getModuleIdentifier());

        // 设置当前函数和类型
        Function prevFunction = currentFunction;
        FunctionType prevFunctionType = currentFunctionType;
        currentFunction = func;
        currentFunctionType = funcType;
        //System.err.println("Debug: visitFuncDef set currentFunctionType: " +
        //        currentFunctionType.getReturnType().getAsString() +
        //        ", currentFunction: " + func.getName());

        // 创建 entry 基本块
        BasicBlock entry = context.newBasicBlock(funcName+"Entry");
        func.addBasicBlock(entry);
        //System.err.println("Debug: visitFuncDef added entry block: " + entry.getName() +
        //        ", parent function: " + (entry.getFunction().isSome() ? entry.getFunction().unwrap().getName() : "none"));

        builder.positionAfter(entry);
        //System.err.println("Debug: visitFuncDef builder insertion block: " +
        //        (builder.getInsertionBlock().isSome() ? builder.getInsertionBlock().unwrap().getName() : "none"));

        // 初始化作用域
        scopeStack.push(new HashMap<>());
        //System.err.println("Debug: visitFuncDef scopeStack push, size: " + scopeStack.size());

        // 处理参数
        if (ctx.funcFParams() != null) {
            List<SysYParser.FuncFParamContext> params = ctx.funcFParams().funcFParam();
            if (params.size() != func.getParameterCount()) {
                throw new RuntimeException("Error: Parameter count mismatch at line " + ctx.getStart().getLine());
            }
            for (int i = 0; i < params.size(); i++) {
                Value paramAlloc = visitFuncFParam(params.get(i));
                Argument paramValue = func.getParameter(i).unwrap();
                builder.buildStore(paramAlloc, paramValue);
            }
        }

        // 处理函数体
        visit(ctx.block());

        // 验证函数终止
        Option<BasicBlock> currentBlock = builder.getInsertionBlock();
        if (currentBlock.isNone()) {
            throw new RuntimeException("Error: No insertion block set after block processing at line " + ctx.getStart().getLine());
        }
        if (getTerminator(currentBlock.unwrap()).isNone()) {
            if (returnType.isVoidType()) {
                builder.buildReturn(Option.empty());
            } else {
                ConstantInt defaultRet = context.getInt32Type().getConstant(0, true);
                builder.buildReturn(new Some<>(defaultRet));
                //System.err.println("Warning: Missing return in non-void function '" + funcName + "' at line " + ctx.getStart().getLine());
            }
        }

        // 清理
        scopeStack.pop();
        //System.err.println("Debug: visitFuncDef scopeStack pop, size: " + scopeStack.size());
        currentFunction = prevFunction;
        currentFunctionType = prevFunctionType;
        //System.err.println("Debug: visitFuncDef cleared currentFunctionType and currentFunction");

        // 验证函数属性
        if (!func.getName().equals(funcName) || func.getParameterCount() != paramTypes.size()) {
            throw new RuntimeException("Error: Function '" + funcName + "' has invalid properties at line " +
                    ctx.getStart().getLine());
        }

        return func;
    }


    public Type visitFuncTypeCustom(SysYParser.FuncTypeContext ctx) {
        if (ctx.INT() != null) {
            return context.getInt32Type();
        }
        if (ctx.VOID() != null) {
            return context.getVoidType();
        }
        throw new RuntimeException("Invalid function type at line " + ctx.getStart().getLine());
    }

    // 保留基类方法，抛出异常
    @Override
    public Value visitFuncType(SysYParser.FuncTypeContext ctx) {
        throw new RuntimeException("Use visitFuncTypeCustom instead");
    }


    @Override
    public Value visitBlock(SysYParser.BlockContext ctx) {
        //System.err.println("Debug: visitBlock start, currentBlock: " +
        //        (builder.getInsertionBlock().isSome() ? builder.getInsertionBlock().unwrap().getName() : "none") +
        //        ", scopeStack size: " + scopeStack.size());

        scopeStack.push(new HashMap<>());
        //System.err.println("Debug: visitBlock scopeStack push, size: " + scopeStack.size());

        for (SysYParser.BlockItemContext item : ctx.blockItem()) {
            Option<BasicBlock> currentBlock = builder.getInsertionBlock();
            if (currentBlock.isNone()) {
                throw new RuntimeException("Error: No insertion block set at line " + item.getStart().getLine());
            }
            if (getTerminator(currentBlock.unwrap()).isSome()) {
                //System.err.println("Debug: visitBlock skipping terminated block: " + currentBlock.unwrap().getName());
                break;
            }
            if (item.decl() != null) {
                //System.err.println("Debug: visitBlock processing decl at line: " + item.getStart().getLine());
                visit(item.decl());
            } else if (item.stmt() != null) {
                //System.err.println("Debug: visitBlock processing stmt at line: " + item.getStart().getLine());
                visit(item.stmt());
            }
        }

        scopeStack.pop();
        //System.err.println("Debug: visitBlock scopeStack pop, size: " + scopeStack.size() +
        //        ", currentBlock: " +
        //        (builder.getInsertionBlock().isSome() ? builder.getInsertionBlock().unwrap().getName() : "none"));

        return null;
    }

    @Override
    public Value visitBlockItem(SysYParser.BlockItemContext ctx) {
        if (ctx.decl() != null) return visit(ctx.decl());
        if (ctx.stmt() != null) return visit(ctx.stmt());
        throw new RuntimeException("Invalid block item at line " + ctx.getStart().getLine());
    }


    @Override
    public Value visitStmt(SysYParser.StmtContext ctx) {
        //System.err.println("Debug: visitStmt start, line: " + ctx.getStart().getLine() +
        //        ", currentBlock: " +
        //        (builder.getInsertionBlock().isSome() ? builder.getInsertionBlock().unwrap().getName() : "none"));

        Option<BasicBlock> currentBlock = builder.getInsertionBlock();
        if (currentBlock.isNone()) {
            throw new RuntimeException("Error: No insertion block set at line " + ctx.getStart().getLine());
        }
        if (getTerminator(currentBlock.unwrap()).isSome()) {
            //System.err.println("Warning: Unreachable statement at line " + ctx.getStart().getLine());
            return null;
        }

        // 处理 return 语句
        if (ctx.RETURN() != null) {
            if (currentFunction == null || currentFunctionType == null) {
                throw new RuntimeException("Error: No function defined for return statement at line " +
                        ctx.getStart().getLine());
            }
            //System.err.println("Debug: visitStmt function: " + currentFunction.getName() +
            //        ", returnType: " + currentFunctionType.getReturnType().getAsString());

            Option<Value> retValue = ctx.exp() != null ? new Some<>(visit(ctx.exp())) : Option.empty();
            Type expectedType = currentFunctionType.getReturnType();

            if (expectedType.isVoidType() && retValue.isSome()) {
                throw new RuntimeException("Error: Void function returning value at line " + ctx.getStart().getLine());
            }
            if (!expectedType.isVoidType() && retValue.isNone()) {
                retValue = new Some<>(context.getInt32Type().getConstant(0, true));
                //System.err.println("Warning: Non-void function missing return value at line " + ctx.getStart().getLine());
            }
            if (retValue.isSome()) {
                Value value = retValue.unwrap();
                //System.err.println("Debug: visitStmt return value class: " + value.getClass().getName() +
                //        ", type: " + value.getType().getAsString());
                if (!value.getType().getAsString().equals(expectedType.getAsString())) {
                    throw new RuntimeException("Error: Return value type " + value.getType().getAsString() +
                            " does not match expected type " + expectedType.getAsString() +
                            " at line " + ctx.getStart().getLine());
                }
            }
            builder.buildReturn(retValue);
            //System.err.println("Debug: visitStmt built return instruction");
            return null;
        }

        // 处理 if 和 if-else 语句
        if (ctx.IF() != null) {
            //System.err.println("Debug: visitStmt if start, line: " + ctx.getStart().getLine());
            Value cond = visit(ctx.cond());
            //System.err.println("Debug: visitStmt if cond class: " + cond.getClass().getName() +
            //        ", type: " + cond.getType().getAsString() +
            //        ", typeKind: " + cond.getType().getTypeKind());

            // 修改类型检查
            if (cond.getType().getTypeKind() != TypeKind.Integer || !cond.getType().getAsString().equals("i1")) {
                //System.err.println("Debug: Type check failed - cond type: " + cond.getType().getClass().getName() +
                //        ", kind: " + cond.getType().getTypeKind() +
                //        ", string: " + cond.getType().getAsString());
                throw new RuntimeException("Error: Condition must evaluate to i1 at line " + ctx.getStart().getLine());
            }

            BasicBlock thenBlock = context.newBasicBlock("if_true");
            BasicBlock elseBlock = /*ctx.ELSE() != null ? */context.newBasicBlock("if_false")/* : null*/;
            BasicBlock mergeBlock = context.newBasicBlock("if_next");

            builder.buildConditionalBranch(cond, thenBlock, elseBlock);
            currentFunction.addBasicBlock(thenBlock);
            builder.positionAfter(thenBlock);
            if (!ctx.stmt().isEmpty()) {
                visit(ctx.stmt(0));
            }
            if (getTerminator(builder.getInsertionBlock().unwrap()).isNone()) {
                builder.buildBranch(mergeBlock);
            }

            currentFunction.addBasicBlock(elseBlock);
            builder.positionAfter(elseBlock);
            if (ctx.ELSE() != null && ctx.stmt().size() > 1) {
                visit(ctx.stmt(1));
            }
            if (getTerminator(builder.getInsertionBlock().unwrap()).isNone()) {
                builder.buildBranch(mergeBlock);
            }

            currentFunction.addBasicBlock(mergeBlock);
            builder.positionAfter(mergeBlock);
            return null;
        }

        // 处理 while 循环
        if (ctx.WHILE() != null) {
            BasicBlock headerBlock = context.newBasicBlock("whileCond");
            BasicBlock bodyBlock = context.newBasicBlock("whileBody");
            BasicBlock exitBlock = context.newBasicBlock("whileNext");

            builder.buildBranch(headerBlock);
            currentFunction.addBasicBlock(headerBlock);
            builder.positionAfter(headerBlock);
            Value cond = visit(ctx.cond());
            //System.err.println("Debug: visitStmt while cond class: " + cond.getClass().getName() +
            //        ", type: " + cond.getType().getAsString() +
            //        ", typeKind: " + cond.getType().getTypeKind());

            // 修改类型检查
            if (cond.getType().getTypeKind() != TypeKind.Integer || !cond.getType().getAsString().equals("i1")) {
                //System.err.println("Debug: Type check failed - cond type: " + cond.getType().getClass().getName() +
                //        ", kind: " + cond.getType().getTypeKind() +
                //        ", string: " + cond.getType().getAsString());
                throw new RuntimeException("Error: Condition must evaluate to i1 at line " + ctx.getStart().getLine());
            }

            builder.buildConditionalBranch(cond, bodyBlock, exitBlock);

            currentFunction.addBasicBlock(bodyBlock);
            builder.positionAfter(bodyBlock);
            loopStack.push(new LoopContext(headerBlock, exitBlock));
            if (!ctx.stmt().isEmpty()) {
                visit(ctx.stmt(0)); // 访问 while 语句
            }
            loopStack.pop();
            if (getTerminator(builder.getInsertionBlock().unwrap()).isNone()) {
                builder.buildBranch(headerBlock);
            }

            currentFunction.addBasicBlock(exitBlock);
            builder.positionAfter(exitBlock);
            return null;
        }

        // 处理 break 语句
        if (ctx.BREAK() != null) {
            if (loopStack.isEmpty()) {
                throw new RuntimeException("Error: break statement outside loop at line " + ctx.getStart().getLine());
            }
            builder.buildBranch(loopStack.peek().exitBlock);
            //BasicBlock afterBreak = context.newBasicBlock("if_false");
            //currentFunction.addBasicBlock(afterBreak);
            //builder.positionAfter(afterBreak);
            return null;
        }

        // 处理 continue 语句
        if (ctx.CONTINUE() != null) {
            if (loopStack.isEmpty()) {
                throw new RuntimeException("Error: continue statement outside loop at line " + ctx.getStart().getLine());
            }
            builder.buildBranch(loopStack.peek().headerBlock);
            /*BasicBlock afterContinue = context.newBasicBlock("after_continue");
            currentFunction.addBasicBlock(afterContinue);
            builder.positionAfter(afterContinue);*/
            return null;
        }

        // 处理赋值语句（lVal = exp;）
        if (ctx.lVal() != null && ctx.ASSIGN() != null) {
            Value lValPtr = visit(ctx.lVal());
            Value rVal = visit(ctx.exp());
            builder.buildStore(lValPtr, rVal);
            return null;
        }

        // 处理表达式语句（exp; 或空语句 ;）
        if (ctx.exp() != null || ctx.SEMICOLON() != null) {
            if (ctx.exp() != null) {
                visit(ctx.exp());
            }
            return null;
        }

        // 处理块语句
        if (ctx.block() != null) {
            visit(ctx.block());
            return null;
        }

        //System.err.println("Debug: visitStmt end, line: " + ctx.getStart().getLine());
        return null;
    }



    @Override
    public Value visitConstExp(SysYParser.ConstExpContext ctx) {
        boolean originalIsConstantEvaluation = isConstantEvaluation;
        isConstantEvaluation = true;
        Value result = visit(ctx.exp());
        isConstantEvaluation = originalIsConstantEvaluation;

        // 调试：输出 result 的详细信息
        //System.err.println("Debug: visitConstExp result class: " + result.getClass().getName() +
        //        ", type: " + result.getType().getClass().getName() +
        //        ", typeKind: " + result.getType().getTypeKind() +
        //        ", typeString: " + result.getType().getAsString() +
        //        ", value: " + (result instanceof ConstantInt ? ((ConstantInt) result).getSignExtendedValue() : "N/A") +
        //        ", line: " + ctx.getStart().getLine());

        if (!(result instanceof ConstantInt)) {
            throw new RuntimeException("Error: constExp 求值为非整数常量类型: " +
                    result.getClass().getSimpleName() + " at line " + ctx.getStart().getLine());
        }

        ConstantInt intResult = (ConstantInt) result;
        Type resultType = intResult.getType();

        // 调试：输出 resultType 的详细信息
        //System.err.println("Debug: visitConstExp resultType class: " + resultType.getClass().getName() +
        //        ", typeKind: " + resultType.getTypeKind() +
        //        ", typeString: " + resultType.getAsString());

        if (resultType.getTypeKind() != TypeKind.Integer) {
            throw new RuntimeException("Error: constExp 求值为非整数类型: " +
                    resultType.getAsString() + " (typeKind: " + resultType.getTypeKind() +
                    ") at line " + ctx.getStart().getLine());
        }

        String typeString = resultType.getAsString();
        if (typeString.equals("i32")) {
            return result;
        } else if (typeString.equals("i1")) {
            long boolValue = intResult.getSignExtendedValue();
            Type i32Type = context.getInt32Type();
            // 调试：输出 i32Type 信息
            //System.err.println("Debug: visitConstExp i32Type class: " + i32Type.getClass().getName() +
            //        ", typeKind: " + i32Type.getTypeKind() +
            //        ", typeString: " + i32Type.getAsString());
            if (i32Type.getTypeKind() != TypeKind.Integer || !i32Type.getAsString().equals("i32")) {
                throw new RuntimeException("Error: context.getInt32Type() 返回无效类型: " +
                        i32Type.getAsString() + " at line " + ctx.getStart().getLine());
            }
            return ((IntegerType) i32Type).getConstant(boolValue, true);
        } else {
            throw new RuntimeException("Error: constExp 求值为意外的整数类型: " +
                    typeString + " at line " + ctx.getStart().getLine());
        }
    }

    @Override
    public Value visitPrimaryExp(SysYParser.PrimaryExpContext ctx) {
        if (ctx.exp() != null) {
            return visit(ctx.exp());
        } else if (ctx.lVal() != null) {
            if (isConstantEvaluation) {
                throw new RuntimeException("Error: lVal in constant expression at line " + ctx.getStart().getLine());
            }
            Value ptr = visit(ctx.lVal());
            return builder.buildLoad(ptr, new Some<>("load_" + ctx.lVal().IDENT().getText()));
        } else if (ctx.number() != null) {
            return visit(ctx.number());
        }
        throw new RuntimeException("Error: Invalid primary expression at line " + ctx.getStart().getLine());
    }

    @Override
    public Value visitExp(SysYParser.ExpContext ctx) {
        if (isConstantEvaluation) {
            // --- 常量表达式模式 (Part 1) ---
            if (ctx.L_PAREN() != null && ctx.exp().size() == 1 && ctx.R_PAREN() != null) {
                return visit(ctx.exp(0));
            } else if (ctx.lVal() != null) {
                throw new RuntimeException("Error: lVal is not allowed in constant expression at line " + ctx.getStart().getLine());
            } else if (ctx.number() != null) {
                return visit(ctx.number());
            } else if (ctx.IDENT() != null && ctx.L_PAREN() != null) {
                throw new RuntimeException("Error: Function call is not allowed in constant expression at line " + ctx.getStart().getLine());
            } else if (ctx.unaryOp() != null && ctx.exp().size() == 1) {
                Value operandVal = visit(ctx.exp(0));
                if (!(operandVal instanceof ConstantInt)) {
                    throw new RuntimeException("Error: Unary operation operand is not an integer constant at line " + ctx.getStart().getLine());
                }
                ConstantInt operand = (ConstantInt) operandVal;
                Type operandType = operand.getType();
                String opText = ctx.unaryOp().getText();

                if (opText.equals("+")) {
                    return operand;
                } else if (opText.equals("-")) {
                    if (!(operandType instanceof IntegerType) || ((IntegerType) operandType).getTypeWidth() != 32) {
                        throw new RuntimeException("Error: Unary minus applied to non-i32 constant integer type at line " + ctx.getStart().getLine());
                    }
                    long operandLong = operand.getSignExtendedValue();
                    long result = -operandLong;
                    return context.getInt32Type().getConstant(result, true);
                } else if (opText.equals("!")) {
                    long operandLong = operand.getSignExtendedValue();
                    long result = (operandLong == 0) ? 1 : 0;
                    return context.getInt32Type().getConstant(result, true);
                }
                throw new RuntimeException("Error: Unknown unary operator in constant expression: " + opText + " at line " + ctx.getStart().getLine());
            } else if (ctx.exp().size() == 2 && (ctx.MUL() != null || ctx.DIV() != null || ctx.MOD() != null || ctx.PLUS() != null || ctx.MINUS() != null)) {
                Value lhsVal = visit(ctx.exp(0));
                Value rhsVal = visit(ctx.exp(1));
                if (!(lhsVal instanceof ConstantInt) || !(rhsVal instanceof ConstantInt)) {
                    throw new RuntimeException("Error: Arithmetic binary operation operands are not integer constants at line " + ctx.getStart().getLine());
                }
                ConstantInt lhs = (ConstantInt) lhsVal;
                ConstantInt rhs = (ConstantInt) rhsVal;
                Type lhsType = lhs.getType();
                Type rhsType = rhs.getType();
                if (!(lhsType instanceof IntegerType) || ((IntegerType) lhsType).getTypeWidth() != 32 ||
                        !(rhsType instanceof IntegerType) || ((IntegerType) rhsType).getTypeWidth() != 32) {
                    throw new RuntimeException("Error: Arithmetic binary operation applied to non-i32 constant integer types at line " + ctx.getStart().getLine());
                }
                long lhsLong = lhs.getSignExtendedValue();
                long rhsLong = rhs.getSignExtendedValue();
                long result;

                if (ctx.MUL() != null) {
                    result = lhsLong * rhsLong;
                } else if (ctx.DIV() != null) {
                    if (rhsLong == 0) {
                        throw new RuntimeException("Error: Division by zero in constant expression at line " + ctx.getStart().getLine());
                    }
                    result = lhsLong / rhsLong;
                } else if (ctx.MOD() != null) {
                    if (rhsLong == 0) {
                        throw new RuntimeException("Error: Modulo by zero in constant expression at line " + ctx.getStart().getLine());
                    }
                    result = lhsLong % rhsLong;
                } else if (ctx.PLUS() != null) {
                    result = lhsLong + rhsLong;
                } else {
                    result = lhsLong - rhsLong;
                }
                return context.getInt32Type().getConstant(result, true);
            }
            throw new RuntimeException("Error: Unexpected expression structure in constant evaluation mode at line " + ctx.getStart().getLine());
        } else {
            // --- 运行时表达式模式 ---
            if (ctx.L_PAREN() != null && ctx.exp().size() == 1 && ctx.R_PAREN() != null) {
                return visit(ctx.exp(0));
            } else if (ctx.lVal() != null) {
                Value ptr = visit(ctx.lVal());
                if (!(ptr instanceof AllocaInstruction || ptr instanceof GlobalVariable)) {
                    throw new RuntimeException("Error: lVal does not resolve to a valid pointer at line " + ctx.getStart().getLine());
                }
                if (!ptr.getType().isPointerType()) {
                    throw new RuntimeException("Error: lVal type is not a pointer at line " + ctx.getStart().getLine());
                }
                return builder.buildLoad(ptr, new Some<>("load_" + ctx.lVal().IDENT().getText()));
            } else if (ctx.number() != null) {
                return visit(ctx.number());
            } else if (ctx.IDENT() != null && ctx.L_PAREN() != null) {
                String funcName = ctx.IDENT().getText();
                Option<Function> funcOpt = module.getFunction(funcName);
                if (funcOpt.isNone()) {
                    throw new RuntimeException("Error: Undefined function '" + funcName + "' at line " + ctx.getStart().getLine());
                }
                Function func = funcOpt.unwrap();
                List<Value> args = ctx.funcRParams() != null ?
                        ctx.funcRParams().param().stream().map(param -> visit(param.exp())).collect(Collectors.toList()) : List.of();
                if (args.size() != func.getParameterCount()) {
                    throw new RuntimeException("Error: Argument count mismatch for function '" + funcName + "' at line " + ctx.getStart().getLine());
                }
                return builder.buildCall(func, args.toArray(new Value[0]), new Some<>(funcName + "_call"));
            } else if (ctx.unaryOp() != null && ctx.exp().size() == 1) {
                Value operand = visit(ctx.exp(0));
                String opText = ctx.unaryOp().getText();
                if (opText.equals("+")) {
                    return operand;
                } else if (opText.equals("-")) {
                    ConstantInt zero = context.getInt32Type().getConstant(0, true);
                    return builder.buildIntSub(zero, operand, WrapSemantics.NoSigned, new Some<>("neg"));
                } else if (opText.equals("!")) {
                    ConstantInt zero = context.getInt32Type().getConstant(0, true);
                    Value i1Result = builder.buildIntCompare(IntPredicate.Equal, operand, zero, new Some<>("not"));
                    return builder.buildZeroExt(i1Result, context.getInt32Type(), new Some<>("zext_not"));
                }
                throw new RuntimeException("Error: Unknown unary operator: " + opText + " at line " + ctx.getStart().getLine());
            } else if (ctx.exp().size() == 2 && (ctx.MUL() != null || ctx.DIV() != null || ctx.MOD() != null || ctx.PLUS() != null || ctx.MINUS() != null)) {
                Value lhs = visit(ctx.exp(0));
                Value rhs = visit(ctx.exp(1));
                if (ctx.MUL() != null) {
                    return builder.buildIntMul(lhs, rhs, WrapSemantics.NoSigned, new Some<>("mul"));
                } else if (ctx.DIV() != null) {
                    return builder.buildSignedDiv(lhs, rhs, true, new Some<>("div"));
                } else if (ctx.MOD() != null) {
                    return builder.buildSignedRem(lhs, rhs, new Some<>("rem"));
                } else if (ctx.PLUS() != null) {
                    return builder.buildIntAdd(lhs, rhs, WrapSemantics.NoSigned, new Some<>("add"));
                } else {
                    return builder.buildIntSub(lhs, rhs, WrapSemantics.NoSigned, new Some<>("sub"));
                }
            }
            throw new RuntimeException("Error: Unexpected expression structure in runtime mode at line " + ctx.getStart().getLine());
        }
    }

    @Override
    public Value visitCond(SysYParser.CondContext ctx) {
        //System.err.println("Debug: visitCond start, line: " + ctx.getStart().getLine());

        if (isConstantEvaluation) {
            // --- 常量模式 ---
            if (ctx.exp() != null) {
                Value expVal = visit(ctx.exp());
                if (!(expVal instanceof ConstantInt)) {
                    throw new RuntimeException("Error: Condition expression is not a constant integer at line " + ctx.getStart().getLine());
                }
                ConstantInt exp = (ConstantInt) expVal;
                if (!(exp.getType() instanceof IntegerType) || ((IntegerType) exp.getType()).getTypeWidth() != 32) {
                    throw new RuntimeException("Error: Condition expression is not an i32 constant at line " + ctx.getStart().getLine());
                }
                long value = exp.getSignExtendedValue();
                return context.getInt32Type().getConstant(value != 0 ? 1 : 0, true);
            } else if (ctx.cond().size() == 2) {
                // 比较表达式：cond (LT | GT | LE | GE | EQ | NEQ) cond
                if (ctx.LT() != null || ctx.GT() != null || ctx.LE() != null || ctx.GE() != null || ctx.EQ() != null || ctx.NEQ() != null) {
                    SysYParser.CondContext leftCond = ctx.cond(0);
                    SysYParser.CondContext rightCond = ctx.cond(1);
                    if (leftCond.exp() == null || rightCond.exp() == null) {
                        throw new RuntimeException("Error: Comparison operands must be expressions at line " + ctx.getStart().getLine());
                    }
                    Value lhsVal = visit(leftCond.exp());
                    Value rhsVal = visit(rightCond.exp());
                    if (!(lhsVal instanceof ConstantInt) || !(rhsVal instanceof ConstantInt)) {
                        throw new RuntimeException("Error: Condition operands are not constant integers at line " + ctx.getStart().getLine());
                    }
                    ConstantInt lhs = (ConstantInt) lhsVal;
                    ConstantInt rhs = (ConstantInt) rhsVal;
                    if (!(lhs.getType() instanceof IntegerType) || ((IntegerType) lhs.getType()).getTypeWidth() != 32 ||
                            !(rhs.getType() instanceof IntegerType) || ((IntegerType) rhs.getType()).getTypeWidth() != 32) {
                        throw new RuntimeException("Error: Condition operands are not i32 constants at line " + ctx.getStart().getLine());
                    }
                    long lhsLong = lhs.getSignExtendedValue();
                    long rhsLong = rhs.getSignExtendedValue();
                    long result;

                    if (ctx.LT() != null) {
                        result = lhsLong < rhsLong ? 1 : 0;
                    } else if (ctx.GT() != null) {
                        result = lhsLong > rhsLong ? 1 : 0;
                    } else if (ctx.LE() != null) {
                        result = lhsLong <= rhsLong ? 1 : 0;
                    } else if (ctx.GE() != null) {
                        result = lhsLong >= rhsLong ? 1 : 0;
                    } else if (ctx.EQ() != null) {
                        result = lhsLong == rhsLong ? 1 : 0;
                    } else if (ctx.NEQ() != null) {
                        result = lhsLong != rhsLong ? 1 : 0;
                    } else {
                        throw new RuntimeException("Error: Unknown comparison operator at line " + ctx.getStart().getLine());
                    }
                    return context.getInt32Type().getConstant(result, true);
                }
                // 逻辑运算：AND, OR
                Value lhsVal = visit(ctx.cond(0));
                Value rhsVal = visit(ctx.cond(1));
                if (!(lhsVal instanceof ConstantInt) || !(rhsVal instanceof ConstantInt)) {
                    throw new RuntimeException("Error: Condition operands are not constant integers at line " + ctx.getStart().getLine());
                }
                ConstantInt lhs = (ConstantInt) lhsVal;
                ConstantInt rhs = (ConstantInt) rhsVal;
                if (!(lhs.getType() instanceof IntegerType) || ((IntegerType) lhs.getType()).getTypeWidth() != 32 ||
                        !(rhs.getType() instanceof IntegerType) || ((IntegerType) rhs.getType()).getTypeWidth() != 32) {
                    throw new RuntimeException("Error: Condition operands are not i32 constants at line " + ctx.getStart().getLine());
                }
                long lhsLong = lhs.getSignExtendedValue();
                long rhsLong = rhs.getSignExtendedValue();
                long result;

                if (ctx.AND() != null) {
                    result = (lhsLong != 0 && rhsLong != 0) ? 1 : 0;
                } else if (ctx.OR() != null) {
                    result = (lhsLong != 0 || rhsLong != 0) ? 1 : 0;
                } else {
                    throw new RuntimeException("Error: Unknown logical operator at line " + ctx.getStart().getLine());
                }
                return context.getInt32Type().getConstant(result, true);
            }
            throw new RuntimeException("Error: Unexpected condition structure in constant evaluation mode at line " + ctx.getStart().getLine());
        } else {
            // --- 运行时模式 ---
            if (ctx.LT() != null || ctx.GT() != null || ctx.LE() != null || ctx.GE() != null || ctx.EQ() != null || ctx.NEQ() != null) {
                // 比较表达式：cond (LT | GT | LE | GE | EQ | NEQ) cond
                SysYParser.CondContext leftCond = ctx.cond(0);
                SysYParser.CondContext rightCond = ctx.cond(1);
                if (leftCond.exp() == null || rightCond.exp() == null) {
                    throw new RuntimeException("Error: Comparison operands must be expressions at line " + ctx.getStart().getLine());
                }
                Value lhs = visit(leftCond.exp());
                Value rhs = visit(rightCond.exp());
                //System.err.println("Debug: visitCond lhs class: " + lhs.getClass().getName() +
                //        ", type: " + lhs.getType().getAsString() +
                //        ", typeKind: " + lhs.getType().getTypeKind() +
                //        ", rhs class: " + rhs.getClass().getName() +
                //        ", type: " + rhs.getType().getAsString() +
                //        ", typeKind: " + rhs.getType().getTypeKind());

                // 修改类型检查
                if (lhs.getType().getTypeKind() != TypeKind.Integer || !lhs.getType().getAsString().equals("i32") ||
                        rhs.getType().getTypeKind() != TypeKind.Integer || !rhs.getType().getAsString().equals("i32")) {
                    //System.err.println("Debug: Type check failed - lhs type: " + lhs.getType().getClass().getName() +
                    //        ", kind: " + lhs.getType().getTypeKind() +
                    //        ", string: " + lhs.getType().getAsString() +
                    //        ", rhs type: " + rhs.getType().getClass().getName() +
                    //        ", kind: " + rhs.getType().getTypeKind() +
                    //        ", string: " + rhs.getType().getAsString());
                    throw new RuntimeException("Error: Comparison operands must be i32 at line " + ctx.getStart().getLine());
                }

                IntPredicate pred;//TODO
                String opName;
                if (ctx.LT() != null) {
                    pred = IntPredicate.SignedLessThan;
                    opName = "lt";
                } else if (ctx.GT() != null) {
                    pred = IntPredicate.SignedGreaterThan;
                    opName = "gt";
                } else if (ctx.LE() != null) {
                    pred = IntPredicate.SignedLessEqual;
                    opName = "le";
                } else if (ctx.GE() != null) {
                    pred = IntPredicate.SignedGreaterEqual;
                    opName = "ge";
                } else if (ctx.EQ() != null) {
                    pred = IntPredicate.Equal;
                    opName = "eq";
                } else if (ctx.NEQ() != null) {
                    pred = IntPredicate.NotEqual;
                    opName = "neq";
                } else {
                    throw new RuntimeException("Error: Unknown comparison operator at line " + ctx.getStart().getLine());
                }

                Value cmp = builder.buildIntCompare(pred, lhs, rhs, new Some<>(opName));
                //System.err.println("Debug: visitCond cmp class: " + cmp.getClass().getName() +
                //        ", type: " + cmp.getType().getAsString());
                Value zext = builder.buildZeroExt(cmp, context.getInt32Type(), new Some<>(opName + "_zext"));
                ConstantInt zero = context.getInt32Type().getConstant(0, true);
                return builder.buildIntCompare(IntPredicate.NotEqual, zext, zero, new Some<>(opName + "_cond"));
            } else if (ctx.cond().size() == 2 && (ctx.AND() != null || ctx.OR() != null)) {
                if (ctx.AND() != null) {
                    BasicBlock entryBlock = builder.getInsertionBlock().unwrap();
                    Function func = entryBlock.getFunction().unwrap();
                    BasicBlock rhsBlock = new BasicBlock(LLVM.LLVMAppendBasicBlock(func.getRef(), "and_rhs"));
                    func.addBasicBlock(rhsBlock);
                    BasicBlock endBlock = new BasicBlock(LLVM.LLVMAppendBasicBlock(func.getRef(), "and_end"));
                    func.addBasicBlock(endBlock);

                    Value cond1 = visit(ctx.cond(0));
                    if (!cond1.getType().equals(context.getInt1Type())) {
                        throw new RuntimeException("Error: AND operand must be i1 at line " + ctx.getStart().getLine());
                    }
                    builder.buildConditionalBranch(cond1, rhsBlock, endBlock);

                    builder.positionAfter(rhsBlock);
                    Value cond2 = visit(ctx.cond(1));
                    if (!cond2.getType().equals(context.getInt1Type())) {
                        throw new RuntimeException("Error: AND operand must be i1 at line " + ctx.getStart().getLine());
                    }
                    builder.buildBranch(endBlock);

                    builder.positionAfter(endBlock);
                    PhiInstruction phi = builder.buildPhi(context.getInt1Type(), new Some<>("and_result"));
                    phi.addIncoming(
                            new Pair<>(entryBlock, context.getInt1Type().getConstant(0, true)),
                            new Pair<>(rhsBlock, cond2)
                    );
                    return phi;
                } else if (ctx.OR() != null) {
                    BasicBlock entryBlock = builder.getInsertionBlock().unwrap();
                    Function func = entryBlock.getFunction().unwrap();
                    BasicBlock rhsBlock = new BasicBlock(LLVM.LLVMAppendBasicBlock(func.getRef(), "or_rhs"));
                    func.addBasicBlock(rhsBlock);
                    BasicBlock endBlock = new BasicBlock(LLVM.LLVMAppendBasicBlock(func.getRef(), "or_end"));
                    func.addBasicBlock(endBlock);

                    Value cond1 = visit(ctx.cond(0));
                    if (!cond1.getType().equals(context.getInt1Type())) {
                        throw new RuntimeException("Error: OR operand must be i1 at line " + ctx.getStart().getLine());
                    }
                    builder.buildConditionalBranch(cond1, endBlock, rhsBlock);

                    builder.positionAfter(rhsBlock);
                    Value cond2 = visit(ctx.cond(1));
                    if (!cond2.getType().equals(context.getInt1Type())) {
                        throw new RuntimeException("Error: OR operand must be i1 at line " + ctx.getStart().getLine());
                    }
                    builder.buildBranch(endBlock);

                    builder.positionAfter(endBlock);
                    PhiInstruction phi = builder.buildPhi(context.getInt1Type(), new Some<>("or_result"));
                    phi.addIncoming(
                            new Pair<>(entryBlock, context.getInt1Type().getConstant(1, true)),
                            new Pair<>(rhsBlock, cond2)
                    );
                    return phi;
                }
            } else if (ctx.exp() != null) {
                Value expVal = visit(ctx.exp());
                if (expVal.getType().getTypeKind() != TypeKind.Integer || !expVal.getType().getAsString().equals("i32")) {
                    //System.err.println("Debug: Type check failed - expVal type: " + expVal.getType().getClass().getName() +
                    //        ", kind: " + expVal.getType().getTypeKind() +
                    //        ", string: " + expVal.getType().getAsString());
                    throw new RuntimeException("Error: Condition expression must be i32 at line " + ctx.getStart().getLine());
                }
                ConstantInt zero = context.getInt32Type().getConstant(0, true);
                Value cmp = builder.buildIntCompare(IntPredicate.NotEqual, expVal, zero, new Some<>("cond"));
                //System.err.println("Debug: visitCond cmp class: " + cmp.getClass().getName() +
                //        ", type: " + cmp.getType().getAsString());
                return cmp;
            }
            throw new RuntimeException("Error: Unexpected condition structure in runtime mode at line " + ctx.getStart().getLine());
        }
    }

    @Override
    public Value visitLVal(SysYParser.LValContext ctx) {
        String varName = ctx.IDENT().getText();
        for (Map<String, Value> scope : scopeStack) {
            if (scope.containsKey(varName)) {
                return scope.get(varName);
            }
        }
        throw new RuntimeException("Error: Undefined variable " + varName + " at line " + ctx.getStart().getLine());
    }



    @Override
    public Value visitNumber(SysYParser.NumberContext ctx) {
        String text = ctx.INTEGR_CONST().getText();
        long intValue;
        try {
            if (text.startsWith("0x") || text.startsWith("0X")) {
                intValue = Long.parseLong(text.substring(2), 16);
            } else if (text.startsWith("0") && text.length() > 1) {
                intValue = Long.parseLong(text.substring(1), 8);
            } else {
                intValue = Long.parseLong(text);
            }
        } catch (NumberFormatException e) {
            throw new RuntimeException("无效的整数常量格式: " + text + " 在行 " +
                    ctx.getStart().getLine(), e);
        }

        Type i32Type = context.getInt32Type();

        // 调试：输出 i32Type 信息
        //System.err.println("Debug: visitNumber i32Type class: " + i32Type.getClass().getName() +
        //        ", typeKind: " + i32Type.getTypeKind() +
        //        ", typeString: " + i32Type.getAsString() +
        //        ", line: " + ctx.getStart().getLine());

        if (i32Type.getTypeKind() != TypeKind.Integer || !i32Type.getAsString().equals("i32")) {
            throw new RuntimeException("Error: context.getInt32Type() 返回无效类型: " +
                    i32Type.getAsString() + " (typeKind: " + i32Type.getTypeKind() +
                    ") 在行 " + ctx.getStart().getLine());
        }

        ConstantInt constant = ((IntegerType) i32Type).getConstant(intValue, true);

        // 调试：输出返回值的详细信息
        //System.err.println("Debug: visitNumber return class: " + constant.getClass().getName() +
        //        ", type: " + constant.getType().getClass().getName() +
        //        ", typeKind: " + constant.getType().getTypeKind() +
        //        ", typeString: " + constant.getType().getAsString() +
        //        ", value: " + constant.getSignExtendedValue());

        return constant;
    }


    @Override
    public Value visitUnaryOp(SysYParser.UnaryOpContext ctx) {
        // This visitor just identifies the operator.
        // The parent (visitExp) handles the operation logic based on `isConstantEvaluation`.
        // This visitor itself doesn't need to return a Value.
        return null; // Or return an enum/object representing the operator
    }

    @Override
    public Value visitFuncRParams(SysYParser.FuncRParamsContext ctx) {
        // Function parameters are not allowed in main function in Part 1.
        // Reaching here is an error.
        throw new RuntimeException("Error: Function real parameters are not handled in Part 1 scope.");
    }

    @Override
    public Value visitFuncFParam(SysYParser.FuncFParamContext ctx) {
        String paramName = ctx.IDENT().getText();
        Type paramType = context.getInt32Type();
        AllocaInstruction alloca = builder.buildAlloca(paramType, new Some<>(paramName));
        scopeStack.peek().put(paramName, alloca);
        return alloca;
    }

    @Override
    public Value visitParam(SysYParser.ParamContext ctx) {
        // Part of function parameters, not handled in Part 1.
        throw new RuntimeException("Error: Function parameter visitor reached unexpectedly in Part 1 scope.");
    }



    // --- Unused Visitor Methods in Part 1 ---
    // These are included in the base visitor but not needed for Part 1.
    // They can be left with default behavior (super.visit...) or explicitly return null/throw error.
    // For clarity in Part 1 scope, let's explicitly handle some top-level ones we expect to skip.

    @Override
    public Value visitDecl(SysYParser.DeclContext ctx) {
        // 调试：输出 decl 处理
        //System.err.println("Debug: visitDecl start, scopeStack size: " + scopeStack.size() +
        //        ", line: " + ctx.getStart().getLine());

        if (ctx.constDecl() != null) {
            visit(ctx.constDecl());
        } else if (ctx.varDecl() != null) {
            visit(ctx.varDecl());
        }

        //System.err.println("Debug: visitDecl end, scopeStack size: " + scopeStack.size());
        return null;
    }

    @Override
    public Value visitVarDef(SysYParser.VarDefContext ctx) {
        boolean isGlobal = scopeStack.size() == 1;
        String varName = ctx.IDENT().getText();
        Type varType = context.getInt32Type();

        // 调试：输出变量定义信息
        //System.err.println("Debug: visitVarDef varName: " + varName + ", isGlobal: " + isGlobal +
        //        ", scopeStack size: " + scopeStack.size() + ", line: " + ctx.getStart().getLine());

        if (isGlobal) {
            Constant initConstant = null;

            if (ctx.initVal() != null) {
                boolean originalIsConstantEvaluation = isConstantEvaluation;
                isConstantEvaluation = true;
                Value initValue = visit(ctx.initVal());

                //System.err.println("Debug: visitVarDef initValue class: " + initValue.getClass().getName() +
                //        ", type: " + initValue.getType().getClass().getName() +
                //        ", typeKind: " + initValue.getType().getTypeKind() +
                //        ", typeString: " + initValue.getType().getAsString() +
                //        ", value: " + (initValue instanceof ConstantInt ? ((ConstantInt) initValue).getSignExtendedValue() : "N/A"));

                isConstantEvaluation = originalIsConstantEvaluation;

                if (!(initValue instanceof Constant)) {
                    throw new RuntimeException("Error: Global variable '" + varName + "' initializer must be a constant at line " + ctx.getStart().getLine());
                }

                if (initValue instanceof ConstantInt && initValue.getType().getTypeKind() == TypeKind.Integer && initValue.getType().getAsString().equals("i32")) {
                    initConstant = (Constant) initValue;
                } else if (initValue instanceof ConstantInt && initValue.getType().getTypeKind() == TypeKind.Integer && initValue.getType().getAsString().equals("i1")) {
                    long boolVal = ((ConstantInt) initValue).getSignExtendedValue();
                    initConstant = context.getInt32Type().getConstant(boolVal, true);
                } else {
                    throw new RuntimeException("Error: Global variable '" + varName + "' initializer resulted in non-i32 constant at line " + ctx.getStart().getLine());
                }
            } else {
                initConstant = context.getInt32Type().getConstant(0, true);
            }

            //System.err.println("Debug: visitVarDef initConstant class: " + initConstant.getClass().getName() +
            //        ", type: " + initConstant.getType().getAsString());

            GlobalVariable global = module.addGlobalVariable(varName, varType, Option.empty()).unwrap();
            //System.err.println("Debug: visitVarDef global created, name: " + global.getName() +
            //        ", type: " + global.getType().getAsString());

            global.setInitializer(initConstant);
            //System.err.println("Debug: visitVarDef global initializer set for " + varName);

            scopeStack.peek().put(varName, global);
            return global;
        } else {
            // 调试：检查 builder 状态
            Option<BasicBlock> currentBlock = builder.getInsertionBlock();
            //System.err.println("Debug: visitVarDef local, currentBlock: " +
            //        (currentBlock.isSome() ? currentBlock.unwrap().getName() : "none"));

            if (currentBlock.isNone()) {
                throw new RuntimeException("Error: No insertion block set for local variable '" + varName + "' at line " + ctx.getStart().getLine());
            }

            AllocaInstruction alloca = builder.buildAlloca(varType, new Some<>(varName));
            //System.err.println("Debug: visitVarDef alloca created, name: " + varName +
            //        ", type: " + varType.getAsString());

            scopeStack.peek().put(varName, alloca);

            if (ctx.initVal() != null) {
                Value initValue = visit(ctx.initVal());
                builder.buildStore(alloca, initValue);
                //System.err.println("Debug: visitVarDef store initValue class: " + initValue.getClass().getName() +
                //        ", type: " + initValue.getType().getAsString());
            }

            return alloca;
        }
    }


    // Similarly, modify visitConstDef to handle global constants
    @Override
    public Value visitConstDef(SysYParser.ConstDefContext ctx) {
        boolean isGlobal = scopeStack.size() == 1;
        String varName = ctx.IDENT().getText();
        Type constType = context.getInt32Type();

        // 调试：输出常量定义的上下文
        //System.err.println("Debug: visitConstDef varName: " + varName + ", isGlobal: " + isGlobal +
        //        ", line: " + ctx.getStart().getLine());

        // 常量初始化值必须是常量表达式
        boolean originalIsConstantEvaluation = isConstantEvaluation;
        isConstantEvaluation = true;
        Value initValue = visit(ctx.constInitVal()); // 调用 visitConstInitVal -> visitConstExp
        isConstantEvaluation = originalIsConstantEvaluation;

        // 调试：输出 initValue 的详细信息
        //System.err.println("Debug: visitConstDef initValue class: " + initValue.getClass().getName() +
        //        ", type: " + initValue.getType().getClass().getName() +
        //        ", typeKind: " + initValue.getType().getTypeKind() +
        //        ", typeString: " + initValue.getType().getAsString() +
        //        ", value: " + (initValue instanceof ConstantInt ? ((ConstantInt) initValue).getSignExtendedValue() : "N/A"));

        // 验证 initValue 是 ConstantInt
        if (!(initValue instanceof ConstantInt)) {
            throw new RuntimeException("Error: Constant '" + varName + "' initializer must be an integer constant, got: " +
                    initValue.getClass().getSimpleName() + " at line " + ctx.getStart().getLine());
        }

        ConstantInt constInt = (ConstantInt) initValue;
        Type valueType = constInt.getType();

        // 调试：输出 valueType 的详细信息
        //System.err.println("Debug: visitConstDef valueType class: " + valueType.getClass().getName() +
        //        ", typeKind: " + valueType.getTypeKind() +
        //        ", typeString: " + valueType.getAsString());

        // 使用 getTypeKind 和 getAsString 验证类型
        if (valueType.getTypeKind() != TypeKind.Integer || !valueType.getAsString().equals("i32")) {
            throw new RuntimeException("Error: Constant '" + varName + "' initializer must be an i32 constant, got: " +
                    valueType.getAsString() + " (typeKind: " + valueType.getTypeKind() +
                    ") at line " + ctx.getStart().getLine());
        }

        Constant initConstant = constInt;

        if (isGlobal) {
            // 全局常量
            GlobalVariable global = module.addGlobalVariable(varName, constType, Option.empty()).unwrap();
            global.setImmutable(true);
            global.setInitializer(initConstant);
            scopeStack.peek().put(varName, global);
            return global;
        } else {
            // 局部常量
            AllocaInstruction alloca = builder.buildAlloca(constType, new Some<>(varName));
            builder.buildStore(alloca, initConstant);
            scopeStack.peek().put(varName, alloca);
            return alloca;
        }
    }


    // visitVarDecl and visitConstDecl should remain simple loops calling visitVarDef/visitConstDef
    @Override
    public Value visitVarDecl(SysYParser.VarDeclContext ctx) {
        // 调试：输出 varDecl 处理
        //System.err.println("Debug: visitVarDecl start, scopeStack size: " + scopeStack.size() +
        //        ", line: " + ctx.getStart().getLine());

        for (SysYParser.VarDefContext varDef : ctx.varDef()) {
            visit(varDef);
        }

        //System.err.println("Debug: visitVarDecl end, scopeStack size: " + scopeStack.size());
        return null;
    }

    @Override
    public Value visitConstDecl(SysYParser.ConstDeclContext ctx) {
        // isGlobal check is now inside visitConstDef
        for (SysYParser.ConstDefContext def : ctx.constDef()) {
            visit(def);
        }
        return null;
    }

    @Override
    public Value visitConstInitVal(SysYParser.ConstInitValContext ctx) {
        Value value = visit(ctx.constExp());

        // 调试：输出 value 的详细信息
        //System.err.println("Debug: visitConstInitVal value class: " + value.getClass().getName() +
        //        ", type: " + value.getType().getClass().getName() +
        //        ", typeKind: " + value.getType().getTypeKind() +
        //        ", typeString: " + value.getType().getAsString() +
        //        ", value: " + (value instanceof ConstantInt ? ((ConstantInt) value).getSignExtendedValue() : "N/A") +
        //        ", line: " + ctx.getStart().getLine());

        return value;
    }
    @Override
    public Value visitInitVal(SysYParser.InitValContext ctx) {
        return visit(ctx.exp());
    }

}