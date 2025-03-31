public class Symbol {
    private String name;      // 符号名称
    private Type type;        // 符号类型
    private int scopeLevel;   // 作用域级别
    private int line;         // 声明行号（可选，用于错误报告）

    private String context;

    public Symbol(String name, Type type, int scopeLevel, int line) {
        this.name = name;
        this.type = type;
        this.scopeLevel = scopeLevel;
        this.line = line;
    }

    public String getName() { return name; }
    public Type getType() { return type; }
    public int getScopeLevel() { return scopeLevel; }
    public int getLine() { return line; }
    public String getContext() { return context; }
}