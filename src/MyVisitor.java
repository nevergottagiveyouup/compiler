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
    private Function currentFunction;
    private boolean isConstantEvaluation = false;
    // 循环栈，用于 break 和 continue
    private final Deque<LoopContext> loopStack = new LinkedList<>();

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
        for (SysYParser.DeclContext decl : ctx.decl()) {
            visit(decl); // 处理全局变量
        }
        for (SysYParser.FuncDefContext funcDef : ctx.funcDef()) {
            visit(funcDef); // 处理所有函数
        }
        return null;
    }


    @Override
    public Value visitFuncDef(SysYParser.FuncDefContext ctx) {
        // 提取函数信息
        String funcName = ctx.IDENT().getText();
        Type returnType = visitFuncTypeCustom(ctx.funcType());

        // Part 1 验证（仅对 main 函数应用）
        if (funcName.equals("main")) {
            if (returnType.isVoidType()) {
                throw new RuntimeException("Error: 'main' must return int at line " + ctx.getStart().getLine());
            }
            if (ctx.funcFParams() != null && !ctx.funcFParams().funcFParam().isEmpty()) {
                throw new RuntimeException("Error: 'main' must have no parameters at line " + ctx.getStart().getLine());
            }
            if (ctx.block().blockItem().size() != 1 || ctx.block().blockItem(0).stmt() == null ||
                    ctx.block().blockItem(0).stmt().RETURN() == null || ctx.block().blockItem(0).stmt().exp() == null) {
                throw new RuntimeException("Error: 'main' must contain a single return statement with expression at line " + ctx.getStart().getLine());
            }
        }

        // 处理函数参数
        List<Type> paramTypes = ctx.funcFParams() != null ?
                ctx.funcFParams().funcFParam().stream()
                        .map(param -> context.getInt32Type())
                        .collect(Collectors.toList()) : List.of();
        boolean isVariadic = false;
        FunctionType funcType = context.getFunctionType(returnType, paramTypes.toArray(new Type[0]), isVariadic);

        // 创建函数
        Function func = module.addFunction(funcName, funcType);
        currentFunction = func;

        // 创建 entry 基本块
        BasicBlock entry = context.newBasicBlock("entry");
        func.addBasicBlock(entry);
        builder.positionAfter(entry); // 替换 positionAtEnd 为 positionAfter

        // 初始化作用域
        scopeStack.push(new HashMap<>());

        // 处理参数
        if (ctx.funcFParams() != null) {
            List<SysYParser.FuncFParamContext> params = ctx.funcFParams().funcFParam();
            if (params.size() != func.getParameterCount()) {
                throw new RuntimeException("Error: Parameter count mismatch at line " + ctx.getStart().getLine());
            }
            for (int i = 0; i < params.size(); i++) {
                Value paramAlloc = visitFuncFParam(params.get(i));
                Argument paramValue = func.getParameter(i).unwrap();
                builder.buildStore(paramValue, paramAlloc);
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
                System.err.println("Warning: Missing return in non-void function '" + funcName + "' at line " + ctx.getStart().getLine());
            }
        }

        // 清理
        scopeStack.pop();
        currentFunction = null;

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
        scopeStack.push(new HashMap<>());
        for (SysYParser.BlockItemContext item : ctx.blockItem()) {
            Option<BasicBlock> currentBlock = builder.getInsertionBlock();
            if (currentBlock.isNone() || getTerminator(currentBlock.unwrap()).isSome()) {
                break; // 停止处理已终止的块
            }
            if (item.decl() != null) visit(item.decl());
            else if (item.stmt() != null) visit(item.stmt());
        }
        scopeStack.pop();
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
        Option<BasicBlock> currentBlock = builder.getInsertionBlock();
        if (currentBlock.isNone()) {
            throw new RuntimeException("Error: No insertion block set at line " + ctx.getStart().getLine());
        }
        if (getTerminator(currentBlock.unwrap()).isSome()) {
            System.err.println("Warning: Unreachable statement at line " + ctx.getStart().getLine());
            return null;
        }

        if (ctx.RETURN() != null) {
            Option<Value> retValue = ctx.exp() != null ? new Some<>(visit(ctx.exp())) : Option.empty();
            Type funcType = currentFunction.getType(); // 使用 getType
            FunctionType functionType;
            if (funcType instanceof FunctionType) {
                functionType = (FunctionType) funcType;
            } else if (funcType instanceof PointerType) {
                functionType = (FunctionType) ((PointerType) funcType).getElementType();
            } else {
                throw new RuntimeException("Error: Invalid function type at line " + ctx.getStart().getLine());
            }
            Type expectedType = functionType.getReturnType(); // 获取返回类型
            if (expectedType.isVoidType() && retValue.isSome()) {
                throw new RuntimeException("Error: Void function returning value at line " + ctx.getStart().getLine());
            }
            if (!expectedType.isVoidType() && retValue.isNone()) {
                retValue = new Some<>(context.getInt32Type().getConstant(0, true));
                System.err.println("Warning: Non-void function missing return value at line " + ctx.getStart().getLine());
            }
            builder.buildReturn(retValue);

            BasicBlock afterReturn = context.newBasicBlock("after_return");
            currentFunction.addBasicBlock(afterReturn);
            builder.positionAfter(afterReturn);
            return null;
        }

        // 其他语句处理（如果有）
        return null;
    }

    @Override
    public Value visitConstExp(SysYParser.ConstExpContext ctx) {
        // This rule simply wraps an 'exp' that *must* be a constant expression.
        // We delegate to visitExp, ensuring the `isConstantEvaluation` flag is set.

        // Set the flag before visiting the child expression
        isConstantEvaluation = true;
        Value result = visit(ctx.exp()); // Visit the inner expression
        // Reset the flag after visiting
        isConstantEvaluation = false;

        // The result MUST be a Constant value
        if (!(result instanceof Constant)) {
            // This indicates an issue in the constant evaluation logic within visitExp/visitPrimaryExp/visitNumber/visitCond
            throw new RuntimeException("Error: constExp child expression did not evaluate to a constant.");
        }

        // Ensure the final result of a constExp is a ConstantInt (as SysY constExp returns int)
        // Although comparison/logical ops within it might produce i1 *during* evaluation,
        // the *final* result of a constExp context is typically an integer.
        // The SysY spec implies constExp result is an int. Let's convert i1 result back to i32 (0 or 1).
        if (result instanceof ConstantInt) {
            ConstantInt intResult = (ConstantInt) result;
            Type resultType = intResult.getType(); // Get the Type

            // Corrected: Get type width from the Type object
            if (!(resultType instanceof IntegerType)) {
                // Should not happen if inner logic is correct, but defensive.
                throw new RuntimeException("Error: constExp evaluated to constant with non-integer type: " + resultType.getAsString());
            }

            if (((IntegerType) resultType).getTypeWidth() == 1) {
                // Convert i1 constant to i32 constant (0 becomes 0, 1 becomes 1)
                long boolValue = intResult.getSignExtendedValue(); // Get 0 or 1
                Type i32Type = context.getInt32Type(); // Get i32 Type
                // Corrected: Use i32Type instance to call getConstant
                return ((IntegerType) i32Type).getConstant(boolValue, true); // Return i32 ConstantInt
            }
            // If it's already an integer constant (i32 or other width), return it directly.
            // Assuming SysY constExp result is always i32. Check width.
            // Corrected: Get type width from the Type object
            if (((IntegerType) resultType).getTypeWidth() != 32) {
                throw new RuntimeException("Error: constExp evaluated to integer constant of non-i32 width (" + ((IntegerType) resultType).getTypeWidth() + ").");
            }
            return result; // Return the i32 ConstantInt
        }

        // If it's a constant but not ConstantInt (e.g., ConstantArray), it's an error for constExp
        throw new RuntimeException("Error: constExp evaluated to a non-integer constant type: " + result.getClass().getSimpleName());
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
                // 括号表达式：(exp)
                return visit(ctx.exp(0));
            } else if (ctx.lVal() != null) {
                // 左值在常量表达式中非法
                throw new RuntimeException("Error: lVal is not allowed in constant expression at line " + ctx.getStart().getLine());
            } else if (ctx.number() != null) {
                // 数字字面量
                return visit(ctx.number());
            } else if (ctx.IDENT() != null && ctx.L_PAREN() != null) {
                // 函数调用在常量表达式中非法
                throw new RuntimeException("Error: Function call is not allowed in constant expression at line " + ctx.getStart().getLine());
            } else if (ctx.unaryOp() != null && ctx.exp().size() == 1) {
                // 一元运算：+exp, -exp, !exp
                Value operandVal = visit(ctx.exp(0));
                if (!(operandVal instanceof ConstantInt)) {
                    throw new RuntimeException("Error: Unary operation operand is not an integer constant at line " + ctx.getStart().getLine());
                }
                ConstantInt operand = (ConstantInt) operandVal;
                Type operandType = operand.getType();
                String opText = ctx.unaryOp().getText();

                if (opText.equals("+")) {
                    // 一元加：返回原值
                    return operand;
                } else if (opText.equals("-")) {
                    // 一元负：验证 i32，计算 -value
                    if (!(operandType instanceof IntegerType) || ((IntegerType) operandType).getTypeWidth() != 32) {
                        throw new RuntimeException("Error: Unary minus applied to non-i32 constant integer type (width " + ((IntegerType) operandType).getTypeWidth() + ") at line " + ctx.getStart().getLine());
                    }
                    long operandLong = operand.getSignExtendedValue();
                    long result = -operandLong;
                    return context.getInt32Type().getConstant(result, true);
                } else if (opText.equals("!")) {
                    // 逻辑非：非零变 0，零变 1，返回 i32（exp 上下文）
                    long operandLong = operand.getSignExtendedValue();
                    long result = (operandLong == 0) ? 1 : 0;
                    return context.getInt32Type().getConstant(result, true);
                }
                throw new RuntimeException("Error: Unknown unary operator in constant expression: " + opText + " at line " + ctx.getStart().getLine());
            } else if (ctx.exp().size() == 2 && (ctx.MUL() != null || ctx.DIV() != null || ctx.MOD() != null || ctx.PLUS() != null || ctx.MINUS() != null)) {
                // 二元算术运算：exp * exp, exp / exp, exp % exp, exp + exp, exp - exp
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
            // --- 运行时表达式模式 (Part 2+) ---
            if (ctx.L_PAREN() != null && ctx.exp().size() == 1 && ctx.R_PAREN() != null) {
                // 括号表达式：(exp)
                return visit(ctx.exp(0));
            } else if (ctx.lVal() != null) {
                // 左值：加载变量值
                Value ptr = visit(ctx.lVal());
                if (!(ptr instanceof AllocaInstruction)) {
                    throw new RuntimeException("Error: lVal does not resolve to a valid pointer at line " + ctx.getStart().getLine());
                }
                return builder.buildLoad(ptr, new Some<>("load_" + ctx.lVal().IDENT().getText()));
            } else if (ctx.number() != null) {
                // 数字字面量
                return visit(ctx.number());
            } else if (ctx.IDENT() != null && ctx.L_PAREN() != null) {
                // 函数调用：IDENT L_PAREN funcRParams? R_PAREN
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
                // 一元运算：+exp, -exp, !exp
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
                    // 使用 buildZeroExt 实现零扩展（i1 到 i32）
                    return builder.buildZeroExt(i1Result, context.getInt32Type(), new Some<>("zext_not"));
                }
                throw new RuntimeException("Error: Unknown unary operator: " + opText + " at line " + ctx.getStart().getLine());
            } else if (ctx.exp().size() == 2 && (ctx.MUL() != null || ctx.DIV() != null || ctx.MOD() != null || ctx.PLUS() != null || ctx.MINUS() != null)) {
                // 二元算术运算
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
                } else if (ctx.AND() != null) {
                    result = (lhsLong != 0 && rhsLong != 0) ? 1 : 0;
                } else if (ctx.OR() != null) {
                    result = (lhsLong != 0 || rhsLong != 0) ? 1 : 0;
                } else {
                    throw new RuntimeException("Error: Unknown condition operator at line " + ctx.getStart().getLine());
                }
                return context.getInt32Type().getConstant(result, true);
            }
            throw new RuntimeException("Error: Unexpected condition structure in constant evaluation mode at line " + ctx.getStart().getLine());
        } else {
            // --- 运行时模式 ---
            if (ctx.exp() != null) {
                Value expVal = visit(ctx.exp());
                ConstantInt zero = context.getInt32Type().getConstant(0, true);
                return builder.buildIntCompare(IntPredicate.NotEqual, expVal, zero, new Some<>("cond"));
            } else if (ctx.cond().size() == 2) {
                if (ctx.LT() != null) {
                    Value lhs = visit(ctx.cond(0));
                    if (lhs.getType().equals(context.getInt1Type())) {
                        lhs = builder.buildZeroExt(lhs, context.getInt32Type(), new Some<>("zext_lhs"));
                    }
                    Value rhs = visit(ctx.cond(1));
                    if (rhs.getType().equals(context.getInt1Type())) {
                        rhs = builder.buildZeroExt(rhs, context.getInt32Type(), new Some<>("zext_rhs"));
                    }
                    return builder.buildIntCompare(IntPredicate.SignedLessThan, lhs, rhs, new Some<>("lt"));
                } else if (ctx.GT() != null) {
                    Value lhs = visit(ctx.cond(0));
                    if (lhs.getType().equals(context.getInt1Type())) {
                        lhs = builder.buildZeroExt(lhs, context.getInt32Type(), new Some<>("zext_lhs"));
                    }
                    Value rhs = visit(ctx.cond(1));
                    if (rhs.getType().equals(context.getInt1Type())) {
                        rhs = builder.buildZeroExt(rhs, context.getInt32Type(), new Some<>("zext_rhs"));
                    }
                    return builder.buildIntCompare(IntPredicate.SignedGreaterThan, lhs, rhs, new Some<>("gt"));
                } else if (ctx.LE() != null) {
                    Value lhs = visit(ctx.cond(0));
                    if (lhs.getType().equals(context.getInt1Type())) {
                        lhs = builder.buildZeroExt(lhs, context.getInt32Type(), new Some<>("zext_lhs"));
                    }
                    Value rhs = visit(ctx.cond(1));
                    if (rhs.getType().equals(context.getInt1Type())) {
                        rhs = builder.buildZeroExt(rhs, context.getInt32Type(), new Some<>("zext_rhs"));
                    }
                    return builder.buildIntCompare(IntPredicate.SignedLessEqual, lhs, rhs, new Some<>("le"));
                } else if (ctx.GE() != null) {
                    Value lhs = visit(ctx.cond(0));
                    if (lhs.getType().equals(context.getInt1Type())) {
                        lhs = builder.buildZeroExt(lhs, context.getInt32Type(), new Some<>("zext_lhs"));
                    }
                    Value rhs = visit(ctx.cond(1));
                    if (rhs.getType().equals(context.getInt1Type())) {
                        rhs = builder.buildZeroExt(rhs, context.getInt32Type(), new Some<>("zext_rhs"));
                    }
                    return builder.buildIntCompare(IntPredicate.SignedGreaterEqual, lhs, rhs, new Some<>("ge"));
                } else if (ctx.EQ() != null) {
                    Value lhs = visit(ctx.cond(0));
                    if (lhs.getType().equals(context.getInt1Type())) {
                        lhs = builder.buildZeroExt(lhs, context.getInt32Type(), new Some<>("zext_lhs"));
                    }
                    Value rhs = visit(ctx.cond(1));
                    if (rhs.getType().equals(context.getInt1Type())) {
                        rhs = builder.buildZeroExt(rhs, context.getInt32Type(), new Some<>("zext_rhs"));
                    }
                    return builder.buildIntCompare(IntPredicate.Equal, lhs, rhs, new Some<>("eq"));
                } else if (ctx.NEQ() != null) {
                    Value lhs = visit(ctx.cond(0));
                    if (lhs.getType().equals(context.getInt1Type())) {
                        lhs = builder.buildZeroExt(lhs, context.getInt32Type(), new Some<>("zext_lhs"));
                    }
                    Value rhs = visit(ctx.cond(1));
                    if (rhs.getType().equals(context.getInt1Type())) {
                        rhs = builder.buildZeroExt(rhs, context.getInt32Type(), new Some<>("zext_rhs"));
                    }
                    return builder.buildIntCompare(IntPredicate.NotEqual, lhs, rhs, new Some<>("neq"));
                } else if (ctx.AND() != null) {
                    BasicBlock entryBlock = builder.getInsertionBlock().unwrap();
                    Function func = entryBlock.getFunction().unwrap();
                    BasicBlock rhsBlock = new BasicBlock(LLVM.LLVMAppendBasicBlock(func.getRef(), "and_rhs"));
                    func.addBasicBlock(rhsBlock);
                    BasicBlock endBlock = new BasicBlock(LLVM.LLVMAppendBasicBlock(func.getRef(), "and_end"));
                    func.addBasicBlock(endBlock);

                    Value cond1 = visit(ctx.cond(0));
                    builder.buildConditionalBranch(cond1, rhsBlock, endBlock);

                    builder.positionAfter(rhsBlock);
                    Value cond2 = visit(ctx.cond(1));
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
                    builder.buildConditionalBranch(cond1, endBlock, rhsBlock);

                    builder.positionAfter(rhsBlock);
                    Value cond2 = visit(ctx.cond(1));
                    builder.buildBranch(endBlock);

                    builder.positionAfter(endBlock);
                    PhiInstruction phi = builder.buildPhi(context.getInt1Type(), new Some<>("or_result"));
                    phi.addIncoming(
                            new Pair<>(entryBlock, context.getInt1Type().getConstant(1, true)),
                            new Pair<>(rhsBlock, cond2)
                    );
                    return phi;
                }
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
        // Number literal. Always returns a ConstantInt.
        // This is used in both constant evaluation and runtime contexts, but in Part 1 only in constExp.

        String text = ctx.INTEGR_CONST().getText();
        long intValue;
        try {
            // Handle different number bases
            if (text.startsWith("0x") || text.startsWith("0X")) {
                // Hexadecimal (base 16)
                intValue = Long.parseLong(text.substring(2), 16);
            } else if (text.startsWith("0") && text.length() > 1) {
                // Octal (base 8) - starts with '0' followed by another digit
                intValue = Long.parseLong(text.substring(1), 8);
            } else {
                // Decimal (base 10)
                intValue = Long.parseLong(text);
            }
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid integer constant format: " + text, e);
        }

        // Get the i32 type (assuming SysY int is i32 for standard integer values)
        Type i32Type = context.getInt32Type(); // Get the IntegerType object

        // Corrected: Use i32Type instance to call getConstant
        // Create a ConstantInt. Use `true` for `isSigned` as SysY int is signed.
        return ((IntegerType) i32Type).getConstant(intValue, true);
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
        if (ctx.constDecl() != null) return visit(ctx.constDecl());
        if (ctx.varDecl() != null) return visit(ctx.varDecl());
        throw new RuntimeException("Invalid declaration at line " + ctx.getStart().getLine());
    }

    @Override
    public Value visitConstDecl(SysYParser.ConstDeclContext ctx) {
        boolean isGlobal = scopeStack.size() == 1; // 全局作用域
        for (SysYParser.ConstDefContext def : ctx.constDef()) {
            String varName = def.IDENT().getText();
            isConstantEvaluation = true;
            Value initValue = visit(def.constInitVal());
            isConstantEvaluation = false;

            if (!(initValue instanceof ConstantInt)) {
                throw new RuntimeException("Error: Constant initializer must be integer constant at line " + def.getStart().getLine());
            }

            if (isGlobal) {
                GlobalVariable global = module.addGlobalVariable(varName, context.getInt32Type(), Option.empty())
                        .unwrap();
                scopeStack.peek().put(varName, global);
            } else {
                AllocaInstruction alloca = builder.buildAlloca(context.getInt32Type(), new Some<>(varName));
                builder.buildStore(initValue, alloca);
                scopeStack.peek().put(varName, alloca);
            }
        }
        return null;
    }


    @Override
    public Value visitConstDef(SysYParser.ConstDefContext ctx) {
        String varName = ctx.IDENT().getText();
        isConstantEvaluation = true;
        Value initValue = visit(ctx.constInitVal());
        isConstantEvaluation = false;

        if (!(initValue instanceof ConstantInt)) {
            throw new RuntimeException("Error: Constant initializer must be integer constant at line " + ctx.getStart().getLine());
        }

        AllocaInstruction alloca = builder.buildAlloca(context.getInt32Type(), new Some<>(varName));
        builder.buildStore(initValue, alloca);
        scopeStack.peek().put(varName, alloca);
        return null;
    }

    @Override
    public Value visitVarDecl(SysYParser.VarDeclContext ctx) {
        boolean isGlobal = scopeStack.size() == 1;
        for (SysYParser.VarDefContext def : ctx.varDef()) {
            String varName = def.IDENT().getText();
            if (isGlobal) {
                Constant initValue = context.getInt32Type().getConstant(0, true);
                GlobalVariable global = module.addGlobalVariable(varName, context.getInt32Type(), Option.empty())
                        .unwrap();
                scopeStack.peek().put(varName, global);
            } else {
                AllocaInstruction alloca = builder.buildAlloca(context.getInt32Type(), new Some<>(varName));
                scopeStack.peek().put(varName, alloca);
                if (def.initVal() != null) {
                    Value initValue = visit(def.initVal());
                    builder.buildStore(initValue, alloca);
                }
            }
        }
        return null;
    }

    @Override
    public Value visitVarDef(SysYParser.VarDefContext ctx) {
        String varName = ctx.IDENT().getText();
        AllocaInstruction alloca = builder.buildAlloca(context.getInt32Type(), new Some<>(varName));
        scopeStack.peek().put(varName, alloca);

        if (ctx.initVal() != null) {
            Value initValue = visit(ctx.initVal());
            builder.buildStore(initValue, alloca);
        }
        return null;
    }

    @Override
    public Value visitConstInitVal(SysYParser.ConstInitValContext ctx) {
        return visit(ctx.constExp());
    }

    @Override
    public Value visitInitVal(SysYParser.InitValContext ctx) {
        return visit(ctx.exp());
    }

}