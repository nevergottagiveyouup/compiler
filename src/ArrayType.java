public class ArrayType extends Type {
    private Type contained; // 元素类型，可以是 int 或嵌套的 ArrayType
    private int numElements; // 当前维度的元素数量


    // 构造函数
    public ArrayType(Type contained, int numElements) {
        super(TypeKind.ARRAY);
        this.contained = contained;
        this.numElements = numElements;
    }

    // 获取元素类型
    public Type getContained() {
        return contained;
    }

    // 获取当前维度元素数量
    public int getNumElements() {
        return numElements;
    }

    // 获取数组的维度数
    public int getDimension() {
        int dim = 1;
        Type current = this.contained;
        while (current.getKind() == TypeKind.ARRAY) {
            dim++;
            current = ((ArrayType) current).getContained();
        }
        return dim;
    }

    // 获取索引一次后的类型（例如 a[1] 的类型）
    public Type getIndexedType() {
        return contained;
    }

    // 类型匹配检查
    @Override
    public boolean matches(Type other) {
        if (other.getKind() != TypeKind.ARRAY) {
            return false;
        }
        ArrayType otherArray = (ArrayType) other;

        // 检查维度是否一致，不检查 numElements
        int thisDim = this.getDimension();
        int otherDim = otherArray.getDimension();
        if (thisDim != otherDim) {
            return false;
        }

        // 递归检查元素类型
        return this.contained.matches(otherArray.contained);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(contained.toString()).append("[").append(numElements).append("]");
        return sb.toString();
    }
}