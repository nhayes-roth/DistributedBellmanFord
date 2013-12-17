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

    /*************** Class Variables ***************/
	
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
	private static ConcurrentHashMap<Node, Path> old_neighbors = new ConcurrentHashMap<Node, Path>();
	private static ConcurrentHashMap<Node, Long> neighbor_timers = new ConcurrentHashMap<Node, Long>();

    /*************** Main Method ***************/

	public static void main(String[] args) throws Exception {
		setup(args);
		startTimers();
		startSending();
		startListening();
		receiveInstructions();
	}
	
    /*************** Setting Up ***************/

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
							removeNeighbor(n, "timeout detected on ", false);
						}
						
					}
				};
				timer.schedule(task, 1000, 1000);
			}
		};
		thread.start();
	}
	
    /*************** Interpret Instructions ***************/

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
				System.err.println("Error - encountered reading user commands.");
				e.printStackTrace();
				System.exit(1);
			}
			if (command.contains("linkdown")){
				if (command.length() <=16){
					linkdown("localhost:" + command.substring(9));
				} else {
					linkdown(command.substring(9));					
				}
			}
			else if (command.contains("linkup")){
				if (command.length() <=14){
					linkup("localhost:" + command.substring(7));
				} else {
					linkup(command.substring(9));					
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
	
    /*************** Carry Out Instructions ***************/
	
	/*
	 * Closes the client process.
	 */
	private static void close(){
		socket.close();
		System.exit(0);
	}
	
	/*
	 * Allows the user to restore the link to the original value after linkdown()
	 * or a timeout destroyed it.
	 */
	private static void linkup(String destination){
		try{
			Node neighbor = new Node(destination);
			if (neighbors.containsKey(neighbor)){
				System.err.println("Error - nodes are already neighbors.");
			} else if (old_neighbors.containsKey(neighbor)){
				print("Linkup restored " + neighbor.format() + old_neighbors.get(neighbor).format());
				// add it back to network
				network.add(neighbor);
				// add it back to distance
				distance.put(neighbor, old_neighbors.get(neighbor));
				// add it back to neighbors
				neighbors.put(neighbor, old_neighbors.get(neighbor));
				// start the timer
				neighbor_timers.put(neighbor, System.currentTimeMillis());
				// remove it from old_neighbors
				old_neighbors.remove(neighbor);
				routeUpdate();
				// TODO: maybe remove here?
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
		sb.append("\n---------------------------------\n");
		sb.append(new SimpleDateFormat("hh:mm:ss").format(new Date()));
		for (Node node : distance.keySet()){
			sb.append("\n");
			sb.append(node.format());
			sb.append(distance.get(node).format());
		}
		sb.append("\n---------------------------------\n");
		System.out.println(sb.toString());
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
				System.err.println("Error - attempted to linkdown non-neighbor node "
						+ neighbor.toString());
			} else {
				Boolean last_neighbor = (neighbors.size()==1);
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
					System.err.println("Error - delivering linkdown message.");
				}
				// destroy the connection locally
				removeNeighbor(neighbor, "linkdown called on ", last_neighbor);
			}
		} catch (Exception e) {
			print("Error - check your arguments.");
		}
	}
	
    /*************** Sending Data ***************/

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
	
    /*************** Reading Data ***************/
	
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
					Boolean is_last = (neighbors.size() == 1);
					removeNeighbor(source, "linkdown received from " + source.toString(), is_last);
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
	 * Reads a route update message and reacts accordingly.
	 */
	private static void readRouteUpdateMessage(Node source, ConcurrentHashMap<Node, Path> table) {
		// compare the received table to the previous one, looking for lost nodes
		if (neighbor_distances.get(source) != null){
			Set<Node> missing_nodes = findMissing(table, neighbor_distances.get(source));
			// remove the missing nodes from the network and distance tables
			if (!missing_nodes.isEmpty()){
				removeMissing(source, missing_nodes);
			}
		}
		// put the rest in the table
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
			}
		}
		return missing;
	}
	
	/*
	 * Remove missing nodes from the network and distance tables
	 */
	private static void removeMissing(Node source, Set<Node> missing){
		for (Node n : missing){			
			network.remove(n);
			distance.remove(n);
			if (neighbors.keySet().contains(n)){
				old_neighbors.put(n, neighbors.get(n));
			}
			neighbors.remove(n);
			neighbor_distances.remove(n);
			neighbor_timers.remove(n);
			
			Set<Node> to_remove = new HashSet<Node>();
			for (Node node : distance.keySet()){
				if (distance.get(node).link.equals(n)){
					to_remove.add(node);
				}
			}
			for (Node node : to_remove){
				print("Removed missing entry from local memory -- " + node.format() + distance.get(node).format());
				distance.remove(node);
			}
		}
	}
	
    /*************** Class Variables ***************/
	
	/*
	 * Removes a neighbor from this client's network, distance, and neighbors
	 * and calls routeUpdate.
	 */
	private static void removeNeighbor(Node n, String message, Boolean last_neighbor) {
		print(message + n.toString());
		old_neighbors.put(n, neighbors.get(n));
		network.remove(n);
		distance.remove(n);
		Set<Node> to_remove = new HashSet<Node>();
		for (Node node : distance.keySet()){
			if (distance.get(node).link.equals(n)){
				to_remove.add(node);
			}
		}
		for (Node node : to_remove){
			distance.remove(node);
		}
		neighbors.remove(n);
		neighbor_distances.remove(n);
		neighbor_timers.remove(n);
		if (!last_neighbor){
			routeUpdate();
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
		for (Node neighbor : neighbor_distances.keySet()){
			network.add(neighbor);
			ConcurrentHashMap<Node, Path> table = neighbor_distances.get(neighbor);
			for (Node node : table.keySet()){
				if (!node.equals(self_node) && !table.get(node).link.equals(self_node)){
					network.add(node);
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
				// avoid possible null pointer exceptions
				else if(neighbor_distances == null) {
					continue;
				}
				// avoid possible null pointer exceptions
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
					Path new_path = new Path(new_distance, neighbor_node);
					distance.put(network_node, new_path);
					print("New shortest path: " + network_node.format() + new_path.format());
					changed = true;
				}
			}
		}
		if (changed){
			routeUpdate();
		}
	}
	
    /*************** Utilities & Other ***************/
	
	/*
	 * Easy print statements.
	 */
	private static void print(String str){
		System.out.println(str);
	}
	
	/*
	 * Avoid having to type InetAddress constantly.
	 */
	public static InetAddress getIP(String str){
		InetAddress ip = null;
		try {
			ip = InetAddress.getByAddress(InetAddress.getByName(str).getAddress());
		} catch (Exception e){
			e.printStackTrace();
			System.err.println("Error - getIP() failed for " + str);
		} return ip;
	}
	
	/*
	 * Required to spawn threads. However, this version of run() is never called.
	 */
	@Override
	public void run() {
	}
}
