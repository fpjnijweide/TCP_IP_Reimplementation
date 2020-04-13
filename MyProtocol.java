import client.*;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
* This is just some example code to show you how to interact 
* with the server using the provided client and two queues.
* Feel free to modify this code in any way you like!
*/


public class MyProtocol{
    public enum State{
        NULL,
        DISCOVERY,
        SENT_DISCOVERY,
        RECEIVED_DISCOVERY,

        // etc
        NEGOTIATING,
        READY
    }

    public class SmallPacket{
        // First byte
        int sourceIP; // 2 bit number, range [0,3]
        int destIP; // 2 bit number, range [0,3]
        boolean ackFlag;
        boolean request;
        boolean broadcast;
        boolean SYN;

        // Second byte
        boolean negotiate;
        int ackNum; // 7 bit number, range [0,127]

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
        byte[] payload;
        byte[] payloadWithoutPadding;

        public BigPacket(int sourceIP, int destIP, int ackNum, boolean ackFlag, boolean request, boolean negotiate, boolean SYN, boolean broadcast, byte[] payloadWithoutPadding, int seqNum, boolean morePackFlag, int size) {
            super(sourceIP, destIP, ackNum, ackFlag, request, negotiate, SYN, broadcast);
            this.payload = new byte[28];
            for (int j = 0; j < size-4; j++) {
                payload[j] = payloadWithoutPadding[j];
            }
            this.payloadWithoutPadding = payloadWithoutPadding;
            this.seqNum = seqNum;
            this.morePackFlag = morePackFlag;
            this.size = size;
        }

        public BigPacket(int sourceIP, int destIP, int ackNum, boolean ackFlag, boolean request, boolean negotiate, boolean SYN, boolean broadcast, byte[] payload, byte[] payloadWithoutPadding, int seqNum, boolean morePackFlag, int size) {
            super(sourceIP, destIP, ackNum, ackFlag, request, negotiate, SYN, broadcast);
            this.payload = payload;
            this.payloadWithoutPadding = payloadWithoutPadding;
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
    private static int frequency = 8400;
    private State state = State.READY; // todo make negotiating

    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    Timer timer;
    List<Byte> buffer = new ArrayList<>();

    public MyProtocol(String server_ip, int server_port, int frequency, int sourceIP){
        receivedQueue = new LinkedBlockingQueue<Message>();
        sendingQueue = new LinkedBlockingQueue<Message>();


        new Client(SERVER_IP, SERVER_PORT, frequency, receivedQueue, sendingQueue); // Give the client the Queues to use

        new receiveThread(receivedQueue).start(); // Start thread to handle received messages!



        timer = new Timer(new Random().nextInt(4000)+8000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state==State.DISCOVERY) {
                    try {
                        sendDiscoveryPacket();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                }
            }
        });
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go go!




        // handle sending from stdin from this thread.
        try{
            ByteBuffer temp = ByteBuffer.allocate(1024);
            int read = 0;
            while(true){
                read = System.in.read(temp.array()); // Get data from stdin, hit enter to send!
                System.out.println(read-1);

                if (state==State.NULL) { // TODO actually use states here
                    System.out.println("Can't send messages while negotiating");
                } else if(read > 0){
                    ByteBuffer text = ByteBuffer.allocate(read-1); // jave includes newlines in System.in.read, so -2 to ignore this
                    text.put( temp.array(), 0, read-1 ); // java includes newlines in System.in.read, so -2 to ignore this
                    for (int i = 0; i < read-1; i+=28) {
                        byte[] partial_text = read-1-i>28? new byte[28] : new byte[read-1-i];
                        System.arraycopy(text.array(), i, partial_text, 0, partial_text.length);
//                        for (int j = 0; j < partial_text.length; j++) {
//                            partial_text[j] = text.array()[j+i];
//                        }
                        boolean morePacketsFlag = read-1-i>28;
                        int size = morePacketsFlag? 32 : read-1-i+4;
                        sendPacket(new BigPacket(sourceIP,2,0,false,false,false,false,false,partial_text,0,morePacketsFlag,size));

                    }
                }
            }

        } catch (InterruptedException e){
            System.exit(2);
        } catch (IOException e){
            System.exit(2);
        }        
    }

    public void sendPacket(BigPacket packet) throws InterruptedException {
        ByteBuffer toSend = ByteBuffer.allocate(32); // jave includes newlines in System.in.read, so -2 to ignore this
        byte[] packetBytes = fillBigPacket(packet);
        toSend.put(packetBytes, 0, 32); // jave includes newlines in System.in.read, so -2 to ignore this
        Message msg = new Message(MessageType.DATA, toSend);
        sendingQueue.put(msg);
    }

    public void sendSmallPacket(SmallPacket packet) throws InterruptedException {
        ByteBuffer toSend = ByteBuffer.allocate(2);
        byte[] packetBytes = fillSmallPacket(packet);
        toSend.put( packetBytes, 0, 2); // jave includes newlines in System.in.read, so -2 to ignore this
        Message msg = new Message(MessageType.DATA_SHORT, toSend);
        sendingQueue.put(msg);
    }

    private void sendDiscoveryPacket() throws InterruptedException {
        int tiebreaker = new Random().nextInt(1<<7);
        SmallPacket packet = new SmallPacket(0,0,tiebreaker,false,true,true,false,true);
        sendSmallPacket(packet);
        state=State.SENT_DISCOVERY;
    }

    public byte[] fillSmallPacket(SmallPacket packet) {
        // TODO aparte negotiation packet structuur?
        byte first_byte = (byte) (packet.sourceIP << 6 | packet.destIP << 4 | (packet.ackFlag ? 1:0) << 3 | (packet.request ? 1:0) << 2 | (packet.broadcast ? 1:0) << 1 | (packet.SYN ? 1:0));
        byte second_byte = (byte) ((packet.negotiate? 1:0) << 7 | packet.ackNum);
        return new byte[]{first_byte, second_byte};
    }

    public byte[] fillBigPacket(BigPacket packet) {
        byte[] result = new byte[32];
        byte[] first_two_bytes = fillSmallPacket(packet);
        result[0] = first_two_bytes[0];
        result[1] = first_two_bytes[1];
        result[2] = (byte) ((packet.morePackFlag?1:0) << 7 | packet.seqNum);
        result[3] = (byte) (packet.size-1); // three bits left here
        for (int i = 4; i <= 31 ; i++) {
            result[i] = packet.payload[i-4];
        }
        return result;
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
        boolean morePackFlag = ((bytes[2] >> 7) & 0x01)==1;
        int seqNum = bytes[2] & 0x7F;
        int size = (bytes[3] & 0x1F) + 1;
        if (size>32){
            System.err.println("Packet size is too big");
        }
        byte[] payload = new byte[28];
        byte[] payloadWithoutPadding = new byte[size-4];

        for (int i = 4; i <= 31; i++) {
            payload[i-4] = bytes[i];
            if (i<size) {
                payloadWithoutPadding[i-4] = bytes[i];
            }
        }
        BigPacket packet = new BigPacket(smallPacket.sourceIP, smallPacket.destIP, smallPacket.ackNum, smallPacket.ackFlag, smallPacket.request, smallPacket.negotiate, smallPacket.SYN, smallPacket.broadcast, payload, payloadWithoutPadding, seqNum, morePackFlag, size);
        return packet;
    }

    public byte[] appendToBuffer(BigPacket packet) {
        // TODO rewrite deze functie. je wilt duidelijk hele packets bufferen (zodat je later bijvoorbeeld kan kijken wat de laatste sequence nr is die je krijgt enzo)
        for (int i = 0; i < packet.payloadWithoutPadding.length; i++) {
            buffer.add(packet.payloadWithoutPadding[i]);
        }

        if (packet.morePackFlag) {
            return new byte[]{};
        } else {
            byte[] bufferCopy = new byte[buffer.size()];
            for (int i = 0; i < buffer.size(); i++) {
                bufferCopy[i] = buffer.get(i);
            }
            buffer.clear();
            return bufferCopy;
        }
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
    public void printByteBuffer(byte[] bytes, boolean buffered){
        for (int i = 0; i < bytes.length; i++) {
            if (!buffered && i%28 == 0) {
                System.out.print("\n");
                System.out.print("HEADER: ");
            }
            if (!buffered && i%28 == 4) {
                System.out.print("\n");
                System.out.print("PAYLOAD: ");
            }
            byte aByte = bytes[i];
            System.out.print((char) aByte);
        }
        System.out.println();
        System.out.print( "HEX: ");
        for (byte aByte : bytes) {
            System.out.print(String.format("%02x", aByte) + " ");
        }
        System.out.println();

    }

    private class receiveThread extends Thread {
        private BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue){
            super();
            this.receivedQueue = receivedQueue;
        }



        // TODO discovery phase: network toplogy

        public void run(){
            while(true) {
                try{
                    Message m = receivedQueue.take();
                    processMessage(m.getData(),m.getType());
                } catch (InterruptedException e){
                    System.err.println("Failed to take from queue: "+e);
                }
            }
        }
    }

    private void processMessage(ByteBuffer data, MessageType type) {
        byte [] bytes = null;
        if (data != null) {
            bytes = data.array();

        }
        switch (state) {
            case DISCOVERY:
                switch (type) {
                    // TODO kil running timers
                }
                break;
            case READY:
                switch (type) {
                    case BUSY:
                        System.out.println("BUSY");
                        break;
                    case FREE:
                        System.out.println("FREE");
                        break;
                    case DATA:
                        System.out.println("DATA");
                        BigPacket packet = readBigPacket(bytes);

                        if (packet.morePackFlag || buffer.size() > 0) {
                            byte[] result = appendToBuffer(packet);
                            if (result.length > 0) {
                                printByteBuffer(result, true);
                            }
                        } else {
                            printByteBuffer(bytes, false); //Just print the data
                        }

                        System.out.println("source IP from this packet is" + packet.sourceIP);
                        System.out.println("packet sent to " + packet.destIP);

                        break;
                    case DATA_SHORT:
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
                        break;
                    case DONE_SENDING:
                        System.out.println("DONE_SENDING");
                        break;
                    case HELLO:
                        System.out.println("HELLO");
                        break;
                    case SENDING:
                        System.out.println("SENDING");
                        break;
                    case END:
                        System.out.println("END");
                        System.exit(0);
                }
        }


        if (state==State.READY) {

        }

    }


}

