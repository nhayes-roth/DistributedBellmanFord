.PHONY:
default: Client.java
	javac Client.java

.PHONY:
run: Client.class
	java Client 1 1 1 1 1

.PHONY:
clean:
	rm -f *.class
