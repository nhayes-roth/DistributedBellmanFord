.PHONY:
default: client.java
	javac client.java

.PHONEY:
run: client.class
	java client

.PHONY:
clean:
	rm -f *.class
