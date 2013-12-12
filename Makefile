.PHONY:
default: test.java
	javac test.java

.PHONEY:
run: test.class
	java test

.PHONY:
clean:
	rm -f *.java
