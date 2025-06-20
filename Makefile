include Makefile.git

export CLASSPATH=/usr/local/lib/antlr-*-complete.jar

DOMAINNAME = oj.compilers.cpl.icu
ANTLR = java -jar /usr/local/lib/antlr-*-complete.jar -listener -visitor -long-messages
JAVAC = javac -g
JAVA = java


PFILE = $(shell find . -name "SysYParser.g4")
LFILE = $(shell find . -name "SysYLexer.g4")
JAVAFILE = $(shell find . -name "*.java")
ANTLRPATH = $(shell find /usr/local/lib -name "antlr-*-complete.jar")

compile: antlr
	$(call git_commit,"make")
	mkdir -p classes
	$(JAVAC) $(JAVAFILE) -d classes

run: compile
	java -classpath ./classes:$(ANTLRPATH) Main $(FILEPATH)


antlr: $(LFILE) $(PFILE) 
	$(ANTLR) $(PFILE) $(LFILE)


test: compile
	$(call git_commit, "test")
	nohup java -classpath ./classes:$(ANTLRPATH) Main ./tests/test1.sysy &


clean:
	rm -f src/*.tokens
	rm -f src/*.interp
	rm -f src/SysYLexer.java src/SysYParser.java src/SysYParserBaseListener.java src/SysYParserBaseVisitor.java src/SysYParserListener.java src/SysYParserVisitor.java
	rm -rf classes
	rm -rf out
	rm -rf src/.antlr
	rm -rf src/*.class



submit:clean
	git gc
	bash submit.sh


.PHONY: compile antlr test run clean submit


