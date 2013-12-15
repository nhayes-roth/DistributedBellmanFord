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
	private static boolean debug = true;
	private static String ip_address;
	private static int port_number;
	private static DatagramSocket socket;
	private static long timeout;
	private static Set<Node> network = new HashSet<Node>();
	private static Hashtable<Node, Path> neighbors = new Hashtable<Node, Path>();
	private static Hashtable<Node, Path> distance = new Hashtable<Node, Path>();
	private static Hashtable<Node, Hashtable<Node,Path>> neighbor_distances = 
			new Hashtable<Node,Hashtable<Node,Path>>();
	private static Hashtable<Node, Path> before_linkdown = new Hashtable<Node, Path>();
	private static Hashtable<Node, Long> neighbor_timers = new Hashtable<Node, Long>();

	/* Main Method */
	public static void main(String[] args) throws Exception {
		// TODO: remove this before submission
		if (args.length == 0 && debug == true){
			args = new String[]{
					"20000", 
					"4", 
					"localhost", 
					"20001", 
					"4.1"};
		}
		setup(args);
		startTimers();
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
						if(debug){
							System.out.println("DEBUG - Sending thread fired.");	
						}
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
					if(debug){
						System.out.println("DEBUG - packet received.");
					}
					respondToPacket(packet);
				}
			}

			/*
			 * Read the message and react accordingly:
			 */
			private void respondToPacket(DatagramPacket packet) {
				// recover hashtable
				Node source = new Node(packet.getAddress(), packet.getPort());
				byte[] data = packet.getData();
				Hashtable<Node, Path> table = recoverObject(data);
				// check if it's a linkdown message
				if (isLinkdownMessage(table)){
					linkdown(source);
				}
				// otherwise treat it like a route update 
				else {
					readRouteUpdateMessage(source, table);
				}
			}

			/*
			 * Determines if a delivered table is actually a linkdown message.
			 */
			private boolean isLinkdownMessage(Hashtable<Node, Path> table) {
				for (Path path : table.values()){
					if (path.cost < 0){
						return true;
					}
				} return false;
			}
		};
		thread.start();
	}
	
	/*
	 * Linkdown destroys an existing link between the client and a neighbor node:
	 * 		if it's a neighbor
	 * 			remove from neighbors
	 * 			remove from neighbor_timers
	 * 			remove from neighbor_distances
	 * 			update distances
	 */
	private static void linkdown(Node node){
		if (!neighbors.containsKey(node)){
			System.err.println("Error: attempted to linkdown non-neighbor node "
					+ node.toString());
		} else {
			before_linkdown.put(node, neighbors.get(node));
			neighbors.remove(node);
			neighbor_timers.remove(node);
			neighbor_distances.remove(node);
			updateDistances();
		}
	}
	

	/*
	 * Responds to a route update message
	 * 		update the neighbors table
	 * 		update the neighbor_distances table
	 * 		reset this node's timer
	 * 		update distances
	 */
	private static void readRouteUpdateMessage(Node source, Hashtable<Node, Path> table) {
		// update the neighbor_distances table
		neighbor_distances.put(source, table);
		// restart the timer
		neighbor_timers.put(source, System.currentTimeMillis());
		// update neighbors table
		Node self = new Node(ip_address, port_number);
		Path path = new Path(table.get(self).cost, source);
		neighbors.put(source, path);
		updateDistances();
	}

	/*
	 * Updates the distance table based on the current state of the network.
	 */
	private static void updateDistances() {
		// make sure the network is up to date
		for (Node neighbor : neighbor_distances.keySet()){
			network.add(neighbor);
			Hashtable<Node, Path> table = neighbor_distances.get(neighbor);
			for (Node node : table.keySet()){
				network.add(node);
			}
		}
		boolean changed = false;
		// for every node in the network
		for (Node network_node : network){
			// find the minimum distance that travels through a neighbor
			double new_distance;
			double old_distance;
			if (distance.get(network_node) == null){
				old_distance = -1;
			} else {
				old_distance = distance.get(network_node).cost;
			}
			for (Node neighbor_node : neighbors.keySet()){
				double cost_to_neighbor = neighbors.get(neighbor_node).cost;
				double remaining_distance;
				// if the nodes are the same, the distance is 0
				if (network_node.equals(neighbor_node)){
					remaining_distance = 0.;
				}
				// ignore neighbors that don't have paths to the node in question
				else if (neighbor_distances
							.get(neighbor_node)
							.get(network_node) == null){
					continue;
				}
				// otherwise calculate the estimated distance
				else {
					remaining_distance = neighbor_distances
							.get(neighbor_node)
							.get(network_node).cost;
				}
				new_distance = cost_to_neighbor + remaining_distance;
				// check if this is the new shortest path
				if (old_distance < 0 || new_distance < old_distance){
					distance.put(network_node, new Path(new_distance, neighbor_node));
					changed = true;
				}
			}
		}
		if (changed){
			routeUpdate();
		}
	}

	/*
	 * Send a copy of the current distance estimates to all of the client's neighbors.
	 */
	protected static void routeUpdate() {
		if (debug){
			System.out.println("DEBUG - route update message sent");
		}
		// construct the byte array
		byte[] bytes = tableToBytes(distance);
		DatagramPacket packet = null;
		// send it to each neighbor
		for (Node neighbor : neighbors.keySet()){
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
	 * Convert the a hashtable to a byte[] for transferral.
	 */
	private static byte[] tableToBytes(Hashtable<Node, Path> table){
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		ObjectOutput out = null;
		byte[] bytes = null;
		try {
			out = new ObjectOutputStream(stream);
			out.writeObject(table);
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
	private static Hashtable<Node, Path> recoverObject(byte[] bytes){
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
	 * 			- Linkdown messages contain a table with only 1 entry; that
	 * 			  entry's path cost is negative
	 */
	private static void linkdown(String destination){
		// send the linkdown message to the neighbor
		Node node = new Node(destination);
		Path path = new Path(-1, destination);
		Hashtable<Node, Path> linkdown_message = new Hashtable<Node, Path>();
		linkdown_message.put(node, path);
		byte[] bytes = tableToBytes(linkdown_message);
		DatagramPacket packet = new DatagramPacket(bytes, bytes.length, node.address, node.port);
		try {
			socket.send(packet);
		} catch (IOException e) {
			System.err.println("Error delivering linkdown message.");
			e.printStackTrace();
			System.exit(1);
		}
		// destroy the connection locally
		linkdown(node);
	}
	
	/*
	 * Allows the user to restore the link to the original value after linkdown()
	 * destroyed it.
	 */
	private static void linkup(String destination){
		Node neighbor = new Node(destination);
		if (neighbors.containsKey(neighbor)){
			System.err.println("ERROR - nodes are already neighbors.");
		} else if (before_linkdown.containsKey(neighbor)){
			// add it back to neighbors
			neighbors.put(neighbor, before_linkdown.get(neighbor));
			// start the timer
			neighbor_timers.put(neighbor, System.currentTimeMillis());
			// remove it from before_linkdown
			before_linkdown.remove(neighbor);
			// update distances
		}
	}
	
	/*
	 * Allows the user to view the current routing table for the client
	 * (i.e. it should indicate the cost and path used to reach that client).
	 */
	private static void showrt(){
		StringBuilder sb = new StringBuilder();
		sb.append(new SimpleDateFormat("hh:mm:ss").format(new Date()));
		for (Node node : distance.keySet()){
			sb.append("\n");
			sb.append(node.format());
			sb.append(distance.get(node).toString());
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
	 * Fills out the neighbor_timers table and starts an independent thread to
	 * periodically check it.
	 */
	private static void startTimers() {
		long time = System.currentTimeMillis();
		for (Node n : neighbors.keySet()){
			neighbor_timers.put(n, time);
		}
		Thread thread = new Thread(new Client()){
			public void run() {
				// setup the timer
				java.util.Timer timer = new java.util.Timer();
				java.util.TimerTask task = new java.util.TimerTask(){
					public void run(){
						if(debug){
							System.out.println("DEBUG - neighbor_timer thread fired.");	
						}
						checkNeighborTimers();
					}

					/*
					 * Checks the neighbor_timers table to see if anyone has
					 * expired.
					 */
					private void checkNeighborTimers() {
						long current_time = System.currentTimeMillis();
						for (Node n : neighbor_timers.keySet()){
							long elapsed = current_time - neighbor_timers.get(n);
							if (elapsed >= 3*timeout){
								removeNeighbor(n);
							}
						}
						
					}
				};
				timer.schedule(task, 1000, 1000);
			}
		};
		thread.start();
	}
	
	/*
	 * Removes a neighbor from this client's network, distance, and neighbors
	 * and calls routeUpdate.
	 */
	private static void removeNeighbor(Node n) {
		if (debug){
			System.out.println("DEBUG - removed neighbor " + n.toString());
		}
		network.remove(n);
		distance.remove(n);
		neighbors.remove(n);
		neighbor_timers.remove(n);
		routeUpdate();
	}

	/*
	 * Check the format of command line arguments for correct form. If correct,
	 * fill out the class variables appropriately.
	 */
	@SuppressWarnings("unused")
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
		// create neighbors, distance, and network
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
			// put into tables
			Node destination = new Node(args[i] + ":" + args[i+1]);
			Path cost = new Path(Double.parseDouble(args[i+2]), destination.toString());
			neighbors.put(destination, cost);
			distance.put(destination, cost);
			network.add(destination);
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
	 * Required to spawn threads. However, this version of run() is never called.
	 */
	@Override
	public void run() {
		if(debug){
			System.out.println("This should never print.");			
		}
		
	}
}