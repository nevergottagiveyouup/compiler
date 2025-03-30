lexer grammar SysYLexer;

// 关键字
CONST      : 'const';
INT        : 'int';
VOID       : 'void';
IF         : 'if';
ELSE       : 'else';
WHILE      : 'while';
BREAK      : 'break';
CONTINUE   : 'continue';
RETURN     : 'return';

// 操作符
PLUS       : '+';
MINUS      : '-';
MUL        : '*';
DIV        : '/';
MOD        : '%';
ASSIGN     : '=';
EQ         : '==';
NEQ        : '!=';
LT         : '<';
GT         : '>';
LE         : '<=';
GE         : '>=';
NOT        : '!';
AND        : '&&';
OR         : '||';

// 括号和分隔符
L_PAREN    : '(';
R_PAREN    : ')';
L_BRACE    : '{';
R_BRACE    : '}';
L_BRACKT   : '[';
R_BRACKT   : ']';
COMMA      : ',';
SEMICOLON  : ';';

// 标识符
IDENT      : [a-zA-Z_][a-zA-Z_0-9]*;

// 整型常量
HEX_INTEGER_CONST : '0' [xX] [0-9a-fA-F]+ ;
INTEGER_CONST     : [1-9] [0-9]* | '0' ;


// 空白符
WS         : [ \r\n\t]+ -> skip;  // 跳过空白字符

// 单行注释
LINE_COMMENT
   : '//' ~[\r\n]* -> skip;  // 跳过单行注释

// 多行注释
MULTILINE_COMMENT
   : '/*' .*? '*/' -> skip;  // 跳过多行






