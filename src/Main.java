import org.antlr.v4.runtime.*;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java Main <input file>");
            System.exit(1);
        }

        String inputFile = args[0]; // 从命令行获取文件路径
        CharStream input = CharStreams.fromFileName(inputFile); // 读取 SysY 代码

        // 词法分析
        SysYLexer lexer = new SysYLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        // 语法分析
        SysYParser parser = new SysYParser(tokens);

        // **移除默认错误监听器，添加自定义错误监听器**
        parser.removeErrorListeners();
        //MySyntaxErrorListener errorListener = new MySyntaxErrorListener();
        //parser.addErrorListener(errorListener);

        // 解析语法
        SysYParser.ProgramContext tree = parser.program();


        // 创建 Visitor 并遍历
        MyVisitor visitor = new MyVisitor();
        visitor.visit(tree);

        // 输出 LLVM IR 到文件
        try {
            visitor.writeToFile("output.ll"); // 使用 .ll 扩展名以符合 LLVM IR 惯例
            System.out.println("LLVM IR successfully written to output.ll");
        } catch (RuntimeException e) {
            System.err.println("Error writing LLVM IR to file: " + e.getMessage());
            System.exit(1);
        } finally {
            visitor.close(); // 确保释放资源
        }

    }

    public static void printSysYTokenInformation(Token token) {
        // 获取 token 类型的整数值
        int tokenType = token.getType();

        // 使用 SysYLexer 的常量映射 token 类型
        String tokenTypeName = SysYLexer.VOCABULARY.getSymbolicName(tokenType);

        System.err.println(tokenTypeName + " " + token.getText() + " at Line " + token.getLine() + ".");
    }
}
