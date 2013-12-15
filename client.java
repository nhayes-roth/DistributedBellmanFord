import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;

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
	private static String ip_address;
	private static int port_number;
	private static DatagramSocket socket;
	private static long timeout;
	private static Hashtable<Node, Path> original_costs = new Hashtable<Node, Path>();
	private static Hashtable<Node, Path> estimated_costs = new Hashtable<Node, Path>();
	private static Hashtable<Node, Long> last_contact = new Hashtable<Node, Long>();

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
		startListening();
		receiveInstructions();
	}
	
	/*
	 * Manages the user interface: accepts commands, prints information, etc.
	 */
	private static void receiveInstructions() {
		BufferedReader input = new BufferedReader(new InputStreamReader(System.in));	
		String command = "";
		while(true){
			try {
				command = input.readLine().toLowerCase();
			} catch (IOException e) {
				System.err.println("Error encountered reading user commands.");
				e.printStackTrace();
				System.exit(1);
			}
			if (command.contains("linkdown")){
				for (int i=0; i<command.length(); i++){
					if (Character.isDigit(command.charAt(i))){
						linkdown(command.substring(i));
						break;
					}
				}
			}
			if (command.contains("linkup")){
				for (int i=0; i<command.length(); i++){
					if (Character.isDigit(command.charAt(i))){
						linkup(command.substring(i));
						break;
					}
				}
			}
			if (command.contains("showrt")){
				showrt();
			}
			if (command.contains("close")){
				close();
			}
		}
		
	}
	
	/*
	 * Starts an independent thread to handle the regular sending of packets.
	 */
	private static void startSending() {
		Thread thread = new Thread(new Client()){
			public void run() {
				// setup the timer
				java.util.Timer timer = new java.util.Timer();
				java.util.TimerTask task = new java.util.TimerTask(){
					public void run(){
						System.out.println("sending thread fired");
						routeUpdate();
					}
				};
				timer.schedule(task, timeout, timeout);
			}
		};
		thread.start();
	}
	
	/*
	 * Starts an independent thread to handle the acceptance of packets.
	 */
	private static void startListening(){
		Thread thread = new Thread(new Client()){
			public void run() {
				while(true){
					byte[] buffer = new byte[576];
					DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
					try {
						socket.receive(packet);
					} catch (IOException e) {
						System.err.println("Error receiving a datagram packet.");
						e.printStackTrace();
						System.exit(1);
					}
					respondToPacket(packet);
				}
			}

			/*
			 * Read the message and react accordingly:
			 * 		- route update => change table and last_contact
			 */
			private void respondToPacket(DatagramPacket packet) {
				Node link = new Node(packet.getAddress(), packet.getPort());
				last_contact.put(link, System.currentTimeMillis());
				byte[] data = packet.getData();
				Hashtable<Node, Path> new_costs = recoverObject(data);
				Set<Node> network = new HashSet<Node>();
				network.addAll(estimated_costs.keySet());
				network.addAll(new_costs.keySet());
				for (Node node : network) {
//					double cost = estimated_costs.get(node).cost;
//					
//					estimated_costs.put(key, value);
				}
			}
		};
		thread.start();
	}
	
	

	/*
	 * Send a copy of the current distance estimates to all of the client's neighbors.
	 */
	protected static void routeUpdate() {
		// construct the byte array
		byte[] bytes = tableToBytes();
		DatagramPacket packet = null;
		// send it to each neighbor
		for (Node neighbor : original_costs.keySet()){
			packet = new DatagramPacket(bytes, bytes.length, neighbor.address, neighbor.port);
			try {
				socket.send(packet);
			} catch (IOException e) {
				System.err.println("Error delivering packet during routeUpdate");
				e.printStackTrace();
				System.exit(1);
			}
		}
	}
	
	/*
	 * Convert the estimated_costs hashtable to a byte[] for transferral.
	 */
	private static byte[] tableToBytes(){
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		ObjectOutput out = null;
		byte[] bytes = null;
		try {
			out = new ObjectOutputStream(stream);
			out.writeObject(estimated_costs);
			bytes = stream.toByteArray();
		} catch (IOException e) {
			System.err.println("Error writing table to bytes");
			e.printStackTrace();
			System.exit(1);
		} finally {
			try {
				if (out != null) {
					out.close();
				}
			} catch (IOException ex) {
				// ignore close exception
			}
			try {
				stream.close();
			} catch (IOException ex) {
				// ignore close exception
			}
		}
		return bytes;
	}
	
	/*
	 * Recover the sent object from a byte array.
	 */
	@SuppressWarnings("unchecked")
	private static Hashtable recoverObject(byte[] bytes){
		ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
		ObjectInput in = null;
		Object obj = null;
		try {
			in = new ObjectInputStream(stream);
			obj = in.readObject(); 
		} catch (Exception e) {
			System.err.println("Error writing bytes to object");
			e.printStackTrace();
			System.exit(1);
		} finally {
			try {
				stream.close();
			} catch (IOException ex) {
				// ignore close exception
			}
			try {
				if (in != null) {
					in.close();
				}
			} catch (IOException ex) {
				// ignore close exception
			}
		}
		return (Hashtable<Node, Path>)obj;
	}

	/*
	 * Allows the user to destroy an existing link (i.e. change cost to infinity).
	 * Both the current client and the specified neighbor break the connection.
	 */
	private static void linkdown(String destination){
		//TODO: implement
	}
	
	/*
	 * Allows the user to restore the link to the original value after linkdown()
	 * destroyed it.
	 * Both the current client and the specified neighbor restore the connection.
	 */
	private static void linkup(String destination){
		//TODO: implement
	}
	
	/*
	 * Allows the user to view the current routing table for the client
	 * (i.e. it should indicate the cost and path used to reach that client).
	 */
	private static void showrt(){
		StringBuilder sb = new StringBuilder();
		sb.append(new SimpleDateFormat("hh:mm:ss").format(new Date()));
		for (Node node : estimated_costs.keySet()){
			sb.append("\n");
			sb.append(node.format());
			sb.append(estimated_costs.get(node).toString());
		}
		System.out.println(sb.toString());
	}
	
	/*
	 * Closes the client process.
	 */
	private static void close(){
		socket.close();
		System.exit(0);
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
				ip_address = InetAddress.getLocalHost().getHostAddress();
				port_number = Integer.parseInt(args[0]);
				socket = new DatagramSocket(Integer.parseInt(args[0]));
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
		// set estimated costs to original costs at beginning
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
