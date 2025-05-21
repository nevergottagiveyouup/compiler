import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class AsmBuilder {
    private List<String> assemblyOutput = new ArrayList<>();

    // 基本操作
    public void op2(String op, String dest, String lhs, String rhs) {
        assemblyOutput.add(String.format("    %s %s, %s, %s", op, dest, lhs, rhs));
    }

    public void op1(String op, String dest, String src) {
        assemblyOutput.add(String.format("    %s %s, %s", op, dest, src));
    }

    public void op3(String op, String dest, String src1, String src2) {
        if (src2.isEmpty()) {
            assemblyOutput.add(String.format("    %s %s, %s", op, dest, src1));
        } else {
            assemblyOutput.add(String.format("    %s %s, %s, %s", op, dest, src1, src2));
        }
    }

    public void loadImm(String dest, long value) {
        assemblyOutput.add(String.format("    li %s, %d", dest, value));
    }

    public void loadAddr(String dest, String label) {
        assemblyOutput.add(String.format("    la %s, %s", dest, label));
    }

    // 内存操作
    public void load(String dest, String base, int offset) {
        assemblyOutput.add(String.format("    lw %s, %d(%s)", dest, offset, base));
    }

    public void store(String src, String base, int offset) {
        assemblyOutput.add(String.format("    sw %s, %d(%s)", src, offset, base));
    }

    // 跳转和分支
    public void jump(String label) {
        assemblyOutput.add(String.format("    j %s", label));
    }

    public void branch(String op, String rs, String label) {
        assemblyOutput.add(String.format("    %s %s, %s", op, rs, label));
    }

    public void branch2(String op, String rs1, String rs2, String label) {
        assemblyOutput.add(String.format("    %s %s, %s, %s", op, rs1, rs2, label));
    }

    // 调用和返回
    public void call(String funcName) {
        assemblyOutput.add(String.format("    call %s", funcName));
    }

    public void ret() {
        assemblyOutput.add("    ret");
    }

    // 标签和注释
    public void label(String label) {
        assemblyOutput.add(label + ":");
    }

    public void comment(String text) {
        assemblyOutput.add("    # " + text);
    }

    public void section(String section) {
        assemblyOutput.add("\n." + section);
    }

    public void directive(String dir) {
        assemblyOutput.add("." + dir);
    }

    public void directive(String dir, String value) {
        assemblyOutput.add(String.format(".%s %s", dir, value));
    }

    public void emptyLine() {
        assemblyOutput.add("");
    }

    // 输出生成
    public void writeToFile(String filename) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            for (String line : assemblyOutput) {
                writer.println(line);
            }
        } catch (IOException e) {
            System.err.println("无法写入汇编文件: " + e.getMessage());
        }
    }

    public List<String> getOutput() {
        return assemblyOutput;
    }

    // 在 AsmBuilder 类中添加此方法
    public void move(String dest, String src) {
        assemblyOutput.add(String.format("    mv %s, %s", dest, src));
    }
}