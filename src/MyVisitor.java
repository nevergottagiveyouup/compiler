import java.util.*;

public class MyVisitor extends SysYParserBaseVisitor<Type> {
    private SymbolTable symbolTable;
    private String currentContext;
    private boolean hasError = false;
    private Map<Integer, Set<Integer>> errorLinesByType = new HashMap<>();

    public MyVisitor() {
        symbolTable = new SymbolTable(null);
        currentContext = "global";
    }

    private void reportError(int errorTypeNo, String message, int line) {
        Set<Integer> errorTypes = errorLinesByType.computeIfAbsent(line, k -> new HashSet<>());
        if (!errorTypes.isEmpty() && !errorTypes.contains(errorTypeNo)) {
            return;
        }
        System.err.println("Error type " + errorTypeNo + " at Line " + line + ": " + message);
        hasError = true;
        errorTypes.add(errorTypeNo);
    }

    public void checkAndPrintResult() {
        if (!hasError) {
            System.err.println("No semantic errors in the program!");
        }
    }

    @Override
    public Type visitProgram(SysYParser.ProgramContext ctx) {
        Type result = visitChildren(ctx);
        checkAndPrintResult();
        return result;
    }

    @Override
    public Type visitCompUnit(SysYParser.CompUnitContext ctx) {
        return visitChildren(ctx);
    }

    @Override
    public Type visitFuncDef(SysYParser.FuncDefContext ctx) {
        String funcName = ctx.IDENT().getText();
        int line = ctx.IDENT().getSymbol().getLine();
        Set<Integer> errorTypes = errorLinesByType.getOrDefault(line, new HashSet<>());
        if (!errorTypes.isEmpty()) {
            return null;
        }

        if (symbolTable.lookup(funcName) != null) {
            reportError(4, "Function '" + funcName + "' is already defined", line);
            return null;
        }

        String typeStr = ctx.getChild(0).getText();
        Type retType = typeStr.equals("int") ? new IntType() : new VoidType();

        ArrayList<String> paramNames = new ArrayList<>();
        ArrayList<Type> paramTypes = new ArrayList<>();
        if (ctx.funcFParams() != null) {
            for (SysYParser.FuncFParamContext paramCtx : ctx.funcFParams().funcFParam()) {
                String paramName = paramCtx.IDENT().getText();
                int paramLine = paramCtx.IDENT().getSymbol().getLine();
                errorTypes = errorLinesByType.getOrDefault(paramLine, new HashSet<>());
                if (!errorTypes.isEmpty() && !errorTypes.contains(3)) {
                    continue;
                }
                if (paramNames.contains(paramName)) {
                    reportError(3, "Redefined variable: " + paramName, paramLine);
                    continue;
                }
                paramNames.add(paramName);
                paramTypes.add(getParamType(paramCtx));
            }
        }

        Type funcType = new FunctionType(retType, paramTypes);
        symbolTable.put(funcName, funcType, line);

        symbolTable = symbolTable.enterScope();
        currentContext = funcName;

        for (int i = 0; i < paramNames.size(); i++) {
            symbolTable.put(paramNames.get(i), paramTypes.get(i), line);
        }

        visitBlock(ctx.block());

        symbolTable.exitScope();
        symbolTable = symbolTable.getParent();
        currentContext = "global";

        return null;
    }

    @Override
    public Type visitBlock(SysYParser.BlockContext ctx) {
        symbolTable = symbolTable.enterScope();
        for (SysYParser.BlockItemContext item : ctx.blockItem()) {
            visit(item);
        }
        symbolTable.exitScope();
        symbolTable = symbolTable.getParent();
        return null;
    }

    @Override
    public Type visitStmt(SysYParser.StmtContext ctx) {
        int line = ctx.getStart().getLine();
        Set<Integer> errorTypes = errorLinesByType.getOrDefault(line, new HashSet<>());
        if (!errorTypes.isEmpty()) {
            return null;
        }

        if (ctx.ASSIGN() != null) {
            Type leftType = visitLVal(ctx.lVal());
            if (leftType instanceof FunctionType) {
                reportError(11, "Left side of assignment must be a variable or array element", line);
                return null;
            }
            Type rightType = visitExp(ctx.exp());
            if (leftType != null && rightType != null) {
                if (leftType instanceof ArrayType && rightType instanceof ArrayType) {
                    ArrayType leftArray = (ArrayType) leftType;
                    ArrayType rightArray = (ArrayType) rightType;
                    int leftDim = leftArray.getDimension();
                    int rightDim = rightArray.getDimension();
                    if (leftDim != rightDim) {
                        reportError(5, "Array dimensions mismatch in assignment", line);
                        return null;
                    }
                    // 不检查 numElements，允许赋值
                } else if (!leftType.matches(rightType)) {
                    reportError(5, "Type mismatch in assignment", line);
                    return null;
                }
            }
            return null;
        }

        if (ctx.RETURN() != null) {
            int retLine = ctx.RETURN().getSymbol().getLine();
            errorTypes = errorLinesByType.getOrDefault(retLine, new HashSet<>());
            if (!errorTypes.isEmpty()) {
                return null;
            }
            Type retType = ctx.exp() != null ? visitExp(ctx.exp()) : new VoidType();
            Symbol symbol = symbolTable.lookup(currentContext);
            Type funcType = (symbol != null) ? symbol.getType() : null;
            if (funcType instanceof FunctionType) {
                Type expectedType = ((FunctionType) funcType).getReturnType();
                if (!expectedType.matches(retType)) {
                    reportError(7, "Return type mismatch", retLine);
                }
            }
            return null;
        }

        return visitChildren(ctx);
    }

    @Override
    public Type visitLVal(SysYParser.LValContext ctx) {
        int line = ctx.IDENT().getSymbol().getLine();
        Set<Integer> errorTypes = errorLinesByType.getOrDefault(line, new HashSet<>());
        if (!errorTypes.isEmpty() && !errorTypes.contains(1)) {
            return null;
        }

        String varName = ctx.IDENT().getText();
        Symbol symbol = symbolTable.lookup(varName);
        Type varType = (symbol != null) ? symbol.getType() : null;

        if (varType == null) {
            reportError(1, "Variable '" + varName + "' is not declared", line);
            return null;
        }

        if (!ctx.L_BRACKT().isEmpty()) {
            if (!(varType instanceof ArrayType)) {
                reportError(9, "Applying subscript operator to non-array variable '" + varName + "'", line);
                return null;
            }

            Type currentType = varType;
            int indexCount = ctx.L_BRACKT().size();
            for (int i = 0; i < indexCount; i++) {
                if (!(currentType instanceof ArrayType)) {
                    reportError(9, "Too many indices for array '" + varName + "'", line);
                    return null;
                }
                currentType = ((ArrayType) currentType).getIndexedType();
            }
            return currentType;
        }

        return varType;
    }

    @Override
    public Type visitVarDecl(SysYParser.VarDeclContext ctx) {
        for (SysYParser.VarDefContext varDef : ctx.varDef()) {
            String varName = varDef.IDENT().getText();
            int line = varDef.IDENT().getSymbol().getLine();
            Set<Integer> errorTypes = errorLinesByType.getOrDefault(line, new HashSet<>());
            if (!errorTypes.isEmpty() && !errorTypes.contains(3)) {
                continue;
            }
            if (currentContext.equals("global") && symbolTable.lookup(varName) != null) {
                reportError(3, "Redefined variable or conflicts with function: " + varName, line);
            } else {
                visitVarDef(varDef);
            }
        }
        return null;
    }

    @Override
    public Type visitVarDef(SysYParser.VarDefContext ctx) {
        String varName = ctx.IDENT().getText();
        int line = ctx.IDENT().getSymbol().getLine();
        Set<Integer> errorTypes = errorLinesByType.getOrDefault(line, new HashSet<>());
        if (!errorTypes.isEmpty() && !errorTypes.contains(3)) {
            return null;
        }

        if (symbolTable.lookupInCurrentScope(varName) != null) {
            reportError(3, "Variable '" + varName + "' is already defined in this scope", line);
            return null;
        }

        Type varType = ctx.constExp().isEmpty() ? new IntType() : getArrayType(ctx);
        if (ctx.ASSIGN() != null && ctx.initVal() != null) {
            Type initType = visitInitVal(ctx.initVal());
            if (initType != null && !varType.matches(initType)) {
                if (!(varType instanceof ArrayType && initType instanceof ArrayType)) {
                    reportError(5, "Type mismatch in variable initialization", line);
                }
            }
        }

        symbolTable.put(varName, varType, line);
        return null;
    }

    @Override
    public Type visitInitVal(SysYParser.InitValContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (ctx.exp() != null) {
            return visitExp(ctx.exp());
        }
        if (ctx.L_BRACE() != null) {
            return new ArrayType(new IntType(), 0);
        }
        return null;
    }

    @Override
    public Type visitExp(SysYParser.ExpContext ctx) {
        int line = ctx.getStart().getLine();
        Set<Integer> errorTypes = errorLinesByType.getOrDefault(line, new HashSet<>());
        if (!errorTypes.isEmpty()) {
            return null;
        }

        if (ctx.IDENT() != null && ctx.L_PAREN() != null) {
            String funcName = ctx.IDENT().getText();
            int identLine = ctx.IDENT().getSymbol().getLine();
            errorTypes = errorLinesByType.getOrDefault(identLine, new HashSet<>());
            if (!errorTypes.isEmpty()) {
                return null;
            }

            Symbol symbol = symbolTable.lookup(funcName);
            Type funcType = (symbol != null) ? symbol.getType() : null;

            if (funcType == null) {
                reportError(2, "Function '" + funcName + "' is not defined", identLine);
                return null;
            }
            if (!(funcType instanceof FunctionType)) {
                reportError(10, "Not a function: " + funcName, identLine);
                return null;
            }
            FunctionType fType = (FunctionType) funcType;
            ArrayList<Type> expectedParams = fType.getParamsType();
            ArrayList<Type> actualParams = new ArrayList<>();
            if (ctx.funcRParams() != null) {
                for (SysYParser.ParamContext param : ctx.funcRParams().param()) {
                    Type paramType = visitExp(param.exp());
                    if (paramType != null) {
                        actualParams.add(paramType);
                    }
                }
            }
            if (expectedParams.size() != actualParams.size()) {
                reportError(8, "Function '" + funcName + "' parameter count mismatch", identLine);
                return null;
            } else {
                for (int i = 0; i < expectedParams.size(); i++) {
                    if (!expectedParams.get(i).matches(actualParams.get(i))) {
                        reportError(8, "Function '" + funcName + "' parameter type mismatch", identLine);
                        return null;
                    }
                }
            }
            return fType.getReturnType();
        }

        if (ctx.lVal() != null) {
            return visitLVal(ctx.lVal());
        }
        if (ctx.number() != null) {
            return new IntType();
        }
        if (ctx.unaryOp() != null) {
            Type operandType = visit(ctx.exp(0));
            if (operandType != null && !(operandType instanceof IntType)) {
                reportError(6, "Operator requires int type", line);
                return null;
            }
            return operandType;
        }
        if (ctx.exp().size() == 2) {
            Type left = visit(ctx.exp(0));
            if (left != null && !(left instanceof IntType)) {
                reportError(6, "Operator requires int type", line);
                return null;
            }
            Type right = visit(ctx.exp(1));
            if (right != null && !(right instanceof IntType)) {
                reportError(6, "Operator requires int type", line);
                return null;
            }
            return left;
        }
        return null;
    }

    private Type getParamType(SysYParser.FuncFParamContext ctx) {
        if (ctx.L_BRACKT().isEmpty()) {
            return new IntType();
        } else {
            return new ArrayType(new IntType(), 0);
        }
    }

    private Type getArrayType(SysYParser.VarDefContext ctx) {
        List<Integer> dimensions = new ArrayList<>();
        for (SysYParser.ConstExpContext constExp : ctx.constExp()) {
            dimensions.add(Integer.parseInt(constExp.getText()));
        }

        Type currentType = new IntType();
        for (int i = dimensions.size() - 1; i >= 0; i--) {
            currentType = new ArrayType(currentType, dimensions.get(i));
        }
        return currentType;
    }
}