import org.antlr.v4.runtime.*;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java Main <input file> <output file>");
            System.exit(1);
        }

        String inputFile = args[0]; // SysY 源文件路径
        String outputFile = args[1]; // LLVM IR 输出文件路径
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
            visitor.writeToFile(outputFile); // 使用命令行指定的输出路径
            System.out.println("LLVM IR successfully written to " + outputFile);
        } catch (RuntimeException e) {
            System.err.println("Error writing LLVM IR to file: " + e.getMessage());
            System.exit(1);
        } finally {
            visitor.close(); // 确保释放资源
        }
    }

}