

class Location {
    public final LocationType type;  // 寄存器或栈
    public final String register;    // 寄存器名称（如果在寄存器中）
    public final int ifSpill;    // 栈偏移量（如果在栈上）

    // 寄存器位置构造函数
    public Location(String register) {
        this.type = LocationType.REGISTER;
        this.register = register;
        this.ifSpill = -1;
    }

    // 不在寄存器上
    public Location() {
        this.type = LocationType.STACK;
        this.register = null;
        this.ifSpill = 1;//值得注意的是这时location并不能找出其在栈上的位置，仅仅是一个标记，不应该用于查找
    }

    enum LocationType {
        REGISTER, STACK
    }
}