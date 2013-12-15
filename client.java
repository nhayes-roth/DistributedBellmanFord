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
 * 
 */

class Client {

	/* Class Variables */
	private static InetAddress ip_address;
	private static int port_number;
	private static double timeout;
	private static Hashtable<String, Double> costs = new Hashtable<String, Double>();

	/* Main Method */
	public static void main(String[] args) throws Exception {
		setup(args);
		printInfo();
	}

	/*
	 * Prints the class information in a readable way.
	 */
	private static void printInfo() {
		StringBuilder sb = new StringBuilder();
		sb.append("\n#### Local ####");
		sb.append("\n" + ip_address.toString());
		sb.append("\n" + port_number);
		sb.append("\n" + timeout);
		for (String key : costs.keySet()){
			sb.append("\n#### Neighbor ####");
			sb.append("\n" + key);
			sb.append("\n" + costs.get(key));
		}
		sb.append("\n");
		System.out.println(sb.toString());
	}

	/*
	 * Check the format of command line arguments for correct form. If correct,
	 * fill out the class variables appropriately.
	 */
	private static void setup(String[] args) throws UnknownHostException {
		// check length
		if (args.length < 5 || (args.length-2)%3 != 0)
			chastise("incorrect number of arguments");
		// create local client
		else {
			try {
				ip_address = InetAddress.getLocalHost();
				port_number = Integer.parseInt(args[0]);
				timeout = Double.parseDouble(args[1]);
			} catch (Exception e) {
				chastise("improper local process arguments");
				System.exit(1);
			}
		}
		// create the costs table
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
			costs.put(args[i] + ":" + args[i+1], Double.parseDouble(args[i+2]));
		}

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

	/*
	 * 
	 */
}
