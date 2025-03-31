import java.util.ArrayList;

public class FunctionType extends Type {
    private Type retTy; // 返回值类型，测试用例中不包含 array
    private ArrayList<Type> paramsType; // 参数类型列表，可能包含 int 或 array

    // 构造函数
    public FunctionType(Type retTy, ArrayList<Type> paramsType) {
        super(TypeKind.FUNCTION);
        // 检查返回值类型是否为数组，若是则抛出异常（根据测试用例约束）
        if (retTy.getKind() == TypeKind.ARRAY) {
            throw new IllegalArgumentException("Function return type cannot be an array");
        }
        this.retTy = retTy;
        // 如果 paramsType 为 null，初始化为空列表
        this.paramsType = (paramsType != null) ? paramsType : new ArrayList<>();
    }

    // 获取返回值类型
    public Type getReturnType() {
        return retTy;
    }

    // 获取参数类型列表
    public ArrayList<Type> getParamsType() {
        return new ArrayList<>(paramsType); // 返回副本以防止外部修改
    }

    // 类型匹配检查
    @Override
    public boolean matches(Type other) {
        if (other.getKind() != TypeKind.FUNCTION) {
            return false;
        }
        FunctionType otherFunc = (FunctionType) other;

        // 检查返回值类型是否匹配
        if (!this.retTy.matches(otherFunc.retTy)) {
            return false;
        }

        // 检查参数数量是否一致
        if (this.paramsType.size() != otherFunc.paramsType.size()) {
            return false;
        }

        // 检查每个参数的类型是否匹配
        for (int i = 0; i < this.paramsType.size(); i++) {
            if (!this.paramsType.get(i).matches(otherFunc.paramsType.get(i))) {
                return false;
            }
        }
        return true;
    }

    // 转换为字符串表示
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(retTy.toString()).append("(");
        for (int i = 0; i < paramsType.size(); i++) {
            sb.append(paramsType.get(i).toString());
            if (i < paramsType.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(")");
        return sb.toString();
    }
}