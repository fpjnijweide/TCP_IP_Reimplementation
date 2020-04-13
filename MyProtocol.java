import client.*;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
* This is just some example code to show you how to interact 
* with the server using the provided client and two queues.
* Feel free to modify this code in any way you like!
*/


public class MyProtocol{
    public enum State{
        NEGOTIATING,
        READY
    }

    public class SmallPacket{
        int sourceIP;
        int destIP;
        int ackNum;
        boolean ackFlag;
        boolean morePackFlag;
        boolean request;
        boolean negotiate;
        boolean SYN;

        public SmallPacket(int sourceIP, int destIP, int ackNum, boolean ackFlag, boolean morePackFlag, boolean request, boolean negotiate, boolean SYN) {
            this.sourceIP = sourceIP;
            this.destIP = destIP;
            this.ackNum = ackNum;
            this.ackFlag = ackFlag;
            this.morePackFlag = morePackFlag;
            this.request = request;
            this.negotiate = negotiate;
            this.SYN = SYN;
        }
    }

    public class BigPacket extends SmallPacket{
        byte[] toSend;
        int seqNum;

        public BigPacket(int sourceIP, int destIP, int seqNum, int ackNum, boolean ackFlag, boolean morePackFlag, byte[] toSend, boolean request, boolean negotiate, boolean SYN) {
            super(sourceIP, destIP, ackNum, ackFlag, morePackFlag, request, negotiate, SYN);
            this.seqNum = seqNum;
            this.toSend = toSend;
        }
    }
    // The host to connect to. Set this to localhost when using the audio interface tool.
    private static String SERVER_IP = "netsys2.ewi.utwente.nl"; //"127.0.0.1";
    // The port to connect to. 8954 for the simulation server.
    private static int SERVER_PORT = 8954;
    // The frequency to use.
    private static int frequency = 5400;
    private State state = State.NEGOTIATING;

    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    public MyProtocol(String server_ip, int server_port, int frequency){
        receivedQueue = new LinkedBlockingQueue<Message>();
        sendingQueue = new LinkedBlockingQueue<Message>();

        new Client(SERVER_IP, SERVER_PORT, frequency, receivedQueue, sendingQueue); // Give the client the Queues to use

        new receiveThread(receivedQueue).start(); // Start thread to handle received messages!







        // handle sending from stdin from this thread.
        try{
            ByteBuffer temp = ByteBuffer.allocate(1024);
            int read = 0;
            while(true){
                read = System.in.read(temp.array()); // Get data from stdin, hit enter to send!
                System.out.println(read-1);

                if (state==State.NEGOTIATING) {
                    System.out.println("Can't send messages while negotiating");
                } else if(read > 0){
                    // TODO when no longer negotiating: send out something in the request phase, and in the data phase
                    ByteBuffer toSend;
                    if(read >2) {
                        toSend = ByteBuffer.allocate(read-1); // jave includes newlines in System.in.read, so -2 to ignore this
                      //  for(int i = read; i<32; i++){
                      //      toSend.put()
                      //  }
                    }
                    else{
                        toSend = ByteBuffer.allocate(2);
                    }
                  //  for
                    toSend.put( temp.array(), 0, read-1 ); // jave includes newlines in System.in.read, so -2 to ignore this
                    Message msg;
                    if( (read-1) > 2 ){
                        msg = new Message(MessageType.DATA, toSend);
                    } else {
                        msg = new Message(MessageType.DATA_SHORT, toSend);
                    }
                    sendingQueue.put(msg);
                }
            }
        } catch (InterruptedException e){
            System.exit(2);
        } catch (IOException e){
            System.exit(2);
        }        
    }


    public int[] fillBigPacket(BigPacket packet) {
        // TODO refactor into smallpacket
        int first_byte = packet.sourceIP << 6 | packet.destIP << 4 | (packet.ackFlag ? 1:0) << 3 | (packet.request ? 1:0) << 2 | (packet.negotiate ? 1:0) << 1 | (packet.SYN ? 1:0);
        int second_byte = (packet.morePackFlag ? 1:0) << 7 | packet.seqNum << 5 | packet.ackNum << 3;
        return new int[]{first_byte, second_byte};
    }


    public static void main(String args[]) {
        if(args.length > 0){
            frequency = Integer.parseInt(args[0]);
        }
        new MyProtocol(SERVER_IP, SERVER_PORT, frequency);        
    }

    private class receiveThread extends Thread {
        private BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue){
            super();
            this.receivedQueue = receivedQueue;
        }

        public void printByteBuffer(ByteBuffer bytes, int bytesLength){
            for(int i=0; i<bytesLength; i++){
                System.out.print((char )( bytes.get(i) ) );
            }
            System.out.println();
            System.out.print( "HEX: ");
            for(int i=0; i<bytesLength; i++){
                System.out.print( String.format("%02x", bytes.get(i)) +" " );
            }
            System.out.println();

        }

        // TODO discovery phase: network toplogy

        public void run(){
            while(true) {
                try{
                    Message m = receivedQueue.take();
                    if (m.getType() == MessageType.BUSY){
                        System.out.println("BUSY");
                    } else if (m.getType() == MessageType.FREE){
                        System.out.println("FREE");
                    } else if (m.getType() == MessageType.DATA){
                        System.out.print("DATA: ");
                        printByteBuffer( m.getData(), m.getData().capacity() ); //Just print the data

                    } else if (m.getType() == MessageType.DATA_SHORT){
                        System.out.print("DATA_SHORT: ");
                        printByteBuffer( m.getData(), m.getData().capacity() ); //Just print the data
                        // TODO read data, check if negotiating
                    } else if (m.getType() == MessageType.DONE_SENDING){
                        System.out.println("DONE_SENDING");
                    } else if (m.getType() == MessageType.HELLO){
                        System.out.println("HELLO");
                    } else if (m.getType() == MessageType.SENDING){
                        System.out.println("SENDING");
                    } else if (m.getType() == MessageType.END){
                        System.out.println("END");
                        System.exit(0);
                    }
                } catch (InterruptedException e){
                    System.err.println("Failed to take from queue: "+e);
                }                
            }
        }
    }


}

