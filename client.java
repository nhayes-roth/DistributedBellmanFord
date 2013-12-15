import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Hashtable;

/*
 * File: Client.java
 * ------------
 * Name:       Nathan Hayes-Roth
 * UNI:        nbh2113
 * Class:      Computer Networks
 * Assignment: Programming Assignment #3
 * ------------
 * Clients perform the distributed distance computation and support a user 
 * interface (edit links to neighbors and view the routing table.)
 * 
 * Clients are identified by an <IP address, Port> tuple.
 * 
 * Each client process gets as input the set of neighbors, the link costs and a 
 * timeout value. 
 * 
 * Each client has a read-only UDP socket to which it listens for incoming 
 * messages and the number of that port is known to its neighbors. 
 * 
 * Each client maintains a distance vector, a list of <destination, cost> tuples, 
 * one tuple per client, where cost is the current estimate of the total link 
 * cost on the shortest path to the other client. 
 * 
 * Clients exchange distance vector information using a ROUTE UPDATE message, 
 * i.e., each client uses this message to send a copy of its current distance 
 * vector to its neighbors. 
 * 
 * Each client uses a set of write only sockets to send these messages to its 
 * neighbors. The clients wait on their sockets 
 * until their distance vector changes or until TIMEOUT seconds pass, whichever 
 * arrives sooner, and then transmit their distance vectors to all neighbors.
 * 
 * Link failures is also assumed when a client doesn’t receive a ROUTE UPDATE 
 * message from a neighbor (i.e., hasn’t ‘heard’ from a neighbor) for 3*TIMEOUT 
 * seconds. This happens when the neighbor client crashes or if the user calls the 
 * CLOSE command for it. When this happens, the link cost should be set to infinity 
 * and the client should stop sending ROUTE UPDATE messages to that neighbor. 
 * The link is assumed to be dead until the process comes up and a ROUTE UPDATE 
 * message is received again from that neighbor. 	
 */

class Client implements Runnable {

	/* Class Variables */
	private static String ip_address;				// TODO: maybe just store string?
	private static int port_number;					// TODO: do i need this?
	private static DatagramSocket read_socket;
	private static DatagramSocket write_socket;
	private static long timeout;
	private static Hashtable<Node, Path> original_costs = new Hashtable<Node, Path>();
	private static Hashtable<Node, Path> estimated_costs = new Hashtable<Node, Path>();

	/* Main Method */
	public static void main(String[] args) throws Exception {
		// TODO: remove this before submission
		if (args.length == 0){
			args = new String[]{
					"20000", 
					"2", 
					"localhost", 
					"20001", 
					"4.1"};
		}
		setup(args);
		startSending();
		receiveInstructions();
		while(true){
		}
	}
	
	/*
	 * Manages the user interface: accepts commands, prints information, etc.
	 */
	private static void receiveInstructions() {
		// TODO Auto-generated method stub
		
	}

	/*
	 * Starts an independent thread to handle the regular sending of packets.
	 */
	private static void startSending() {
		new Thread(new Client()).start();
	}
	
	/*
	 * Automatically runs when the new thread is started for sending packets.
	 */
	@Override
	public void run() {
		// setup the timer
		java.util.Timer timer = new java.util.Timer();
		java.util.TimerTask task = new java.util.TimerTask(){
			public void run(){
				sendPackets();
			}
		};
		
		timer.schedule(task, timeout, timeout);
	}

	/*
	 * 
	 */
	protected void sendPackets() {
		System.out.println("Packets sent");
		
		
	}

	/*
	 * Allows the user to destroy an existing link (i.e. change cost to infinity).
	 * Both the current client and the specified neighbor break the connection.
	 */
	private static void linkdown(String remote_ip, String remote_port){
		//TODO: implement
	}
	
	/*
	 * Allows the user to restore the link to the original value after linkdown()
	 * destroyed it.
	 * Both the current client and the specified neighbor restore the connection.
	 */
	private static void linkup(String remote_ip, String remote_port){
		//TODO: implement
	}
	
	/*
	 * Allows the user to view the current routing table for the client
	 * (i.e. it should indicate the cost and path used to reach that client).
	 */
	private static void showrt(){
		//TODO: implement
	}
	
	/*
	 * Closes the client process.
	 */
	private static void close(){
		//TODO: implement
	}

	/*
	 * Check the format of command line arguments for correct form. If correct,
	 * fill out the class variables appropriately.
	 */
	private static void setup(String[] args) throws UnknownHostException {
		// check length
		if (args.length < 5 || (args.length-2)%3 != 0){
			chastise("incorrect number of arguments");
		}
		// create local client
		else {
			try {
				// TODO: make sure this gives the literal IP and not the hostname/IP
				ip_address = InetAddress.getLocalHost().toString();
				port_number = Integer.parseInt(args[0]);
				timeout = Long.parseLong(args[1])*1000;
			} catch (Exception e) {
				chastise("improper local process arguments");
				System.exit(1);
			}
		}
		// create both routing tables
		for (int i=2; i<args.length; i=i+3){
			// check formatting
			try {
				InetAddress remote_address = InetAddress.getByName(args[i]);
				Integer remote_number = Integer.parseInt(args[i+1]);
				double cost = Double.parseDouble(args[i+2]);
			} catch (Exception e) {
				chastise("improper arguments for neighbor " + i);
				System.exit(1);
			}
			// put into table
			Node destination = new Node(args[i] + ":" + args[i+1]);
			Path cost = new Path(Double.parseDouble(args[i+2]), destination.toString());
			original_costs.put(destination, cost);	
		}
		estimated_costs = original_costs;

	}

	/*
	 * Write message to command line explaining why the user messed up.
	 */
	private static void chastise(String reason) {
		System.err.println("\n##############################################");
		System.err.println("ERROR:\tImproper command format, please try again.");
		System.err.println("REASON:\t" + reason);
        System.err.println("USAGE:\tjava Client [port] [timeout] " +
        				   "[remote_ip1] [remote_port1] [weight1] ...");
		System.err.println("##############################################\n");
        System.exit(1);
		
	}
}
