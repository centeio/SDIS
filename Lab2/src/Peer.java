import java.io.IOException;
import java.net.*;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

public class Peer implements PeerObj {
	private String id;
	private Registry registry;
		
	public String getId() {	return id;}
	public void setId(String id) {this.id = id;}
	public Registry getRegistry() {return registry;}
	
	public void setRegistry(Registry registry) {
		this.registry = registry;
	}
	
	public Peer(String id) throws RemoteException {
		super();
		this.id = id;
	    PeerObj stub = (PeerObj) UnicastRemoteObject.exportObject(this, 0);

	    // Bind the remote object's stub in the registry
	    this.registry = LocateRegistry.getRegistry();
	    this.registry.rebind(this.id, stub);
	}
	
	public static void main(String[] args) throws IOException{
		
		if(args.length != 3){
			System.out.println("Usage: Peer <mcast_addr> <mcast_port> <name>");
			return;
		}		
		
	    Peer obj = new Peer(args[2]);	    
	    
		MulticastSocket mcsocket;

		try{	
			mcsocket = new MulticastSocket(Integer.parseInt(args[1]));
			mcsocket.setTimeToLive(1);
			mcsocket.setSoTimeout(10000);	
			mcsocket.joinGroup(InetAddress.getByName(args[0]));
		}
		catch(SocketException e){
			System.out.println("Try another address...");
			return;
		}
		
		byte[] rbuf = new byte[(int) Math.pow(2,16)];
		DatagramPacket packet = new DatagramPacket(rbuf, rbuf.length);
		
	/*	Thread peerp = new Thread(new PeerInitiator());
		peerp.start();*/
		
		while(true){
			
		try{
			System.out.println("will receive packet");		
			mcsocket.receive(packet);
		}catch(SocketTimeoutException e){
			mcsocket.close();
			return;
		}
		System.out.println("will leave group");	
		}

			
	}
	
	@Override
	public String initOp(String operation, String file) throws RemoteException { //Restore and delete
		// TODO Auto-generated method stub
		return null;
	}
	@Override
	public String initOp(String operation, int space) throws RemoteException { //Reclaim
		// TODO Auto-generated method stub
		return null;
	}
	@Override
	public String initOp(String operation, String file, int repdegree) throws RemoteException { //Backup
		// TODO Auto-generated method stub
		return null;
	}




}
