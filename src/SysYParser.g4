parser grammar SysYParser;

options { tokenVocab=SysYLexer; }

compUnit : (funcDef | decl)* EOF;

decl
    : constDecl
    | varDecl
    ;

constDecl
    : CONST INT constDef (COMMA constDef)* SEMICOLON
    ;

constDef
    : IDENT (L_BRACKT constExp R_BRACKT)* ASSIGN constInitVal
    ;

constInitVal
    : constExp
    | L_BRACE (constExp (COMMA constExp)*)? R_BRACE
    ;

varDecl
    : INT varDef (COMMA varDef)* SEMICOLON
    ;

varDef
    : IDENT (L_BRACKT constExp R_BRACKT)* (ASSIGN initVal)?
    ;

initVal
    : exp
    | L_BRACE (exp (COMMA exp)*)? R_BRACE
    ;

funcDef
    : funcType IDENT L_PAREN funcFParams? R_PAREN block
    ;

funcType
    : INT
    | VOID
    ;

funcFParams
    : funcFParam (COMMA funcFParam)*
    ;

funcFParam
    : INT IDENT (L_BRACKT R_BRACKT (L_BRACKT exp R_BRACKT)*)?
    ;

block
    : L_BRACE blockItem* R_BRACE
    ;

blockItem
    : decl
    | stmt
    ;

stmt
    : lVal ASSIGN exp SEMICOLON
    | exp SEMICOLON
    | block
    | IF L_PAREN cond R_PAREN stmt (ELSE stmt)?
    | WHILE L_PAREN cond R_PAREN stmt
    | RETURN exp? SEMICOLON
    ;

exp
    : L_PAREN exp R_PAREN
    | lVal
    | number
    | IDENT L_PAREN funcRParams? R_PAREN
    | unaryOp exp
    | exp (MUL | DIV | MOD) exp
    | exp (PLUS | MINUS) exp
    ;

cond
    : exp
    | cond (LT | GT | LE | GE) cond
    | cond (EQ | NEQ) cond
    | cond AND cond
    | cond OR cond
    ;

lVal
    : IDENT (L_BRACKT exp R_BRACKT)*
    ;

number
    : INTEGER_CONST
    | HEX_INTEGER_CONST
    ;


unaryOp
    : PLUS
    | MINUS
    | NOT
    ;

funcRParams
    : param (COMMA param)*
    ;

param
    : exp
    ;

constExp
    : exp
    ;
