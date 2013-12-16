import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;

/*
 * File: Cost.java
 * ------------
 * Name:       Nathan Hayes-Roth
 * UNI:        nbh2113
 * Class:      Computer Networks
 * Assignment: Programming Assignment #3
 * ------------
 * Represents an entry in a routing table: cost and link.
 */

public class Path implements Serializable{
	
	/* Class Variables */
	public double cost;
	public Node link;
	// auto-generated static final serialVersionUID field
	private static final long serialVersionUID = -7878397739821850978L;
	
	/* Constructors */
	Path(double cost, Node link){
		this.cost = cost;
		this.link = link;
	}
	Path(String cost, Node link){
		this.cost = Double.parseDouble(cost);
		this.link = link;
	}
	Path(double cost, String link){
		this.cost = cost;
		this.link = new Node(link);
	}
	Path(String cost, String link){
		this.cost = Double.parseDouble(cost);
		this.link = new Node(link);
	}
	/* Converters */
	public InetAddress getIP() throws UnknownHostException{
		return this.link.address;
	}
	
	/* toString() */
	public String toString(){
		return this.cost + ":" + this.link.toString();
	}
	
	public String format(){
		return String.format("Cost = %.2f, Link = (%s)", this.cost, this.link.toString());
	}
	
	public static void main(String[] args){
		System.out.println((new Path ("4.1", "128.59.196.2:20000")));
	}
	
}
