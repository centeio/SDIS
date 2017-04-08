import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.ArrayList;


public class Operator implements Runnable{
	
	private Peer peer;
	private ArrayList<byte[]> chunks;
	
	public Operator(Peer peer) {
		super();
		this.peer = peer;
	}

	
	
	public boolean reclaim(double space) throws IOException{
		//check the amount of space to free
		double freeSpace = space - peer.maxspace; 
		
		if(freeSpace > 0){
			peer.space += freeSpace;
			freeSpace(peer.directory.getTotalSpace());
		}else{
			freeSpace(space);
		}
		
		return false;
		
	}
	
	private void freeSpace(double tofree) throws IOException {
		double removed = 0;
		tofree *= 1024; //convert to Byte
		ArrayList<Backup> chunks;
		MulticastSocket socket = new MulticastSocket();

		for (String key: peer.protocols.keySet()) {
			if((chunks = peer.protocols.get(key)) != null){
				for (Backup chunk: chunks){
					File f = new File("../peers/"+peer.getId()+"/"+chunk.getFileId()+"."+chunk.getChunkNo());
					long space = f.getTotalSpace();
					if(f.delete()){
						chunks.remove(chunk);
						String message = "REMOVED " + peer.getVersion() + " " + peer.getId() + " " + chunk.getFileId() + " " + chunk.getChunkNo() + " <CRLF><CRLF>";
						InetAddress address = InetAddress.getByName(peer.mc.getMcast_addr());
						DatagramPacket packet = new DatagramPacket(message.getBytes(), message.length(), address, peer.mc.getPort());
						socket.send(packet);
						removed += space;
					}else{
						System.out.println("Could not delete chunk "+chunk.getFileId() + "." + chunk.getChunkNo());
					}
					if(removed >= tofree){
						socket.close();
						break;
					}
				}
			}
		}
		
	}

	

	
	public void divideFileIntoChunks(String name){
		try{
			File file = new File(name);

			chunks = new ArrayList<byte[]>();
			
			FileInputStream stream = new FileInputStream(file);
			MulticastSocket socket = new MulticastSocket();

			byte[] chunkData;
			long filelength = file.length();
			int chunkMaxSize = 1000 * 64;
			int readLength = chunkMaxSize;
			
			while(filelength > 0){
				if(filelength < chunkMaxSize){
					readLength = (int)filelength;
				}
				
				chunkData = new byte[readLength];
				
				int bytesRead = stream.read(chunkData, 0, readLength);
				filelength -= bytesRead;
				
				if(chunkData.length != bytesRead){
					System.out.println("Error reading chunk");
					break;
				}
				chunks.add(chunkData);

				chunkData = null;
			}
			
			stream.close();
			stream = null;
			socket.close();
		}catch(FileNotFoundException e){
			System.out.println("File " + name + " not found");
			return;
		}catch(SecurityException e){
			System.out.println("Denied reading file " + name);
			return;
		} catch (IOException e) {
			System.out.println("Error closing stream of file " + name);
			return;
		}
	}
	
	/**
	 * Encodes a given String according to the MessageDigest SHA-256 algorithm
	 * 
	 * @param base String to encode
	 * @return Encoded String
	 */
	static public String sha256(String base) {
	    try{
	        MessageDigest digest = MessageDigest.getInstance("SHA-256");
	        byte[] hash = digest.digest(base.getBytes("UTF-8"));
	        StringBuffer hexString = new StringBuffer();

	        for (int i = 0; i < hash.length; i++) {
	            String hex = Integer.toHexString(0xff & hash[i]);
	            if(hex.length() == 1) hexString.append('0');
	            hexString.append(hex);
	        }

	        return hexString.toString();
	    } catch(Exception ex){
	       throw new RuntimeException(ex);
	    }
	}

	@Override
	public void run() {
		//TODO access peer's blocking queue		
		while(true){
			try {
				Object protocol = peer.queue.take();
				
				//work 
				if(protocol instanceof Delete){
					Delete del = (Delete) protocol;
					
					if(del.state == Delete.State.DELETEFILE){
						String message = del.getMessage();
						MulticastSocket socket = new MulticastSocket();
						InetAddress address = InetAddress.getByName(peer.mc.getMcast_addr());
						DatagramPacket packet = new DatagramPacket(message.getBytes(), message.length(), address, peer.mc.getPort());
						socket.send(packet);
						
						System.out.println("Sending DELETE message to: \n\t\taddress:" + peer.mc.getMcast_addr() + "\n\t\tport: " + peer.mc.getPort());
						
						del.updateState();
					}
					else if(del.state == Delete.State.DELETECHUNKS ){
						final File folder = peer.getDirectory();
						final String filename = del.getFileId();
						final File[] files = folder.listFiles( new FilenameFilter(){

							@Override
							public boolean accept(File dir, String name) {
								return name.matches( filename + ".*" );
							}
							
						});
						for ( final File file : files ) {
						    if ( !file.delete() ) {
						        System.err.println( "Can't remove " + file.getAbsolutePath() );
						    }
						}
						del.updateState();
					}
				}else if(protocol instanceof BackupInitiator){
					BackupInitiator bkupInit = (BackupInitiator) protocol;
					File f = new File(bkupInit.getFileName());
					divideFileIntoChunks(bkupInit.getFileName());
					String file_id = sha256(f.getName() + f.lastModified() + bkupInit.getPeerID());

					for(int i = 0; i < this.chunks.size(); i++){
						this.peer.queue.add(new Backup(file_id, this.chunks.get(i), i+1, bkupInit.getPeerID(), bkupInit.getRepdegree(), Backup.State.SENDCHUNK));
					}
				}else if(protocol instanceof Backup){
					Backup bkup = (Backup) protocol;

					if(bkup.state == Backup.State.SENDCHUNK){
						String message_header = bkup.getPutchunk();
						byte[] message_header_bytes = message_header.getBytes();
						byte[] message_body = bkup.getChunk();
						byte[] putchunk = new byte[message_header_bytes.length + message_body.length];
						
						for(int i = 0; i < putchunk.length; i++){
							if(i < message_header_bytes.length){
								putchunk[i] = message_header_bytes[i];
							}else{
								putchunk[i] = message_body[i-message_header_bytes.length];
							}
						}
						
						MulticastSocket socket = new MulticastSocket();
						InetAddress address = InetAddress.getByName(this.peer.mdb.getMcast_addr());
						DatagramPacket packet = new DatagramPacket(putchunk, putchunk.length, address, this.peer.mdb.getPort());
						socket.send(packet);
						
						System.out.println("Sending PUTCHUNK message");
						System.out.println("Waiting STORED");
						
						int timeout = 1000;
						int actualtries = 0;
						
						Thread.sleep(timeout);
						while(this.peer.getReceivedStored() < bkup.getReplication_degree() && actualtries < 5){
							System.out.println("Retransmiting PUTCHUNK...");
							this.peer.mc.receivedStored = 0;
							actualtries++;
							socket.send(packet);
							timeout = timeout * 2;
							Thread.sleep(timeout);
						}
						socket.close();
						
						System.out.println("Received all STORED messages for chunk " + bkup.getChunkNo());
						
						bkup.setState(Backup.State.DONE);
					}else if(bkup.state == Backup.State.SAVECHUNK){
						File output = new File("../peers/" + this.peer.getId() + "/" + bkup.getFileId() + "." + bkup.getChunkNo());
						
						if(!output.exists()){
							FileOutputStream chunk = new FileOutputStream(output);
							chunk.write(bkup.getChunk());
							chunk.flush();
							chunk.close();
							//aumenta rep_degree
					
							String message = bkup.getStored();
							MulticastSocket socket = new MulticastSocket();
							InetAddress address = InetAddress.getByName(this.peer.mc.getMcast_addr());
							DatagramPacket packet = new DatagramPacket(message.getBytes(), message.length(), address, this.peer.mc.getPort());
							
							long randomTime = (0 + (int)(Math.random() * 4))*100;
							Thread.sleep(randomTime);
							
							socket.send(packet);
														
							System.out.println("Sending STORED message");
							
							socket.close();
						}
					}else if(bkup.state == Backup.State.RECEIVESTORED){
						System.out.println("Received STORED message from " + bkup.getSenderId());					
					}
				}
				
				//if not done
				//this.peer.queue.put(protocol);
				//else
				//peer.protocols.put(id, protocol);
				
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}


	private void updateDelete(Delete protocol) throws IOException, InterruptedException {
		switch(protocol.state){
		case DELETEFILE:
			String message = protocol.getMessage();
			MulticastSocket socket = new MulticastSocket();
			InetAddress address = InetAddress.getByName(peer.mc.getMcast_addr());
			DatagramPacket packet = new DatagramPacket(message.getBytes(), message.length(), address, peer.mc.getPort());
			socket.send(packet);
			
			System.out.println("Sending DELETE message to: \n\t\taddress:" + peer.mc.getMcast_addr() + "\n\t\tport: " + peer.mc.getPort());
			
			break;
		case DELETECHUNKS:
			final File folder = peer.getDirectory();
			final String filename = protocol.getFileId();
			final File[] files = folder.listFiles( new FilenameFilter(){

				@Override
				public boolean accept(File dir, String name) {
					return name.matches( filename + ".*" );
				}
				
			});
			for ( final File file : files ) {
			    if ( !file.delete() ) {
			        System.err.println( "Can't remove " + file.getAbsolutePath() );
			    }
			}
			
			break;
		default:
			break;
		}
		
		if(protocol.updateState() != Delete.State.DONE){
			this.peer.queue.put(protocol);
		}
		else
			System.out.println("Delete " + protocol.getFileId() + " done");
	}
	
	public void receiveStored(){
		
	}
}
