import java.io.Serializable;
import java.net.InetAddress;

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

public class Node implements Serializable{

	/* Class variables */
	public InetAddress address;
	public Integer port;
	// auto-generated static final serialVersionUID field
	private static final long serialVersionUID = -9113369215235909987L;
	
	/* Constructors */
	public Node(InetAddress address, int port){
		this.address = address;
		this.port = port;
	}
	public Node(String address, int port){
		try {
			this.address = Client.getIP(address);
		} catch (Exception e) {
			System.err.println("Error creating a Node");
			e.printStackTrace();
			System.exit(1);
		}
		this.port = port;
	}
	public Node(String str){
		try {
			int index = str.indexOf(':');
			this.address = Client.getIP(str.substring(0, index));
			this.port = Integer.parseInt(str.substring(index+1));
		} catch (Exception e) {
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
	
	/* Equality */
	@Override
	public boolean equals(Object other){
		Node that = (Node) other;
		return (this.toString().equals(that.toString()));
	}
	
	/* hashCode */
	@Override
	public int hashCode(){
		int hash = 3;
		hash = 7*3 + this.address.hashCode();
		hash = 7*3 + this.port.hashCode();
		return hash;
	}
}
