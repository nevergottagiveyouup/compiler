import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class SymbolTable {
    private Map<String, ArrayList<Symbol>> table;
    private int currentScopeLevel;
    private SymbolTable parent;

    public SymbolTable(SymbolTable parent) {
        this.table = new HashMap<>();
        this.currentScopeLevel = parent != null ? parent.currentScopeLevel + 1 : 0;
        this.parent = parent;
    }

    public void put(String name, Type type, int line) {
        ArrayList<Symbol> symbols = table.getOrDefault(name, new ArrayList<>());
        for (Symbol sym : symbols) {
            if (sym.getScopeLevel() == currentScopeLevel) {
                throw new RuntimeException("Duplicate declaration of '" + name + "' at line " + line);
            }
        }
        symbols.add(new Symbol(name, type, currentScopeLevel, line));
        table.put(name, symbols);
    }

    public Symbol lookup(String name) {
        ArrayList<Symbol> symbols = table.get(name);
        if (symbols != null) {
            for (int i = symbols.size() - 1; i >= 0; i--) {
                if (symbols.get(i).getScopeLevel() <= currentScopeLevel) {
                    return symbols.get(i);
                }
            }
        }
        if (parent != null) {
            return parent.lookup(name);
        }
        return null;
    }

    public SymbolTable enterScope() {
        return new SymbolTable(this);
    }

    public void exitScope() {
        for (ArrayList<Symbol> symbols : table.values()) {
            symbols.removeIf(symbol -> symbol.getScopeLevel() == currentScopeLevel);
        }
        // 不修改 currentScopeLevel，因为切换回父符号表会自动调整
    }

    public Symbol lookupInCurrentScope(String name) {
        ArrayList<Symbol> symbols = table.get(name);
        if (symbols == null) return null;

        for (int i = symbols.size() - 1; i >= 0; i--) {
            Symbol sym = symbols.get(i);
            if (sym.getScopeLevel() == currentScopeLevel) {
                return sym;
            }
        }
        return null;
    }

    public SymbolTable getParent() {
        return parent;
    }
}