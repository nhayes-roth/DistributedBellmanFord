- Nathan Hayes-Roth
- CSEE 4119 - Computer Networks
- Programming Assignment 3 - Distributed Bellman Ford


### Program Description

This application simulates a version of the distributed, asynchronous Bellman
-Ford algorithm that determines the shortest paths between nodes in a router
network. Each client maps to a localhost address with an attached port number
as instructed on the command line.

Clients maintain and exchange multiple tables of data with each others to build
their networks. The most common data structure is a thread-safe mapping
of Nodes and Paths, custom classes that represent entries in the routing table:

For instance, ConcurrentHashMap<Node, Path> distance contains the client's
current estimated distance for all reachable nodes in the network. Clients 
exchange messages by serializing these objects into byte arrays, and passing
them through DatagramPacket Sockes. The objects are recovered on the other side
of the socket and interpretted in one of three ways: route update messages,
linkdown messages, and linkup messages. Linkdown messages are indicated with 
negative cost values and linkup messages are inferred by Clients who recognize
a prior neighbor's address.

The algorithm performs as expected and does not experience errors other than
those that occur naturally from the Bellman-Ford algorithm. For instance, the
counting to infinity problem can occur in the following scenario:
	
	Node 1 to Node 2 = 1
	Node 1 to Node 3 = 1
	Node 2 to Node 3 = 100

If Node 1 is removed from the network (either by timeout or by linkdowns),
Nodes 2 and 3 will count to infinity.

### User Commands
- showrt 		- display the client's current routing table 
- neighbors 	- display the client's neighbors and their costs
- network 		- list all nodes that the client is aware of
- nd 			- display all neighbors' distance vectors currently held
- timers		- display the number of seconds elapsed on each neighbor's timeou timer
- linkup port 	- restore a link with a prior neighboring node
	$ linkup 5003
- linkdown port - destroy a link between two neighboring nodes
	$ linkdown 5003
- close			- stop the cient's operations

### Development Information

- Programming Language: Java 
- Language Version: 	1.7.0_45
- Operating System:		Mac OSX 	
- Software: 			Terminal and Eclipse


### Instructions to Compile and Run

1.	Enter the project director
	
	~/$ cd ~/.../nbh2113

2.	Compile project
    
    ~/.../nbh2113$ make

3.	Start a client by specifying its port number, timeout,  
	and any number of {localhost port weight} neighbors.

    ~/.../nbh2113$ java Client 5000 10 localhost 6000 10
    ~/.../nbh2113$ java Client 4119 10 localhost 5000 4
    ~/.../nbh2113$ java Client 5005 5 localhost 600 1.5 localhost 4119 5 