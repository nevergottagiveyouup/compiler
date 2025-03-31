public class IntType extends Type {
    public IntType() {
        super(TypeKind.INT);
    }

    @Override
    public boolean matches(Type other) {
        return other.getKind() == TypeKind.INT;
    }

    @Override
    public String toString() {
        return "int";
    }
}