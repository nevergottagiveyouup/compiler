public class VoidType extends Type {
    // 单例模式，确保全局只有一个 VoidType 实例
    private static final VoidType instance = new VoidType();

    public VoidType() {
        super(TypeKind.VOID);
    }

    public static VoidType getInstance() {
        return instance;
    }

    @Override
    public boolean matches(Type other) {
        return other.getKind() == TypeKind.VOID;
    }

    @Override
    public String toString() {
        return "void";
    }
}

