public class SysYFormatter extends SysYParserBaseVisitor<Void> {
    private StringBuilder formattedCode = new StringBuilder();
    private int indentLevel = 0;
    private static final String INDENT = "    "; // 4个空格

    /**
     * 修正空格：
     * 1. 在类型关键字 (int, const, void) 后面若直接紧跟标识符，则插入空格；
     * 2. 确保 "=" 两边有空格；
     * 3. 合并多余空格，并去除多余分号（仅在 fixSpacing 中去除连续分号）。
     */
    private String fixSpacing(String text) {
        // 在 int, const, void 后如果紧跟字母，则插入空格
        text = text.replaceAll("(?<![a-zA-Z0-9_])(int|const|void)(?=[A-Z_a-z])", "$1 ");
        // 在 "=" 两边添加空格
        text = text.replaceAll("\\s*=\\s*", " = ");
        // 将多个分号合并成一个（仅用于清理多余分号，后面统一添加一个）
        text = text.replaceAll(";+", ";");
        // 合并多个空格
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    private void appendWithIndent(String text) {
        formattedCode.append(INDENT.repeat(indentLevel)).append(text);
    }

    @Override
    public Void visitCompUnit(SysYParser.CompUnitContext ctx) {
        for (var child : ctx.children) {
            visit(child);
            formattedCode.append("\n"); // 每个顶层声明或函数后空一行
        }
        return null;
    }

    @Override
    public Void visitDecl(SysYParser.DeclContext ctx) {
        String text = fixSpacing(ctx.getText().trim());
        // 如果末尾有分号，去掉
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        appendWithIndent(text + ";\n");
        return null;
    }

    @Override
    public Void visitFuncDef(SysYParser.FuncDefContext ctx) {
        formattedCode.append("\n");
        // 函数头：函数类型和函数名及参数。函数名和左括号之间不加空格。
        String header = ctx.funcType().getText() + " " + ctx.IDENT().getText() + "(";
        appendWithIndent(header);
        if (ctx.funcFParams() != null) {
            // 参数部分用 fixSpacing 调整空格
            formattedCode.append(fixSpacing(ctx.funcFParams().getText().trim()));
        }
        formattedCode.append(") {\n");
        indentLevel++;
        visit(ctx.block());
        indentLevel--;
        appendWithIndent("}\n");
        return null;
    }

    @Override
    public Void visitBlock(SysYParser.BlockContext ctx) {
        for (var item : ctx.blockItem()) {
            visit(item);
        }
        return null;
    }

    @Override
    public Void visitStmt(SysYParser.StmtContext ctx) {
        // 对不同类型语句单独处理
        if (ctx.RETURN() != null) {
            appendWithIndent("return " + fixSpacing(ctx.exp().getText().trim()) + ";\n");
            return null;
        } else if (ctx.IF() != null) {
            formattedCode.append(INDENT.repeat(indentLevel))
                    .append("if (")
                    .append(fixSpacing(ctx.exp().getText().trim()))
                    .append(") {\n");
            indentLevel++;
            visit(ctx.stmt(0));
            indentLevel--;
            if (ctx.ELSE() != null) {
                appendWithIndent("} else {\n");
                indentLevel++;
                visit(ctx.stmt(1));
                indentLevel--;
            }
            appendWithIndent("}\n");
            return null;
        } else if (ctx.WHILE() != null) {
            formattedCode.append(INDENT.repeat(indentLevel))
                    .append("while (")
                    .append(fixSpacing(ctx.exp().getText().trim()))
                    .append(") {\n");
            indentLevel++;
            visit(ctx.stmt(0));
            indentLevel--;
            appendWithIndent("}\n");
            return null;
        } else if (ctx.getChildCount() >= 3 && "=".equals(ctx.getChild(1).getText())) {
            // 处理赋值语句：lVal = exp ;
            String left = fixSpacing(ctx.lVal().getText().trim());
            String right = fixSpacing(ctx.exp().getText().trim());
            appendWithIndent(left + " = " + right + ";\n");
            return null;
        } else {
            // 其他语句
            String text = fixSpacing(ctx.getText().trim());
            // 去除末尾多余的分号（如果有）
            if (text.endsWith(";")) {
                text = text.substring(0, text.length() - 1).trim();
            }
            appendWithIndent(text + ";\n");
            return null;
        }
    }

    @Override
    public Void visitVarDef(SysYParser.VarDefContext ctx) {
        String text = fixSpacing(ctx.getText().trim());
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        appendWithIndent(text + ";\n");
        return null;
    }

    @Override
    public Void visitFuncFParams(SysYParser.FuncFParamsContext ctx) {
        // 简单输出参数列表（若需要更复杂的格式，可进一步拆分参数）
        appendWithIndent(fixSpacing(ctx.getText().trim()));
        return null;
    }

    @Override
    public Void visitExp(SysYParser.ExpContext ctx) {
        // 针对简单二元表达式：如 a+b, a-b 等
        if (ctx.getChildCount() == 3) {
            String left = fixSpacing(ctx.getChild(0).getText().trim());
            String operator = ctx.getChild(1).getText().trim();
            String right = fixSpacing(ctx.getChild(2).getText().trim());
            formattedCode.append(left)
                    .append(" ")
                    .append(operator)
                    .append(" ")
                    .append(right);
            return null;
        }
        return super.visitExp(ctx);
    }

    @Override
    public Void visitLVal(SysYParser.LValContext ctx) {
        appendWithIndent(fixSpacing(ctx.getText().trim()));
        return null;
    }

    @Override
    public Void visitFuncRParams(SysYParser.FuncRParamsContext ctx) {
        appendWithIndent(fixSpacing(ctx.getText().trim()));
        return null;
    }

    public String getFormattedCode() {
        return formattedCode.toString().trim();
    }
}
