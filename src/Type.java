public abstract class Type {
    // 类型枚举，用于区分具体类型
    public enum TypeKind {
        INT,
        ARRAY,
        FUNCTION,
        VOID
    }

    protected TypeKind kind;

    public Type(TypeKind kind) {
        this.kind = kind;
    }

    public TypeKind getKind() {
        return kind;
    }

    // 用于类型匹配的抽象方法，子类实现
    public abstract boolean matches(Type other);
}