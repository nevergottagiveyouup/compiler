import org.antlr.v4.runtime.*;
import java.util.BitSet;

public class MySyntaxErrorListener extends BaseErrorListener {
    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                            int line, int charPositionInLine, String msg,
                            RecognitionException e) {
        // 提取错误的 token 信息
        String errorToken = (offendingSymbol instanceof Token) ?
                "'" + ((Token) offendingSymbol).getText() + "'" : "";

        // 格式化输出错误信息
        System.out.println("Error type B at Line " + line + ": " + msg + " " + errorToken);
    }
}
