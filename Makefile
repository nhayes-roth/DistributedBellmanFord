.PHONY:
default: Client.java Node.java Path.java
	javac *.java

.PHONY:
clean:
	rm -f *.class
