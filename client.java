import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 */

class Client implements Runnable {

	/* Class Variables */
	private static boolean debug = true;
	private static String ip_address;
	private static int port_number;
	private static Node self_node;
	private static DatagramSocket socket;
	private static long timeout;
	private static Set<Node> network = new HashSet<Node>();
	private static ConcurrentHashMap<Node, Path> neighbors = new ConcurrentHashMap<Node, Path>();
	private static ConcurrentHashMap<Node, Path> distance = new ConcurrentHashMap<Node, Path>();
	private static ConcurrentHashMap<Node, ConcurrentHashMap<Node,Path>> neighbor_distances = 
			new ConcurrentHashMap<Node,ConcurrentHashMap<Node,Path>>();
	private static ConcurrentHashMap<Node, Path> before_linkdown = new ConcurrentHashMap<Node, Path>();
	private static ConcurrentHashMap<Node, Long> neighbor_timers = new ConcurrentHashMap<Node, Long>();

	/* Main Method */
	public static void main(String[] args) throws Exception {
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
			else if (command.contains("linkup")){
				for (int i=0; i<command.length(); i++){
					if (Character.isDigit(command.charAt(i))){
						linkup(command.substring(i));
						break;
					}
				}
			}
			else if (command.contains("showrt")){
				showrt();
			}
			else if (command.contains("close")){
				close();
			} else {
				print("Sorry, I couldn't understand that command.");
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
					byte[] buffer = new byte[4000];
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
			 */
			private void respondToPacket(DatagramPacket packet) {
				// recover ConcurrentHashMap
				Node source = new Node(packet.getAddress(), packet.getPort());
				byte[] data = packet.getData();
				ConcurrentHashMap<Node, Path> table = recoverObject(data);
				if (table == null){
					return;
				}
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
			private boolean isLinkdownMessage(ConcurrentHashMap<Node, Path> table) {
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
	 * 			from distance
	 * 			remove from neighbors
	 * 			remove from neighbor_timers
	 * 			remove from neighbor_distances
	 * 			update distances
	 */
	private static void linkdown(Node node){
		print("Linkdown removed " + node.toString());
		before_linkdown.put(node, neighbors.get(node));
		distance.remove(node);
		neighbors.remove(node);
		neighbor_timers.remove(node);
		neighbor_distances.clear();
		updateDistances(true);
	}

	/*
	 * Responds to a route update message
	 * 		update the neighbors table
	 * 		update the neighbor_distances table
	 * 		reset this node's timer
	 * 		update distances
	 */
	private static void readRouteUpdateMessage(Node source, ConcurrentHashMap<Node, Path> table) {
		print("######### MESSAGE from " + source.toString() + " ########");
		for(Node node : table.keySet()){
			print(node.format() + table.get(node).format());
		}
		// compare it to previous table, looking for removed nodes
		if (neighbor_distances.get(source) != null){
			Set<Node> missing_nodes = findMissing(table, neighbor_distances.get(source));
			// remove the missing nodes from the network and distance tables
			if (!missing_nodes.isEmpty()){
				removeMissing(missing_nodes);
				// update the neighbor_distances table
			}
		}
		neighbor_distances.put(source, table);
		// restart the timer
		neighbor_timers.put(source, System.currentTimeMillis());
		// update neighbors table
		Path path = new Path(table.get(self_node).cost, source);
		neighbors.put(source, path);
		updateDistances();
	}
	
	/*
	 * Compares a received distance table to the currently held copy, returning
	 * a set of Nodes to remove from the network.
	 */
	private static Set<Node> findMissing(ConcurrentHashMap<Node, Path> received,
			ConcurrentHashMap<Node, Path> previous) {
		Set<Node> missing = new HashSet<Node>();
		for (Node had : previous.keySet()){
			if (!received.keySet().contains(had)){
				missing.add(had);
				print("MISSING NODE: " + had.toString());
			}
		}
		return missing;
	}
	
	/*
	 * Remove missing nodes from the network and distance tables
	 */
	private static void removeMissing(Set<Node> missing){
		for (Node n : missing){
			network.remove(n);
			distance.remove(n);
		}
	}

	/*
	 * Wrapper function
	 */
	private static void updateDistances(){
		updateDistances(false);
	}

	/*
	 * Updates the distance table based on the current state of the network.
	 */
	private static void updateDistances(boolean changed) {
		// make sure the network is up to date
//		print("NETWORK IN UPDATEDISTANCES()");
		for (Node neighbor : neighbor_distances.keySet()){
//			print("neighbor: " + neighbor.toString());
			network.add(neighbor);
			ConcurrentHashMap<Node, Path> table = neighbor_distances.get(neighbor);
			for (Node node : table.keySet()){
				if (!node.equals(self_node) && !table.get(node).link.equals(self_node)){
					network.add(node);
//					print("found neighbor_distance: " + node.toString());
				}
			}
		}
		for (Node network_node : network){
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
				else if(neighbor_distances == null) {
					continue;
				}
				// ignore neighbors that don't have paths to the node in question
				else if (neighbor_distances.get(neighbor_node) == null){
					continue;
				}
				// ignore neighbors that don't have paths to the node in question
				else if (neighbor_distances.get(neighbor_node)
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
					print("Network node: " + network_node);
					print("Neighbor node: " + neighbor_node);
					Path new_path = new Path(new_distance, neighbor_node);
					distance.put(network_node, new_path);
					print("Added to routing table: " + network_node.format() + new_path.format());
					changed = true;
				}
			}
		}
		if (changed){
			routeUpdate();
		} else {
		}
	}

	/*
	 * Send a copy of the current distance estimates to all of the client's neighbors.
	 */
	protected static void routeUpdate() {
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
			}
		}
	}
	
	/*
	 * Convert a ConcurrentHashMap to a byte[] for transferral.
	 */
	private static byte[] tableToBytes(ConcurrentHashMap<Node, Path> table){
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
	private static ConcurrentHashMap<Node, Path> recoverObject(byte[] bytes){
		ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
		ObjectInput in = null;
		Object obj = null;
		try {
			in = new ObjectInputStream(stream);
			obj = in.readObject(); 
		} catch (Exception e) {
			System.err.println("Error writing bytes to object");
			System.err.println("Try again next time");
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
		return (ConcurrentHashMap<Node, Path>)obj;
	}

	/*
	 * Allows the user to destroy an existing link (i.e. change cost to infinity).
	 * Both the current client and the specified neighbor break the connection.
	 * 			- Linkdown messages contain a table with only 1 entry; that
	 * 			  entry's path cost is negative
	 */
	private static void linkdown(String destination){
		try{
			Node neighbor = new Node (destination);
			if (!neighbors.containsKey(neighbor)){
				System.err.println("Error: attempted to linkdown non-neighbor node "
						+ neighbor.toString());
			} else {
				// send the linkdown message to the neighbor
				Path path = new Path(-1, destination);
				ConcurrentHashMap<Node, Path> linkdown_message = new ConcurrentHashMap<Node, Path>();
				linkdown_message.put(neighbor, path);
				byte[] bytes = tableToBytes(linkdown_message);
				DatagramPacket packet = new DatagramPacket(bytes, bytes.length, 
						neighbor.address, neighbor.port);
				try {
					socket.send(packet);
				} catch (IOException e) {
					System.err.println("Error delivering linkdown message.");
				}
				// destroy the connection locally
				linkdown(neighbor);
			}
		} catch (Exception e) {
			print("Error - check your arguments.");
		}
	}
	
	/*
	 * Allows the user to restore the link to the original value after linkdown()
	 * destroyed it.
	 */
	private static void linkup(String destination){
		try{
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
				updateDistances();
			} else { 
				print("Error - " + destination + " was never a neighbor.");
			}
		} catch (Exception e) {
			print("Error - " + destination + " was never a neighbor.");
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
			sb.append(distance.get(node).format());
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
						checkNeighborTimers();
					}

					/*
					 * Checks the neighbor_timers table to see if anyone has
					 * expired.
					 */
					synchronized private void checkNeighborTimers() {
						long current_time = System.currentTimeMillis();
						Set<Node> to_remove = new HashSet<Node>();
						for (Node n : neighbor_timers.keySet()){
							long elapsed = current_time - neighbor_timers.get(n);
							if (elapsed >= 3*timeout){
								to_remove.add(n);
							}
						}
						for (Node n : to_remove){
							removeNeighbor(n);
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
		print("Neighbor: " + n.toString() + " timed out and was removed.");
		print("############# Network Before");
		for (Node node : network){
			print("Node: " + node.toString());
		}
		network.remove(n); 			// ***************
		print("############# Network After");
		for (Node node : network){
			print("Node: " + node.toString());
		}
		print("############# Distance Before");
		for (Node node : distance.keySet()){
			print("Node: " + node.toString());
		}
		distance.remove(n); 		// ***************
		print("############# Distance After");
		for (Node node : distance.keySet()){
			print("Node: " + node.toString());
		}
		print("############# Neighbors Before");
		for (Node node : neighbors.keySet()){
			print("Node: " + node.toString());
		}
		neighbors.remove(n); 		// ***************
		print("############# Neighbors After");
		for (Node node : neighbors.keySet()){
			print("Node: " + node.toString());
		}
		print("############# Timers Before");
		for (Node node : neighbor_timers.keySet()){
			print("Node: " + node.toString());
		}
		neighbor_timers.remove(n); 	// ***************
		print("############# Timers After");
		for (Node node : neighbor_timers.keySet()){
			print("Node: " + node.toString());
		}
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
				// ip_address = "127.0.0.1";
				// TODO: fix this
				ip_address = getIP("localhost").getHostAddress();
				port_number = Integer.parseInt(args[0]);
				socket = new DatagramSocket(Integer.parseInt(args[0]));
				timeout = Long.parseLong(args[1])*1000;
				self_node = new Node(ip_address, port_number);
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
			InetAddress ip = getIP(args[i]);
			Integer port = Integer.parseInt(args[i+1]);
			Node destination = new Node(ip, port);
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
		System.err.println("Error:\tImproper command format, please try again.");
		System.err.println("Reason:\t" + reason);
        System.err.println("Usage:\tjava Client [port] [timeout] " +
        				   "[remote_ip1] [remote_port1] [weight1] ...");
		System.err.println("##############################################\n");
        System.exit(1);
	}

	private static InetAddress getIP(String str){
		InetAddress ip = null;
		try {
			ip = InetAddress.getByAddress(InetAddress.getByName(str).getAddress());
		} catch (Exception e){
			e.printStackTrace();
			System.err.println("Error encountered in getIP()");
		} return ip;
	}
	
	/*
	 * Pretty print debug statements.
	 */
	private static void print(String str){
		if (debug){
			System.out.println(str);
		}
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
