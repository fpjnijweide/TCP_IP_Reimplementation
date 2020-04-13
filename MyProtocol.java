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
        int sourceIP; // 2 bit number, range [0,3]
        int destIP; // 2 bit number, range [0,3]
        int ackNum; // 7 bit number, range [0,127]
        boolean ackFlag;
        boolean request;
        boolean negotiate;
        boolean SYN;
        boolean broadcast;

        public SmallPacket(){

        }

        public SmallPacket(int sourceIP, int destIP, int ackNum, boolean ackFlag, boolean request, boolean negotiate, boolean SYN, boolean broadcast) {
            this.sourceIP = sourceIP;
            this.destIP = destIP;
            this.ackNum = ackNum;
            this.ackFlag = ackFlag;
            this.request = request;
            this.negotiate = negotiate;
            this.SYN = SYN;
            this.broadcast = broadcast;
        }
    }

    public class BigPacket extends SmallPacket{
        // Third byte
        boolean morePackFlag;
        int seqNum; // 7 bit number, range [0,127]

        // Fourth byte
        int size; // 5 bit number, range [0,31]

        // Other bytes
        byte[] payload; // TODO what to do with this



        public BigPacket(int sourceIP, int destIP, int ackNum, boolean ackFlag, boolean request, boolean negotiate, boolean SYN, boolean broadcast, byte[] payload, int seqNum, boolean morePackFlag, int size) {
            super(sourceIP, destIP, ackNum, ackFlag, request, negotiate, SYN, broadcast);
            this.payload = payload;
            this.seqNum = seqNum;
            this.morePackFlag = morePackFlag;
            this.size = size;
        }
    }
    // The host to connect to. Set this to localhost when using the audio interface tool.
    private static String SERVER_IP = "netsys2.ewi.utwente.nl"; //"127.0.0.1";
    // The port to connect to. 8954 for the simulation server.
    private static int SERVER_PORT = 8954;
    // The frequency to use.
    private static int frequency = 5400;
    private State state = State.READY; // todo make negotiating

    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    public static int read;

    public MyProtocol(String server_ip, int server_port, int frequency, int sourceIP){
        receivedQueue = new LinkedBlockingQueue<Message>();
        sendingQueue = new LinkedBlockingQueue<Message>();

        new Client(SERVER_IP, SERVER_PORT, frequency, receivedQueue, sendingQueue); // Give the client the Queues to use

        new receiveThread(receivedQueue).start(); // Start thread to handle received messages!







        // handle sending from stdin from this thread.
        try{
            ByteBuffer temp = ByteBuffer.allocate(1024);
            read = 0;
            while(true){
                read = System.in.read(temp.array()); // Get data from stdin, hit enter to send!
                System.out.println(read-1);

                if (state==State.NEGOTIATING) {
                    System.out.println("Can't send messages while negotiating");
                } else if(read > 0){
                    // TODO when no longer negotiating: send out something in the request phase, and in the data phase
                    ByteBuffer toSend;
                    if(read >2) {
                        toSend = ByteBuffer.allocate(read - 1 + 4); // jave includes newlines in System.in.read, so -2 to ignore this
                      //  for(int i = read; i<32; i++){
                      //      toSend.put()
                      //  }
                    }
                    else{
                        toSend = ByteBuffer.allocate(2);
                    }
                  //  for
                    //toSend.put( temp.array(), 0, read-1 ); // java includes newlines in System.in.read, so -2 to ignore this
                    toSend.put( fillBigPacket(new BigPacket(sourceIP,2,0,false,false,false,false,false,temp.array(),0,false,30)), 0, read-1 ); // jave includes newlines in System.in.read, so -2 to ignore this
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

    public byte[] fillSmallPacket(SmallPacket packet) {
        // TODO aparte negotiation packet structuur?
        byte first_byte = (byte) (packet.sourceIP << 6 | packet.destIP << 4 | (packet.ackFlag ? 1:0) << 3 | (packet.request ? 1:0) << 2 | (packet.broadcast ? 1:0) << 1 | (packet.SYN ? 1:0));
        byte second_byte = (byte) ((packet.negotiate? 1:0) << 7 | packet.ackNum);
        return new byte[]{first_byte, second_byte};
    }

    public byte[] splitBigPacket(BigPacket packet){
        int byte_nums_msg = (read + 28 - 1)/ 28;
        byte msg_byte = 0;
        byte[] message = new byte [byte_nums_msg];
        for (int i = 0; i <byte_nums_msg;i++){
            for(int j = i * 28; j< (i+1)*28; j++){
               msg_byte = (byte) (msg_byte | packet.payload[j]);
            }
            message[i] = msg_byte;
        }
        return message;
    }

    public byte[] fillBigPacket(BigPacket packet) {
        byte[] first_two_bytes = fillSmallPacket(packet);
        byte third_byte = (byte) ((packet.morePackFlag?1:0) << 7 | packet.seqNum);
        byte fourth_byte = (byte) packet.size; // three bits left here
        byte[] msg_bytes = splitBigPacket(packet);
        byte[] filled_big_packet = new byte[4 + (read - 1 + 8 - 1)/ 8];
        filled_big_packet[0] = first_two_bytes[0];
        filled_big_packet[1] = first_two_bytes[1];
        filled_big_packet[2] = third_byte;
        filled_big_packet[3] = fourth_byte;
        for(int i = 0; i<msg_bytes.length;i++){
            filled_big_packet[4 + i] = msg_bytes[i];
        }
        return filled_big_packet; // TODO fill the rest of the bytes with packet.toSend
    }

    public SmallPacket readSmallPacket(byte[] bytes) {
        SmallPacket packet = new SmallPacket();
        packet.sourceIP = (bytes[0] >> 6) & 0x03;
        packet.destIP = (bytes[0] >> 4) & 0x03;
        packet.ackFlag = ((bytes[0] >> 3) & 0x01)==1;
        packet.request = ((bytes[0] >> 2) & 0x01)==1;
        packet.broadcast = ((bytes[0] >> 1) & 0x01)==1;
        packet.SYN = (bytes[0]  & 0x01)==1;

        packet.negotiate = ((bytes[1] >> 7) & 0x01)==1;
        packet.ackNum = bytes[1] & 0x7F;

        return packet;
    }

    public BigPacket readBigPacket(byte[] bytes) {
        SmallPacket smallPacket = readSmallPacket(bytes);
        boolean morePackFlag = ((bytes[3] >> 7) & 0x01)==1;
        int seqNum = bytes[3] & 0x7F;
        int size = bytes[4] & 0x1F;
        if (size>32){
            System.err.println("Packet size is too big");
        }
        int byte_nums_msg = (read - 1 + 8 - 1)/ 8;
        byte msg_byte = 0;
        byte[] payload = new byte [byte_nums_msg];
        for (int i = 0; i <byte_nums_msg;i++){
            for(int j = i * 8; j< i*8 + 8; j++){
                payload = (byte) (payload | (bytes[j]);
            }
            payload[i] = msg_byte;
        }
       // TODO implement how to read data
        BigPacket packet = new BigPacket(smallPacket.sourceIP, smallPacket.destIP, smallPacket.ackNum, smallPacket.ackFlag, smallPacket.request, smallPacket.negotiate, smallPacket.SYN, smallPacket.broadcast, payload, seqNum, morePackFlag, size);
        return packet;
    }


    public static void main(String args[]) {
        int sourceIP= -1;
        if(args.length > 0){
            //frequency = Integer.parseInt(args[0]);

            sourceIP = Integer.parseInt(args[0]);
            System.out.println("source IP is: " + sourceIP);
        }
         new MyProtocol(SERVER_IP, SERVER_PORT, frequency, sourceIP);
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

                        byte[] receivedBytes = m.getData().array();
                        BigPacket packet = readBigPacket(receivedBytes);

                            System.out.println("source IP from this packet is" + packet.sourceIP);
                            System.out.println("packet sent to " + packet.destIP);
                        //System.out.println("message is: " + packet.mess);


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

