import java.net.InetAddress;
import java.net.UnknownHostException;

/*
 * File: Node.java
 * ------------
 * Name:       Nathan Hayes-Roth
 * UNI:        nbh2113
 * Class:      Computer Networks
 * Assignment: Programming Assignment #3
 * ------------
 * A client's representation in a routing table.
 */

public class Node {

	/* Class variables */
	public InetAddress address;
	public int port;
	
	/* Constructors */
	public Node(InetAddress address, int port){
		this.address = address;
		this.port = port;
	}
	public Node(InetAddress address, String port){
		this.address = address;
		this.port = Integer.parseInt(port);
	}
	public Node(String address, int port){
		try {
			this.address = InetAddress.getByName(address);
		} catch (Exception e) {
			System.err.println("Error creating a Node");
			e.printStackTrace();
			System.exit(1);
		}
		this.port = port;
	}
	public Node(String address, String port){
		try {
			this.address = InetAddress.getByName(address);
		} catch (Exception e) {
			System.err.println("Error creating a Node address from String");
			e.printStackTrace();
			System.exit(1);
		}
		this.port = Integer.parseInt(port);
	}
	public Node(String str){
		try {
			int index = str.indexOf(':');
			this.address = InetAddress.getByName(str.substring(0, index));
			this.port = Integer.parseInt(str.substring(index+1));
		} catch (UnknownHostException e) {
			System.err.println("Error creating a Node address from String");
			e.printStackTrace();
			System.exit(1);
		}
	}
	
	/* toString() */
	public String toString(){
		return this.address.getHostAddress() + ":" + this.port;
	}
	
	/* pretty toString() */
	public String format(){
		return String.format("Destination: " + this.toString() + ", ");
	}
}
