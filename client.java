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
 * interface.
 */

class Client implements Runnable {

    /*************** Class Variables ***************/
	
	private final static double INF = 999999;		// avoid overflow
	private final static boolean debug = false;
	private static String ip_address;
	private static int port_number;
	private static Node self_node;
	private static DatagramSocket socket;
	private static long timeout;
	private static ConcurrentHashMap<Node, Path> network = // don't actually use the Paths, just nice to be threadsafe
			new ConcurrentHashMap<Node, Path>();
	private static ConcurrentHashMap<Node, Path> neighbors = 
			new ConcurrentHashMap<Node, Path>();
	private static ConcurrentHashMap<Node, Path> distance = 
			new ConcurrentHashMap<Node, Path>();
	private static ConcurrentHashMap<Node, ConcurrentHashMap<Node,Path>> neighbor_distances = 
			new ConcurrentHashMap<Node,ConcurrentHashMap<Node,Path>>();
	private static ConcurrentHashMap<Node, Path> old_neighbors = 
			new ConcurrentHashMap<Node, Path>();
	private static ConcurrentHashMap<Node, Long> neighbor_timers = 
			new ConcurrentHashMap<Node, Long>();

    /*************** Main Method ***************/

	public static void main(String[] args) throws Exception {
		setup(args);
		routeUpdate();
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
	synchronized private static void setup(String[] args) throws UnknownHostException {
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
			}
		}
		// create neighbors, distance, and network
		for (int i=2; i<args.length; i=i+3){
			// check formatting
			try {
				InetAddress remote_address = InetAddress.getByName(args[i]);
				Integer remote_number = Integer.parseInt(args[i+1]);
				double cost = Double.parseDouble(args[i+2]);
				if (cost <= 0){
					chastise("costs must be greater than 0");
				}
			} catch (Exception e) {
				chastise("improper arguments for neighbor " + i);
			}
			// put into tables
			InetAddress ip = getIP(args[i]);
			Integer port = Integer.parseInt(args[i+1]);
			Node destination = new Node(ip, port);
			Path cost = new Path(Double.parseDouble(args[i+2]), destination.toString());
			neighbors.put(destination, cost);
			distance.put(destination, cost);
			network.put(destination, cost);
			routeUpdate();
		}
	}

	/*
	 * Write message to command line explaining why the user messed up.
	 */
	private static void chastise(String reason) {
		print("\n##############################################");
		print("Error:\tImproper command format, please try again.");
		print("Reason:\t" + reason);
		print("Usage:\tjava Client [port] [timeout] " +
        				   "[remote_ip1] [remote_port1] [weight1] ...");
		print("##############################################\n");
        System.exit(1);
	}
	
	/*
	 * Fills out the neighbor_timers table and starts an independent thread to
	 * periodically check it.
	 */
	private static void startTimers() {
		long time = System.currentTimeMillis();
		synchronized(Client.class){
			for (Node n : neighbors.keySet()){
				neighbor_timers.put(n, time);
			}
		}
		Thread thread = new Thread(new Client()){
			public void run() {
				// setup the timer
				java.util.Timer timer = new java.util.Timer();
				java.util.TimerTask task = new java.util.TimerTask(){
					public void run(){
						checkNeighborTimers();
					}
				};
				timer.schedule(task, 1000, 1000);
			}
		};
		thread.start();
	}
	
	/*
	 * Checks the neighbor_timers table to see if anyone has
	 * expired.
	 */
	private static void checkNeighborTimers() {
		long current_time = System.currentTimeMillis();
		Set<Node> to_remove = new HashSet<Node>();
		synchronized(neighbor_timers){
			for (Node n : neighbor_timers.keySet()){
				long elapsed = current_time - neighbor_timers.get(n);
				if (elapsed >= 3*timeout){
					to_remove.add(n);
				}
			}
			for (Node n : to_remove){
				removeNeighbor(n, "timeout detected on ");
				// update distances
				updateDistances();
			}
		}
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
			} else if (command.contains("close")){
				close();
			} else if (command.contains("neighbor")){
				showNeighbors();
			} else if (command.contains("network")){
				showNetwork();
			} else if (command.contains("nd")){
				showNeighborsDistances();
			} else if (command.contains("timers")){
				showTimers();
			} else if (command.contains("old")){
				showOldNeighbors();
			} else if (command.length() == 0) {
				// do nothing
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
		try {
			socket.close();
			System.exit(0);
		} catch (Exception e) {
			// ignore and exit
		}
	}
	
	/*
	 * Pretty print all of the client's (living) neighbors.
	 */
	private static void showNeighbors() {
		print("\n---------------------------------");
		synchronized(neighbors){
			for(Node neighbor : neighbors.keySet()){
				print(neighbor.format() + neighbors.get(neighbor).format());
			}
		}
		print("---------------------------------");
	}
	
	/*
	 * Pretty print all of the client currently tracked timers.
	 */
	private static void showTimers() {
		print("\n---------------------------------");
		synchronized(neighbor_timers){
			for(Node neighbor : neighbor_timers.keySet()){
				long current = System.currentTimeMillis();
				int elapsed = (int)((current - neighbor_timers.get(neighbor))/1000);
				print(neighbor.toString() + " --- " + elapsed + "sec");
			}
		}
		print("---------------------------------");
	}
	
	/*
	 * Pretty print all of the client's departed neighbors.
	 */
	private static void showOldNeighbors() {
		print("\n---------------------------------");
		synchronized(old_neighbors){
			for(Node neighbor : old_neighbors.keySet()){
				print(neighbor.format() + old_neighbors.get(neighbor).format());
			}
		}
		print("---------------------------------");
	}

	/*
	 * Pretty print all of the nodes the client is aware of.
	 */
	private static void showNetwork() {
		print("\n---------------------------------");
		print(self_node.toString() + " (self)");
		synchronized(network){
			for(Node node : network.keySet()){
				print(node.toString() + " (other)");
			}
		}
		print("---------------------------------");
	}

	/*
	 * Pretty print all of the currently held neighbor distance tables.
	 */
	private static void showNeighborsDistances() {
		print("\n---------------------------------");
		synchronized(neighbor_distances){
			for (Node neighbor : neighbor_distances.keySet()){
				print(neighbor.toString());
				ConcurrentHashMap<Node, Path> table = neighbor_distances.get(neighbor);
				for (Node node : table.keySet()){
					print("\t" + node.format() + table.get(node).format());
				}
			}
		}
		print("---------------------------------");
	}
	
	/*
	 * Allows the user to view the current routing table for the client
	 * (i.e. it should indicate the cost and path used to reach that client).
	 */
	private static void showrt(){
		synchronized(distance){
			StringBuilder sb = new StringBuilder();
			sb.append("\n---------------------------------\n");
			sb.append(new SimpleDateFormat("hh:mm:ss").format(new Date()));
			for (Node node : distance.keySet()){
				sb.append("\n");
				sb.append(node.format());
				sb.append(distance.get(node).format());
			}
			sb.append("\n---------------------------------\n");
			print(sb.toString());
		}
	}
	
	/*
	 * Allows the user to restore the link to the original value after linkdown()
	 * or a timeout destroyed it.
	 */
	synchronized private static void linkup(String destination){
		try{
			Node neighbor = new Node(destination);
			
			if (neighbors.containsKey(neighbor)){
				print("Error - nodes are already neighbors.");
				
			} else if (old_neighbors.containsKey(neighbor)){
				Path old_path = old_neighbors.get(neighbor);	
				old_neighbors.remove(neighbor);
				neighbors.put(neighbor, old_path);
				network.put(neighbor, old_path);
				neighbor_timers.put(neighbor, System.currentTimeMillis());
				print("linked up to " + neighbor.format() + old_path.format());
			} else { 
				print("Error - " + destination + " was never a neighbor.");
			}
		} catch (Exception e) {
			print("Error - exception encountered while attempting to link up.");
			e.printStackTrace();
		}
	}
	
	/*
	 * Allows the user to destroy an existing link (i.e. change cost to infinity).
	 * Both the current client and the specified neighbor break the connection.
	 * 		- Linkdown messages contain a table with negative costs
	 */
	private static void linkdown(String destination){
		try{
			Node neighbor = new Node (destination);
			synchronized (neighbors) {
				if (!neighbors.containsKey(neighbor)){
					print("Error - attempted to linkdown non-neighbor node "
							+ neighbor.toString());
					return;
				}
			}
			// create the linkdown message
			Path path = new Path(-1, destination);	// negative cost indicates a linkdown
			ConcurrentHashMap<Node, Path> linkdown_message = new ConcurrentHashMap<Node, Path>();
			linkdown_message.put(neighbor, path);
			byte[] bytes = tableToBytes(linkdown_message);
			DatagramPacket packet = new DatagramPacket(bytes, bytes.length, 
					neighbor.address, neighbor.port);
			// send the message over the socket
			try {
				socket.send(packet);
			} catch (IOException e) {
				// ignore
			}
			// destroy the connection locally
			removeNeighbor(neighbor, "linkdown called on ");
			// update distances
			updateDistances();
		} catch (Exception e) {
			e.printStackTrace();
			print("Error - " + destination + " is not a node on this network.");
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
		routeUpdate(distance);
	}
	protected static void routeUpdate(ConcurrentHashMap<Node, Path> table){
		// construct the byte array
		byte[] bytes = tableToBytes(table);
		DatagramPacket packet = null;
		// send it to each neighbor
		synchronized(neighbors){
			for (Node neighbor : neighbors.keySet()){
				packet = new DatagramPacket(bytes, bytes.length, neighbor.address, neighbor.port);
				try {
					socket.send(packet);
				} catch (IOException e) {
					// ignore
				}
			}
		}
	}
	
	/*
	 * Convert a ConcurrentHashMap to a byte[] for transferral.
	 */
	private static byte[] tableToBytes(ConcurrentHashMap<Node, Path> table){
		synchronized (distance) {
			ByteArrayOutputStream stream = new ByteArrayOutputStream();
			ObjectOutput out = null;
			byte[] bytes = null;
			try {
				out = new ObjectOutputStream(stream);
				out.writeObject(table);
				bytes = stream.toByteArray();
			} catch (IOException e) {
				print("Error - failed to write table to bytes.");
				e.printStackTrace();
				System.exit(1);
			} finally {
				try {
					if (out != null) {
						out.close();
					}
				} catch (IOException ex) {
					// ignore
				}
				try {
					stream.close();
				} catch (IOException ex) {
					// ignore close exception
				}
			}
			return bytes;
		}
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
						// ignore
					}
					read(packet);
				}
			}
		};
		thread.start();
	}
	
	/*
	 * Read the message and react accordingly:
	 */
	private static void read(DatagramPacket packet) {
		
		// recover distances
		Node source = new Node(packet.getAddress(), packet.getPort());
		byte[] data = packet.getData();
		ConcurrentHashMap<Node, Path> table = recoverObject(data);
		
		// check if it's a linkdown message
		if (isLinkdownMessage(table)){
			removeNeighbor(source, "linkdown message received from ");
			// update distances
			updateDistances();
		}
		// otherwise treat it like a route update 
		else {
			readRouteUpdateMessage(source, table);
		}
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
	 * Determines if a delivered table is actually a linkdown message by checking
	 * for negative costs.
	 */
	private static boolean isLinkdownMessage(ConcurrentHashMap<Node, Path> table) {
		for (Path path : table.values()){
			if (path.cost < 0){
				return true;
			}
		} return false;
	}

	/*
	 * Reads a route update message and reacts accordingly.
	 */
	private static void readRouteUpdateMessage(Node source, ConcurrentHashMap<Node, Path> table) {
		
		// if this is the first message, add the source as a neighbor
		firstContact(source, table);
		synchronized(neighbor_distances) {
			// store the neighbor's distance table
			neighbor_distances.put(source, table);
		}
		synchronized(neighbor_timers) {
			// restart the timer
			neighbor_timers.put(source, System.currentTimeMillis());
		}
		synchronized(network) {
			// check the table for new nodes
			for (Node node : table.keySet()){
				
				// add them to the network 
				if (network.get(node) == null){
					network.put(node, new Path(INF, source));
				}
			}
		}
		updateDistances();
	}
	
	/*
	 * Checks if this is the first message from a neighbor and responds accordingly.
	 */
	synchronized private static void firstContact(Node source, ConcurrentHashMap<Node, Path> table) {
		
		// quit out if this is a known neighbor
		if (neighbors.keySet().contains(source)){
			return;
		} 
		else {
			Double cost = INF;
			
			// recover old cost if there's a matching old_neighbor
			if (old_neighbors.keySet().contains(source)) {
				cost = old_neighbors.get(source).cost;
				print("linkup message received from " + source.toString());
			}
			// otherwise assume the cost provided in the table is accurate
			else {
				cost = table.get(self_node).cost;
			}
			
			// add to neighbors and network
			neighbors.put(source, new Path(cost, source));
			network.put(source, new Path(cost, source));
		}
	}

    /*************** Editing Tables ***************/
	
	/*
	 * Removes a neighbor from this client's list of active neighbors:
	 */
	synchronized private static void removeNeighbor(Node neighbor, String message) {
		print(message + neighbor.toString());
		if (neighbors.keySet().contains(neighbor)){
			
			// remove from neighbors and store in old_neighbors
			Path prev_path = neighbors.get(neighbor);
			neighbors.remove(neighbor);
			old_neighbors.put(neighbor, prev_path);
			
			// remove from timer and distance tables
			neighbor_distances.remove(neighbor);
			neighbor_timers.remove(neighbor);
			
			// update network cost to infinity
			Path infinite_path = new Path(INF, neighbor);
			network.put(neighbor, infinite_path);
		}
	}

	/*
	 * Updates the distance table based on the current state of the network.
	 */
	synchronized private static void updateDistances() {
		
		boolean changed = false;
		
		// No distance entry can link through non-neighbors as their link
		Set<Node> distance_nodes = distance.keySet();
		Set<Node> neighbor_nodes = neighbors.keySet();
		for (Node destination : distance_nodes){
			Node link = distance.get(destination).link;
			if (!neighbor_nodes.contains(link)){
				distance.remove(destination);
				changed = true;
			}
		}
		
		// recognize if Client has no neighbors
		if (neighbors.size() == 0) {
			distance.clear();
			network.clear();
			neighbor_distances.clear();
			neighbor_timers.clear();
		}
		
		// for each node in the network
		for (Node network_node : network.keySet()){

			
			// skip the client's own node
			if (network_node.equals(self_node))
				continue;
			
			// values to compare against
			Path previous_path = distance.get(network_node);
			Double previous_distance = null;
			try {
				previous_distance = previous_path.cost;
			} catch (NullPointerException e) {
				previous_distance = INF;
			}
			
			// variables to hold minimum values
			Double best_distance = null;
			Node best_link = null;
			
			// try to find a better estimate
			for (Node neighbor_node : neighbors.keySet()){
				
				double cost_to_neighbor = neighbors.get(neighbor_node).cost;
				double distance_from_neighbor_to_target;
				
				// distance from node to itself is 0
				if (neighbor_node.equals(network_node)){
					distance_from_neighbor_to_target = 0;
				} else {
					// check for an estimate in neighbor_distances
					try {
						distance_from_neighbor_to_target = neighbor_distances
								.get(neighbor_node)
								.get(network_node).cost;
					} catch (NullPointerException e) {
						// default to infinite
						distance_from_neighbor_to_target = INF;
					}
				}
				
				double estimate = cost_to_neighbor + distance_from_neighbor_to_target;
				
				// if the estimate is lower, or this is new, make a note
				if (best_distance == null || estimate < best_distance){
					best_distance = estimate;
					best_link = neighbor_node;
				}
			}
			// if your best guess is still null, you've got nothing
			if (best_distance == null)
				break;
			// if necessary, change the entry in the distance table
			if (!previous_distance.equals(best_distance)){
				changed=true;
				if (debug){
					print("new best distance to " + network_node.toString());
				}
				distance.put(network_node, new Path (best_distance, best_link));
			}
		}
		// if any distance entry changed, send the table to all neighbors
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
